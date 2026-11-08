"""Catalog labels for one scan pass, without storing anything.

The phone shows what the vendored Fieldwatch catalog says about the devices
it just heard (a Nespresso machine, a Tesla phone key, a BMW_ hotspot). The
matching itself stays here, the one implementation (see MEMORY.md), so the
phone and the PWA never disagree. Labels are informational pattern matches:
no alert state, nothing persisted, nothing correlated.
"""

from __future__ import annotations

from .ble_signatures import Catalog, load_catalog, match_ble, parse_advert
from .wifi_signatures import match_wifi, parse_wifi

MAX_ITEMS = 1000
MAX_LIST = 64


def _strings(value, limit: int = MAX_LIST) -> list[str]:
    if not isinstance(value, list):
        return []
    return [v for v in value[:limit] if isinstance(v, str) and len(v) <= 1024]


def _text(value, limit: int = 256) -> str:
    return value[:limit] if isinstance(value, str) else ""


def _labels(matches: list[dict], catalog: Catalog) -> list[dict]:
    out = []
    for match in matches:
        fleet = catalog.get(match["id"])
        if fleet is not None:
            out.append({"id": fleet.id, "name": fleet.name, "kind": fleet.kind})
    return out


def lookup(payload, catalog: Catalog | None = None) -> dict:
    """`{"ble": [advert...], "wifi": [ap...]}` in the ingest field names ->
    `{"catalog_version", "ble": {mac: [label]}, "wifi": {bssid: [label]}}`,
    listing only keys that matched. Junk entries are skipped, never fatal."""
    catalog = catalog or load_catalog()
    payload = payload if isinstance(payload, dict) else {}
    ble, wifi = {}, {}
    for item in _strings_or_dicts(payload.get("ble")):
        mac = _text(item.get("ble_mac"), 64)
        if not mac:
            continue
        advert = parse_advert(
            mac,
            _text(item.get("device_name")),
            _strings(item.get("service_uuids")),
            _strings(item.get("manufacturer_records")),
            _strings(item.get("service_data")),
        )
        labels = _labels(match_ble(advert, catalog), catalog)
        if labels:
            ble[mac] = labels
    for item in _strings_or_dicts(payload.get("wifi")):
        bssid = _text(item.get("bssid"), 64)
        if not bssid:
            continue
        advert = parse_wifi(bssid, _text(item.get("ssid")), _strings(item.get("vendor_ie_ouis")))
        labels = _labels(match_wifi(advert, catalog), catalog)
        if labels:
            wifi[bssid] = labels
    return {"catalog_version": catalog.version, "ble": ble, "wifi": wifi}


def _strings_or_dicts(value) -> list[dict]:
    if not isinstance(value, list):
        return []
    return [v for v in value[:MAX_ITEMS] if isinstance(v, dict)]
