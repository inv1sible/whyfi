"""BLE signature catalog: loader, matcher, ingest labeling, relabel and API.

The catalog is Fieldwatch's stock signature export, vendored unmodified in
scans/signatures/. See backend/scans/signatures/README.md.
"""

import io
import json
from pathlib import Path

from django.contrib.auth import get_user_model
from django.test import SimpleTestCase, TestCase
from rest_framework.test import APIClient

from sensors.models import Sensor

from django.core.management import call_command

from .ble_signatures import BleAdvert, load_catalog, match_ble, parse_advert, parse_catalog, relabel_all

from .models import BLEDevice, BLEObservation, ScanSession

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


def _session(client_scan_id, ble_observations):
    return {
        "client_scan_id": client_scan_id,
        "started_at": "2026-11-07T10:00:00Z",
        "completed_at": "2026-11-07T10:00:06Z",
        "latitude": 48.1351, "longitude": 11.582,
        "ble_observations": ble_observations,
    }


class IngestSignatureTests(TestCase):
    def setUp(self):
        self.sensor = Sensor.objects.create(name="Field Phone")
        self.ingest = APIClient()
        self.ingest.credentials(HTTP_AUTHORIZATION=f"Token {self.sensor.token}")

    def _post(self, client_scan_id, *observations):
        response = self.ingest.post(
            "/api/v1/scan-sessions/", _session(client_scan_id, list(observations)), format="json",
        )
        self.assertEqual(response.status_code, 201, response.content)
        return response

    def test_new_payload_is_labeled(self):
        self._post("sig-1", {
            "ble_mac": "7A:11:22:33:44:55", "rssi": -60, "device_name": "Flipper Zero",
            "manufacturer_records": [], "service_data": [],
        })
        obs = BLEObservation.objects.get()
        self.assertEqual([m["id"] for m in obs.signature_matches], ["fleet-flipper"])
        self.assertEqual(obs.signature_matches[0]["rule"], "NAME_CONTAINS")
        self.assertEqual(obs.signature_catalog_version, 98)
        self.assertEqual(BLEDevice.objects.get(device_key="7A:11:22:33:44:55").signature_ids, ["fleet-flipper"])

    def test_raw_advert_lists_are_stored(self):
        self._post("sig-raw", {
            "ble_mac": "7A:11:22:33:44:56", "rssi": -60,
            "manufacturer_records": ["004c:1005", "ffff:0109"],
            "service_data": ["0000fd5a-0000-1000-8000-00805f9b34fb:01"],
        })
        obs = BLEObservation.objects.get()
        self.assertEqual(obs.manufacturer_records, ["004c:1005", "ffff:0109"])
        self.assertEqual(obs.service_data, ["0000fd5a-0000-1000-8000-00805f9b34fb:01"])
        self.assertEqual([m["id"] for m in obs.signature_matches], ["fleet-apple-device", "fleet-smarttag"])

    def test_legacy_payload_is_labeled(self):
        # Old APKs send only the first manufacturer record, and no new fields.
        self._post("sig-legacy", {
            "ble_mac": "5C:11:22:33:44:55", "rssi": -70, "manufacturer_data": "004c:12190000",
            "service_uuids": [],
        })
        obs = BLEObservation.objects.get()
        self.assertEqual([m["id"] for m in obs.signature_matches], ["fleet-airtag"])
        self.assertEqual(obs.manufacturer_records, [])
        self.assertEqual(BLEDevice.objects.get().signature_ids, ["fleet-airtag"])

    def test_long_manufacturer_record_does_not_reject_session(self):
        self._post("sig-long", {
            "ble_mac": "7A:11:22:33:44:57", "rssi": -60,
            "manufacturer_records": ["08aa:0600" + "ab" * 298],
            "manufacturer_data": "08aa:0600" + "ab" * 250,
        })
        obs = BLEObservation.objects.get()
        self.assertEqual(len(obs.manufacturer_records[0]), 605)
        self.assertEqual(len(obs.manufacturer_data_raw), 509)
        self.assertEqual([m["id"] for m in obs.signature_matches], ["fleet-osmo"])

    def test_too_many_records_are_truncated_not_rejected(self):
        self._post("sig-many", {
            "ble_mac": "7A:11:22:33:44:5E", "rssi": -60,
            "manufacturer_records": [f"{i:04x}:00" for i in range(0xF000, 0xF040)],
            "service_data": [f"0000{i:04x}-0000-1000-8000-00805f9b34fb:00" for i in range(0xE000, 0xE040)],
        })
        obs = BLEObservation.objects.get()
        self.assertEqual(len(obs.manufacturer_records), 32)
        self.assertEqual(len(obs.service_data), 32)

    def test_device_signature_ids_accumulate(self):
        self._post("sig-a", {"ble_mac": "7A:11:22:33:44:58", "rssi": -60, "device_name": "Flipper Zero"})
        self._post("sig-b", {"ble_mac": "7A:11:22:33:44:58", "rssi": -61, "device_name": ""})
        self.assertEqual(BLEDevice.objects.get(device_key="7A:11:22:33:44:58").signature_ids, ["fleet-flipper"])
        self.assertEqual(BLEObservation.objects.filter(signature_matches=[]).count(), 1)

    def test_device_signature_ids_are_a_sorted_union(self):
        self._post("sig-c", {"ble_mac": "7A:11:22:33:44:59", "rssi": -60, "device_name": "Tile"})
        self._post("sig-d", {"ble_mac": "7A:11:22:33:44:59", "rssi": -60, "device_name": "Flipper"})
        self.assertEqual(
            BLEDevice.objects.get(device_key="7A:11:22:33:44:59").signature_ids, ["fleet-flipper", "fleet-tile"],
        )

    def test_export_import_preserves_signature_inputs(self):
        self._post("sig-io", {
            "ble_mac": "7A:11:22:33:44:5A", "rssi": -60,
            "manufacturer_records": ["0075:0102"],
            "service_data": ["0000fd5a-0000-1000-8000-00805f9b34fb:01"],
        })
        reader = APIClient()
        reader.force_authenticate(user=get_user_model().objects.create_user(username="op", password="pw-123456"))
        exported = reader.get("/api/v1/scan-sessions/export/").json()
        ble = exported["sessions"][0]["ble_observations"][0]
        self.assertEqual(ble["manufacturer_records"], ["0075:0102"])
        self.assertEqual(ble["service_data"], ["0000fd5a-0000-1000-8000-00805f9b34fb:01"])

        ScanSession.objects.all().delete()
        BLEDevice.objects.all().delete()
        restored = reader.post("/api/v1/scan-sessions/import_sessions/", exported, format="json")
        self.assertEqual(restored.json()["created"], 1, restored.content)

        obs = BLEObservation.objects.get()
        self.assertEqual(obs.manufacturer_records, ["0075:0102"])
        self.assertEqual([m["id"] for m in obs.signature_matches], ["fleet-smarttag"])
        self.assertEqual(BLEDevice.objects.get().signature_ids, ["fleet-smarttag"])

    def test_read_serializer_exposes_matches(self):
        self._post("sig-read", {"ble_mac": "7A:11:22:33:44:5B", "rssi": -60, "device_name": "Flipper"})
        reader = APIClient()
        reader.force_authenticate(user=get_user_model().objects.create_user(username="op2", password="pw-123456"))
        rows = reader.get("/api/v1/ble-devices/7A:11:22:33:44:5B/ble-observations/").json()
        self.assertEqual(rows[0]["signature_matches"], [{"id": "fleet-flipper", "rule": "NAME_CONTAINS"}])
        self.assertEqual(rows[0]["signature_catalog_version"], 98)

    def test_coverage_reports_signature_names(self):
        # The heatmap's Type column prefers a signature name over the legacy badge.
        self._post("sig-cov-1", {"ble_mac": "7A:11:22:33:44:5C", "rssi": -60, "device_name": "Flipper"})
        self._post("sig-cov-2", {"ble_mac": "7A:11:22:33:44:5C", "rssi": -62, "device_name": ""})
        self._post("sig-cov-3", {"ble_mac": "7A:11:22:33:44:5D", "rssi": -62, "device_name": "Kitchen scale"})
        reader = APIClient()
        reader.force_authenticate(user=get_user_model().objects.create_user(username="op3", password="pw-123456"))
        rows = {r["key"]: r for r in reader.get("/api/v1/ble-observations/coverage/").json()["results"]}
        self.assertEqual(rows["7A:11:22:33:44:5C"]["signature_names"], ["Flipper Zero"])
        self.assertEqual(rows["7A:11:22:33:44:5D"]["signature_names"], [])


class RelabelAndApiTests(TestCase):
    def setUp(self):
        self.session = ScanSession.objects.create(
            sensor=Sensor.objects.create(name="Field Phone"), client_scan_id="relabel-1",
            started_at="2026-11-07T10:00:00Z", completed_at="2026-11-07T10:00:06Z",
        )
        self.flipper = self._raw("7A:00:00:00:00:01", name="Flipper Zero")
        self.airtag = self._raw("5C:00:00:00:00:02", legacy="004c:12190000")
        self.plain = self._raw("7A:00:00:00:00:03", name="Kitchen scale")
        self.reader = APIClient()
        self.reader.force_authenticate(user=get_user_model().objects.create_user(username="op", password="pw-123456"))

    def _raw(self, mac, name="", legacy=""):
        """A row created without ingest, so it carries no labels yet."""
        device = BLEDevice.objects.create(device_key=mac, device_name=name)
        BLEObservation.objects.create(
            scan_session=self.session, ble_device=device, ble_mac=mac, rssi=-60,
            device_name=name, manufacturer_data_raw=legacy, observed_at="2026-11-07T10:00:01Z",
        )
        return device

    def _state(self):
        return (
            sorted(BLEObservation.objects.values_list("ble_mac", "signature_matches", "signature_catalog_version")),
            sorted(BLEDevice.objects.values_list("device_key", "signature_ids", "last_seen_at")),
        )

    def test_relabel_labels_unlabeled_rows(self):
        self.assertEqual(BLEObservation.objects.exclude(signature_matches=[]).count(), 0)
        result = relabel_all()
        self.assertEqual(result, {"observations": 3, "devices": 3, "catalog_version": 98})
        self.flipper.refresh_from_db()
        self.airtag.refresh_from_db()
        self.plain.refresh_from_db()
        self.assertEqual(self.flipper.signature_ids, ["fleet-flipper"])
        self.assertEqual(self.airtag.signature_ids, ["fleet-airtag"])
        self.assertEqual(self.plain.signature_ids, [])
        self.assertEqual(set(BLEObservation.objects.values_list("signature_catalog_version", flat=True)), {98})

    def test_relabel_command_prints_counts(self):
        out = io.StringIO()
        call_command("relabel_ble_signatures", stdout=out)
        self.assertIn("'observations': 3", out.getvalue())
        self.assertIn("'catalog_version': 98", out.getvalue())

    def test_relabel_drops_removed_fleet(self):
        relabel_all()
        stock = load_catalog()
        newer = parse_catalog({
            "format": "fieldwatch-signatures", "formatVersion": 1, "catalogVersion": 99,
            "fleets": [_fleet(f.id, [_rule(r.kind, r.text, r.company_id, r.data_prefix_hex, r.radio) for r in f.rules],
                              kind=f.kind, match_any=f.match_any)
                       for f in stock.fleets if f.id != "fleet-flipper"],
        })
        relabel_all(newer)
        self.flipper.refresh_from_db()
        self.airtag.refresh_from_db()
        self.assertEqual(self.flipper.signature_ids, [])
        self.assertEqual(self.airtag.signature_ids, ["fleet-airtag"])
        self.assertEqual(BLEObservation.objects.get(ble_mac="7A:00:00:00:00:01").signature_matches, [])
        self.assertEqual(set(BLEObservation.objects.values_list("signature_catalog_version", flat=True)), {99})

    def test_relabel_is_idempotent(self):
        relabel_all(batch_size=2)
        first = self._state()
        relabel_all(batch_size=2)
        self.assertEqual(self._state(), first)

    def test_relabel_does_not_touch_last_seen(self):
        before = dict(BLEDevice.objects.values_list("device_key", "last_seen_at"))
        relabel_all()
        self.assertEqual(dict(BLEDevice.objects.values_list("device_key", "last_seen_at")), before)

    def test_device_without_observations_is_cleared(self):
        BLEDevice.objects.create(device_key="orphan", signature_ids=["fleet-flipper"])
        relabel_all()
        self.assertEqual(BLEDevice.objects.get(device_key="orphan").signature_ids, [])

    def test_unknown_signature_id_is_served_without_crash(self):
        BLEDevice.objects.filter(pk=self.plain.pk).update(signature_ids=["fleet-gone"])
        BLEObservation.objects.filter(ble_device=self.plain).update(
            signature_matches=[{"id": "fleet-gone", "rule": "NAME_GLOB"}],
        )
        listed = self.reader.get("/api/v1/ble-devices/")
        self.assertEqual(listed.status_code, 200)
        detail = self.reader.get(f"/api/v1/ble-devices/{self.plain.device_key}/")
        self.assertEqual(detail.status_code, 200)
        self.assertEqual(detail.json()["signatures"], [{
            "id": "fleet-gone", "name": "fleet-gone", "kind": "OTHER", "rules": ["NAME_GLOB"],
            "notes": "", "attention_note": "",
        }])

    def test_device_serializer_signatures(self):
        relabel_all()
        listed = {d["device_key"]: d for d in self.reader.get("/api/v1/ble-devices/").json()["results"]}
        self.assertEqual(listed[self.flipper.device_key]["signatures"], [
            {"id": "fleet-flipper", "name": "Flipper Zero", "kind": "HACKING", "rules": ["NAME_CONTAINS"]},
        ])
        self.assertEqual(listed[self.plain.device_key]["signatures"], [])
        self.assertNotIn("notes", listed[self.flipper.device_key]["signatures"][0])
        detail = self.reader.get(f"/api/v1/ble-devices/{self.flipper.device_key}/").json()
        flipper = load_catalog().get("fleet-flipper")
        self.assertEqual(detail["signatures"][0]["notes"], flipper.notes)
        self.assertEqual(detail["signatures"][0]["attention_note"], flipper.attention_note)

    def test_filter_by_signature_and_class(self):
        relabel_all()
        keys = lambda query: {  # noqa: E731
            d["device_key"] for d in self.reader.get(f"/api/v1/ble-devices/?{query}").json()["results"]
        }
        self.assertEqual(keys("signature_class=HACKING"), {self.flipper.device_key})
        self.assertEqual(keys("signature_class=FINDER"), {self.airtag.device_key})
        self.assertEqual(keys("signature=fleet-airtag"), {self.airtag.device_key})
        self.assertEqual(keys("signature=fleet-nope"), set())
        self.assertEqual(keys("signature_class=NOPE"), set())

    def test_signature_list_is_ble_only_and_authenticated(self):
        response = self.reader.get("/api/v1/ble-signatures/")
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["catalog_version"], 98)
        self.assertEqual(len(body["results"]), 173)
        ids = {r["id"] for r in body["results"]}
        self.assertIn("fleet-flipper", ids)
        self.assertNotIn("fleet-meraki", ids)
        self.assertEqual(set(body["results"][0]), {"id", "name", "kind"})
        self.assertEqual(APIClient().get("/api/v1/ble-signatures/").status_code, 403)
