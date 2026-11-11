"""The Manage scans list and "last N scans" with the focus area (user,
2026-11-11: a location and date/time filter on scans, and the two combined).
"""
from django.contrib.auth import get_user_model
from django.test import TestCase
from rest_framework.test import APIClient

from sensors.models import Sensor

CENTRE = (48.1351, 11.582)
# Two sites ~2.2 km apart, scanned alternately over a morning.
SITE_A = (48.1351, 11.5820)
SITE_B = (48.1551, 11.5820)
AREA_A = f"area_lat={CENTRE[0]}&area_lng={CENTRE[1]}&area_radius_m=300"


class ScanListFilterTests(TestCase):
    def setUp(self):
        self.sensor = Sensor.objects.create(name="Test Phone")
        ingest = APIClient()
        ingest.credentials(HTTP_AUTHORIZATION=f"Token {self.sensor.token}")
        self.scans = []
        plan = [
            ("a1", "08:00", SITE_A), ("b1", "09:00", SITE_B), ("a2", "10:00", SITE_A),
            ("b2", "11:00", SITE_B), ("a3", "12:00", SITE_A), ("b3", "13:00", SITE_B),
            ("nowhere", "14:00", None),
        ]
        for name, hour, site in plan:
            payload = {
                "client_scan_id": name,
                "started_at": f"2026-07-16T{hour}:00Z",
                "completed_at": f"2026-07-16T{hour}:03Z",
                "wifi_observations": [
                    {"bssid": f"aa:bb:cc:dd:ee:{len(self.scans):02x}", "ssid": name.upper(), "rssi": -50,
                     "frequency_mhz": 2437, "capabilities": "[ESS]"},
                ],
            }
            if site:
                payload["latitude"], payload["longitude"] = site
            response = ingest.post("/api/v1/scan-sessions/", payload, format="json")
            self.assertEqual(response.status_code, 201, response.content)
            self.scans.append(name)
        self.user = get_user_model().objects.create_user(username="operator", password="test-pass-123")
        self.client = APIClient()
        self.client.force_authenticate(user=self.user)

    def names(self, query=""):
        body = self.client.get(f"/api/v1/scan-sessions/?{query}").json()
        return [row["started_at"][11:16] for row in body["results"]]

    def test_without_filters_every_scan_is_listed(self):
        self.assertEqual(self.names(), ["14:00", "13:00", "12:00", "11:00", "10:00", "09:00", "08:00"])

    def test_area_keeps_the_scans_recorded_inside_it(self):
        # The unpositioned scan is never inside an area.
        self.assertEqual(self.names(AREA_A), ["12:00", "10:00", "08:00"])

    def test_date_range_bounds_the_scan_start(self):
        self.assertEqual(
            self.names("since=2026-07-16T09:30:00Z&until=2026-07-16T12:30:00Z"),
            ["12:00", "11:00", "10:00"],
        )

    def test_date_range_and_area_combine(self):
        self.assertEqual(self.names(f"since=2026-07-16T09:30:00Z&until=2026-07-16T12:30:00Z&{AREA_A}"), ["12:00", "10:00"])

    def test_last_n_scans_inside_the_area(self):
        # Last 2 scans overall are 14:00 and 13:00; inside the area they are
        # 12:00 and 10:00 — the whole point of combining the two filters.
        self.assertEqual(self.names("session_limit=2"), ["14:00", "13:00"])
        self.assertEqual(self.names(f"session_limit=2&{AREA_A}"), ["12:00", "10:00"])

    def test_last_n_scans_on_a_device_list_count_only_scans_inside_the_area(self):
        # Without an area, "last 1 scan" is the unpositioned 14:00 scan and the
        # coverage map (which needs positions) is empty. With the area it is
        # the 12:00 scan at site A.
        body = self.client.get("/api/v1/access-points/coverage/?session_limit=1").json()
        self.assertEqual([e["ssid"] for e in body["results"]], [])
        body = self.client.get(f"/api/v1/access-points/coverage/?session_limit=1&{AREA_A}").json()
        self.assertEqual([e["ssid"] for e in body["results"]], ["A3"])

    def test_export_follows_the_same_filters(self):
        body = self.client.get(f"/api/v1/scan-sessions/export/?session_limit=2&{AREA_A}").json()
        self.assertEqual(sorted(s["client_scan_id"] for s in body["sessions"]), ["a2", "a3"])
        body = self.client.get("/api/v1/scan-sessions/export/?since=2026-07-16T12:30:00Z").json()
        self.assertEqual(sorted(s["client_scan_id"] for s in body["sessions"]), ["b3", "nowhere"])
