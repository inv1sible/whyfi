"""Writes the BLE/Wi-Fi signature parity fixture to stdout.

The Android app carries a Kotlin port of this matcher (com.whyfi.app.signatures)
so a standalone phone labels like the server. Both test suites check the same
cases: scans/test_signature_parity.py here, SignatureParityTest on Android.
Cases are synthetic, derived from the vendored catalog (no scan data), and
the expected labels are this backend's. Regenerate after a catalog or matcher
change and copy to both places:

    docker compose exec -T backend python manage.py generate_signature_parity \\
        > backend/scans/fixtures/signature_parity_cases.json
    cp backend/scans/fixtures/signature_parity_cases.json \\
        android/app/src/test/resources/signature_parity_cases.json
"""

import hashlib
import json
import random

from django.core.management.base import BaseCommand

from scans.ble_signatures import CATALOG_PATH, hex_only, load_catalog, match_ble, parse_advert
from scans.wifi_signatures import match_wifi, parse_wifi

SEED = 20261108
OUI_SAMPLE = 700
COMBOS = 600
HEXCHARS = "0123456789ABCDEF"
NAMECHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_ "


class Command(BaseCommand):
    help = "Print the signature parity fixture (JSON) for the backend and Android tests."

    def handle(self, *args, **options):
        self.stdout.write(json.dumps(build(), indent=None, separators=(",", ":"), ensure_ascii=False))


def _hex(rng, n):
    return "".join(rng.choice(HEXCHARS) for _ in range(n))


def _mac(prefix, rng):
    full = (prefix + _hex(rng, 12))[:12]
    return ":".join(full[i:i + 2] for i in range(0, 12, 2))


def _local(mac_hex):
    """The locally-administered twin of a universal address (virtual BSSID)."""
    first = int(mac_hex[:2], 16) | 0x02
    return f"{first:02X}{mac_hex[2:]}"


def _glob_instance(pattern, rng):
    out = []
    for ch in pattern:
        if ch == "*":
            out.append("".join(rng.choice(NAMECHARS) for _ in range(rng.randint(0, 4))))
        elif ch == "?":
            out.append(rng.choice(NAMECHARS))
        else:
            out.append(ch)
    return "".join(out)


def _uuid_spellings(text, rng):
    h = hex_only(text)
    out = [text, text.lower()]
    if len(h) == 4:
        out += [f"0000{h.lower()}-0000-1000-8000-00805f9b34fb", f"0x{h}"]
    return out


def _empty_ble(rng):
    return {"mac": _mac("", rng), "name": "", "service_uuids": [], "manufacturer_records": [], "service_data": []}


def _ble_triggers(rule, rng):
    """Advert fragments that should make this rule hit (dicts merged into an advert)."""
    k, text = rule.kind, rule.text
    if k in ("OUI", "MAC_PREFIX"):
        want = hex_only(text)
        return [{"mac": _mac(want, rng)}] if want else []
    if k == "NAME_CONTAINS" and text.strip():
        return [{"name": f"x{text}y"}, {"name": text.upper()}]
    if k == "NAME_GLOB" and text.strip():
        return [{"name": _glob_instance(text, rng)}, {"name": _glob_instance(text, rng).lower()}]
    if k == "SERVICE_UUID" and text.strip():
        return [{"service_uuids": [s]} for s in _uuid_spellings(text, rng)]
    if k == "MANUFACTURER_ID":
        return [{"manufacturer_records": [f"{rule.company_id:04x}:{_hex(rng, 8).lower()}"]}]
    if k == "MANUFACTURER_DATA":
        cid = rule.company_id or rng.randint(1, 0xFFFF)
        prefix = hex_only(rule.data_prefix_hex)
        return [
            {"manufacturer_records": [f"{cid:04x}:{prefix}{_hex(rng, 6)}"]},
            {"manufacturer_records": [f"{cid:04x}:{prefix[:-2] or '00'}"]},  # near miss
        ]
    if k == "SERVICE_DATA":
        prefix = hex_only(rule.data_prefix_hex)
        uuid = text.strip() or f"0000{_hex(rng, 4).lower()}-0000-1000-8000-00805f9b34fb"
        rev = "".join(prefix[i:i + 2] for i in range(len(prefix) - 2, -1, -2)) if len(prefix) % 2 == 0 else prefix
        return [
            {"service_data": [f"{uuid}:{prefix}{_hex(rng, 4)}"]},
            {"service_data": [f"{uuid}:{_hex(rng, 4)}{rev}"]},
        ]
    return []


def _wifi_triggers(rule, rng):
    k, text = rule.kind, rule.text
    want = hex_only(text)
    if k in ("OUI", "MAC_PREFIX") and want:
        mac = _mac(want, rng)
        out = [{"bssid": mac}]
        if len(want) == 6:
            out.append({"bssid": _mac(_local(want + "000000")[:6], rng)})
        if k == "OUI":
            out.append({"vendor_ie_ouis": [f"{want[0:2]}:{want[2:4]}:{want[4:6]}"]})
        return out
    if k == "VENDOR_IE_OUI" and want:
        return [{"vendor_ie_ouis": [want[:6]]}]
    if k == "NAME_CONTAINS" and text.strip():
        return [{"ssid": f"Net {text} 5G"}]
    if k == "NAME_GLOB" and text.strip():
        return [{"ssid": _glob_instance(text, rng)}]
    if k == "HIDDEN_SSID":
        return [{"ssid": ""}]
    return []


def _merge(base, part):
    out = dict(base)
    for key, value in part.items():
        out[key] = (out.get(key) or []) + value if isinstance(value, list) else value
    return out


def build():
    rng = random.Random(SEED)
    catalog = load_catalog()
    rules = [(fleet, rule) for fleet in catalog.fleets for rule in fleet.rules]
    address = [fr for fr in rules if fr[1].kind in ("OUI", "MAC_PREFIX")]
    others = [fr for fr in rules if fr[1].kind not in ("OUI", "MAC_PREFIX")]
    picked = others + rng.sample(address, min(OUI_SAMPLE, len(address)))

    ble_parts, wifi_parts = [], []
    for _, rule in picked:
        ble_parts += _ble_triggers(rule, rng)
        wifi_parts += _wifi_triggers(rule, rng)

    ble_adverts = [_merge(_empty_ble(rng), p) for p in ble_parts]
    wifi_aps = [_merge({"bssid": _mac("", rng), "ssid": "Home", "vendor_ie_ouis": []}, p) for p in wifi_parts]
    # Several triggers at once: catalog order, the earliest rule kind, post-filters.
    for _ in range(COMBOS):
        advert = _empty_ble(rng)
        for part in rng.sample(ble_parts, rng.randint(2, 4)):
            advert = _merge(advert, part)
        ble_adverts.append(advert)
        ap = {"bssid": _mac("", rng), "ssid": "", "vendor_ie_ouis": []}
        for part in rng.sample(wifi_parts, rng.randint(2, 3)):
            ap = _merge(ap, part)
        wifi_aps.append(ap)
    # Hand-written edge cases: Tesla key in iBeacon layout, AirTag vs. a phone,
    # junk entries, Meraki carrying Cisco's IE, Nespresso's byte-swapped id.
    ble_adverts += [
        {"mac": "6A:00:00:00:00:01", "name": "", "service_uuids": [], "service_data": [],
         "manufacturer_records": ["004c:021574278BDAB64445208F0C720EAF0599350001"]},
        {"mac": "6A:00:00:00:00:02", "name": "", "service_uuids": [], "service_data": [],
         "manufacturer_records": ["004c:1219000000", "004c:1005aa"]},
        {"mac": "6A:00:00:00:00:03", "name": "My AirTag", "service_uuids": [], "service_data": [],
         "manufacturer_records": ["004c:1219000000"]},
        {"mac": "98:CD:AC:4C:67:8A", "name": "Venus_98CDAC4C678A", "service_uuids": ["06aa1910-f22a-11e3-9daa-0002a5d5c51b"],
         "manufacturer_records": ["2502:408900000000"], "service_data": []},
        {"mac": "", "name": "  ", "service_uuids": ["", "zz"], "manufacturer_records": ["nope", "12345:00", "004c:zz"],
         "service_data": [":00", "fd44:"]},
    ]
    wifi_aps += [
        {"bssid": "0C:8D:DB:00:00:01", "ssid": "Office", "vendor_ie_ouis": ["00:40:96", "00:50:F2", "00:0F:AC"]},
        {"bssid": "", "ssid": "  ", "vendor_ie_ouis": ["xyz", "00:40:96:01", "004096", "004096"]},
    ]

    ble_cases, wifi_cases = [], []
    for a in ble_adverts:
        advert = parse_advert(a["mac"], a["name"], a["service_uuids"], a["manufacturer_records"], a["service_data"])
        ble_cases.append({"advert": a, "expect": match_ble(advert, catalog)})
    for ap in wifi_aps:
        wifi_cases.append({"ap": ap, "expect": match_wifi(parse_wifi(ap["bssid"], ap["ssid"], ap["vendor_ie_ouis"]), catalog)})

    return {
        "catalog_sha256": hashlib.sha256(CATALOG_PATH.read_bytes()).hexdigest(),
        "catalog_version": catalog.version,
        "ble": ble_cases,
        "wifi": wifi_cases,
    }
