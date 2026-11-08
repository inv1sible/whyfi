"""signatures/match: catalog labels for one pass, nothing stored."""

from django.test import TestCase
from rest_framework.test import APIClient

from sensors.models import Sensor

from .models import BLEObservation, ScanSession
from .signature_lookup import lookup

# Adverts as stored from real passes (names/MACs shortened).
NESPRESSO = {
    "ble_mac": "98:CD:AC:4C:67:8A", "device_name": "Venus_98CDAC4C678A",
    "manufacturer_records": ["2502:408900000000"],
    "service_uuids": ["06aa1910-f22a-11e3-9daa-0002a5d5c51b"],
}
TESLA = {
    "ble_mac": "6A:11:22:33:44:55", "device_name": "S512d65624d55b716C",
    "service_uuids": ["00001122-0000-1000-8000-00805f9b34fb"],
}
VW = {"ble_mac": "4B:00:00:00:00:01", "service_uuids": ["0000fe30-0000-1000-8000-00805f9b34fb"]}
PLAIN = {"ble_mac": "7F:00:00:00:00:02", "device_name": "", "service_uuids": []}


class LookupTests(TestCase):
    def test_labels_known_adverts_and_skips_unmatched(self):
        out = lookup({"ble": [NESPRESSO, TESLA, VW, PLAIN]})
        names = {mac: [l["name"] for l in labels] for mac, labels in out["ble"].items()}
        self.assertIn("Nespresso", names[NESPRESSO["ble_mac"]])
        self.assertIn("Tesla", names[TESLA["ble_mac"]])
        self.assertIn("Volkswagen", names[VW["ble_mac"]])
        self.assertNotIn(PLAIN["ble_mac"], out["ble"])
        self.assertEqual(out["ble"][TESLA["ble_mac"]][0]["kind"], "VEHICLE")

    def test_wifi_hotspot_by_ssid(self):
        out = lookup({"wifi": [{"bssid": "02:11:22:33:44:55", "ssid": "BMW_12345", "vendor_ie_ouis": []}]})
        self.assertEqual([l["name"] for l in out["wifi"]["02:11:22:33:44:55"]], ["BMW"])

    def test_junk_is_skipped_not_fatal(self):
        out = lookup({"ble": ["x", {"ble_mac": 5}, {"ble_mac": "AA", "service_uuids": "nope"}], "wifi": None})
        self.assertEqual(out["ble"], {})
        self.assertEqual(lookup(None)["wifi"], {})


class EndpointTests(TestCase):
    def test_needs_sensor_token_and_stores_nothing(self):
        self.assertIn(APIClient().post("/api/v1/signatures/match/", {"ble": [NESPRESSO]}, format="json").status_code, (401, 403))
        sensor = Sensor.objects.create(name="Field Phone")
        client = APIClient()
        client.credentials(HTTP_AUTHORIZATION=f"Token {sensor.token}")
        response = client.post("/api/v1/signatures/match/", {"ble": [NESPRESSO]}, format="json")
        self.assertEqual(response.status_code, 200, response.content)
        self.assertEqual(response.json()["ble"][NESPRESSO["ble_mac"]][0]["name"], "Nespresso")
        self.assertEqual(BLEObservation.objects.count(), 0)
        self.assertEqual(ScanSession.objects.count(), 0)
