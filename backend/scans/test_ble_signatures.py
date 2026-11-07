"""BLE signature catalog: loader, matcher, ingest labeling, relabel and API.

The catalog is Fieldwatch's stock signature export, vendored unmodified in
scans/signatures/. See backend/scans/signatures/README.md.
"""

import json
from pathlib import Path

from django.test import SimpleTestCase

from .ble_signatures import BleAdvert, load_catalog, match_ble, parse_advert, parse_catalog

FIXTURE = Path(__file__).resolve().parent / "fixtures" / "ble_signature_cases.json"


def _catalog_dict(fleets):
    return {"format": "fieldwatch-signatures", "formatVersion": 1, "catalogVersion": 1, "fleets": fleets}


def _rule(kind, text="", company_id=0, prefix="", radio=None, enabled=True):
    return {
        "kind": kind, "text": text, "companyId": company_id, "dataPrefixHex": prefix,
        "radio": radio, "enabled": enabled,
    }


def _fleet(fleet_id, rules, kind="OTHER", match_any=True, enabled=True, **extra):
    return {
        "id": fleet_id, "name": fleet_id, "enabled": enabled, "matchAny": match_any,
        "kind": kind, "rules": rules, **extra,
    }


class CatalogLoaderTests(SimpleTestCase):
    def test_stock_catalog_loads(self):
        catalog = load_catalog()
        self.assertEqual(catalog.version, 98)
        self.assertEqual(len(catalog.fleets), 255)
        self.assertEqual(catalog.get("fleet-flipper").kind, "HACKING")
        self.assertIsNone(catalog.get("fleet-does-not-exist"))

    def test_rejects_foreign_format(self):
        with self.assertRaises(ValueError):
            parse_catalog({"format": "x", "formatVersion": 1, "fleets": []})
        with self.assertRaises(ValueError):
            parse_catalog({"format": "fieldwatch-signatures", "formatVersion": 2, "fleets": []})

    def test_disabled_rule_and_fleet_are_dropped(self):
        catalog = parse_catalog(_catalog_dict([
            _fleet("fleet-off", [_rule("NAME_CONTAINS", "x")], enabled=False),
            _fleet("fleet-on", [
                _rule("NAME_CONTAINS", "kept"),
                _rule("NAME_CONTAINS", "dropped", enabled=False),
            ]),
        ]))
        self.assertEqual([f.id for f in catalog.fleets], ["fleet-on"])
        self.assertEqual([r.text for r in catalog.get("fleet-on").rules], ["kept"])

    def test_cluster_fleet_flagged(self):
        catalog = parse_catalog(_catalog_dict([
            _fleet("fleet-cluster", [_rule("NAME_CONTAINS", "x")], minPeers=3),
            _fleet("fleet-plain", [_rule("NAME_CONTAINS", "x")]),
        ]))
        self.assertIs(catalog.get("fleet-cluster").clustered, True)
        self.assertIs(catalog.get("fleet-plain").clustered, False)


def _advert(mac="", name="", service_uuids=(), manufacturer_records=(), service_data=()):
    return parse_advert(mac, name, list(service_uuids), list(manufacturer_records), list(service_data))


class MatcherFixtureTests(SimpleTestCase):
    """Shared conformance cases (also meant for a future on-device matcher)."""

    def test_fixture_cases(self):
        cases = json.loads(FIXTURE.read_text())
        self.assertGreaterEqual(len(cases), 11)
        for case in cases:
            with self.subTest(case["name"]):
                a = case["advert"]
                advert = _advert(a["mac"], a["name"], a["service_uuids"], a["manufacturer_records"], a["service_data"])
                self.assertEqual([hit["id"] for hit in match_ble(advert)], case["expect"])


class MatcherTests(SimpleTestCase):
    def test_oui_match_records_rule_kind(self):
        self.assertEqual(match_ble(_advert(mac="0C:FA:22:11:22:33")), [{"id": "fleet-flipper", "rule": "OUI"}])

    def test_rule_is_first_hit_in_catalog_order(self):
        # Flipper's rules run OUI, SERVICE_UUID x4, NAME_CONTAINS, ... so a
        # name-only advert reports NAME_CONTAINS, and adding the OUI moves it.
        self.assertEqual(match_ble(_advert(name="Flipper Zero")), [{"id": "fleet-flipper", "rule": "NAME_CONTAINS"}])
        self.assertEqual(
            match_ble(_advert(mac="0C:FA:22:11:22:33", name="Flipper Zero")),
            [{"id": "fleet-flipper", "rule": "OUI"}],
        )

    def test_wifi_scoped_rule_ignored_for_ble(self):
        catalog = parse_catalog(_catalog_dict([
            _fleet("fleet-wifi-name", [_rule("NAME_CONTAINS", "Router", radio="WIFI")]),
            _fleet("fleet-wifi-oui", [_rule("OUI", "AA:BB:CC", radio="WIFI")]),
            _fleet("fleet-wifi-only-kinds", [_rule("VENDOR_IE_OUI", "AA:BB:CC"), _rule("HIDDEN_SSID")]),
            _fleet("fleet-ble-name", [_rule("NAME_CONTAINS", "Router", radio="BLE")]),
            _fleet("fleet-radio-ble", [_rule("RADIO_KIND", radio="BLE")]),
            _fleet("fleet-radio-wifi", [_rule("RADIO_KIND", radio="WIFI")]),
        ]))
        hits = match_ble(_advert(mac="AA:BB:CC:00:00:01", name="Router"), catalog)
        self.assertEqual(
            hits,
            [{"id": "fleet-ble-name", "rule": "NAME_CONTAINS"}, {"id": "fleet-radio-ble", "rule": "RADIO_KIND"}],
        )

    def test_match_all_needs_every_rule(self):
        catalog = parse_catalog(_catalog_dict([
            _fleet("fleet-both", [_rule("NAME_CONTAINS", "Cam"), _rule("MANUFACTURER_ID", company_id=0x1234)],
                   match_any=False),
        ]))
        self.assertEqual(match_ble(_advert(name="Cam"), catalog), [])
        self.assertEqual(
            match_ble(_advert(name="Cam", manufacturer_records=["1234:00"]), catalog),
            [{"id": "fleet-both", "rule": "NAME_CONTAINS"}],
        )

    def test_clustered_and_empty_fleets_never_match(self):
        catalog = parse_catalog(_catalog_dict([
            _fleet("fleet-cluster", [_rule("NAME_CONTAINS", "Cam")], clusterByOui=True),
            _fleet("fleet-seq", [_rule("NAME_CONTAINS", "Cam")], sequentialMac=True),
            _fleet("fleet-empty", []),
            _fleet("fleet-empty-all", [], match_any=False),
        ]))
        self.assertEqual(match_ble(_advert(name="Cam"), catalog), [])

    def test_manufacturer_data_company_zero_matches_any_company(self):
        catalog = parse_catalog(_catalog_dict([
            _fleet("fleet-any-co", [_rule("MANUFACTURER_DATA", company_id=0, prefix="ab:cd")]),
        ]))
        self.assertEqual(
            match_ble(_advert(manufacturer_records=["0001:ABCDEF"]), catalog),
            [{"id": "fleet-any-co", "rule": "MANUFACTURER_DATA"}],
        )
        self.assertEqual(match_ble(_advert(manufacturer_records=["0001:00ABCD"]), catalog), [])

    def test_continuity_payload_drops_airtag_without_apple_fleet(self):
        # The Continuity check is independent of the Apple fleets: a 0x004C
        # record whose first byte is a Continuity type is enough.
        catalog = parse_catalog(_catalog_dict([
            _fleet("fleet-airtag", [_rule("MANUFACTURER_DATA", company_id=0x004C, prefix="12")], kind="FINDER"),
        ]))
        self.assertEqual(match_ble(_advert(manufacturer_records=["004c:1202", "004c:0F05"]), catalog), [])
        self.assertEqual(
            [h["id"] for h in match_ble(_advert(manufacturer_records=["004c:1202", "004c:0605"]), catalog)],
            ["fleet-airtag"],
        )

    def test_parse_advert_skips_malformed_entries(self):
        advert = _advert(manufacturer_records=["zz:00", "nocolon", "12345:00", ":00"])
        self.assertEqual(advert.manufacturer_records, ())
        advert = _advert(service_data=["nocolon", ":00", "---:00"])
        self.assertEqual(advert.service_data, ())

    def test_parse_advert_normalises_hex(self):
        advert = _advert(
            manufacturer_records=["004c:12:19:ab", "00e0:"],
            service_data=["0000fd5a-0000-1000-8000-00805f9b34fb:0a1b"],
        )
        self.assertIsInstance(advert, BleAdvert)
        self.assertEqual(advert.manufacturer_records, ((0x004C, "1219AB"), (0x00E0, "")))
        self.assertEqual(advert.service_data, (("0000fd5a-0000-1000-8000-00805f9b34fb", "0A1B"),))

    def test_legacy_field_used_only_without_records(self):
        legacy = parse_advert("", "", [], [], [], legacy_manufacturer_data="004c:1219")
        self.assertEqual(legacy.manufacturer_records, ((0x004C, "1219"),))
        both = parse_advert("", "", [], ["0075:0102"], [], legacy_manufacturer_data="004c:1219")
        self.assertEqual(both.manufacturer_records, ((0x0075, "0102"),))
        self.assertEqual(parse_advert("", "", [], [], [], legacy_manufacturer_data="4c00").manufacturer_records, ())

    def test_glob_is_literal_except_wildcards(self):
        catalog = parse_catalog(_catalog_dict([
            _fleet("fleet-pine", [_rule("NAME_GLOB", "Pineapple_*")]),
            _fleet("fleet-dot", [_rule("NAME_GLOB", "a.b")]),
            _fleet("fleet-q", [_rule("NAME_GLOB", "x?z")]),
        ]))
        ids = lambda name: [h["id"] for h in match_ble(_advert(name=name), catalog)]  # noqa: E731
        self.assertEqual(ids("PineappleX"), [])
        self.assertEqual(ids("pineapple_ab12"), ["fleet-pine"])
        self.assertEqual(ids("axb"), [])
        self.assertEqual(ids("A.B"), ["fleet-dot"])
        self.assertEqual(ids("xyz"), ["fleet-q"])
        self.assertEqual(ids("xyyz"), [])

    def test_blank_name_never_matches_name_rules(self):
        catalog = parse_catalog(_catalog_dict([
            _fleet("fleet-star", [_rule("NAME_GLOB", "*")]),
            _fleet("fleet-blank-contains", [_rule("NAME_CONTAINS", " ")]),
        ]))
        self.assertEqual(match_ble(_advert(name=""), catalog), [])
        self.assertEqual(match_ble(_advert(name="   "), catalog), [])
        self.assertEqual(match_ble(_advert(name="anything x"), catalog), [{"id": "fleet-star", "rule": "NAME_GLOB"}])

    def test_uuid_aliases_for_non_sig_uuid_are_exact(self):
        catalog = parse_catalog(_catalog_dict([
            _fleet("fleet-vendor-uuid", [_rule("SERVICE_UUID", "6E400001-B5A3-F393-E0A9-E50E24DCCA9E")]),
        ]))
        hit = [{"id": "fleet-vendor-uuid", "rule": "SERVICE_UUID"}]
        self.assertEqual(match_ble(_advert(service_uuids=["6e400001-b5a3-f393-e0a9-e50e24dcca9e"]), catalog), hit)
        self.assertEqual(match_ble(_advert(service_uuids=["6E400001B5A3F393E0A9E50E24DCCA9E"]), catalog), hit)
        self.assertEqual(match_ble(_advert(service_uuids=["0001"]), catalog), [])
