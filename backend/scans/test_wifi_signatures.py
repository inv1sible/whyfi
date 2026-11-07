"""Wi-Fi signature labels. See docs/superpowers/plans/2026-11-07-wifi-signature-labels.md."""

import json
from pathlib import Path

from django.contrib.auth import get_user_model
from django.test import SimpleTestCase, TestCase
from rest_framework.test import APIClient

from sensors.models import Sensor

from .ble_signatures import parse_catalog
from .test_ble_signatures import _catalog_dict, _fleet, _rule
from .models import AccessPoint, ScanSession, WiFiObservation
from .wifi_signatures import match_wifi, parse_wifi, universal_oui

FIXTURE = Path(__file__).resolve().parent / "fixtures" / "wifi_signature_cases.json"


def _ap(bssid="", ssid="", vendor_ie_ouis=()):
    return parse_wifi(bssid, ssid, list(vendor_ie_ouis))


class WifiMatcherFixtureTests(SimpleTestCase):
    def test_fixture_cases(self):
        cases = json.loads(FIXTURE.read_text())
        for case in cases:
            with self.subTest(case["name"]):
                a = case["ap"]
                hits = match_wifi(_ap(a["bssid"], a["ssid"], a["vendor_ie_ouis"]))
                self.assertEqual([h["id"] for h in hits], case["expect"])


class WifiMatcherTests(SimpleTestCase):
    def test_universal_oui(self):
        self.assertEqual(universal_oui("02:18:0A:11:22:33"), "00180A")
        self.assertEqual(universal_oui("9e-aa-bb-00-00-01"), "9CAABB")
        self.assertIsNone(universal_oui("00:18:0A:11:22:33"))  # already universal
        self.assertIsNone(universal_oui("03:18:0A:11:22:33"))  # multicast
        self.assertIsNone(universal_oui("02:18"))

    def test_virtual_bssid_matches_universal_oui(self):
        self.assertEqual(
            match_wifi(_ap("02:18:0A:11:22:33")),
            [{"id": "fleet-meraki", "rule": "OUI", "via": "bssid_universal"}],
        )
        self.assertEqual(
            match_wifi(_ap("00:18:0A:11:22:33")),
            [{"id": "fleet-meraki", "rule": "OUI", "via": "bssid"}],
        )

    def test_vendor_ie_evidence(self):
        self.assertEqual(
            match_wifi(_ap("9E:AA:BB:00:00:01", "x", ["00:00:0C"])),
            [{"id": "fleet-cisco", "rule": "OUI", "via": "vendor_ie"}],
        )
        self.assertEqual(
            match_wifi(_ap("9E:AA:BB:00:00:01", "x", ["FA:0B:BC"])),
            [{"id": "fleet-remote-id", "rule": "VENDOR_IE_OUI", "via": "vendor_ie"}],
        )

    def test_rule_is_first_hit_in_catalog_order(self):
        # Meraki's rules run NAME_GLOB, NAME_CONTAINS, OUI...: the glob wins.
        self.assertEqual(
            match_wifi(_ap("00:18:0A:11:22:33", "Meraki-Guest")),
            [{"id": "fleet-meraki", "rule": "NAME_GLOB"}],
        )

    def test_protocol_vendor_ies_ignored(self):
        catalog = parse_catalog(_catalog_dict([
            _fleet("fleet-ms-oui", [_rule("OUI", "00:50:F2")]),
            _fleet("fleet-ms-ie", [_rule("VENDOR_IE_OUI", "00:50:F2")]),
            _fleet("fleet-rsn-long", [_rule("VENDOR_IE_OUI", "00:0F:AC:04")]),
        ]))
        self.assertEqual(match_wifi(_ap("9E:AA:BB:00:00:01", "x", ["00:50:F2", "00:0F:AC"]), catalog), [])
        # The BSSID path still applies to a protocol OUI used as an address.
        self.assertEqual(
            [h["id"] for h in match_wifi(_ap("00:50:F2:00:00:01"), catalog)], ["fleet-ms-oui"],
        )

    def test_wifi_only_kinds(self):
        catalog = parse_catalog(_catalog_dict([
            _fleet("fleet-hidden", [_rule("HIDDEN_SSID")]),
            _fleet("fleet-radio-wifi", [_rule("RADIO_KIND", radio="WIFI")]),
            _fleet("fleet-radio-ble", [_rule("RADIO_KIND", radio="BLE")]),
            _fleet("fleet-radio-any", [_rule("RADIO_KIND")]),
            _fleet("fleet-ble-payload", [_rule("MANUFACTURER_ID", company_id=0x4C), _rule("SERVICE_UUID", "FEED")]),
            _fleet("fleet-ble-name", [_rule("NAME_CONTAINS", "Home", radio="BLE")]),
        ]))
        self.assertEqual(
            [h["id"] for h in match_wifi(_ap("9E:AA:BB:00:00:01", ""), catalog)],
            ["fleet-hidden", "fleet-radio-wifi", "fleet-radio-any"],
        )
        self.assertEqual(
            [h["id"] for h in match_wifi(_ap("9E:AA:BB:00:00:01", "Home"), catalog)],
            ["fleet-radio-wifi", "fleet-radio-any"],
        )

    def test_mac_prefix_and_long_prefixes(self):
        catalog = parse_catalog(_catalog_dict([
            _fleet("fleet-mac6", [_rule("MAC_PREFIX", "9C:AA:BB")]),
            _fleet("fleet-mac-long", [_rule("MAC_PREFIX", "9E:AA:BB:00")]),
            _fleet("fleet-oui-long-ie", [_rule("OUI", "FA:0B")]),
        ]))
        hits = match_wifi(_ap("9E:AA:BB:00:00:01", "x", ["FA:0B:BC"]), catalog)
        self.assertEqual(hits, [
            {"id": "fleet-mac6", "rule": "MAC_PREFIX", "via": "bssid_universal"},
            {"id": "fleet-mac-long", "rule": "MAC_PREFIX", "via": "bssid"},
            {"id": "fleet-oui-long-ie", "rule": "OUI", "via": "vendor_ie"},
        ])

    def test_match_all(self):
        catalog = parse_catalog(_catalog_dict([
            _fleet("fleet-both", [_rule("NAME_GLOB", "Cam*"), _rule("VENDOR_IE_OUI", "AA:BB:CC")], match_any=False),
        ]))
        self.assertEqual(match_wifi(_ap("9E:AA:BB:00:00:01", "Cam1"), catalog), [])
        self.assertEqual(
            match_wifi(_ap("9E:AA:BB:00:00:01", "Cam1", ["AA:BB:CC"]), catalog),
            [{"id": "fleet-both", "rule": "NAME_GLOB"}],
        )

    def test_parse_wifi_normalises_and_skips_malformed(self):
        parsed = _ap("9e:aa:bb:00:00:01", "x", ["fa:0b:bc", "FA-0B-BC", "zz:00:11", "12", 7, "00:50:F2:01"])
        self.assertEqual(parsed.vendor_ie_ouis, ("FA0BBC",))
        self.assertEqual(parsed.bssid_hex, "9EAABB000001")


def _wifi(bssid, ssid="", **extra):
    return {"bssid": bssid, "ssid": ssid, "rssi": -60, "frequency_mhz": 2437,
            "capabilities": "[WPA2-PSK-CCMP][ESS]", **extra}


class IngestWifiSignatureTests(TestCase):
    def setUp(self):
        self.sensor = Sensor.objects.create(name="Field Phone")
        self.ingest = APIClient()
        self.ingest.credentials(HTTP_AUTHORIZATION=f"Token {self.sensor.token}")
        self.reader = APIClient()
        self.reader.force_authenticate(user=get_user_model().objects.create_user(username="op", password="pw-123456"))

    def _post(self, client_scan_id, *observations):
        response = self.ingest.post("/api/v1/scan-sessions/", {
            "client_scan_id": client_scan_id,
            "started_at": "2026-11-07T10:00:00Z", "completed_at": "2026-11-07T10:00:06Z",
            "latitude": 48.1351, "longitude": 11.582,
            "wifi_observations": list(observations),
        }, format="json")
        self.assertEqual(response.status_code, 201, response.content)

    def test_new_payload_is_labeled(self):
        self._post("w-1", _wifi("9E:AA:BB:00:00:01", "DJI-Mavic-3", vendor_ie_ouis=["FA:0B:BC", "00:50:F2"]))
        obs = WiFiObservation.objects.get()
        self.assertEqual(obs.vendor_ie_ouis, ["FA:0B:BC", "00:50:F2"])
        self.assertEqual(obs.signature_matches, [
            {"id": "fleet-dji", "rule": "NAME_CONTAINS"},
            {"id": "fleet-remote-id", "rule": "VENDOR_IE_OUI", "via": "vendor_ie"},
        ])
        self.assertEqual(obs.signature_catalog_version, 98)
        self.assertEqual(AccessPoint.objects.get().signature_ids, ["fleet-dji", "fleet-remote-id"])

    def test_legacy_wifi_payload_is_labeled(self):
        self._post("w-legacy", _wifi("24:16:1B:00:00:01", "Corp"))
        obs = WiFiObservation.objects.get()
        self.assertEqual(obs.vendor_ie_ouis, [])
        self.assertEqual(obs.signature_matches, [{"id": "fleet-cisco", "rule": "OUI", "via": "bssid"}])

    def test_vendor_ies_truncated_not_rejected(self):
        self._post("w-many", _wifi("9E:AA:BB:00:00:02", "x", vendor_ie_ouis=[f"AA:BB:{i:02X}" for i in range(40)]))
        self.assertEqual(len(WiFiObservation.objects.get().vendor_ie_ouis), 32)

    def test_hidden_sighting_uses_the_known_ssid(self):
        # The SSID lives on AccessPoint; a later hidden sighting keeps its name.
        self._post("w-h1", _wifi("9E:AA:BB:00:00:03", "DJI-Mavic-3"))
        self._post("w-h2", _wifi("9E:AA:BB:00:00:03", ""))
        self.assertEqual(
            [o.signature_matches for o in WiFiObservation.objects.order_by("id")],
            [[{"id": "fleet-dji", "rule": "NAME_CONTAINS"}]] * 2,
        )

    def test_ap_signature_ids_accumulate(self):
        self._post("w-a", _wifi("9E:AA:BB:00:00:04", "x", vendor_ie_ouis=["FA:0B:BC"]))
        self._post("w-b", _wifi("9E:AA:BB:00:00:04", "x"))
        self.assertEqual(AccessPoint.objects.get().signature_ids, ["fleet-remote-id"])

    def test_export_import_preserves_vendor_ies(self):
        self._post("w-io", _wifi("9E:AA:BB:00:00:05", "x", vendor_ie_ouis=["50:52:4B"]))
        exported = self.reader.get("/api/v1/scan-sessions/export/").json()
        self.assertEqual(exported["sessions"][0]["wifi_observations"][0]["vendor_ie_ouis"], ["50:52:4B"])
        ScanSession.objects.all().delete()
        AccessPoint.objects.all().delete()
        restored = self.reader.post("/api/v1/scan-sessions/import_sessions/", exported, format="json")
        self.assertEqual(restored.json()["created"], 1, restored.content)
        self.assertEqual(AccessPoint.objects.get().signature_ids, ["fleet-porkchop"])

    def test_read_serializer_exposes_matches(self):
        self._post("w-read", _wifi("24:16:1B:00:00:02", "Corp"))
        rows = self.reader.get("/api/v1/access-points/24:16:1B:00:00:02/wifi-observations/").json()
        self.assertEqual(rows[0]["signature_matches"], [{"id": "fleet-cisco", "rule": "OUI", "via": "bssid"}])
        self.assertEqual(rows[0]["vendor_ie_ouis"], [])
