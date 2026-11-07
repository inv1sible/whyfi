"""BLE signature catalog: loading and matching.

The catalog is Fieldwatch's stock signature export, vendored unmodified in
`signatures/fieldwatch-signatures-v2.json` (MIT, see the root NOTICE and
`signatures/README.md`). Matching is a port of Fieldwatch's
`SignatureEngine.kt`, and it lives only here, on the backend: one
implementation, and stored history can be relabeled whenever the catalog
changes (`manage.py relabel_ble_signatures`).

A match is a *pattern match* on the advertisement, not proof of what the
device is. Labels are informational, like `device_type_guess`. There is
deliberately no alert state or correlation here (see MEMORY.md).
"""

from __future__ import annotations

import functools
import json
import re
from dataclasses import dataclass, field
from pathlib import Path

CATALOG_PATH = Path(__file__).resolve().parent / "signatures" / "fieldwatch-signatures-v2.json"
CATALOG_FORMAT = "fieldwatch-signatures"
CATALOG_FORMAT_VERSION = 1


@dataclass(frozen=True)
class Rule:
    kind: str
    text: str
    company_id: int
    data_prefix_hex: str
    radio: str | None


@dataclass(frozen=True)
class Fleet:
    id: str
    name: str
    kind: str
    match_any: bool
    rules: tuple[Rule, ...]
    notes: str
    attention_note: str
    # Peer-clustered fleets need several radios seen together. That isn't
    # ported (no stock fleet uses it), so such a fleet never matches.
    clustered: bool


@dataclass(frozen=True)
class Catalog:
    version: int
    fleets: tuple[Fleet, ...]
    _by_id: dict = field(init=False, repr=False, compare=False, hash=False)

    def __post_init__(self):
        object.__setattr__(self, "_by_id", {fleet.id: fleet for fleet in self.fleets})

    def get(self, fleet_id: str) -> Fleet | None:
        return self._by_id.get(fleet_id)

    def compiled(self) -> "_Compiled":
        # Built once per Catalog instance; frozen, so stash it past __setattr__.
        compiled = self.__dict__.get("_compiled")
        if compiled is None:
            compiled = _Compiled(self)
            object.__setattr__(self, "_compiled", compiled)
        return compiled


def _parse_rule(raw: dict) -> Rule | None:
    if not raw.get("enabled", True):
        return None
    return Rule(
        kind=str(raw.get("kind", "")),
        text=str(raw.get("text") or ""),
        company_id=int(raw.get("companyId") or 0),
        data_prefix_hex=str(raw.get("dataPrefixHex") or ""),
        radio=raw.get("radio") or None,
    )


def parse_catalog(data: dict) -> Catalog:
    if data.get("format") != CATALOG_FORMAT or data.get("formatVersion") != CATALOG_FORMAT_VERSION:
        raise ValueError(
            f"Not a {CATALOG_FORMAT} v{CATALOG_FORMAT_VERSION} catalog: "
            f"format={data.get('format')!r} formatVersion={data.get('formatVersion')!r}"
        )
    fleets = []
    for raw in data.get("fleets", []):
        if not raw.get("enabled", True):
            continue
        rules = tuple(rule for rule in map(_parse_rule, raw.get("rules", [])) if rule is not None)
        fleets.append(Fleet(
            id=str(raw["id"]),
            name=str(raw.get("name") or raw["id"]),
            kind=str(raw.get("kind") or "OTHER"),
            match_any=bool(raw.get("matchAny", True)),
            rules=rules,
            notes=str(raw.get("notes") or ""),
            attention_note=str(raw.get("attentionNote") or ""),
            clustered=bool(
                int(raw.get("minPeers") or 0) > 0 or raw.get("clusterByOui") or raw.get("sequentialMac")
            ),
        ))
    return Catalog(version=int(data.get("catalogVersion") or 0), fleets=tuple(fleets))


@functools.cache
def load_catalog() -> Catalog:
    with CATALOG_PATH.open(encoding="utf-8") as fh:
        return parse_catalog(json.load(fh))


# --------------------------------------------------------------------------
# Matching (port of Fieldwatch SignatureEngine.kt, BLE only)
# --------------------------------------------------------------------------

# Tesla phone-key adverts use Apple's iBeacon layout so iOS can find the car.
TESLA_IBEACON_MFG_PREFIX = "021574278BDAB64445208F0C720EAF059935"
APPLE_COMPANY_ID = 0x004C
# Nearby Info / Handoff / AirDrop / AirPods / ...: a phone, Mac or buds, not a tag.
APPLE_CONTINUITY_PREFIXES = frozenset({"05", "07", "08", "09", "0A", "0B", "0C", "0D", "0E", "0F", "10"})
SIG_BASE_SUFFIX = "00001000800000805F9B34FB"

_ADDRESS_KINDS = frozenset({"OUI", "MAC_PREFIX"})
_WIFI_ONLY_KINDS = frozenset({"VENDOR_IE_OUI", "HIDDEN_SSID"})
_HEX_RE = re.compile(r"[0-9A-F]*")
_COMPANY_RE = re.compile(r"[0-9A-Fa-f]{1,4}")


@dataclass(frozen=True)
class BleAdvert:
    mac: str
    name: str
    service_uuids: tuple[str, ...]
    # (company id, data hex). Data excludes the two company-id bytes, as
    # Android's ScanRecord.manufacturerSpecificData values already do.
    manufacturer_records: tuple[tuple[int, str], ...]
    # (uuid as sent, data hex)
    service_data: tuple[tuple[str, str], ...]


def hex_only(raw: str) -> str:
    """Fieldwatch's hexOnly: letters and digits only, uppercased."""
    return "".join(ch for ch in raw if ch.isalnum()).upper()


def uuid_aliases(raw: str) -> frozenset[str]:
    """Port of Fieldwatch's uuidAliases: every spelling a UUID can match under."""
    hex_ = hex_only(raw)
    out = {raw.upper(), hex_}
    if len(hex_) == 4:
        out.add(f"0x{hex_}")
        out.add(f"0000{hex_}-0000-1000-8000-00805F9B34FB")
        out.add(f"0000{hex_}{SIG_BASE_SUFFIX}")
        return frozenset(out)
    # 16-bit alias only for Bluetooth SIG base UUIDs.
    if len(hex_) == 32 and hex_.startswith("0000") and hex_.endswith(SIG_BASE_SUFFIX):
        short = hex_[4:8]
        out.add(short)
        out.add(f"0x{short}")
        out.add(f"0000{short}-0000-1000-8000-00805F9B34FB")
    return frozenset(out)


def _data_hex(raw: str) -> str | None:
    data = hex_only(raw)
    return data if _HEX_RE.fullmatch(data) else None


def _split_entry(entry) -> tuple[str, str] | None:
    if not isinstance(entry, str) or ":" not in entry:
        return None
    head, _, tail = entry.partition(":")
    return head.strip(), tail


def _parse_manufacturer(entries) -> tuple[tuple[int, str], ...]:
    out = []
    for entry in entries or ():
        parts = _split_entry(entry)
        if parts is None or not _COMPANY_RE.fullmatch(parts[0]):
            continue
        data = _data_hex(parts[1])
        if data is not None:
            out.append((int(parts[0], 16), data))
    return tuple(out)


def parse_advert(
    mac,
    name,
    service_uuids: list[str],
    manufacturer_records: list[str],
    service_data: list[str],
    legacy_manufacturer_data: str = "",
) -> BleAdvert:
    """Builds a BleAdvert from the ingest wire format.

    Manufacturer records are `"cccc:HEX"` (company id in hex) and service data
    `"uuid:HEX"`, split on the first colon. Malformed entries are skipped.
    `legacy_manufacturer_data` is the single record old APKs send; it's used
    only when there are no `manufacturer_records`.
    """
    records = _parse_manufacturer(manufacturer_records)
    if not records and legacy_manufacturer_data:
        records = _parse_manufacturer([legacy_manufacturer_data])
    svc_data = []
    for entry in service_data or ():
        parts = _split_entry(entry)
        if parts is None or not hex_only(parts[0]):
            continue
        data = _data_hex(parts[1])
        if data is not None:
            svc_data.append((parts[0], data))
    uuids = tuple(u.strip() for u in (service_uuids or ()) if isinstance(u, str) and hex_only(u))
    return BleAdvert(
        mac=mac if isinstance(mac, str) else "",
        name=name if isinstance(name, str) else "",
        service_uuids=uuids,
        manufacturer_records=records,
        service_data=tuple(svc_data),
    )


def _compile_glob(pattern: str) -> re.Pattern:
    body = "".join(".*" if ch == "*" else "." if ch == "?" else re.escape(ch) for ch in pattern)
    return re.compile(body, re.IGNORECASE)


def _reverse_hex_bytes(hex_: str) -> str:
    if len(hex_) < 2 or len(hex_) % 2:
        return ""
    return "".join(hex_[i:i + 2] for i in range(len(hex_) - 2, -1, -2))


class _AdvertView:
    """Per-advert values that several rules need, computed once."""

    __slots__ = ("advert", "mac_hex", "name_blank", "name_lower", "uuid_aliases")

    def __init__(self, advert: BleAdvert):
        self.advert = advert
        self.mac_hex = hex_only(advert.mac)
        self.name_blank = not advert.name.strip()
        self.name_lower = advert.name.lower()
        aliases = set()
        for uuid in advert.service_uuids:
            aliases |= uuid_aliases(uuid)
        for uuid, _ in advert.service_data:
            aliases |= uuid_aliases(uuid)
        self.uuid_aliases = aliases


def _service_data_hits(view: _AdvertView, aliases: frozenset[str], prefix: str, contains: bool) -> bool:
    if contains:
        rev = _reverse_hex_bytes(prefix)
        needles = (prefix,) if not rev or rev == prefix else (prefix, rev)
    for uuid, data in view.advert.service_data:
        if aliases and not (uuid_aliases(uuid) & aliases):
            continue
        if contains:
            if any(needle in data for needle in needles):
                return True
        elif data.startswith(prefix):
            return True
    return False


def _compile_rule(rule: Rule):
    """A predicate `(view) -> bool` for one rule against a BLE advert, or None
    when the rule can never hit BLE. Same semantics as SignatureEngine.ruleHits
    for RadioKind.BLE."""
    kind = rule.kind
    if kind in _WIFI_ONLY_KINDS:
        return None
    if kind == "RADIO_KIND":
        return (lambda view: True) if rule.radio in (None, "BLE") else None
    if rule.radio not in (None, "BLE"):
        return None
    if kind in _ADDRESS_KINDS:
        want = hex_only(rule.text)
        return (lambda view: view.mac_hex.startswith(want)) if want else None
    if kind == "NAME_CONTAINS":
        if not rule.text.strip():
            return None
        needle = rule.text.lower()
        return lambda view: not view.name_blank and needle in view.name_lower
    if kind == "NAME_GLOB":
        if not rule.text.strip():
            return None
        regex = _compile_glob(rule.text)
        return lambda view: not view.name_blank and regex.fullmatch(view.advert.name) is not None
    if kind == "SERVICE_UUID":
        if not rule.text.strip():
            return None
        want = uuid_aliases(rule.text)
        return lambda view: not want.isdisjoint(view.uuid_aliases)
    if kind == "MANUFACTURER_ID":
        company = rule.company_id
        return lambda view: any(cid == company for cid, _ in view.advert.manufacturer_records)
    if kind == "MANUFACTURER_DATA":
        prefix = hex_only(rule.data_prefix_hex)
        if not prefix:
            return None
        company = rule.company_id
        return lambda view: any(
            (company == 0 or cid == company) and data.startswith(prefix)
            for cid, data in view.advert.manufacturer_records
        )
    if kind == "SERVICE_DATA":
        prefix = hex_only(rule.data_prefix_hex)
        aliases = frozenset(a for a in uuid_aliases(rule.text) if a)
        if not prefix and not aliases:
            return None
        contains = not rule.text.strip() and bool(prefix)
        return lambda view: _service_data_hits(view, aliases, prefix, contains)
    return None  # Unknown rule kind (newer catalog): never hits.


class _Compiled:
    """Catalog indexed for BLE, like SignatureEngine's Compiled: 6-hex OUI
    rules go in a dict, so the thousands of address prefixes cost one lookup
    per advert instead of a scan."""

    def __init__(self, catalog: Catalog):
        self.fleets = catalog.fleets
        self.kind_by_id = {fleet.id: fleet.kind for fleet in catalog.fleets}
        # oui6 -> [(fleet index, rule position)]
        self.oui6: dict[str, list[tuple[int, int]]] = {}
        # [(prefix hex, fleet index, rule position)] for prefixes that aren't 6 hex long
        self.long_prefixes: list[tuple[str, int, int]] = []
        # fleet index -> [(rule position, kind, predicate)] for non-address rules
        self.other_rules: list[list[tuple[int, str, object]]] = []
        # fleet index -> [predicate or None per rule] for match-all fleets
        self.all_rules: list[list | None] = []
        for idx, fleet in enumerate(catalog.fleets):
            others = []
            for pos, rule in enumerate(fleet.rules):
                if rule.kind in _ADDRESS_KINDS:
                    if rule.radio not in (None, "BLE"):
                        continue
                    want = hex_only(rule.text)
                    if len(want) == 6:
                        self.oui6.setdefault(want, []).append((idx, pos))
                    elif want:
                        self.long_prefixes.append((want, idx, pos))
                    continue
                predicate = _compile_rule(rule)
                if predicate is not None:
                    others.append((pos, rule.kind, predicate))
            self.other_rules.append(others)
            self.all_rules.append(None if fleet.match_any else [_compile_rule(rule) for rule in fleet.rules])

    def address_hits(self, mac_hex: str) -> dict[int, int]:
        """fleet index -> position of its earliest address rule that hits."""
        hits: dict[int, int] = {}
        if not mac_hex:
            return hits
        for idx, pos in self.oui6.get(mac_hex[:6], ()):
            if pos < hits.get(idx, pos + 1):
                hits[idx] = pos
        for want, idx, pos in self.long_prefixes:
            if mac_hex.startswith(want) and pos < hits.get(idx, pos + 1):
                hits[idx] = pos
        return hits


def _is_tesla_phone_key(advert: BleAdvert) -> bool:
    return any(
        cid == APPLE_COMPANY_ID and data.startswith(TESLA_IBEACON_MFG_PREFIX)
        for cid, data in advert.manufacturer_records
    )


def _is_apple_continuity(advert: BleAdvert) -> bool:
    return any(
        cid == APPLE_COMPANY_ID and data[:2] in APPLE_CONTINUITY_PREFIXES
        for cid, data in advert.manufacturer_records
    )


_FIND_MY_ACCESSORY_ALIASES = uuid_aliases("FD44")


def match_ble(advert: BleAdvert, catalog: Catalog | None = None) -> list[dict]:
    """Fleets whose rules hit this advert, in catalog order, as
    `[{"id": fleet_id, "rule": kind of the first rule (catalog order) that hit}]`."""
    catalog = catalog or load_catalog()
    compiled = catalog.compiled()
    view = _AdvertView(advert)
    address = compiled.address_hits(view.mac_hex)
    tesla = _is_tesla_phone_key(advert)

    hits: dict[str, str] = {}
    for idx, fleet in enumerate(compiled.fleets):
        if fleet.clustered or not fleet.rules:
            continue
        if fleet.id == "fleet-ibeacon" and tesla:
            continue
        if not fleet.match_any:
            predicates = compiled.all_rules[idx]
            if all(p is not None and p(view) for p in predicates):
                hits[fleet.id] = fleet.rules[0].kind
            continue
        first_pos = address.get(idx)
        rule_kind = fleet.rules[first_pos].kind if first_pos is not None else None
        for pos, kind, predicate in compiled.other_rules[idx]:
            if first_pos is not None and pos > first_pos:
                break
            if predicate(view):
                rule_kind = kind
                break
        if rule_kind is not None:
            hits[fleet.id] = rule_kind

    # Post-filters, in SignatureEngine's order. Wi-Fi-only ones (Meraki/Cisco)
    # can't arise for BLE and aren't ported.
    # iBeacon is a payload layout, not a product: drop it next to any
    # non-beacon product match.
    if "fleet-ibeacon" in hits and any(
        fid != "fleet-ibeacon" and compiled.kind_by_id.get(fid) != "BEACON" for fid in hits
    ):
        del hits["fleet-ibeacon"]
    # Osmo cameras and DJI Power stations share DJI's company id 0x08AA.
    if "fleet-osmo" in hits or "fleet-dji-power" in hits:
        hits.pop("fleet-dji", None)
    # A Polar strap advertises GoPro's heart-rate service FEA5.
    if "fleet-polar" in hits:
        hits.pop("fleet-gopro", None)
    # Offline Finding (0x12) is the Find My protocol, which iPhones also send.
    if "fleet-airtag" in hits and "airtag" not in view.name_lower \
            and _FIND_MY_ACCESSORY_ALIASES.isdisjoint(view.uuid_aliases):
        if "fleet-apple-device" in hits or "fleet-apple-audio" in hits or _is_apple_continuity(advert):
            del hits["fleet-airtag"]

    return [{"id": fid, "rule": kind} for fid, kind in hits.items()]
