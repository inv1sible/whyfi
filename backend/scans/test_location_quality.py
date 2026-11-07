"""Scan position quality: ingest check against the fused fix, and the
non-destructive history cleanup. See
docs/superpowers/plans/2026-11-07-live-scan-location.md."""

import io
from datetime import datetime, timedelta, timezone

from django.contrib.auth import get_user_model
from django.core.management import call_command
from django.test import SimpleTestCase, TestCase
from rest_framework.test import APIClient

from sensors.models import Sensor

from .location_quality import clean_session_locations, disagrees_with_fused, is_spike
from .models import ScanSession

M = 1.0 / 111_320.0  # ~1 m of latitude
LAT, LNG = 48.1351, 11.5820
T0 = datetime(2026, 11, 7, 10, 0, tzinfo=timezone.utc)


class RuleTests(SimpleTestCase):
    def test_disagrees_with_fused(self):
        self.assertFalse(disagrees_with_fused(LAT, LNG, 20, LAT + 90 * M, LNG, 15))  # within 100 m
        self.assertFalse(disagrees_with_fused(LAT, LNG, 60, LAT + 300 * M, LNG, 50))  # within 3x(60+50)
        self.assertTrue(disagrees_with_fused(LAT, LNG, 20, LAT + 500 * M, LNG, 15))
        self.assertFalse(disagrees_with_fused(LAT, LNG, 20, None, None, None))  # no fused: no verdict

    def test_spike_needs_both_legs_and_out_and_back(self):
        a = (LAT, LNG, 0.0)
        far = (LAT + 5000 * M, LNG, 60.0)
        back = (LAT + 10 * M, LNG, 120.0)
        self.assertTrue(is_spike(a, far, back))
        # A fast train keeps going: not out-and-back, so not a spike.
        self.assertFalse(is_spike(a, (LAT + 5000 * M, LNG, 60.0), (LAT + 10000 * M, LNG, 120.0)))
        # Slow enough to have walked there and back.
        self.assertFalse(is_spike(a, (LAT + 500 * M, LNG, 600.0), (LAT, LNG, 1200.0)))


def _payload(client_scan_id, north_m=0.0, fused_north_m=None, at=T0, accuracy=12.0, **extra):
    body = {
        "client_scan_id": client_scan_id,
        "started_at": at.isoformat(), "completed_at": (at + timedelta(seconds=10)).isoformat(),
        "latitude": LAT + north_m * M, "longitude": LNG,
        "location_accuracy_meters": accuracy, "location_provider": "network",
        **extra,
    }
    if fused_north_m is not None:
        body.update(fused_latitude=LAT + fused_north_m * M, fused_longitude=LNG, fused_accuracy_meters=10.0)
    return body


class IngestLocationTests(TestCase):
    def setUp(self):
        self.sensor = Sensor.objects.create(name="Field Phone")
        self.ingest = APIClient()
        self.ingest.credentials(HTTP_AUTHORIZATION=f"Token {self.sensor.token}")

    def _post(self, body):
        response = self.ingest.post("/api/v1/scan-sessions/", body, format="json")
        self.assertEqual(response.status_code, 201, response.content)
        return ScanSession.objects.get(client_scan_id=body["client_scan_id"])

    def test_position_far_from_its_fused_fix_is_corrected(self):
        session = self._post(_payload("far", north_m=5000, fused_north_m=0))
        self.assertAlmostEqual(session.latitude, LAT, places=6)
        self.assertEqual(session.location_provider, "fused")
        self.assertEqual(session.location_accuracy_meters, 10.0)
        self.assertEqual(session.location_correction, "fused")
        self.assertIn("fused", session.location_correction_reason)
        self.assertAlmostEqual(session.raw_latitude, LAT + 5000 * M, places=6)
        self.assertEqual(session.raw_location_provider, "network")
        self.assertEqual(session.raw_location_accuracy_meters, 12.0)

    def test_consistent_position_is_untouched_and_fix_age_stored(self):
        session = self._post(_payload("ok", north_m=10, fused_north_m=0, location_fix_age_ms=850))
        self.assertEqual(session.location_correction, "")
        self.assertIsNone(session.raw_latitude)
        self.assertEqual(session.location_fix_age_ms, 850)

    def test_old_apk_without_fused_is_untouched(self):
        session = self._post(_payload("old", north_m=5000))
        self.assertEqual(session.location_correction, "")
        self.assertAlmostEqual(session.latitude, LAT + 5000 * M, places=6)

    def test_session_api_exposes_the_correction(self):
        self._post(_payload("api", north_m=5000, fused_north_m=0, location_fix_age_ms=300))
        reader = APIClient()
        reader.force_authenticate(user=get_user_model().objects.create_user(username="op2", password="pw-123456"))
        row = reader.get("/api/v1/scan-sessions/").json()["results"][0]
        self.assertEqual(row["location_correction"], "fused")
        self.assertIn("fused fix", row["location_correction_reason"])
        self.assertAlmostEqual(row["raw_latitude"], LAT + 5000 * M, places=6)
        self.assertEqual(row["location_fix_age_ms"], 300)

    def test_export_writes_original_coordinates(self):
        self._post(_payload("exp", north_m=5000, fused_north_m=0, location_fix_age_ms=400))
        reader = APIClient()
        reader.force_authenticate(user=get_user_model().objects.create_user(username="op", password="pw-123456"))
        exported = reader.get("/api/v1/scan-sessions/export/").json()["sessions"][0]
        self.assertAlmostEqual(exported["latitude"], LAT + 5000 * M, places=6)
        self.assertEqual(exported["location_provider"], "network")
        self.assertEqual(exported["location_fix_age_ms"], 400)
        # Re-importing reaches the same corrected state.
        ScanSession.objects.all().delete()
        reader.post("/api/v1/scan-sessions/import_sessions/", {"sessions": [exported]}, format="json")
        self.assertEqual(ScanSession.objects.get().location_correction, "fused")


class CleanupTests(TestCase):
    def setUp(self):
        self.sensor = Sensor.objects.create(name="Field Phone")
        self.other = Sensor.objects.create(name="Other Phone")

    def _session(self, name, minute, north_m, fused_north_m=None, sensor=None, accuracy=15.0):
        fused = {} if fused_north_m is None else {
            "fused_latitude": LAT + fused_north_m * M, "fused_longitude": LNG, "fused_accuracy_meters": 10.0,
        }
        return ScanSession.objects.create(
            sensor=sensor or self.sensor, client_scan_id=name,
            started_at=T0 + timedelta(minutes=minute), completed_at=T0 + timedelta(minutes=minute, seconds=10),
            latitude=LAT + north_m * M, longitude=LNG, location_accuracy_meters=accuracy,
            location_provider="network", **fused,
        )

    def _walk(self):
        # A walk north at ~1.4 m/s, one scan a minute, with two bad fixes.
        for i in range(8):
            self._session(f"w{i}", i, i * 84)
        ScanSession.objects.filter(client_scan_id="w3").update(latitude=LAT + 40_000 * M)  # spike, no fused
        ScanSession.objects.filter(client_scan_id="w5").update(
            latitude=LAT + 20_000 * M, fused_latitude=LAT + 5 * 84 * M, fused_longitude=LNG, fused_accuracy_meters=8.0,
        )  # spike with a good fused fix

    def _state(self):
        return sorted(ScanSession.objects.values_list(
            "client_scan_id", "latitude", "longitude", "location_correction", "raw_latitude",
        ))

    def test_spikes_are_corrected_or_cleared(self):
        self._walk()
        report = clean_session_locations()
        self.assertEqual(report["fused"], 1)
        self.assertEqual(report["cleared"], 1)
        w3 = ScanSession.objects.get(client_scan_id="w3")
        self.assertIsNone(w3.latitude)
        self.assertIsNone(w3.longitude)
        self.assertEqual(w3.location_correction, "cleared")
        self.assertAlmostEqual(w3.raw_latitude, LAT + 40_000 * M, places=6)
        w5 = ScanSession.objects.get(client_scan_id="w5")
        self.assertAlmostEqual(w5.latitude, LAT + 5 * 84 * M, places=6)
        self.assertEqual(w5.location_correction, "fused")
        # The rest of the walk is untouched.
        self.assertEqual(ScanSession.objects.exclude(location_correction="").count(), 2)

    def test_end_points_and_other_sensors_are_judged_separately(self):
        self._session("a0", 0, 0)
        self._session("a1", 1, 50_000)  # last point of its sensor: no second neighbour
        self._session("b0", 0, 0, sensor=self.other)
        self._session("b1", 1, 30, sensor=self.other)
        self.assertEqual(clean_session_locations()["cleared"], 0)

    def test_fast_train_is_kept(self):
        for i in range(6):
            self._session(f"t{i}", i, i * 4_800)  # 80 m/s
        self.assertEqual(clean_session_locations()["fused"] + clean_session_locations()["cleared"], 0)

    def test_clean_is_idempotent(self):
        self._walk()
        clean_session_locations()
        first = self._state()
        report = clean_session_locations()
        self.assertEqual(report["changed"], 0)
        self.assertEqual(self._state(), first)

    def test_dry_run_changes_nothing(self):
        self._walk()
        before = self._state()
        report = clean_session_locations(dry_run=True)
        self.assertEqual(report["changed"], 2)
        self.assertEqual(self._state(), before)

    def test_revert_restores_originals(self):
        self._walk()
        before = self._state()
        clean_session_locations()
        clean_session_locations(revert=True)
        self.assertEqual(self._state(), before)

    def test_command(self):
        self._walk()
        out = io.StringIO()
        call_command("clean_session_locations", "--dry-run", stdout=out)
        self.assertIn("'changed': 2", out.getvalue())
        self.assertIn("dry run", out.getvalue())
