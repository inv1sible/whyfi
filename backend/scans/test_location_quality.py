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

from .location_quality import clean_session_locations, disagrees_with_fused, hop_plausible
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

    def test_hop_plausibility(self):
        a = (LAT, LNG, 0.0)
        self.assertTrue(hop_plausible(a, (LAT + 30 * M, LNG, 0.1)))  # under 40 m
        self.assertTrue(hop_plausible(a, (LAT + 4_800 * M, LNG, 60.0)))  # 80 m/s train
        self.assertFalse(hop_plausible(a, (LAT + 9_000 * M, LNG, 60.0)))  # 150 m/s
        self.assertTrue(hop_plausible(a, (LAT + 9_000_000 * M, LNG, 36_000.0)))  # 10 h flight, 250 m/s
        # 2,000 km in 55 min (600 m/s) is impossible even by plane (real data:
        # a relocated-Wi-Fi run ending 55 min before a GPS fix).
        self.assertFalse(hop_plausible(a, (LAT + 2_000_000 * M, LNG, 55 * 60.0)))


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

    def test_fused_fix_wins_when_only_it_fits_the_previous_scan(self):
        self._post(_payload("prev", north_m=0))
        session = self._post(_payload("far", north_m=9000, fused_north_m=60, at=T0 + timedelta(minutes=1)))
        self.assertAlmostEqual(session.latitude, LAT + 60 * M, places=6)
        self.assertEqual(session.location_provider, "fused")
        self.assertEqual(session.location_accuracy_meters, 10.0)
        self.assertEqual(session.location_correction, "fused")
        self.assertIn("fused", session.location_correction_reason)
        self.assertAlmostEqual(session.raw_latitude, LAT + 9000 * M, places=6)
        self.assertEqual(session.raw_location_provider, "network")
        self.assertEqual(session.raw_location_accuracy_meters, 12.0)

    def test_wrong_fused_fix_does_not_replace_a_good_original(self):
        # Real data: the original was right and the fused fix was relocated Wi-Fi.
        self._post(_payload("prev", north_m=0))
        session = self._post(_payload("ok-orig", north_m=50, fused_north_m=4_000_000, at=T0 + timedelta(minutes=1)))
        self.assertEqual(session.location_correction, "")
        self.assertAlmostEqual(session.latitude, LAT + 50 * M, places=6)

    def test_gps_original_is_never_swapped_at_ingest(self):
        # Reviewer scenario: the previous stored scan is a fake (relocated
        # Wi-Fi) and the new GPS fix's fused fix agrees with the fake.
        self._post(_payload("fake-prev", north_m=2_000_000))
        session = self._post(_payload("gps-now", north_m=0, fused_north_m=2_000_000, at=T0 + timedelta(minutes=1),
                                      location_provider="gps", location_accuracy_meters=4.0))
        self.assertEqual(session.location_correction, "")
        self.assertEqual(session.location_provider, "gps")

    def test_fractional_fix_age_does_not_reject_the_session(self):
        session = self._post(_payload("frac", location_fix_age_ms=12.5))
        self.assertEqual(session.location_fix_age_ms, 12)

    def test_disagreement_without_a_previous_scan_is_left_for_the_cleanup(self):
        session = self._post(_payload("first", north_m=5000, fused_north_m=0))
        self.assertEqual(session.location_correction, "")

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
        self._post(_payload("api-prev", north_m=0))
        self._post(_payload("api", north_m=9000, fused_north_m=0, location_fix_age_ms=300, at=T0 + timedelta(minutes=1)))
        reader = APIClient()
        reader.force_authenticate(user=get_user_model().objects.create_user(username="op2", password="pw-123456"))
        row = next(r for r in reader.get("/api/v1/scan-sessions/").json()["results"] if r["location_correction"])
        self.assertEqual(row["location_correction"], "fused")
        self.assertIn("fused fix", row["location_correction_reason"])
        self.assertAlmostEqual(row["raw_latitude"], LAT + 9000 * M, places=6)
        self.assertEqual(row["location_fix_age_ms"], 300)

    def test_import_replays_oldest_first_and_reaches_the_same_state(self):
        # Export is newest-first; replaying that order left the ingest check
        # with no previous scan, so corrections didn't survive a round trip.
        for i in range(6):
            self._post(_payload(f"rt{i}", north_m=2_000_000 if i == 3 else i * 30, at=T0 + timedelta(minutes=i)))
        clean_session_locations()
        before = sorted(ScanSession.objects.values_list("client_scan_id", "latitude", "location_correction"))
        reader = APIClient()
        reader.force_authenticate(user=get_user_model().objects.create_user(username="op-rt", password="pw-123456"))
        exported = reader.get("/api/v1/scan-sessions/export/").json()
        ScanSession.objects.all().delete()
        reader.post("/api/v1/scan-sessions/import_sessions/", exported, format="json")
        self.assertEqual(sorted(ScanSession.objects.values_list("client_scan_id", "latitude", "location_correction")), before)

    def test_export_writes_original_coordinates(self):
        self._post(_payload("exp-prev", north_m=0))
        self._post(_payload("exp", north_m=9000, fused_north_m=0, location_fix_age_ms=400, at=T0 + timedelta(minutes=1)))
        reader = APIClient()
        reader.force_authenticate(user=get_user_model().objects.create_user(username="op", password="pw-123456"))
        sessions = sorted(reader.get("/api/v1/scan-sessions/export/").json()["sessions"], key=lambda e: e["started_at"])
        exported = sessions[1]
        self.assertAlmostEqual(exported["latitude"], LAT + 9000 * M, places=6)
        self.assertEqual(exported["location_provider"], "network")
        self.assertEqual(exported["location_fix_age_ms"], 400)
        # Re-importing (in time order) reaches the same corrected state.
        ScanSession.objects.all().delete()
        reader.post("/api/v1/scan-sessions/import_sessions/", {"sessions": sessions}, format="json")
        self.assertEqual(ScanSession.objects.get(client_scan_id="exp").location_correction, "fused")


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

    def test_wrong_fused_fix_does_not_replace_a_good_original(self):
        for i in range(6):
            self._session(f"g{i}", i, i * 84, fused_north_m=4_000_000 if i == 3 else None)
        self.assertEqual(clean_session_locations()["changed"], 0)

    def test_run_of_relocated_fixes_is_cleared(self):
        # Both readings relocated for several scans in a row (real data:
        # identical Tokyo/Iowa/New York/Germany fixes amid Las Vegas scans).
        for i in range(12):
            far = 3 <= i <= 6
            self._session(f"r{i}", i, 2_000_000 if far else i * 30, fused_north_m=2_000_000 if far else None)
        report = clean_session_locations()
        self.assertEqual(report["cleared"], 4)
        self.assertEqual(set(ScanSession.objects.filter(location_correction="cleared").values_list("client_scan_id", flat=True)),
                         {"r3", "r4", "r5", "r6"})

    def test_long_relocated_run_loses_to_gps(self):
        # Real data (08-07): 71 network scans in Iowa over five hours while the
        # phone was in Las Vegas. The phone flip-flopped (Vegas network scans
        # *inside* the Iowa run, minutes apart), and the GPS fixes, all in Vegas,
        # came 55 min after it. A fake run longer than the real scans
        # around it must still lose.
        def scan(name, minute, north_m, provider="network"):
            self._session(name, minute, north_m)
            ScanSession.objects.filter(client_scan_id=name).update(location_provider=provider)
        for i in range(10):
            scan(f"before{i}", i, i * 5)
        minute = 15
        for burst in range(3):
            for i in range(20):
                scan(f"iowa{burst}_{i}", minute, 2_000_000)
                minute += 1
            for i in range(3):
                scan(f"vegas{burst}_{i}", minute, 10)
                minute += 1
        for i in range(8):
            scan(f"gps{i}", minute + 55 + i, i * 5, provider="gps")
        report = clean_session_locations()
        self.assertEqual(report["cleared"], 60)
        self.assertFalse(ScanSession.objects.filter(client_scan_id__startswith="iowa").exclude(latitude=None).exists())
        self.assertFalse(ScanSession.objects.filter(client_scan_id__startswith="vegas", latitude=None).exists())

    def test_no_flight_while_the_phone_kept_scanning(self):
        # Real data (08-07): a fake Iowa run after a two-day gap, then Las
        # Vegas scans every few minutes, GPS-confirmed later. Dropping the
        # Vegas scans would open a 2 h window that a flight could explain,
        # but the phone was scanning in Vegas throughout, so no flight.
        def scan(name, minute, north_m, provider="network"):
            self._session(name, minute, north_m)
            ScanSession.objects.filter(client_scan_id=name).update(location_provider=provider)
        for i in range(5):
            scan(f"days_before{i}", i, i * 5)
        start = 2 * 24 * 60
        for i in range(80):
            scan(f"iowa{i}", start + i, 2_000_000)
        for i in range(28):
            scan(f"vegas_net{i}", start + 81 + i * 5, 10)
        for i in range(10):
            scan(f"vegas_gps{i}", start + 81 + 28 * 5 + i * 3, 10, provider="gps")
        report = clean_session_locations()
        self.assertEqual(report["cleared"], 80)
        self.assertFalse(ScanSession.objects.filter(client_scan_id__startswith="iowa").exclude(latitude=None).exists())

    def test_fake_run_longer_than_the_lookback_window(self):
        # Reviewer scenario: 20 GPS, 250 fake network scans, 20 GPS.
        def scan(name, minute, north_m, provider):
            self._session(name, minute, north_m)
            ScanSession.objects.filter(client_scan_id=name).update(location_provider=provider)
        for i in range(20):
            scan(f"g_before{i}", i, i * 5, "gps")
        for i in range(250):
            scan(f"fake{i}", 20 + i, 2_000_000, "network")
        for i in range(20):
            scan(f"g_after{i}", 270 + i, i * 5, "gps")
        report = clean_session_locations()
        self.assertEqual(report["cleared"], 250)
        self.assertFalse(ScanSession.objects.filter(location_provider="gps", latitude=None).exists())

    def test_sessions_without_a_sensor_are_left_alone(self):
        # Sensor deleted with its data kept: sessions from different phones
        # end up with sensor=None and must not be judged as one track.
        for i, north in enumerate([0, 52_000, 10, 52_010]):
            self._session(f"orphan{i}", 0 if i < 2 else 1, north)
        ScanSession.objects.filter(client_scan_id__startswith="orphan").update(sensor=None)
        self.assertEqual(clean_session_locations()["changed"], 0)

    def test_scans_without_a_position_are_not_flight_time(self):
        # Reviewer scenario: GPS in Vegas, 3 h of scans without a position,
        # then network scans in Iowa. The phone was scanning, not flying.
        def scan(name, minute, north_m, provider="network"):
            self._session(name, minute, north_m)
            ScanSession.objects.filter(client_scan_id=name).update(location_provider=provider)
        for i in range(5):
            scan(f"gps{i}", i, i * 5, "gps")
        for i in range(36):
            scan(f"nopos{i}", 5 + i * 5, 0)
            ScanSession.objects.filter(client_scan_id=f"nopos{i}").update(latitude=None, longitude=None)
        for i in range(3):
            scan(f"iowa{i}", 185 + i, 2_000_000)
        self.assertEqual(clean_session_locations()["cleared"], 3)

    def test_end_points_and_other_sensors_are_judged_separately(self):
        for i in range(5):
            self._session(f"a{i}", i, i * 30)
        self._session("a5", 5, 50_000)  # last scan of its sensor, 50 km away a minute later
        for i in range(3):
            self._session(f"b{i}", i, 50_000 + i * 30, sensor=self.other)
        report = clean_session_locations()
        self.assertEqual(report["cleared"], 1)
        self.assertEqual(ScanSession.objects.get(client_scan_id="a5").location_correction, "cleared")

    def test_fast_train_is_kept(self):
        for i in range(6):
            self._session(f"t{i}", i, i * 4_800)  # 80 m/s
        self.assertEqual(clean_session_locations()["changed"], 0)

    def test_flight_gap_is_kept(self):
        for i in range(5):
            self._session(f"home{i}", i, i * 30)
        for i in range(3):
            self._session(f"away{i}", 600 + i, 9_000_000 + i * 30)  # 10 h later, 9,000 km away
        self.assertEqual(clean_session_locations()["changed"], 0)

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
