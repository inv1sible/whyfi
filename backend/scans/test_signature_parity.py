"""The shared parity fixture still describes this matcher and catalog.

The Android port (com.whyfi.app.signatures, SignatureParityTest) checks the
same file, so a phone and the server label identically. If this fails after
a catalog or matcher change, regenerate (see generate_signature_parity) and
copy the fixture to android/app/src/test/resources/.
"""

import hashlib
import json
from pathlib import Path

from django.test import SimpleTestCase

from .ble_signatures import CATALOG_PATH, load_catalog, match_ble, parse_advert
from .wifi_signatures import match_wifi, parse_wifi

FIXTURE = Path(__file__).resolve().parent / "fixtures" / "signature_parity_cases.json"


class SignatureParityTests(SimpleTestCase):
    @classmethod
    def setUpClass(cls):
        super().setUpClass()
        cls.data = json.loads(FIXTURE.read_text(encoding="utf-8"))

    def test_fixture_is_for_this_catalog(self):
        self.assertEqual(self.data["catalog_sha256"], hashlib.sha256(CATALOG_PATH.read_bytes()).hexdigest())

    def test_ble_cases(self):
        catalog = load_catalog()
        self.assertGreater(len(self.data["ble"]), 500)
        for case in self.data["ble"]:
            a = case["advert"]
            got = match_ble(parse_advert(a["mac"], a["name"], a["service_uuids"], a["manufacturer_records"], a["service_data"]), catalog)
            self.assertEqual(got, case["expect"], a)

    def test_wifi_cases(self):
        catalog = load_catalog()
        self.assertGreater(len(self.data["wifi"]), 500)
        for case in self.data["wifi"]:
            ap = case["ap"]
            self.assertEqual(match_wifi(parse_wifi(ap["bssid"], ap["ssid"], ap["vendor_ie_ouis"]), catalog), case["expect"], ap)
