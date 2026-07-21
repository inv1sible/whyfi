from django.contrib.auth import get_user_model
from django.test import TestCase
from rest_framework.test import APIClient

from sensors.models import Sensor

from .models import AccessPoint, ScanSession


class HealthCheckTests(TestCase):
    def test_health_ok(self):
        response = APIClient().get("/api/v1/health/")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"status": "ok"})


class ScanSessionIngestTests(TestCase):
    def setUp(self):
        self.sensor = Sensor.objects.create(name="Test Phone")
        self.client = APIClient()
        self.client.credentials(HTTP_AUTHORIZATION=f"Token {self.sensor.token}")

    def _payload(self, client_scan_id="scan-1"):
        return {
            "client_scan_id": client_scan_id,
            "started_at": "2026-07-16T10:00:00Z",
            "completed_at": "2026-07-16T10:00:03Z",
            "latitude": 48.1351,
            "longitude": 11.582,
            "location_accuracy_meters": 12.5,
            "location_provider": "gps",
            "wifi_observations": [
                {"bssid": "aa:bb:cc:dd:ee:ff", "ssid": "MyNetwork", "rssi": -55,
                 "frequency_mhz": 2437, "capabilities": "[WPA2-PSK-CCMP][ESS]"},
            ],
            "cell_observations": [
                {"mcc": "262", "mnc": "01", "radio_type": "LTE", "is_serving_cell": True,
                 "signal_dbm": -85, "rsrp": -95, "rsrq": -10, "sinr": 12},
            ],
            "ble_observations": [
                {"ble_mac": "11:22:33:44:55:66", "rssi": -70, "tx_power": -12,
                 "manufacturer_data": "4c00", "service_uuids": []},
            ],
            "satellite_observations": [
                {"constellation": "GPS", "svid": 14, "cn0_db_hz": 34.5,
                 "elevation_degrees": 61.2, "azimuth_degrees": 210.0, "used_in_fix": True},
            ],
            "lan_observations": [
                {"ip_address": "192.168.1.42", "mac_address": "aa:bb:cc:11:22:33",
                 "hostname": "printer.local", "vendor_oui": "aa:bb:cc", "open_ports": [80, 443]},
            ],
        }

    def test_ingest_requires_auth(self):
        anon = APIClient()
        response = anon.post("/api/v1/scan-sessions/", self._payload(), format="json")
        self.assertEqual(response.status_code, 401)

    def test_ingest_creates_all_radio_types(self):
        response = self.client.post("/api/v1/scan-sessions/", self._payload(), format="json")
        self.assertEqual(response.status_code, 201, response.content)

        session = ScanSession.objects.get(client_scan_id="scan-1")
        self.assertEqual(session.wifi_observations.count(), 1)
        self.assertEqual(session.cell_observations.count(), 1)
        self.assertEqual(session.ble_observations.count(), 1)
        self.assertEqual(session.satellite_observations.count(), 1)
        self.assertEqual(session.lan_observations.count(), 1)
        self.assertTrue(AccessPoint.objects.filter(bssid="aa:bb:cc:dd:ee:ff").exists())

    def test_ingest_stores_location_accuracy_and_provider(self):
        self.client.post("/api/v1/scan-sessions/", self._payload("scan-loc"), format="json")
        session = ScanSession.objects.get(client_scan_id="scan-loc")
        self.assertEqual(session.location_accuracy_meters, 12.5)
        self.assertEqual(session.location_provider, "gps")

    def test_ingest_is_idempotent_on_client_scan_id(self):
        payload = self._payload("scan-dup")
        first = self.client.post("/api/v1/scan-sessions/", payload, format="json")
        second = self.client.post("/api/v1/scan-sessions/", payload, format="json")
        self.assertEqual(first.status_code, 201)
        self.assertEqual(second.status_code, 201)
        self.assertEqual(first.json()["id"], second.json()["id"])

        session = ScanSession.objects.get(client_scan_id="scan-dup")
        self.assertEqual(session.wifi_observations.count(), 1)  # not duplicated

    def test_ingest_updates_last_seen_at_even_when_ssid_unchanged(self):
        self.client.post("/api/v1/scan-sessions/", self._payload("scan-first"), format="json")
        access_point = AccessPoint.objects.get(bssid="aa:bb:cc:dd:ee:ff")
        first_seen_at = access_point.last_seen_at

        self.client.post("/api/v1/scan-sessions/", self._payload("scan-second"), format="json")
        access_point.refresh_from_db()
        self.assertGreater(access_point.last_seen_at, first_seen_at)

    def test_ingest_rejects_invalid_token(self):
        bad_client = APIClient()
        bad_client.credentials(HTTP_AUTHORIZATION="Token not-a-real-token")
        response = bad_client.post("/api/v1/scan-sessions/", self._payload("scan-2"), format="json")
        self.assertEqual(response.status_code, 401)


class ReadEndpointTests(TestCase):
    """Read endpoints require a logged-in session (the same admin account
    used for /admin/) — see MEMORY.md. Ingest stays sensor-token-only."""

    def setUp(self):
        self.sensor = Sensor.objects.create(name="Test Phone")
        ingest_client = APIClient()
        ingest_client.credentials(HTTP_AUTHORIZATION=f"Token {self.sensor.token}")
        ingest_client.post(
            "/api/v1/scan-sessions/",
            {
                "client_scan_id": "scan-read-1",
                "started_at": "2026-07-16T10:00:00Z",
                "completed_at": "2026-07-16T10:00:03Z",
                "latitude": 48.1351,
                "longitude": 11.582,
                "location_accuracy_meters": 8.0,
                "location_provider": "gps",
                "wifi_observations": [
                    {"bssid": "aa:bb:cc:dd:ee:ff", "ssid": "MyNetwork", "rssi": -55,
                     "frequency_mhz": 2437, "capabilities": "[WPA2-PSK-CCMP][ESS]"},
                ],
                "lan_observations": [
                    {"ip_address": "192.168.1.10", "hostname": "router.local", "open_ports": [80, 443]},
                ],
            },
            format="json",
        )

        self.user = get_user_model().objects.create_user(username="operator", password="test-pass-123")
        self.client = APIClient()
        self.client.force_authenticate(user=self.user)

    def test_anonymous_read_is_rejected(self):
        # 403, not 401 — see MEMORY.md (SessionAuthentication sets no
        # WWW-Authenticate header, so DRF reports anonymous as 403).
        response = APIClient().get("/api/v1/access-points/")
        self.assertEqual(response.status_code, 403)

    def test_access_points_list_includes_channel(self):
        response = self.client.get("/api/v1/access-points/")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["count"], 1)
        ap = response.json()["results"][0]
        self.assertEqual(ap["latest_channel"], 6)

    def test_channel_congestion(self):
        # Explicit `since` in the past — the default (no `since`) window is
        # a rolling last-24h from *now*, which the fixed 2026-07-16 test
        # fixture data would fall outside of whenever this actually runs.
        response = self.client.get("/api/v1/channel-congestion/?band=2.4GHz&since=2020-01-01T00:00:00Z")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), [{"channel": 6, "ap_count": 1}])

    def test_scan_sessions_list_requires_login_not_sensor_token(self):
        # A sensor token authenticates ingest (create) only — reading the
        # session list is a human/browser action requiring a login session.
        # GET only recognizes SessionAuthentication, so a bearer token here
        # is simply not understood -> treated as anonymous -> 403.
        sensor_client = APIClient()
        sensor_client.credentials(HTTP_AUTHORIZATION=f"Token {self.sensor.token}")
        response = sensor_client.get("/api/v1/scan-sessions/")
        self.assertEqual(response.status_code, 403)

        response = self.client.get("/api/v1/scan-sessions/")
        self.assertEqual(response.status_code, 200)
        session = response.json()["results"][0]
        self.assertEqual(session["location_accuracy_meters"], 8.0)
        self.assertEqual(session["location_provider"], "gps")

    def test_lan_observations_list(self):
        response = self.client.get("/api/v1/lan-observations/")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["count"], 1)
        self.assertEqual(response.json()["results"][0]["hostname"], "router.local")
