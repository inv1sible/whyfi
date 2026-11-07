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
