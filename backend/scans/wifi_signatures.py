"""Wi-Fi signature labels from the vendored Fieldwatch catalog.

Same catalog and storage pattern as `ble_signatures.py` (see its docstring);
this is the Wi-Fi side of Fieldwatch's `SignatureEngine.kt`: BSSID prefixes
(including the universal OUI behind a virtual BSSID), SSID name rules, and
the OUIs of the beacon's vendor-specific information elements. Labels are
pattern matches, informational only, with no alert state (see MEMORY.md).

Semantics follow SignatureEngine's indexed fast path, which every match-any
fleet goes through. See docs/superpowers/plans/2026-11-07-wifi-signature-labels.md.
"""

from __future__ import annotations

from dataclasses import dataclass

from .ble_signatures import Catalog, Fleet, Rule, _compile_glob, hex_only, load_catalog

# WPA (Microsoft) and RSN (IEEE) vendor IE OUIs: protocol tags, not products.
PROTOCOL_IE_OUIS = frozenset({"0050F2", "000FAC"})
_ADDRESS_KINDS = frozenset({"OUI", "MAC_PREFIX", "VENDOR_IE_OUI"})
_BLE_PAYLOAD_KINDS = frozenset({"SERVICE_UUID", "SERVICE_DATA", "MANUFACTURER_ID", "MANUFACTURER_DATA"})
# When one rule hits several ways, report the most specific.
_VIA_ORDER = ("bssid", "bssid_universal", "vendor_ie")
MAX_VENDOR_IES = 32


@dataclass(frozen=True)
class WifiAdvert:
    bssid_hex: str
    ssid: str
    # Distinct 6-hex OUIs of vendor-specific IEs (element 221), beacon order.
    vendor_ie_ouis: tuple[str, ...]


def universal_oui(bssid: str) -> str | None:
    """Port of MacUtil.wifiOui24Universal: the burned-in vendor OUI behind a
    virtual (locally-administered, unicast) BSSID; None if the address is
    already universal, multicast or too short."""
    hex_ = hex_only(bssid)
    if len(hex_) < 6:
        return None
    try:
        first = int(hex_[:2], 16)
    except ValueError:
        return None
    if not first & 0x02 or first & 0x01:
        return None
    return f"{first & 0xFD:02X}{hex_[2:6]}"


def parse_wifi(bssid, ssid, vendor_ie_ouis) -> WifiAdvert:
    """Builds a WifiAdvert from ingest values. Vendor IE entries that aren't
    exactly three hex bytes are skipped; duplicates collapse."""
    ouis = []
    for entry in vendor_ie_ouis or ():
        if not isinstance(entry, str):
            continue
        hex_ = hex_only(entry)
        if len(hex_) == 6 and all(c in "0123456789ABCDEF" for c in hex_) and hex_ not in ouis:
            ouis.append(hex_)
    return WifiAdvert(
        bssid_hex=hex_only(bssid) if isinstance(bssid, str) else "",
        ssid=ssid.strip() if isinstance(ssid, str) else "",
        vendor_ie_ouis=tuple(ouis[:MAX_VENDOR_IES]),
    )


def _vendor_ies(advert: WifiAdvert) -> list[str]:
    return [ie for ie in advert.vendor_ie_ouis if ie[:6] not in PROTOCOL_IE_OUIS]


def _address_vias(rule: Rule, advert: WifiAdvert, universal: str | None) -> list[str]:
    """Ways an OUI / MAC_PREFIX / VENDOR_IE_OUI rule hits this AP."""
    want = hex_only(rule.text)
    if not want:
        return []
    vias = []
    if rule.kind in ("OUI", "MAC_PREFIX"):
        if advert.bssid_hex.startswith(want):
            vias.append("bssid")
        if len(want) == 6 and universal == want:
            vias.append("bssid_universal")
    if rule.kind in ("OUI", "VENDOR_IE_OUI") and want[:6] not in PROTOCOL_IE_OUIS:
        if any(ie.startswith(want) for ie in _vendor_ies(advert)):
            vias.append("vendor_ie")
    return vias


def _compile_other(rule: Rule):
    """Predicate `(advert) -> bool` for a non-address rule on Wi-Fi, or None
    when it can never hit Wi-Fi."""
    kind = rule.kind
    if kind == "RADIO_KIND":
        return (lambda advert: True) if rule.radio in (None, "WIFI") else None
    if rule.radio not in (None, "WIFI") or kind in _BLE_PAYLOAD_KINDS:
        return None
    if kind == "HIDDEN_SSID":
        return lambda advert: not advert.ssid
    if kind == "NAME_CONTAINS":
        if not rule.text.strip():
            return None
        needle = rule.text.lower()
        return lambda advert: bool(advert.ssid) and needle in advert.ssid.lower()
    if kind == "NAME_GLOB":
        if not rule.text.strip():
            return None
        regex = _compile_glob(rule.text)
        return lambda advert: bool(advert.ssid) and regex.fullmatch(advert.ssid) is not None
    return None


class _WifiCompiled:
    """Per-catalog index: 6-hex address rules in dicts, the rest as lists."""

    def __init__(self, catalog: Catalog):
        self.fleets = catalog.fleets
        self.kind_by_id = {fleet.id: fleet.kind for fleet in catalog.fleets}
        # hex -> [(fleet index, rule position, rule)]
        self.by_bssid6: dict[str, list] = {}
        self.by_universal6: dict[str, list] = {}
        self.by_ie6: dict[str, list] = {}
        self.long_rules: list = []  # (fleet index, position, rule) for non-6-hex address rules
        self.other_rules: list[list] = []
        for idx, fleet in enumerate(catalog.fleets):
            others = []
            for pos, rule in enumerate(fleet.rules):
                if rule.kind in _ADDRESS_KINDS:
                    if rule.radio == "BLE":
                        continue
                    want = hex_only(rule.text)
                    if not want:
                        continue
                    if len(want) != 6:
                        self.long_rules.append((idx, pos, rule))
                        continue
                    if rule.kind in ("OUI", "MAC_PREFIX"):
                        self.by_bssid6.setdefault(want, []).append((idx, pos))
                        self.by_universal6.setdefault(want, []).append((idx, pos))
                    if rule.kind in ("OUI", "VENDOR_IE_OUI") and want not in PROTOCOL_IE_OUIS:
                        self.by_ie6.setdefault(want, []).append((idx, pos))
                    continue
                predicate = _compile_other(rule)
                if predicate is not None:
                    others.append((pos, rule.kind, predicate))
            self.other_rules.append(others)

    def address_hits(self, advert: WifiAdvert) -> dict[int, tuple[int, str]]:
        """fleet index -> (position, via) of its earliest address rule hit."""
        found: dict[int, dict[int, set[str]]] = {}

        def mark(entries, via):
            for idx, pos in entries or ():
                found.setdefault(idx, {}).setdefault(pos, set()).add(via)

        universal = universal_oui(advert.bssid_hex)
        mark(self.by_bssid6.get(advert.bssid_hex[:6]) if len(advert.bssid_hex) >= 6 else None, "bssid")
        if universal:
            mark(self.by_universal6.get(universal), "bssid_universal")
        for ie in _vendor_ies(advert):
            mark(self.by_ie6.get(ie[:6]), "vendor_ie")
        for idx, pos, rule in self.long_rules:
            for via in _address_vias(rule, advert, universal):
                found.setdefault(idx, {}).setdefault(pos, set()).add(via)

        out = {}
        for idx, by_pos in found.items():
            pos = min(by_pos)
            out[idx] = (pos, next(v for v in _VIA_ORDER if v in by_pos[pos]))
        return out


def _compiled(catalog: Catalog) -> _WifiCompiled:
    compiled = catalog.__dict__.get("_wifi_compiled")
    if compiled is None:
        compiled = _WifiCompiled(catalog)
        object.__setattr__(catalog, "_wifi_compiled", compiled)
    return compiled


def _rule_hits(rule: Rule, advert: WifiAdvert, universal: str | None) -> str | bool:
    """For match-all fleets: the via string for an address hit, True for any
    other hit, False for a miss."""
    if rule.kind in _ADDRESS_KINDS:
        if rule.radio == "BLE":
            return False
        vias = _address_vias(rule, advert, universal)
        return next((v for v in _VIA_ORDER if v in vias), False)
    predicate = _compile_other(rule)
    return predicate is not None and predicate(advert)


def match_wifi(advert: WifiAdvert, catalog: Catalog | None = None) -> list[dict]:
    """Fleets whose rules hit this access point, in catalog order:
    `[{"id", "rule", "via"?}]`. `rule` is the kind of the first rule (catalog
    order) that hit; address hits add `via` (bssid / bssid_universal / vendor_ie)."""
    catalog = catalog or load_catalog()
    compiled = _compiled(catalog)
    address = compiled.address_hits(advert)
    universal = universal_oui(advert.bssid_hex)

    hits: dict[str, dict] = {}
    for idx, fleet in enumerate(compiled.fleets):
        if fleet.clustered or not fleet.rules:
            continue
        if not fleet.match_any:
            results = [_rule_hits(rule, advert, universal) for rule in fleet.rules]
            if all(results):
                hits[fleet.id] = _evidence(fleet, 0, results[0])
            continue
        first = address.get(idx)
        evidence = _evidence(fleet, first[0], first[1]) if first else None
        for pos, kind, predicate in compiled.other_rules[idx]:
            if first is not None and pos > first[0]:
                break
            if predicate(advert):
                evidence = {"id": fleet.id, "rule": kind}
                break
        if evidence is not None:
            hits[fleet.id] = evidence

    # SignatureEngine's post-filters. Only Meraki/Cisco can arise on Wi-Fi in
    # practice; the BLE ones are kept so a catalog change can't diverge.
    if "fleet-ibeacon" in hits and any(
        fid != "fleet-ibeacon" and compiled.kind_by_id.get(fid) != "BEACON" for fid in hits
    ):
        del hits["fleet-ibeacon"]
    if "fleet-osmo" in hits or "fleet-dji-power" in hits:
        hits.pop("fleet-dji", None)
    if "fleet-polar" in hits:
        hits.pop("fleet-gopro", None)
    # Meraki APs carry Cisco's vendor IE; the BSSID is Meraki.
    if "fleet-meraki" in hits:
        hits.pop("fleet-cisco", None)
    return list(hits.values())


def _evidence(fleet: Fleet, pos: int, via) -> dict:
    entry = {"id": fleet.id, "rule": fleet.rules[pos].kind}
    if isinstance(via, str):
        entry["via"] = via
    return entry


def is_wifi_capable(fleet: Fleet) -> bool:
    """Whether any rule could ever hit an access point."""
    for rule in fleet.rules:
        if rule.kind in _ADDRESS_KINDS:
            if rule.radio != "BLE" and hex_only(rule.text):
                return True
        elif _compile_other(rule) is not None:
            return True
    return False
