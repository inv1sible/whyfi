import math
from unittest import mock

from django.contrib.auth import get_user_model
from django.db import DatabaseError
from django.test import TestCase
from rest_framework.test import APIClient

from sensors.models import Sensor

from .models import (
    AccessPoint,
    FloorPlan,
    CalibratedRangeModel,
    FtmRangingObservation,
    GroundTruthPosition,
    ScanSession,
    SecurityType,
    WiFiObservation,
)
from .serializers import band_for_frequency, channel_for_frequency, security_type_from_capabilities


class HealthCheckTests(TestCase):
    def test_health_ok(self):
        response = APIClient().get("/api/v1/health/")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"status": "ok"})


class SecurityParsingTests(TestCase):
    """Pinned against the strings Android's framework actually produces
    (`InformationElementUtil.Capabilities.generateCapabilitiesString()`),
    not against how the schemes are spelled in marketing.

    The protocol prefix is `RSN` for everything SAE/OWE/Suite-B based, so a
    WPA3 network's capabilities contain the substring "WPA3" nowhere at all.
    Matching protocol names (which is what this did originally) classified
    every WPA3/transition/OWE network as UNKNOWN — grey "Unknown" badge in
    the PWA, and `?security=WPA3` matching nothing, ever.
    """

    def test_wpa3_personal_sae(self):
        self.assertEqual(
            security_type_from_capabilities("[RSN-SAE-CCMP][ESS][MFPR][MFPC]"), SecurityType.WPA3
        )

    def test_wpa3_with_fast_transition(self):
        self.assertEqual(
            security_type_from_capabilities("[RSN-SAE+FT/SAE-CCMP][ESS][MFPR]"), SecurityType.WPA3
        )

    def test_wpa3_enterprise_192_bit(self):
        self.assertEqual(
            security_type_from_capabilities("[RSN-EAP_SUITE_B_192-GCMP-256][ESS][MFPR]"), SecurityType.WPA3
        )

    def test_transition_mode_advertises_both(self):
        self.assertEqual(
            security_type_from_capabilities("[RSN-PSK+SAE-CCMP][ESS][MFPC]"), SecurityType.WPA2_WPA3
        )

    def test_enhanced_open_is_not_wpa2_or_plain_open(self):
        # Encrypted, but joinable with no credential — its own value.
        self.assertEqual(security_type_from_capabilities("[RSN-OWE-CCMP][ESS][MFPR]"), SecurityType.OWE)
        self.assertEqual(
            security_type_from_capabilities("[RSN-OWE_TRANSITION-CCMP][ESS]"), SecurityType.OWE
        )

    def test_wpa2_both_spellings(self):
        # Older builds say WPA2, newer ones say RSN for the same network.
        self.assertEqual(security_type_from_capabilities("[WPA2-PSK-CCMP][ESS]"), SecurityType.WPA2)
        self.assertEqual(security_type_from_capabilities("[RSN-PSK-CCMP][ESS]"), SecurityType.WPA2)
        self.assertEqual(security_type_from_capabilities("[WPA2-EAP-CCMP][ESS]"), SecurityType.WPA2)

    def test_wpa1_and_wep(self):
        self.assertEqual(security_type_from_capabilities("[WPA-PSK-TKIP][ESS]"), SecurityType.WPA)
        self.assertEqual(security_type_from_capabilities("[WEP][ESS]"), SecurityType.WEP)

    def test_open_with_and_without_extra_flags(self):
        # "[ESS][WPS]" is extremely common and used to come back UNKNOWN,
        # because the check was an exact match against "[ESS]".
        self.assertEqual(security_type_from_capabilities("[ESS]"), SecurityType.OPEN)
        self.assertEqual(security_type_from_capabilities("[ESS][WPS]"), SecurityType.OPEN)
        self.assertEqual(security_type_from_capabilities("[ESS][MFPC]"), SecurityType.OPEN)
        self.assertEqual(security_type_from_capabilities(""), SecurityType.OPEN)

    def test_band_and_channel_for_frequency(self):
        self.assertEqual((band_for_frequency(2437), channel_for_frequency(2437)), ("2.4GHz", 6))
        self.assertEqual((band_for_frequency(2484), channel_for_frequency(2484)), ("2.4GHz", 14))
        self.assertEqual((band_for_frequency(5180), channel_for_frequency(5180)), ("5GHz", 36))
        self.assertEqual((band_for_frequency(5955), channel_for_frequency(5955)), ("6GHz", 1))
        self.assertEqual((band_for_frequency(7115), channel_for_frequency(7115)), ("6GHz", 233))


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

    def test_lan_onvif_evidence_is_preserved(self):
        payload = self._payload("scan-onvif")
        payload["lan_observations"][0]["onvif"] = {
            "endpoint": "urn:uuid:camera-1",
            "types": "tds:Device",
            "scopes": "onvif://www.onvif.org/name/Front%20Door",
            "xaddrs": "http://192.168.1.42/onvif/device_service",
        }
        response = self.client.post("/api/v1/scan-sessions/", payload, format="json")
        self.assertEqual(response.status_code, 201, response.content)
        observation = ScanSession.objects.get(client_scan_id="scan-onvif").lan_observations.get()
        self.assertEqual(observation.onvif["endpoint"], "urn:uuid:camera-1")

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

    def test_a_failure_mid_payload_leaves_nothing_behind(self):
        """Ingest is all-or-nothing, and it has to be *because* it's
        idempotent: a committed session with only half its observations would
        make the device's retry a no-op (it hits the "already exists" branch,
        gets a 201, drops the payload from its outbox) and the missing rows
        would never be written by anyone.

        Fails on the satellite loop specifically — that's after WiFi, cell
        and BLE have already been inserted, so it only passes if those get
        rolled back too.
        """
        with mock.patch(
            "scans.serializers.SatelliteObservation.objects.create",
            side_effect=DatabaseError("simulated failure mid-payload"),
        ):
            with self.assertRaises(DatabaseError):
                self.client.post("/api/v1/scan-sessions/", self._payload("scan-atomic"), format="json")

        self.assertFalse(ScanSession.objects.filter(client_scan_id="scan-atomic").exists())
        self.assertEqual(WiFiObservation.objects.count(), 0)
        self.assertEqual(AccessPoint.objects.count(), 0)
        self.sensor.refresh_from_db()
        self.assertIsNone(self.sensor.last_scan_upload_at)

    def test_retry_after_a_rolled_back_failure_writes_everything(self):
        # The other half of the guarantee: because nothing was committed, the
        # outbox's retry is a clean first insert rather than a no-op.
        with mock.patch(
            "scans.serializers.SatelliteObservation.objects.create",
            side_effect=DatabaseError("simulated failure mid-payload"),
        ):
            with self.assertRaises(DatabaseError):
                self.client.post("/api/v1/scan-sessions/", self._payload("scan-retry"), format="json")

        response = self.client.post("/api/v1/scan-sessions/", self._payload("scan-retry"), format="json")
        self.assertEqual(response.status_code, 201, response.content)
        session = ScanSession.objects.get(client_scan_id="scan-retry")
        self.assertEqual(session.wifi_observations.count(), 1)
        self.assertEqual(session.satellite_observations.count(), 1)

    def test_wpa3_capabilities_are_stored_as_wpa3(self):
        # End-to-end counterpart to SecurityParsingTests: what the Android
        # app actually sends for a WPA3 AP has to land as WPA3 in the row the
        # PWA reads back.
        payload = self._payload("scan-wpa3")
        payload["wifi_observations"][0]["capabilities"] = "[RSN-SAE-CCMP][ESS][MFPR][MFPC]"
        self.client.post("/api/v1/scan-sessions/", payload, format="json")
        observation = WiFiObservation.objects.get(scan_session__client_scan_id="scan-wpa3")
        self.assertEqual(observation.security_type, SecurityType.WPA3)
        self.assertEqual(observation.capabilities_raw, "[RSN-SAE-CCMP][ESS][MFPR][MFPC]")


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

    def test_scan_sessions_list_accepts_login_or_own_sensor_token(self):
        # Both a login session (the PWA) and a sensor token (the Android app,
        # backfilling its Dashboard on a fresh install — see
        # LatestScanFetcher) can read the list; a sensor token scopes the
        # result to that sensor's own sessions (see get_queryset).
        sensor_client = APIClient()
        sensor_client.credentials(HTTP_AUTHORIZATION=f"Token {self.sensor.token}")
        response = sensor_client.get("/api/v1/scan-sessions/")
        self.assertEqual(response.status_code, 200)
        session = response.json()["results"][0]
        self.assertEqual(session["location_accuracy_meters"], 8.0)
        self.assertEqual(session["location_provider"], "gps")

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


class LimitablePaginationTests(TestCase):
    """`?limit=` actually changes the page size — see pagination.py.

    Before LimitablePageNumberPagination existed, every overview list page
    sent `?limit=200` and it was silently ignored: plain PageNumberPagination
    only understands `page`, so anything past the 50 most-recently-seen
    matches was invisible with no indication a wider window would have shown
    more. This is what made "I know this device was seen in that window, but
    it's not in the list" look like a data bug when it was a pagination bug.
    """

    def setUp(self):
        # 60 distinct APs — comfortably past the default PAGE_SIZE of 50, so
        # the un-widened case is provably truncated, not just "happens to fit".
        self.sensor = Sensor.objects.create(name="Test Phone")
        ingest = APIClient()
        ingest.credentials(HTTP_AUTHORIZATION=f"Token {self.sensor.token}")
        for i in range(60):
            ingest.post(
                "/api/v1/scan-sessions/",
                {
                    "client_scan_id": f"scan-pagination-{i}",
                    "started_at": "2026-07-16T10:00:00Z",
                    "completed_at": "2026-07-16T10:00:03Z",
                    "latitude": 48.1351,
                    "longitude": 11.582,
                    "wifi_observations": [
                        {"bssid": f"aa:bb:cc:dd:{i:02x}:00", "ssid": f"Net{i}", "rssi": -55,
                         "frequency_mhz": 2437, "capabilities": "[RSN-PSK-CCMP][ESS]"},
                    ],
                },
                format="json",
            )
        self.user = get_user_model().objects.create_user(username="operator", password="test-pass-123")
        self.client = APIClient()
        self.client.force_authenticate(user=self.user)

    def test_default_page_size_is_unchanged(self):
        # No behaviour change for a caller that never passes `limit` — this
        # class is additive, not a silent widening of every response.
        body = self.client.get("/api/v1/access-points/").json()
        self.assertEqual(body["count"], 60)
        self.assertEqual(len(body["results"]), 50)
        self.assertIsNotNone(body["next"])

    def test_limit_raises_the_page_size(self):
        body = self.client.get("/api/v1/access-points/?limit=200").json()
        self.assertEqual(body["count"], 60)
        self.assertEqual(len(body["results"]), 60)
        self.assertIsNone(body["next"])

    def test_limit_is_capped_at_the_maximum(self):
        # A hand-edited URL asking for far more than the ceiling gets the
        # ceiling, not an unbounded read — same reasoning as
        # scans.views.positive_int's maximum.
        from scans.pagination import MAX_PAGE_SIZE

        body = self.client.get(f"/api/v1/access-points/?limit={MAX_PAGE_SIZE * 10}").json()
        self.assertLessEqual(len(body["results"]), MAX_PAGE_SIZE)

    def test_limit_applies_to_other_device_list_endpoints_too(self):
        for path in ("/api/v1/cell-towers/", "/api/v1/ble-devices/", "/api/v1/lan-devices/", "/api/v1/scan-sessions/"):
            with self.subTest(path=path):
                response = self.client.get(f"{path}?limit=200")
                self.assertEqual(response.status_code, 200)


class ScanSessionRadioCountTests(TestCase):
    """Per-radio counts on the session list, which are computed as five
    correlated subqueries rather than five Count(..., distinct=True)
    aggregates.

    The aggregate form asked the database to join five unrelated to-many
    tables at once — a five-way cartesian product per session — and then
    deduplicate it. On a real deployment (1418 sessions, 70k WiFi rows) that
    took 6.3s versus 0.07s for one such Count, which is what left the
    floor-plan page's "which scan are you placing?" dropdown empty for
    ~9 seconds on load and again after every placement.

    The counts chosen here are deliberately distinct and mutually coprime, so
    the cartesian product a broken rewrite produces (2*3*5 = 30 everywhere)
    cannot coincide with any correct answer.
    """

    def setUp(self):
        self.sensor = Sensor.objects.create(name="Count Phone")
        ingest = APIClient()
        ingest.credentials(HTTP_AUTHORIZATION=f"Token {self.sensor.token}")
        ingest.post(
            "/api/v1/scan-sessions/",
            {
                "client_scan_id": "scan-radio-counts",
                "started_at": "2026-07-16T10:00:00Z",
                "completed_at": "2026-07-16T10:00:03Z",
                "latitude": 48.1351,
                "longitude": 11.582,
                "wifi_observations": [
                    {"bssid": f"aa:bb:cc:00:00:{i:02x}", "ssid": f"W{i}", "rssi": -55,
                     "frequency_mhz": 2437, "capabilities": "[ESS]"}
                    for i in range(2)
                ],
                "ble_observations": [
                    {"mac_address": f"bb:cc:dd:00:00:{i:02x}", "device_name": f"B{i}", "rssi": -70}
                    for i in range(3)
                ],
                "lan_observations": [
                    {"ip_address": f"192.168.1.{i + 10}", "hostname": f"host{i}"}
                    for i in range(5)
                ],
            },
            format="json",
        )
        # A second session with nothing in it at all: a correlated aggregate
        # subquery returns NULL rather than 0 for an empty relation, so
        # without the Coalesce this row serialises its counts as null and
        # every "N WiFi" label in the UI reads "null WiFi".
        ingest.post(
            "/api/v1/scan-sessions/",
            {
                "client_scan_id": "scan-radio-counts-empty",
                "started_at": "2026-07-16T11:00:00Z",
                "completed_at": "2026-07-16T11:00:03Z",
                "latitude": 48.1351,
                "longitude": 11.582,
            },
            format="json",
        )
        self.user = get_user_model().objects.create_user(username="counter", password="test-pass-123")
        self.client = APIClient()
        self.client.force_authenticate(user=self.user)

    def test_each_radio_count_is_independent_of_the_others(self):
        body = self.client.get("/api/v1/scan-sessions/?limit=100").json()
        rows = {r["started_at"]: r for r in body["results"]}
        session = rows["2026-07-16T10:00:00Z"]
        self.assertEqual(session["wifi_count"], 2)
        self.assertEqual(session["ble_count"], 3)
        self.assertEqual(session["lan_count"], 5)
        self.assertEqual(session["cell_count"], 0)
        self.assertEqual(session["satellite_count"], 0)

    def test_a_session_with_no_observations_counts_zero_not_null(self):
        body = self.client.get("/api/v1/scan-sessions/?limit=100").json()
        rows = {r["started_at"]: r for r in body["results"]}
        empty = rows["2026-07-16T11:00:00Z"]
        for key in ("wifi_count", "cell_count", "ble_count", "satellite_count", "lan_count"):
            with self.subTest(key=key):
                self.assertEqual(empty[key], 0)

    def test_counts_survive_the_detail_endpoint_too(self):
        body = self.client.get("/api/v1/scan-sessions/?limit=100").json()
        rows = {r["started_at"]: r for r in body["results"]}
        detail = self.client.get(f"/api/v1/scan-sessions/{rows['2026-07-16T10:00:00Z']['id']}/").json()
        self.assertEqual(detail["wifi_count"], 2)
        self.assertEqual(detail["ble_count"], 3)
        self.assertEqual(detail["lan_count"], 5)

    def test_list_ordering_is_still_newest_first(self):
        # The old aggregate annotate() dropped Meta.ordering via GROUP BY and
        # had to re-apply it by hand; the rewrite must not quietly lose the
        # ordering that pagination determinism depends on.
        body = self.client.get("/api/v1/scan-sessions/?limit=100").json()
        started = [r["started_at"] for r in body["results"]]
        self.assertEqual(started, sorted(started, reverse=True))


class SearchFilterTests(TestCase):
    """`?q=` — the server-side counterpart to searchFilter.ts's
    filterBySearch, added so a search box backed by real pagination can find
    a match anywhere in the table, not just on whichever page is loaded (the
    reported "Venus" scan really did have a device_name of
    "Venus_98CDAC4C678A" ranked past page 1 by recency — this pins that
    exact shape of bug at the API layer).
    """

    def setUp(self):
        self.sensor = Sensor.objects.create(name="Test Phone")
        ingest = APIClient()
        ingest.credentials(HTTP_AUTHORIZATION=f"Token {self.sensor.token}")
        ingest.post(
            "/api/v1/scan-sessions/",
            {
                "client_scan_id": "scan-search-1",
                "started_at": "2026-07-16T10:00:00Z",
                "completed_at": "2026-07-16T10:00:03Z",
                "latitude": 48.1351,
                "longitude": 11.582,
                "wifi_observations": [
                    {"bssid": "aa:bb:cc:dd:ee:ff", "ssid": "HomeNetwork", "rssi": -55,
                     "frequency_mhz": 2437, "capabilities": "[RSN-PSK-CCMP][ESS]"},
                    {"bssid": "11:22:33:44:55:66", "ssid": "CoffeeShopWiFi", "rssi": -70,
                     "frequency_mhz": 5180, "capabilities": "[ESS]"},
                ],
                "cell_observations": [
                    {"mcc": "262", "mnc": "01", "carrier_name": "Deutsche Telekom", "radio_type": "LTE",
                     "is_serving_cell": True, "cell_id": "12345", "tac_or_lac": "678", "signal_dbm": -85},
                ],
                "ble_observations": [
                    {"ble_mac": "98:cd:ac:4c:67:8a", "device_name": "Venus_98CDAC4C678A", "rssi": -60},
                    {"ble_mac": "aa:aa:aa:aa:aa:aa", "device_name": "Mars_headset", "rssi": -80},
                ],
                "lan_observations": [
                    {"ip_address": "192.168.1.42", "hostname": "printer-office", "open_ports": [631]},
                ],
            },
            format="json",
        )
        self.user = get_user_model().objects.create_user(username="operator", password="test-pass-123")
        self.client = APIClient()
        self.client.force_authenticate(user=self.user)

    def test_free_text_finds_ble_device_by_name(self):
        # The literal reported bug: searching by (partial) device name.
        body = self.client.get("/api/v1/ble-devices/?q=venus").json()
        self.assertEqual(body["count"], 1)
        self.assertEqual(body["results"][0]["device_key"], "98:cd:ac:4c:67:8a")

    def test_free_text_is_case_insensitive_substring(self):
        body = self.client.get("/api/v1/access-points/?q=coffee").json()
        self.assertEqual(body["count"], 1)
        self.assertEqual(body["results"][0]["bssid"], "11:22:33:44:55:66")

    def test_free_text_matches_across_multiple_fields(self):
        # "network" appears in HomeNetwork's ssid only, not CoffeeShopWiFi's.
        body = self.client.get("/api/v1/access-points/?q=network").json()
        self.assertEqual(body["count"], 1)
        self.assertEqual(body["results"][0]["ssid"], "HomeNetwork")

    def test_column_filter_is_exact_not_substring(self):
        # ssid=HomeNetwork must not also match a hypothetical "HomeNetwork2".
        exact = self.client.get("/api/v1/access-points/?q=ssid=HomeNetwork").json()
        self.assertEqual(exact["count"], 1)
        prefix_only = self.client.get("/api/v1/access-points/?q=ssid=HomeNetwor").json()
        self.assertEqual(prefix_only["count"], 0)

    def test_column_filter_key_matches_by_containment(self):
        # "carrier" should find the field named "carrier_name", same
        # contract as the frontend's filterBySearch.
        body = self.client.get("/api/v1/cell-towers/?q=carrier%3DDeutsche%20Telekom").json()
        self.assertEqual(body["count"], 1)

    def test_unknown_column_key_returns_no_matches_not_an_error(self):
        response = self.client.get("/api/v1/access-points/?q=nonexistentfield=x")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["count"], 0)

    def test_lan_device_search_by_hostname(self):
        body = self.client.get("/api/v1/lan-devices/?q=printer").json()
        self.assertEqual(body["count"], 1)
        self.assertEqual(body["results"][0]["ip_address"], "192.168.1.42")

    def test_no_query_returns_everything(self):
        body = self.client.get("/api/v1/ble-devices/").json()
        self.assertEqual(body["count"], 2)

    def test_scan_session_search_finds_session_via_related_ble_device(self):
        # Cross-relation search — must not corrupt the per-radio counts the
        # same queryset annotates (see the get_queryset() comment about why
        # this runs as a separate id-subquery rather than filtering in place).
        body = self.client.get("/api/v1/scan-sessions/?q=venus").json()
        self.assertEqual(body["count"], 1)
        session = body["results"][0]
        self.assertEqual(session["wifi_count"], 2)
        self.assertEqual(session["cell_count"], 1)
        self.assertEqual(session["ble_count"], 2)
        self.assertEqual(session["lan_count"], 1)

    def test_scan_session_search_no_match_returns_empty_not_everything(self):
        body = self.client.get("/api/v1/scan-sessions/?q=no-such-thing-anywhere").json()
        self.assertEqual(body["count"], 0)


class QueryParamRobustnessTests(TestCase):
    """`session_limit`/`limit` both end up as queryset slice bounds, and
    Django raises ValueError("Negative indexing is not supported.") on a
    negative slice — so `?session_limit=-1` was an unhandled 500 on every
    endpoint that takes it, as was `?limit=abc`. Unusable values fall back to
    the default instead of erroring: these are view hints from the UI, not
    load-bearing input."""

    def setUp(self):
        self.sensor = Sensor.objects.create(name="Test Phone")
        ingest = APIClient()
        ingest.credentials(HTTP_AUTHORIZATION=f"Token {self.sensor.token}")
        ingest.post(
            "/api/v1/scan-sessions/",
            {
                "client_scan_id": "scan-params",
                "started_at": "2026-07-16T10:00:00Z",
                "completed_at": "2026-07-16T10:00:03Z",
                "latitude": 48.1351,
                "longitude": 11.582,
                "wifi_observations": [
                    {"bssid": "aa:bb:cc:dd:ee:ff", "ssid": "MyNetwork", "rssi": -55,
                     "frequency_mhz": 2437, "capabilities": "[RSN-PSK-CCMP][ESS]"},
                ],
            },
            format="json",
        )
        self.user = get_user_model().objects.create_user(username="operator", password="test-pass-123")
        self.client = APIClient()
        self.client.force_authenticate(user=self.user)

    def test_negative_and_garbage_session_limit_do_not_500(self):
        for value in ("-1", "0", "abc", "", "1e9999", "-99999999"):
            for path in (
                f"/api/v1/access-points/?session_limit={value}",
                f"/api/v1/cell-towers/?session_limit={value}",
                f"/api/v1/ble-devices/?session_limit={value}",
                f"/api/v1/lan-devices/?session_limit={value}",
                f"/api/v1/lan-observations/?session_limit={value}",
                f"/api/v1/ble-observations/?session_limit={value}",
                f"/api/v1/access-points/coverage/?session_limit={value}",
                f"/api/v1/cell-towers/coverage/?session_limit={value}",
                f"/api/v1/ble-observations/coverage/?session_limit={value}",
                f"/api/v1/heatmap/?source=wifi&session_limit={value}",
                f"/api/v1/channel-congestion/?band=2.4GHz&session_limit={value}",
            ):
                with self.subTest(path=path):
                    self.assertEqual(self.client.get(path).status_code, 200)

    def test_negative_and_garbage_observation_limit_do_not_500(self):
        for value in ("-5", "0", "abc"):
            with self.subTest(value=value):
                response = self.client.get(
                    f"/api/v1/access-points/aa:bb:cc:dd:ee:ff/wifi-observations/?limit={value}"
                )
                self.assertEqual(response.status_code, 200)
                # Falls back to the default rather than returning nothing.
                self.assertEqual(len(response.json()), 1)

    def test_observation_limit_is_clamped_not_unbounded(self):
        response = self.client.get("/api/v1/access-points/aa:bb:cc:dd:ee:ff/wifi-observations/?limit=999999")
        self.assertEqual(response.status_code, 200)

    def test_valid_session_limit_still_filters(self):
        # Regression guard: rejecting bad values mustn't break good ones.
        response = self.client.get("/api/v1/access-points/?session_limit=1")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["count"], 1)

    def test_resolve_addresses_limit_is_parsed_and_capped(self):
        # Patched out: each resolution makes a live Nominatim call and sleeps
        # ~1.1s to respect its rate limit — this is about the parsing.
        with mock.patch("scans.views.resolve_missing_addresses", return_value=0) as resolve:
            self.assertEqual(
                self.client.post("/api/v1/scan-sessions/resolve-addresses/", {"limit": "abc"}, format="json").status_code,
                200,
            )
            self.assertEqual(resolve.call_args.kwargs["limit"], 20)

            self.client.post("/api/v1/scan-sessions/resolve-addresses/", {"limit": 9999}, format="json")
            self.assertEqual(resolve.call_args.kwargs["limit"], 50)


class CoverageTruncationTests(TestCase):
    """Coverage/heatmap payloads are capped server-side. They used to be bare
    arrays silently sliced at the cap, so a partial map was indistinguishable
    from a complete one — the caller now gets the cap and whether it was
    hit."""

    def setUp(self):
        self.sensor = Sensor.objects.create(name="Test Phone")
        ingest = APIClient()
        ingest.credentials(HTTP_AUTHORIZATION=f"Token {self.sensor.token}")
        for index in range(3):
            ingest.post(
                "/api/v1/scan-sessions/",
                {
                    "client_scan_id": f"scan-cap-{index}",
                    "started_at": "2026-07-16T10:00:00Z",
                    "completed_at": "2026-07-16T10:00:03Z",
                    "latitude": 48.1351 + index * 0.001,
                    "longitude": 11.582,
                    "wifi_observations": [
                        {"bssid": f"aa:bb:cc:dd:ee:0{index}", "ssid": "MyNetwork", "rssi": -55,
                         "frequency_mhz": 2437, "capabilities": "[RSN-PSK-CCMP][ESS]"},
                    ],
                },
                format="json",
            )
        self.user = get_user_model().objects.create_user(username="operator", password="test-pass-123")
        self.client = APIClient()
        self.client.force_authenticate(user=self.user)

    def test_untruncated_response_is_an_envelope(self):
        response = self.client.get("/api/v1/access-points/coverage/")
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertFalse(body["truncated"])
        self.assertEqual(len(body["results"]), 3)
        self.assertEqual(body["observation_limit"], 20000)

    def test_hitting_the_cap_is_reported(self):
        with mock.patch("scans.views.COVERAGE_OBSERVATION_CAP", 2):
            body = self.client.get("/api/v1/access-points/coverage/").json()
        self.assertTrue(body["truncated"])
        self.assertEqual(len(body["results"]), 2)
        self.assertEqual(body["observation_limit"], 2)

    def test_cell_and_ble_coverage_use_the_same_envelope(self):
        for path in ("/api/v1/cell-towers/coverage/", "/api/v1/ble-observations/coverage/"):
            with self.subTest(path=path):
                body = self.client.get(path).json()
                self.assertEqual(set(body), {"results", "truncated", "observation_limit"})

    def test_heatmap_reports_its_own_cap(self):
        body = self.client.get("/api/v1/heatmap/?source=wifi").json()
        self.assertFalse(body["truncated"])
        self.assertEqual(body["observation_limit"], 5000)
        self.assertEqual(len(body["results"]), 3)

        with mock.patch("scans.views.HEATMAP_OBSERVATION_CAP", 1):
            body = self.client.get("/api/v1/heatmap/?source=wifi").json()
        self.assertTrue(body["truncated"])
        self.assertEqual(len(body["results"]), 1)


class TimeWindowFilterTests(TestCase):
    """`until` closes the far end of the observation window.

    Without it the only expressible window is "the last N minutes up to now",
    which slides every time you look at it — so a report can't cover a fixed
    interval, and can't be reproduced tomorrow. See parse_window().
    """

    def setUp(self):
        self.sensor = Sensor.objects.create(name="Test Phone")
        ingest = APIClient()
        ingest.credentials(HTTP_AUTHORIZATION=f"Token {self.sensor.token}")
        # Three passes an hour apart, each seeing its own AP.
        for index, hour in enumerate((10, 11, 12)):
            ingest.post(
                "/api/v1/scan-sessions/",
                {
                    "client_scan_id": f"scan-window-{index}",
                    "started_at": f"2026-07-16T{hour:02d}:00:00Z",
                    "completed_at": f"2026-07-16T{hour:02d}:00:03Z",
                    "latitude": 48.1351,
                    "longitude": 11.582,
                    "wifi_observations": [
                        {"bssid": f"aa:bb:cc:dd:ee:0{index}", "ssid": f"Net{index}", "rssi": -55,
                         "frequency_mhz": 2437, "capabilities": "[RSN-PSK-CCMP][ESS]"},
                    ],
                },
                format="json",
            )
        self.user = get_user_model().objects.create_user(username="operator", password="test-pass-123")
        self.client = APIClient()
        self.client.force_authenticate(user=self.user)

    def coverage_ssids(self, query=""):
        body = self.client.get(f"/api/v1/access-points/coverage/{query}").json()
        return sorted(entry["ssid"] for entry in body["results"])

    def test_no_window_returns_everything(self):
        self.assertEqual(self.coverage_ssids(), ["Net0", "Net1", "Net2"])

    def test_until_excludes_later_observations(self):
        self.assertEqual(self.coverage_ssids("?until=2026-07-16T11:30:00Z"), ["Net0", "Net1"])

    def test_since_and_until_bound_both_ends(self):
        self.assertEqual(
            self.coverage_ssids("?since=2026-07-16T10:30:00Z&until=2026-07-16T11:30:00Z"),
            ["Net1"],
        )

    def test_until_before_since_returns_nothing(self):
        # Not an error: an empty window is a coherent question with an empty
        # answer, and the UI can express it while someone is mid-edit.
        self.assertEqual(self.coverage_ssids("?since=2026-07-16T12:00:00Z&until=2026-07-16T10:00:00Z"), [])

    def test_until_applies_to_cell_and_ble_coverage_too(self):
        for path in ("/api/v1/cell-towers/coverage/", "/api/v1/ble-observations/coverage/"):
            with self.subTest(path=path):
                response = self.client.get(f"{path}?until=2026-07-16T11:30:00Z")
                self.assertEqual(response.status_code, 200)

    def test_until_applies_to_per_entity_observations(self):
        body = self.client.get(
            "/api/v1/access-points/aa:bb:cc:dd:ee:02/wifi-observations/?until=2026-07-16T11:30:00Z"
        ).json()
        self.assertEqual(body, [])


class ActiveWindowFilterTests(TestCase):
    """`active_since`/`active_until` bound the four device-list endpoints
    (AccessPoint/CellTower/BLEDevice/LANDevice) by `last_seen_at`.

    These are a distinct pair from `since`/`until`: a device list has no
    single `observed_at` of its own, only the aggregate's `last_seen_at` —
    see apply_active_window(). Without `active_until`, "Date range" mode
    could only ever close the *start* of a device list's window, and a
    device last seen after the requested range would still appear in a list
    that's supposed to stop at the end of it.
    """

    def setUp(self):
        self.sensor = Sensor.objects.create(name="Test Phone")
        ingest = APIClient()
        ingest.credentials(HTTP_AUTHORIZATION=f"Token {self.sensor.token}")
        for index, hour in enumerate((10, 11, 12)):
            ingest.post(
                "/api/v1/scan-sessions/",
                {
                    "client_scan_id": f"scan-active-window-{index}",
                    "started_at": f"2026-07-16T{hour:02d}:00:00Z",
                    "completed_at": f"2026-07-16T{hour:02d}:00:03Z",
                    "latitude": 48.1351,
                    "longitude": 11.582,
                    "wifi_observations": [
                        {"bssid": f"aa:bb:cc:dd:ee:1{index}", "ssid": f"ActiveNet{index}", "rssi": -55,
                         "frequency_mhz": 2437, "capabilities": "[RSN-PSK-CCMP][ESS]"},
                    ],
                },
                format="json",
            )
            # AccessPoint.last_seen_at is auto_now=True, so it was just
            # stamped with the real wall-clock time regardless of the
            # fictional started_at/completed_at above — .update() (unlike
            # .save()) doesn't trigger auto_now, so this is the only way to
            # give it the historical value apply_active_window is meant to
            # filter on.
            AccessPoint.objects.filter(bssid=f"aa:bb:cc:dd:ee:1{index}").update(
                last_seen_at=f"2026-07-16T{hour:02d}:00:00Z"
            )
        self.user = get_user_model().objects.create_user(username="operator", password="test-pass-123")
        self.client = APIClient()
        self.client.force_authenticate(user=self.user)

    def ssids(self, query=""):
        body = self.client.get(f"/api/v1/access-points/{query}").json()
        return sorted(entry["ssid"] for entry in body["results"])

    def test_no_window_returns_everything(self):
        self.assertEqual(self.ssids(), ["ActiveNet0", "ActiveNet1", "ActiveNet2"])

    def test_active_until_excludes_later_devices(self):
        self.assertEqual(self.ssids("?active_until=2026-07-16T11:30:00Z"), ["ActiveNet0", "ActiveNet1"])

    def test_active_since_and_until_bound_both_ends(self):
        self.assertEqual(
            self.ssids("?active_since=2026-07-16T10:30:00Z&active_until=2026-07-16T11:30:00Z"),
            ["ActiveNet1"],
        )

    def test_active_until_before_since_returns_nothing(self):
        self.assertEqual(
            self.ssids("?active_since=2026-07-16T12:00:00Z&active_until=2026-07-16T10:00:00Z"), []
        )

    def test_active_until_applies_to_cell_ble_and_lan_device_lists_too(self):
        for path in ("/api/v1/cell-towers/", "/api/v1/ble-devices/", "/api/v1/lan-devices/"):
            with self.subTest(path=path):
                response = self.client.get(f"{path}?active_until=2026-07-16T11:30:00Z")
                self.assertEqual(response.status_code, 200)


class AreaFilterTests(TestCase):
    """The map's focus circle keeps devices whose *estimated position* falls
    inside it — see within_area()/weighted_centroid()."""

    # ~48.1351 N: 0.01 degrees of latitude is roughly 1.1 km.
    CENTER = (48.1351, 11.582)

    def ingest_ap(self, bssid, ssid, points):
        """One AP observed from each of `points` (lat, lng, rssi)."""
        ingest = APIClient()
        ingest.credentials(HTTP_AUTHORIZATION=f"Token {self.sensor.token}")
        for index, (lat, lng, rssi) in enumerate(points):
            ingest.post(
                "/api/v1/scan-sessions/",
                {
                    "client_scan_id": f"{bssid}-{index}",
                    "started_at": "2026-07-16T10:00:00Z",
                    "completed_at": "2026-07-16T10:00:03Z",
                    "latitude": lat,
                    "longitude": lng,
                    "wifi_observations": [
                        {"bssid": bssid, "ssid": ssid, "rssi": rssi,
                         "frequency_mhz": 2437, "capabilities": "[RSN-PSK-CCMP][ESS]"},
                    ],
                },
                format="json",
            )

    def setUp(self):
        self.sensor = Sensor.objects.create(name="Test Phone")
        # Tight cluster at the centre.
        self.ingest_ap("aa:bb:cc:dd:ee:01", "Inside", [
            (48.1351, 11.5820, -50),
            (48.1352, 11.5821, -55),
            (48.1350, 11.5819, -60),
        ])
        # ~2.2 km north — well outside any circle used below.
        self.ingest_ap("aa:bb:cc:dd:ee:02", "Outside", [
            (48.1551, 11.5820, -50),
            (48.1552, 11.5821, -55),
            (48.1550, 11.5819, -60),
        ])
        # One reading at the centre and one 2.2 km away, at equal signal, so
        # its centroid lands midway — about 1.1 km out.
        self.ingest_ap("aa:bb:cc:dd:ee:03", "Straddler", [
            (48.1351, 11.5820, -55),
            (48.1551, 11.5820, -55),
        ])
        self.user = get_user_model().objects.create_user(username="operator", password="test-pass-123")
        self.client = APIClient()
        self.client.force_authenticate(user=self.user)

    def coverage_ssids(self, query=""):
        body = self.client.get(f"/api/v1/access-points/coverage/{query}").json()
        return sorted(entry["ssid"] for entry in body["results"])

    def area_query(self, radius_m, lat=None, lng=None):
        lat = self.CENTER[0] if lat is None else lat
        lng = self.CENTER[1] if lng is None else lng
        return f"?area_lat={lat}&area_lng={lng}&area_radius_m={radius_m}"

    def test_without_an_area_every_device_is_returned(self):
        self.assertEqual(self.coverage_ssids(), ["Inside", "Outside", "Straddler"])

    def test_area_keeps_only_devices_centred_inside(self):
        self.assertEqual(self.coverage_ssids(self.area_query(200)), ["Inside"])

    def test_straddling_device_is_judged_on_its_centroid_not_its_nearest_reading(self):
        # It has a reading exactly at the centre, so a "any reading inside"
        # rule would keep it. Its estimated position is ~1.1 km away, so the
        # centroid rule doesn't — that distinction is the whole design.
        self.assertNotIn("Straddler", self.coverage_ssids(self.area_query(200)))
        # Widen past the midpoint and it comes back.
        self.assertIn("Straddler", self.coverage_ssids(self.area_query(1500)))

    def test_a_large_enough_circle_keeps_everything(self):
        self.assertEqual(self.coverage_ssids(self.area_query(5000)), ["Inside", "Outside", "Straddler"])

    def test_kept_device_keeps_all_its_points(self):
        # Selecting a device shouldn't clip its coverage to the circle — the
        # filter picks which devices to report on, not which of their
        # readings count.
        body = self.client.get(f"/api/v1/access-points/coverage/{self.area_query(1500)}").json()
        straddler = next(e for e in body["results"] if e["ssid"] == "Straddler")
        self.assertEqual(len(straddler["points"]), 2)

    def test_partial_area_parameters_are_ignored(self):
        # A half-specified circle means the intent is unknown; filtering by a
        # guess would silently drop data.
        for query in (
            "?area_lat=48.1351",
            "?area_lat=48.1351&area_lng=11.582",
            "?area_lng=11.582&area_radius_m=200",
        ):
            with self.subTest(query=query):
                self.assertEqual(len(self.coverage_ssids(query)), 3)

    def test_unusable_area_parameters_fall_back_to_no_filter(self):
        for query in (
            "?area_lat=abc&area_lng=11.582&area_radius_m=200",
            "?area_lat=48.1351&area_lng=11.582&area_radius_m=-1",
            "?area_lat=48.1351&area_lng=11.582&area_radius_m=0",
            "?area_lat=48.1351&area_lng=11.582&area_radius_m=nan",
            "?area_lat=999&area_lng=11.582&area_radius_m=200",
        ):
            with self.subTest(query=query):
                response = self.client.get(f"/api/v1/access-points/coverage/{query}")
                self.assertEqual(response.status_code, 200)
                self.assertEqual(len(response.json()["results"]), 3)

    def test_area_filters_the_device_list_endpoint_too(self):
        body = self.client.get(f"/api/v1/access-points/{self.area_query(200)}").json()
        self.assertEqual([ap["ssid"] for ap in body["results"]], ["Inside"])

    def test_area_and_time_window_compose(self):
        query = self.area_query(5000) + "&until=2026-07-16T09:00:00Z"
        self.assertEqual(self.coverage_ssids(query), [])


class CentroidParityTests(TestCase):
    """weighted_centroid() must match weightedCentroid() in
    frontend/src/geo.ts exactly.

    The frontend draws each device's estimated position from that function;
    the backend filters on this one. Any drift and the map shows a device
    inside the focus circle that the filter excluded, with nothing on screen
    to explain why. The reference implementation below is transcribed
    straight from geo.ts — if someone "simplifies" the view helper, this
    fails.
    """

    @staticmethod
    def geo_ts_reference(points):
        weights = [p["weight"] for p in points]
        min_w, max_w = min(weights), max(weights)
        spread = (max_w - min_w) or 1
        w = [0.1 + 0.9 * ((weight - min_w) / spread) for weight in weights]
        total = sum(w)
        return (
            sum(wi * p["lat"] for wi, p in zip(w, points)) / total,
            sum(wi * p["lng"] for wi, p in zip(w, points)) / total,
        )

    def test_matches_the_frontend_formula(self):
        from .views import weighted_centroid

        cases = [
            [{"lat": 48.1351, "lng": 11.582, "weight": -50}],
            [
                {"lat": 48.1351, "lng": 11.5820, "weight": -50},
                {"lat": 48.1361, "lng": 11.5830, "weight": -80},
            ],
            [
                {"lat": 48.1351, "lng": 11.5820, "weight": -55},
                {"lat": 48.1361, "lng": 11.5830, "weight": -55},
                {"lat": 48.1371, "lng": 11.5840, "weight": -55},
            ],
        ]
        for points in cases:
            with self.subTest(n=len(points)):
                self.assertEqual(weighted_centroid(points), self.geo_ts_reference(points))

    def test_equal_weights_collapse_to_a_plain_mean(self):
        from .views import weighted_centroid

        # Every weight identical => spread falls back to 1 => every point gets
        # 0.1 => a plain average. This is what makes weight_field=None a valid
        # way to place LAN devices, which carry no signal strength.
        points = [
            {"lat": 0.0, "lng": 0.0, "weight": -60},
            {"lat": 2.0, "lng": 4.0, "weight": -60},
        ]
        self.assertEqual(weighted_centroid(points), (1.0, 2.0))

    def test_weakest_reading_still_contributes(self):
        from .views import weighted_centroid

        # The 0.1 floor: without it the weakest point's weight would be 0 and
        # the centroid would sit exactly on the strongest reading.
        points = [
            {"lat": 0.0, "lng": 0.0, "weight": -90},
            {"lat": 1.0, "lng": 0.0, "weight": -30},
        ]
        lat, _ = weighted_centroid(points)
        self.assertLess(lat, 1.0)
        self.assertGreater(lat, 0.5)


class HaversineTests(TestCase):
    def test_known_distance(self):
        from .views import haversine_m

        # One degree of latitude is ~111.2 km anywhere on the sphere.
        self.assertAlmostEqual(haversine_m(48.0, 11.0, 49.0, 11.0), 111195, delta=200)

    def test_zero_distance(self):
        from .views import haversine_m

        self.assertEqual(haversine_m(48.1351, 11.582, 48.1351, 11.582), 0.0)


class LocalizationSolverTests(TestCase):
    """solve_ap_position() — see scans/localization.py (V4 of
    ap-localization-design.md). Distances in these tests are always
    generated with haversine_m itself, so a passing test proves the solver
    correctly inverts that exact distance function, independent of any
    coordinate-projection approximation error."""

    def test_converges_to_known_position_noiseless(self):
        from .localization import haversine_m, solve_ap_position

        true_lat, true_lng = 48.1355, 11.5825
        observer_positions = [(48.1364, 11.5825), (48.1349, 11.5837), (48.1349, 11.5813)]
        observations = [
            {"lat": lat, "lng": lng, "distance_m": haversine_m(lat, lng, true_lat, true_lng), "weight": 1.0}
            for lat, lng in observer_positions
        ]
        result = solve_ap_position(observations)
        self.assertAlmostEqual(result["lat"], true_lat, delta=1e-6)
        self.assertAlmostEqual(result["lng"], true_lng, delta=1e-6)
        self.assertLess(result["rms_residual_m"], 0.01)

    def test_low_weight_outlier_barely_perturbs_the_fit(self):
        from .localization import haversine_m, solve_ap_position

        true_lat, true_lng = 48.1355, 11.5825
        good = [(48.1364, 11.5825), (48.1349, 11.5837), (48.1349, 11.5813)]
        observations = [
            {"lat": lat, "lng": lng, "distance_m": haversine_m(lat, lng, true_lat, true_lng), "weight": 100.0}
            for lat, lng in good
        ]
        # A fourth reading, badly wrong (claims the AP is 500m further away
        # than it really is), but weighted a thousand times less than the
        # good readings — should barely move the solved position.
        outlier_lat, outlier_lng = 48.1400, 11.5900
        observations.append({
            "lat": outlier_lat,
            "lng": outlier_lng,
            "distance_m": haversine_m(outlier_lat, outlier_lng, true_lat, true_lng) + 500,
            "weight": 0.001,
        })
        result = solve_ap_position(observations)
        drift_m = haversine_m(result["lat"], result["lng"], true_lat, true_lng)
        self.assertLess(drift_m, 5.0)


class UncertaintyTests(TestCase):
    """position_covariance() — V5 of ap-localization-design.md."""

    @staticmethod
    def _observations(true_lat, true_lng, positions, noise=None):
        from .localization import haversine_m

        noise = noise or [0.0] * len(positions)
        return [
            {
                "lat": lat,
                "lng": lng,
                "distance_m": haversine_m(lat, lng, true_lat, true_lng) + n,
                "weight": 1.0,
            }
            for (lat, lng), n in zip(positions, noise)
        ]

    def test_none_without_degrees_of_freedom(self):
        from .localization import position_covariance

        # 2 observations, 2 unknowns — nothing left over to estimate spread
        # from, so this must decline rather than invent an ellipse.
        obs = self._observations(48.1355, 11.5825, [(48.1364, 11.5825), (48.1349, 11.5837)])
        self.assertIsNone(position_covariance(48.1355, 11.5825, obs))

    def test_noisy_readings_widen_the_ellipse(self):
        from .localization import position_covariance, solve_ap_position

        positions = [(48.1364, 11.5825), (48.1349, 11.5837), (48.1349, 11.5813), (48.1360, 11.5840)]
        clean = self._observations(48.1355, 11.5825, positions)
        noisy = self._observations(48.1355, 11.5825, positions, noise=[12.0, -9.0, 7.0, -11.0])

        clean_solved = solve_ap_position(clean)
        noisy_solved = solve_ap_position(noisy)
        clean_ellipse = position_covariance(clean_solved["lat"], clean_solved["lng"], clean)
        noisy_ellipse = position_covariance(noisy_solved["lat"], noisy_solved["lng"], noisy)

        self.assertLess(clean_ellipse["semi_major_m"], 1.0)
        self.assertGreater(noisy_ellipse["semi_major_m"], clean_ellipse["semi_major_m"])
        self.assertGreaterEqual(noisy_ellipse["semi_major_m"], noisy_ellipse["semi_minor_m"])
        self.assertEqual(noisy_ellipse["confidence"], 0.95)
        self.assertGreaterEqual(noisy_ellipse["orientation_deg"], 0.0)
        self.assertLess(noisy_ellipse["orientation_deg"], 180.0)


class ProbabilityGridTests(TestCase):
    """position_probability_grid() — the design doc's AP *position* heatmap,
    which is a different thing from the RSSI coverage heatmap."""

    def test_peak_sits_at_the_true_position(self):
        from .localization import haversine_m, position_probability_grid

        true_lat, true_lng = 48.1355, 11.5825
        positions = [(48.1364, 11.5825), (48.1349, 11.5837), (48.1349, 11.5813)]
        obs = [
            {"lat": lat, "lng": lng, "distance_m": haversine_m(lat, lng, true_lat, true_lng), "weight": 1.0}
            for lat, lng in positions
        ]
        grid = position_probability_grid(obs, true_lat, true_lng, span_m=60, steps=21)

        self.assertEqual(len(grid["cells"]), 21 * 21)
        best = max(grid["cells"], key=lambda c: c["relative_likelihood"])
        self.assertAlmostEqual(best["relative_likelihood"], 1.0, places=6)
        self.assertLess(haversine_m(best["lat"], best["lng"], true_lat, true_lng), 4.0)

    def test_step_count_is_bounded(self):
        from .localization import position_probability_grid

        obs = [{"lat": 48.0, "lng": 11.0, "distance_m": 10.0, "weight": 1.0}]
        self.assertEqual(position_probability_grid(obs, 48.0, 11.0, steps=5000)["steps"], 61)
        self.assertEqual(position_probability_grid(obs, 48.0, 11.0, steps=1)["steps"], 3)


class NextMeasurementTests(TestCase):
    """suggest_next_positions() — V6's geometric heuristic."""

    def test_suggests_the_widest_open_direction(self):
        from .localization import initial_bearing_deg, suggest_next_positions

        # Three observers all clustered to the north of the AP — the useful
        # next measurement is clearly to the south.
        ap_lat, ap_lng = 48.1355, 11.5825
        observers = [(48.1364, 11.5820), (48.1364, 11.5825), (48.1364, 11.5830)]
        suggestions = suggest_next_positions(ap_lat, ap_lng, observers, count=1)

        self.assertEqual(len(suggestions), 1)
        bearing = initial_bearing_deg(ap_lat, ap_lng, suggestions[0]["lat"], suggestions[0]["lng"])
        # Southerly means roughly 180 deg.
        self.assertGreater(bearing, 120)
        self.assertLess(bearing, 240)
        self.assertGreater(suggestions[0]["gap_deg"], 180)

    def test_no_observers_yields_no_suggestions(self):
        from .localization import suggest_next_positions

        self.assertEqual(suggest_next_positions(48.0, 11.0, []), [])


class MeshClusteringTests(TestCase):
    """cluster_ap_hypotheses() — V8. BSSID != physical AP."""

    def test_colocated_consecutive_macs_group_into_one_hypothesis(self):
        from .localization import cluster_ap_hypotheses

        candidates = [
            {"bssid": "aa:bb:cc:dd:ee:01", "ssid": "Mesh", "vendor_oui": "AABBCC",
             "band": "2.4GHz", "lat": 48.1355, "lng": 11.5825},
            {"bssid": "aa:bb:cc:dd:ee:02", "ssid": "Mesh", "vendor_oui": "AABBCC",
             "band": "5GHz", "lat": 48.13551, "lng": 11.58251},
        ]
        groups = cluster_ap_hypotheses(candidates)
        self.assertEqual(len(groups), 1)
        self.assertTrue(groups[0]["is_multi_radio"])
        self.assertEqual(groups[0]["radio_count"], 2)
        self.assertGreater(groups[0]["confidence"], 0.8)
        self.assertIn("consecutive MAC addresses", groups[0]["evidence"])

    def test_distant_radios_stay_separate(self):
        from .localization import cluster_ap_hypotheses

        candidates = [
            {"bssid": "aa:bb:cc:dd:ee:01", "ssid": "Mesh", "vendor_oui": "AABBCC",
             "band": "2.4GHz", "lat": 48.1355, "lng": 11.5825},
            # ~300m away: same SSID and vendor, but a different mesh node.
            {"bssid": "aa:bb:cc:dd:ee:02", "ssid": "Mesh", "vendor_oui": "AABBCC",
             "band": "5GHz", "lat": 48.1382, "lng": 11.5825},
        ]
        groups = cluster_ap_hypotheses(candidates)
        self.assertEqual(len(groups), 2)
        self.assertFalse(groups[0]["is_multi_radio"])

    def test_colocation_alone_does_not_merge_unrelated_aps(self):
        from .localization import cluster_ap_hypotheses

        # The common sparse-data case: everything scanned from one spot gets
        # the same estimated centroid. Without corroborating identity
        # evidence that must NOT collapse a neighbourhood into one node.
        candidates = [
            {"bssid": "aa:bb:cc:dd:ee:01", "ssid": "Mine", "vendor_oui": "AABBCC",
             "band": "2.4GHz", "lat": 48.1355, "lng": 11.5825},
            {"bssid": "99:88:77:66:55:44", "ssid": "Neighbour", "vendor_oui": "998877",
             "band": "2.4GHz", "lat": 48.1355, "lng": 11.5825},
            {"bssid": "11:22:33:44:55:66", "ssid": "CafeWiFi", "vendor_oui": "112233",
             "band": "5GHz", "lat": 48.1355, "lng": 11.5825},
        ]
        groups = cluster_ap_hypotheses(candidates)
        self.assertEqual(len(groups), 3)
        self.assertTrue(all(not g["is_multi_radio"] for g in groups))

    def test_lone_bssid_is_not_reported_as_a_mesh_finding(self):
        from .localization import cluster_ap_hypotheses

        groups = cluster_ap_hypotheses([
            {"bssid": "aa:bb:cc:dd:ee:01", "ssid": "Solo", "vendor_oui": "AABBCC",
             "band": "5GHz", "lat": 48.1355, "lng": 11.5825},
        ])
        self.assertEqual(len(groups), 1)
        self.assertFalse(groups[0]["is_multi_radio"])
        self.assertEqual(groups[0]["evidence"], [])


class FtmPositionEndpointTests(TestCase):
    """/api/v1/access-points/<bssid>/ftm-position/ — session-auth read
    endpoint (same pattern as ReadEndpointTests) wrapping
    scans/localization.py's solver around one BSSID's stored
    FtmRangingObservation rows."""

    def setUp(self):
        self.sensor = Sensor.objects.create(name="Test Phone")
        self.access_point = AccessPoint.objects.create(bssid="aa:bb:cc:dd:ee:ff", ssid="TargetAP")
        self.user = get_user_model().objects.create_user(username="ftm-operator", password="test-pass-123")
        self.client = APIClient()
        self.client.force_authenticate(user=self.user)

    def _seed(self, client_scan_id, lat, lng, distance_m):
        session = ScanSession.objects.create(
            sensor=self.sensor,
            client_scan_id=client_scan_id,
            started_at="2026-08-14T10:00:00Z",
            completed_at="2026-08-14T10:00:05Z",
            latitude=lat,
            longitude=lng,
        )
        FtmRangingObservation.objects.create(
            scan_session=session,
            access_point=self.access_point,
            success=True,
            distance_mm=round(distance_m * 1000),
            distance_std_dev_mm=200,
            observed_at="2026-08-14T10:00:03Z",
        )

    def test_insufficient_data_reports_unavailable(self):
        from .localization import haversine_m

        true_lat, true_lng = 48.1355, 11.5825
        self._seed("ftm-1", 48.1364, 11.5825, haversine_m(48.1364, 11.5825, true_lat, true_lng))

        response = self.client.get(f"/api/v1/access-points/{self.access_point.bssid}/ftm-position/")
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertFalse(body["available"])
        self.assertEqual(body["sample_count"], 1)

    def test_solves_position_from_seeded_observations(self):
        from .localization import haversine_m

        true_lat, true_lng = 48.1355, 11.5825
        for i, (lat, lng) in enumerate([(48.1364, 11.5825), (48.1349, 11.5837), (48.1349, 11.5813)]):
            self._seed(f"ftm-{i}", lat, lng, haversine_m(lat, lng, true_lat, true_lng))

        response = self.client.get(f"/api/v1/access-points/{self.access_point.bssid}/ftm-position/")
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertTrue(body["available"])
        self.assertEqual(body["sample_count"], 3)
        self.assertEqual(body["distinct_position_count"], 3)
        self.assertAlmostEqual(body["lat"], true_lat, delta=0.0001)
        self.assertAlmostEqual(body["lng"], true_lng, delta=0.0001)
        self.assertEqual(len(body["observations"]), 3)

    def test_anonymous_read_is_rejected(self):
        response = APIClient().get(f"/api/v1/access-points/{self.access_point.bssid}/ftm-position/")
        self.assertEqual(response.status_code, 403)

    def test_response_carries_uncertainty_and_next_measurements(self):
        from .localization import haversine_m

        true_lat, true_lng = 48.1355, 11.5825
        # 4 readings so there's a degree of freedom left for the ellipse.
        positions = [(48.1364, 11.5825), (48.1349, 11.5837), (48.1349, 11.5813), (48.1360, 11.5840)]
        for i, (lat, lng) in enumerate(positions):
            self._seed(f"ftm-u{i}", lat, lng, haversine_m(lat, lng, true_lat, true_lng))

        body = self.client.get(f"/api/v1/access-points/{self.access_point.bssid}/ftm-position/").json()
        self.assertTrue(body["available"])
        self.assertIsNotNone(body["uncertainty"])
        self.assertEqual(body["uncertainty"]["confidence"], 0.95)
        self.assertGreaterEqual(body["uncertainty"]["semi_major_m"], body["uncertainty"]["semi_minor_m"])
        self.assertTrue(body["next_measurements"])
        # The grid is opt-in — it's the biggest part of the payload by far.
        self.assertIsNone(body["probability_grid"])

    def test_probability_grid_is_opt_in(self):
        from .localization import haversine_m

        true_lat, true_lng = 48.1355, 11.5825
        for i, (lat, lng) in enumerate([(48.1364, 11.5825), (48.1349, 11.5837), (48.1349, 11.5813)]):
            self._seed(f"ftm-g{i}", lat, lng, haversine_m(lat, lng, true_lat, true_lng))

        url = f"/api/v1/access-points/{self.access_point.bssid}/ftm-position/?include_grid=1&grid_steps=11"
        body = self.client.get(url).json()
        self.assertIsNotNone(body["probability_grid"])
        self.assertEqual(body["probability_grid"]["steps"], 11)
        self.assertEqual(len(body["probability_grid"]["cells"]), 11 * 11)


class PathLossParityTests(TestCase):
    """path_loss_distance_m() must match estimateRangeMeters() in
    frontend/src/coverageConfig.ts.

    Same reasoning as CentroidParityTests: the frontend draws a range blob
    from its copy, the backend now solves a position from this one, and if
    they drift the map and the estimate disagree with nothing on screen to
    explain why. The expectations below are the sanity-check values written
    in coverageConfig.ts's own comment.
    """

    def test_matches_the_documented_sanity_values(self):
        from .estimators import path_loss_distance_m

        # From coverageConfig.ts: "WiFi: -70 ≈ 13 m, -85 ≈ 46 m.
        # BLE: -85 ≈ 15 m. Cellular: -70 ≈ 190 m, -100 ≈ 1.4 km."
        self.assertAlmostEqual(path_loss_distance_m(-70, "wifi"), 13, delta=1)
        self.assertAlmostEqual(path_loss_distance_m(-85, "wifi"), 46, delta=2)
        self.assertAlmostEqual(path_loss_distance_m(-85, "ble"), 15, delta=1)
        self.assertAlmostEqual(path_loss_distance_m(-70, "cellular"), 190, delta=10)
        self.assertAlmostEqual(path_loss_distance_m(-100, "cellular"), 1400, delta=100)

    def test_clamped_to_the_same_bounds_as_the_frontend(self):
        from .estimators import path_loss_distance_m

        # MIN_RANGE_METERS floor: a very strong reading never collapses to 0.
        self.assertEqual(path_loss_distance_m(0, "wifi"), 3.0)
        # RADIUS_CAP_METERS ceiling for wifi (75m).
        self.assertEqual(path_loss_distance_m(-120, "wifi"), 75.0)
        # Cellular is uncapped in RADIUS_CAP_METERS and uses the 1500m ceiling.
        self.assertEqual(path_loss_distance_m(-150, "cellular"), 1500.0)

    def test_no_model_for_lan(self):
        from .estimators import path_loss_distance_m

        # LAN's weight is response time in ms, not dBm — no distance can be
        # inferred, and inventing one would be worse than declining.
        self.assertIsNone(path_loss_distance_m(-70, "lan"))


class EstimatorTests(TestCase):
    """estimate_position() — see scans/estimators.py."""

    @staticmethod
    def _rssi_points():
        # Readings around a target, strongest to the north.
        return [
            {"lat": 48.1360, "lng": 11.5825, "weight": -45},
            {"lat": 48.1350, "lng": 11.5837, "weight": -70},
            {"lat": 48.1350, "lng": 11.5813, "weight": -72},
            {"lat": 48.1348, "lng": 11.5825, "weight": -75},
        ]

    def test_centroid_is_the_default_and_always_available(self):
        from .estimators import CENTROID, estimate_position

        result = estimate_position(self._rssi_points(), CENTROID, "wifi")
        self.assertTrue(result["available"])
        self.assertEqual(result["estimator"], CENTROID)
        self.assertEqual(len(result["residuals"]), 4)
        # No distance model behind a centroid, so residuals say so rather than
        # reporting a fit residual they can't compute.
        self.assertTrue(all(r["kind"] == "distance_only" for r in result["residuals"]))

    def test_strongest_returns_the_strongest_readings_position(self):
        from .estimators import STRONGEST, estimate_position

        result = estimate_position(self._rssi_points(), STRONGEST, "wifi")
        self.assertTrue(result["available"])
        self.assertEqual(result["lat"], 48.1360)
        self.assertEqual(result["lng"], 11.5825)

    def test_rssi_multilateration_solves_and_reports_fit_residuals(self):
        from .estimators import RSSI_MULTILATERATION, estimate_position

        result = estimate_position(self._rssi_points(), RSSI_MULTILATERATION, "wifi")
        self.assertTrue(result["available"])
        self.assertIn("rms_residual_m", result)
        self.assertTrue(all(r["kind"] == "fit_residual" for r in result["residuals"]))
        # Worst-disagreeing reading first, so an outlier is the first row.
        magnitudes = [abs(r["residual_m"]) for r in result["residuals"]]
        self.assertEqual(magnitudes, sorted(magnitudes, reverse=True))

    def test_rssi_multilateration_unavailable_for_lan(self):
        from .estimators import CENTROID, RSSI_MULTILATERATION, estimate_position

        points = [{"lat": 48.135, "lng": 11.582, "weight": 12.0}]
        result = estimate_position(points, RSSI_MULTILATERATION, "lan")
        self.assertFalse(result["available"])
        self.assertEqual(result["fell_back_to"], CENTROID)
        self.assertIn("signal-to-distance", result["reason"])
        # Still returns a usable position — it just says which one it is.
        self.assertIsNotNone(result["lat"])

    def test_ftm_unavailable_without_ranging_data_falls_back_to_centroid(self):
        from .estimators import CENTROID, FTM_MULTILATERATION, estimate_position

        result = estimate_position(self._rssi_points(), FTM_MULTILATERATION, "wifi")
        self.assertFalse(result["available"])
        self.assertEqual(result["fell_back_to"], CENTROID)
        self.assertIsNotNone(result["lat"])

    def test_ftm_beats_rssi_multilateration_on_real_distances(self):
        from .estimators import FTM_MULTILATERATION, RSSI_MULTILATERATION, estimate_position
        from .localization import haversine_m

        true_lat, true_lng = 48.1355, 11.5825
        positions = [(48.1364, 11.5825), (48.1349, 11.5837), (48.1349, 11.5813), (48.1360, 11.5840)]
        ftm_points = [
            {
                "lat": lat,
                "lng": lng,
                "weight": 1.0,
                "distance_m": haversine_m(lat, lng, true_lat, true_lng),
                "scan_session_id": f"s{i}",
            }
            for i, (lat, lng) in enumerate(positions)
        ]
        ftm = estimate_position(ftm_points, FTM_MULTILATERATION, "wifi")
        rssi = estimate_position(self._rssi_points(), RSSI_MULTILATERATION, "wifi")

        ftm_error = haversine_m(ftm["lat"], ftm["lng"], true_lat, true_lng)
        rssi_error = haversine_m(rssi["lat"], rssi["lng"], true_lat, true_lng)
        # Real measured distances should beat a generic path-loss guess by a
        # wide margin — that gap is the whole argument for FTM.
        self.assertLess(ftm_error, 1.0)
        self.assertLess(ftm_error, rssi_error)

    def test_no_points_declines_rather_than_inventing_a_position(self):
        from .estimators import CENTROID, estimate_position

        result = estimate_position([], CENTROID, "wifi")
        self.assertFalse(result["available"])
        self.assertIsNone(result["lat"])

    def test_compare_reports_every_estimator_and_their_disagreement(self):
        from .estimators import ESTIMATORS, compare_estimators

        result = compare_estimators(self._rssi_points(), "wifi")
        self.assertEqual(set(result["estimates"]), set(ESTIMATORS))
        # Disagreements are pairwise over the estimators that actually ran,
        # widest first.
        distances = [d["distance_m"] for d in result["disagreements"]]
        self.assertEqual(distances, sorted(distances, reverse=True))


class OutlierRejectionTests(TestCase):
    """Readings that can't describe one transmitter must not reach an
    estimator. Regression tests for a real bug: the same BSSID present in two
    datasets ~9000km apart made rssi_multilateration diverge to coordinates
    that don't exist (lat 138, lng 40253), which rendered as a marker near the
    North Pole."""

    @staticmethod
    def _two_continents():
        munich = [{"lat": 48.1355 + i * 0.0005, "lng": 11.5825, "weight": -55 - i} for i in range(6)]
        vegas = [{"lat": 36.1200 + i * 0.0005, "lng": -115.1600, "weight": -60 - i} for i in range(3)]
        return munich + vegas

    def test_keeps_the_larger_cluster_and_reports_the_discards(self):
        from .estimators import reject_outlying_readings

        kept, discarded = reject_outlying_readings(self._two_continents(), "wifi")
        self.assertEqual(discarded, 3)
        self.assertEqual(len(kept), 6)
        self.assertTrue(all(p["lng"] > 0 for p in kept))  # the Munich cluster

    def test_tight_readings_are_left_alone(self):
        from .estimators import reject_outlying_readings

        points = [{"lat": 48.1355 + i * 0.0001, "lng": 11.5825, "weight": -55} for i in range(5)]
        kept, discarded = reject_outlying_readings(points, "wifi")
        self.assertEqual(discarded, 0)
        self.assertEqual(len(kept), 5)

    def test_no_estimator_returns_an_impossible_coordinate(self):
        from .estimators import ESTIMATORS, estimate_position

        for name in ESTIMATORS:
            with self.subTest(estimator=name):
                result = estimate_position(self._two_continents(), name, "wifi")
                self.assertIsNotNone(result["lat"])
                # The actual bug: latitude 138 / longitude 40253.
                self.assertGreaterEqual(result["lat"], -90)
                self.assertLessEqual(result["lat"], 90)
                self.assertGreaterEqual(result["lng"], -180)
                self.assertLessEqual(result["lng"], 180)
                self.assertEqual(result["discarded_outliers"], 3)

    def test_estimates_land_near_the_surviving_readings(self):
        from .estimators import ESTIMATORS, estimate_position
        from .localization import haversine_m

        for name in ESTIMATORS:
            with self.subTest(estimator=name):
                result = estimate_position(self._two_continents(), name, "wifi")
                # Munich, not the Atlantic between the two clusters.
                self.assertLess(haversine_m(result["lat"], result["lng"], 48.1355, 11.5825), 2000)

    def test_diverged_solve_is_reported_unavailable_not_returned(self):
        from .estimators import CENTROID, RSSI_MULTILATERATION, estimate_position

        # Distances that no single position can satisfy: readings metres apart
        # whose implied ranges differ by kilometres.
        points = [
            {"lat": 48.1355, "lng": 11.5825, "weight": -20},
            {"lat": 48.13551, "lng": 11.58251, "weight": -95},
            {"lat": 48.13552, "lng": 11.58252, "weight": -20},
            {"lat": 48.13553, "lng": 11.58253, "weight": -95},
        ]
        result = estimate_position(points, RSSI_MULTILATERATION, "cellular")
        if not result["available"]:
            self.assertEqual(result["fell_back_to"], CENTROID)
        # Either way it must be a real coordinate.
        self.assertGreaterEqual(result["lat"], -90)
        self.assertLessEqual(result["lat"], 90)


class PositionEndpointTests(TestCase):
    """/api/v1/access-points/{bssid}/position/ and its BLE/cell siblings."""

    def setUp(self):
        self.sensor = Sensor.objects.create(name="Test Phone")
        self.user = get_user_model().objects.create_user(username="pos-operator", password="test-pass-123")
        self.client = APIClient()
        self.client.force_authenticate(user=self.user)

        ingest = APIClient()
        ingest.credentials(HTTP_AUTHORIZATION=f"Token {self.sensor.token}")
        for i, (lat, lng, rssi) in enumerate([(48.1360, 11.5825, -45), (48.1350, 11.5837, -70), (48.1350, 11.5813, -72)]):
            ingest.post(
                "/api/v1/scan-sessions/",
                {
                    "client_scan_id": f"pos-{i}",
                    "started_at": f"2026-08-15T10:0{i}:00Z",
                    "completed_at": f"2026-08-15T10:0{i}:03Z",
                    "latitude": lat,
                    "longitude": lng,
                    "wifi_observations": [
                        {"bssid": "aa:bb:cc:dd:ee:ff", "ssid": "Target", "rssi": rssi,
                         "frequency_mhz": 2437, "capabilities": "[WPA2-PSK-CCMP][ESS]"},
                    ],
                },
                format="json",
            )

    def test_defaults_to_centroid(self):
        body = self.client.get("/api/v1/access-points/aa:bb:cc:dd:ee:ff/position/").json()
        self.assertEqual(body["estimator"], "centroid")
        self.assertTrue(body["available"])
        self.assertEqual(body["sample_count"], 3)

    def test_estimator_param_switches_algorithm(self):
        body = self.client.get(
            "/api/v1/access-points/aa:bb:cc:dd:ee:ff/position/?estimator=strongest"
        ).json()
        self.assertEqual(body["estimator"], "strongest")
        # The -45 dBm reading's own position.
        self.assertAlmostEqual(body["lat"], 48.1360, places=4)

    def test_unknown_estimator_falls_back_to_default(self):
        body = self.client.get(
            "/api/v1/access-points/aa:bb:cc:dd:ee:ff/position/?estimator=telepathy"
        ).json()
        self.assertEqual(body["estimator"], "centroid")

    def test_compare_returns_all_estimators(self):
        body = self.client.get("/api/v1/access-points/aa:bb:cc:dd:ee:ff/position/?compare=1").json()
        self.assertIn("estimates", body)
        self.assertIn("ftm_multilateration", body["estimates"])
        # No FTM data was ingested, so that one must report itself unavailable
        # rather than quietly returning a centroid labelled as FTM.
        self.assertFalse(body["estimates"]["ftm_multilateration"]["available"])

    def test_honours_the_time_window(self):
        # The bug this endpoint shipped with: estimates ignored the window
        # while the page (and a printed report header) claimed one.
        body = self.client.get(
            "/api/v1/access-points/aa:bb:cc:dd:ee:ff/position/?since=2026-08-15T10:01:30Z"
        ).json()
        self.assertEqual(body["sample_count"], 1)

    def test_coverage_carries_the_estimated_position(self):
        body = self.client.get("/api/v1/access-points/coverage/?estimator=strongest").json()
        entry = body["results"][0]
        self.assertEqual(entry["estimated_position"]["estimator"], "strongest")
        self.assertIsNotNone(entry["estimated_position"]["lat"])

    def test_anonymous_read_is_rejected(self):
        self.assertEqual(
            APIClient().get("/api/v1/access-points/aa:bb:cc:dd:ee:ff/position/").status_code, 403
        )


class MeshGroupsEndpointTests(TestCase):
    """/api/v1/access-points/mesh-groups/ — V8 over ordinary RSSI scan data
    (no FTM ranging required)."""

    def setUp(self):
        self.sensor = Sensor.objects.create(name="Test Phone")
        self.user = get_user_model().objects.create_user(username="mesh-operator", password="test-pass-123")
        self.client = APIClient()
        self.client.force_authenticate(user=self.user)

        ingest = APIClient()
        ingest.credentials(HTTP_AUTHORIZATION=f"Token {self.sensor.token}")
        # Two radios of one physical node (consecutive MACs, same spot,
        # different bands) plus an unrelated AP far away.
        ingest.post(
            "/api/v1/scan-sessions/",
            {
                "client_scan_id": "mesh-1",
                "started_at": "2026-08-14T10:00:00Z",
                "completed_at": "2026-08-14T10:00:03Z",
                "latitude": 48.1355,
                "longitude": 11.5825,
                "wifi_observations": [
                    {"bssid": "aa:bb:cc:dd:ee:01", "ssid": "Mesh", "rssi": -50,
                     "frequency_mhz": 2437, "capabilities": "[WPA2-PSK-CCMP][ESS]"},
                    {"bssid": "aa:bb:cc:dd:ee:02", "ssid": "Mesh", "rssi": -52,
                     "frequency_mhz": 5180, "capabilities": "[WPA2-PSK-CCMP][ESS]"},
                    {"bssid": "99:88:77:66:55:44", "ssid": "Neighbour", "rssi": -80,
                     "frequency_mhz": 2437, "capabilities": "[WPA2-PSK-CCMP][ESS]"},
                ],
            },
            format="json",
        )

    def test_groups_colocated_radios(self):
        response = self.client.get("/api/v1/access-points/mesh-groups/")
        self.assertEqual(response.status_code, 200)
        results = response.json()["results"]

        multi = [g for g in results if g["is_multi_radio"]]
        self.assertEqual(len(multi), 1)
        self.assertEqual(multi[0]["bssids"], ["aa:bb:cc:dd:ee:01", "aa:bb:cc:dd:ee:02"])
        self.assertEqual(multi[0]["ssids"], ["Mesh"])
        # The neighbour AP was heard from the same spot, so it can't be
        # separated by position here — what keeps it apart is its unrelated
        # MAC/OUI.
        self.assertIn("99:88:77:66:55:44", [b for g in results for b in g["bssids"]])

    def test_anonymous_read_is_rejected(self):
        self.assertEqual(APIClient().get("/api/v1/access-points/mesh-groups/").status_code, 403)


class MissionWifiObservationsTests(TestCase):
    """/api/v1/mission/wifi-observations/ — feeds the Android app's Mission
    view. Sensor-token-only (the reverse of every other read endpoint in this
    file, which are session-only), and its near_lat/near_lng/near_radius_m
    filter is the whole reason it exists: a "travel router" SSID seen from
    many unrelated physical locations must not corrupt the position estimate,
    so only observations near the caller's current position come back."""

    NEAR_LAT = 48.1351
    NEAR_LNG = 11.582
    # Roughly 60km away — comfortably outside any plausible near_radius_m,
    # simulating a travel router's SSID seen from a different city.
    FAR_LAT = 48.6
    FAR_LNG = 11.582

    def setUp(self):
        self.sensor = Sensor.objects.create(name="Test Phone")
        self.ingest = APIClient()
        self.ingest.credentials(HTTP_AUTHORIZATION=f"Token {self.sensor.token}")
        self._ingest_scan("scan-near-1", self.NEAR_LAT, self.NEAR_LNG, "aa:bb:cc:dd:ee:01")
        self._ingest_scan("scan-far-1", self.FAR_LAT, self.FAR_LNG, "aa:bb:cc:dd:ee:02")

        # Real session login, not force_authenticate() — force_authenticate()
        # bypasses get_authenticators() entirely (it sets request.user
        # directly), so it can't prove SessionAuthentication is actually
        # rejected here. A real logged-in session is the only way to test
        # that this sensor-token-only endpoint really excludes it.
        get_user_model().objects.create_user(username="operator", password="test-pass-123")
        self.session_client = APIClient()
        self.session_client.login(username="operator", password="test-pass-123")

    def _ingest_scan(self, client_scan_id, lat, lng, bssid, ssid="MyNetwork"):
        return self.ingest.post(
            "/api/v1/scan-sessions/",
            {
                "client_scan_id": client_scan_id,
                "started_at": "2026-07-16T10:00:00Z",
                "completed_at": "2026-07-16T10:00:03Z",
                "latitude": lat,
                "longitude": lng,
                "wifi_observations": [
                    {"bssid": bssid, "ssid": ssid, "rssi": -55,
                     "frequency_mhz": 2437, "capabilities": "[RSN-PSK-CCMP][ESS]"},
                ],
            },
            format="json",
        )

    def _get(self, client, **params):
        query = {
            "ssid_exact": "MyNetwork",
            "near_lat": self.NEAR_LAT,
            "near_lng": self.NEAR_LNG,
            "near_radius_m": 500,
            **params,
        }
        qs = "&".join(f"{key}={value}" for key, value in query.items() if value is not None)
        return client.get(f"/api/v1/mission/wifi-observations/?{qs}")

    def test_sensor_token_is_required_not_session(self):
        # Unlike ReadEndpointTests.test_scan_sessions_list_accepts_login_or_own_sensor_token —
        # this endpoint is machine-to-machine, a real logged-in session must
        # NOT work here. 401 (not 403): SensorTokenAuthentication is
        # TokenAuthentication-based, which sets a WWW-Authenticate header,
        # unlike the SessionAuthentication-only endpoints elsewhere in this
        # file that report anonymous/wrong-auth as 403 — see MEMORY.md.
        response = self._get(self.session_client)
        self.assertEqual(response.status_code, 401)

    def test_sensor_token_succeeds(self):
        response = self._get(self.ingest)
        self.assertEqual(response.status_code, 200)

    def test_anonymous_is_rejected(self):
        response = self._get(APIClient())
        self.assertIn(response.status_code, (401, 403))

    def test_inactive_sensor_token_is_rejected(self):
        self.sensor.is_active = False
        self.sensor.save(update_fields=["is_active"])
        response = self._get(self.ingest)
        self.assertEqual(response.status_code, 401)

    def test_missing_ssid_exact_is_400(self):
        response = self._get(self.ingest, ssid_exact=None)
        self.assertEqual(response.status_code, 400)

    def test_missing_near_lat_is_400(self):
        response = self._get(self.ingest, near_lat=None)
        self.assertEqual(response.status_code, 400)

    def test_missing_near_lng_is_400(self):
        response = self._get(self.ingest, near_lng=None)
        self.assertEqual(response.status_code, 400)

    def test_malformed_near_radius_m_is_400(self):
        response = self._get(self.ingest, near_radius_m="not-a-number")
        self.assertEqual(response.status_code, 400)

    def test_negative_near_radius_m_is_400(self):
        response = self._get(self.ingest, near_radius_m=-5)
        self.assertEqual(response.status_code, 400)

    def test_near_observation_included_far_one_excluded(self):
        body = self._get(self.ingest).json()
        self.assertEqual(body["ssid"], "MyNetwork")
        bssids = {p["bssid"] for p in body["points"]}
        self.assertIn("aa:bb:cc:dd:ee:01", bssids)
        self.assertNotIn("aa:bb:cc:dd:ee:02", bssids)

    def test_truncation_flag_when_near_points_exceed_cap(self):
        with mock.patch("scans.views.MISSION_OBSERVATION_CAP", 0):
            body = self._get(self.ingest).json()
        self.assertTrue(body["truncated"])
        self.assertEqual(body["points"], [])
        self.assertEqual(body["observation_limit"], 0)

    def test_cap_does_not_exclude_near_points_via_far_ones(self):
        # The core correctness regression: many far-away observations must
        # not be capped-away before the near filter even runs, which would
        # let them silently starve the near ones out of a low cap.
        for i in range(5):
            self._ingest_scan(f"scan-far-extra-{i}", self.FAR_LAT, self.FAR_LNG, f"aa:bb:cc:dd:ff:{i:02x}")

        with mock.patch("scans.views.MISSION_OBSERVATION_CAP", 1):
            body = self._get(self.ingest).json()
        self.assertEqual(len(body["points"]), 1)
        self.assertEqual(body["points"][0]["bssid"], "aa:bb:cc:dd:ee:01")

    def test_since_narrows_results(self):
        response = self._get(self.ingest, since="2030-01-01T00:00:00Z")
        body = response.json()
        self.assertEqual(body["points"], [])


class MissionBleObservationsTests(TestCase):
    """/api/v1/mission/ble-observations/ — BLE sibling of
    MissionWifiObservationsTests; see that class for the full reasoning.
    Abbreviated here since parse_required_near() is already exercised there;
    this focuses on what's specific to this endpoint."""

    NEAR_LAT = 48.1351
    NEAR_LNG = 11.582
    FAR_LAT = 48.6
    FAR_LNG = 11.582
    DEVICE_KEY = "11:22:33:44:55:66"

    def setUp(self):
        self.sensor = Sensor.objects.create(name="Test Phone")
        self.ingest = APIClient()
        self.ingest.credentials(HTTP_AUTHORIZATION=f"Token {self.sensor.token}")
        self._ingest_scan("scan-ble-near", self.NEAR_LAT, self.NEAR_LNG)
        self._ingest_scan("scan-ble-far", self.FAR_LAT, self.FAR_LNG)
        get_user_model().objects.create_user(username="operator", password="test-pass-123")
        self.session_client = APIClient()
        self.session_client.login(username="operator", password="test-pass-123")

    def _ingest_scan(self, client_scan_id, lat, lng):
        return self.ingest.post(
            "/api/v1/scan-sessions/",
            {
                "client_scan_id": client_scan_id,
                "started_at": "2026-07-16T10:00:00Z",
                "completed_at": "2026-07-16T10:00:03Z",
                "latitude": lat,
                "longitude": lng,
                "ble_observations": [
                    {"ble_mac": self.DEVICE_KEY, "rssi": -60, "device_name": "Test Beacon"},
                ],
            },
            format="json",
        )

    def _get(self, client, **params):
        query = {
            "device_key_exact": self.DEVICE_KEY,
            "near_lat": self.NEAR_LAT,
            "near_lng": self.NEAR_LNG,
            "near_radius_m": 500,
            **params,
        }
        qs = "&".join(f"{key}={value}" for key, value in query.items() if value is not None)
        return client.get(f"/api/v1/mission/ble-observations/?{qs}")

    def test_sensor_token_required_not_session(self):
        self.assertEqual(self._get(self.session_client).status_code, 401)

    def test_missing_device_key_exact_is_400(self):
        self.assertEqual(self._get(self.ingest, device_key_exact=None).status_code, 400)

    def test_near_observation_included_far_one_excluded(self):
        body = self._get(self.ingest).json()
        self.assertEqual(body["identifier"], self.DEVICE_KEY)
        self.assertEqual(len(body["points"]), 1)
        self.assertAlmostEqual(body["points"][0]["lat"], self.NEAR_LAT)

    def test_truncation_flag_when_near_points_exceed_cap(self):
        with mock.patch("scans.views.MISSION_OBSERVATION_CAP", 0):
            body = self._get(self.ingest).json()
        self.assertTrue(body["truncated"])
        self.assertEqual(body["points"], [])


class MissionCellObservationsTests(TestCase):
    """/api/v1/mission/cell-observations/ — cellular sibling of
    MissionWifiObservationsTests; see that class for the full reasoning."""

    NEAR_LAT = 48.1351
    NEAR_LNG = 11.582
    FAR_LAT = 48.6
    FAR_LNG = 11.582
    TOWER_KEY = "262-01-678-12345"

    def setUp(self):
        self.sensor = Sensor.objects.create(name="Test Phone")
        self.ingest = APIClient()
        self.ingest.credentials(HTTP_AUTHORIZATION=f"Token {self.sensor.token}")
        self._ingest_scan("scan-cell-near", self.NEAR_LAT, self.NEAR_LNG)
        self._ingest_scan("scan-cell-far", self.FAR_LAT, self.FAR_LNG)
        get_user_model().objects.create_user(username="operator", password="test-pass-123")
        self.session_client = APIClient()
        self.session_client.login(username="operator", password="test-pass-123")

    def _ingest_scan(self, client_scan_id, lat, lng):
        return self.ingest.post(
            "/api/v1/scan-sessions/",
            {
                "client_scan_id": client_scan_id,
                "started_at": "2026-07-16T10:00:00Z",
                "completed_at": "2026-07-16T10:00:03Z",
                "latitude": lat,
                "longitude": lng,
                "cell_observations": [
                    {"mcc": "262", "mnc": "01", "radio_type": "LTE", "is_serving_cell": True,
                     "cell_id": "12345", "tac_or_lac": "678", "signal_dbm": -85},
                ],
            },
            format="json",
        )

    def _get(self, client, **params):
        query = {
            "tower_key_exact": self.TOWER_KEY,
            "near_lat": self.NEAR_LAT,
            "near_lng": self.NEAR_LNG,
            "near_radius_m": 500,
            **params,
        }
        qs = "&".join(f"{key}={value}" for key, value in query.items() if value is not None)
        return client.get(f"/api/v1/mission/cell-observations/?{qs}")

    def test_sensor_token_required_not_session(self):
        self.assertEqual(self._get(self.session_client).status_code, 401)

    def test_missing_tower_key_exact_is_400(self):
        self.assertEqual(self._get(self.ingest, tower_key_exact=None).status_code, 400)

    def test_near_observation_included_far_one_excluded(self):
        body = self._get(self.ingest).json()
        self.assertEqual(body["tower_key"], self.TOWER_KEY)
        self.assertEqual(len(body["points"]), 1)
        self.assertAlmostEqual(body["points"][0]["lat"], self.NEAR_LAT)
        self.assertEqual(body["points"][0]["weight"], -85)

    def test_truncation_flag_when_near_points_exceed_cap(self):
        with mock.patch("scans.views.MISSION_OBSERVATION_CAP", 0):
            body = self._get(self.ingest).json()
        self.assertTrue(body["truncated"])
        self.assertEqual(body["points"], [])


class PathLossFitTests(TestCase):
    """fit_path_loss() — see scans/calibration.py.

    The decisive test is recovery: generate readings *from* a known
    ref/exponent, and the fit must return those constants back. A fit that
    can't invert its own forward model is worthless as a calibration."""

    @staticmethod
    def _samples(ref, exponent, distances):
        import math

        return [{"rssi": ref - 10 * exponent * math.log10(d), "distance_m": d} for d in distances]

    def test_recovers_the_constants_it_was_generated_from(self):
        from .calibration import fit_path_loss

        distances = [2, 3, 5, 8, 12, 20, 35, 50, 70]
        fit = fit_path_loss(self._samples(-42.0, 3.1, distances))

        self.assertTrue(fit["available"])
        self.assertAlmostEqual(fit["ref_rssi_at_1m"], -42.0, places=6)
        self.assertAlmostEqual(fit["path_loss_exponent"], 3.1, places=6)
        self.assertAlmostEqual(fit["r_squared"], 1.0, places=9)
        self.assertEqual(fit["sample_count"], len(distances))
        self.assertAlmostEqual(fit["min_distance_m"], 2)
        self.assertAlmostEqual(fit["max_distance_m"], 70)

    def test_declines_with_too_few_samples(self):
        from .calibration import fit_path_loss

        fit = fit_path_loss(self._samples(-40, 2.7, [5, 10, 20]))
        self.assertFalse(fit["available"])
        self.assertIn("at least", fit["reason"])

    def test_declines_when_every_reading_is_at_one_distance(self):
        from .calibration import fit_path_loss

        fit = fit_path_loss(self._samples(-40, 2.7, [10] * 12))
        self.assertFalse(fit["available"])
        self.assertIn("same distance", fit["reason"])

    def test_flags_an_implausible_fit_rather_than_clamping_it(self):
        from .calibration import fit_path_loss

        # Signal *rising* with distance — physically impossible, so the fit is
        # being driven by noise. It must be reported, not silently corrected.
        rising = [{"rssi": -90 + i * 4, "distance_m": 2 + i * 5} for i in range(10)]
        fit = fit_path_loss(rising)
        self.assertTrue(fit["available"])
        self.assertLess(fit["path_loss_exponent"], 0)
        self.assertFalse(fit["plausible"])


class GroundTruthTests(TestCase):
    """Pinned positions correct the input and score the output."""

    def setUp(self):
        self.sensor = Sensor.objects.create(name="Test Phone")
        self.user = get_user_model().objects.create_user(username="gt-operator", password="test-pass-123")
        self.client = APIClient()
        self.client.force_authenticate(user=self.user)
        self.ap = AccessPoint.objects.create(bssid="aa:bb:cc:dd:ee:ff", ssid="Target")

        # One session with a deliberately wrong GPS fix, far from the others.
        self.sessions = []
        for i, (lat, lng) in enumerate([(48.1360, 11.5825), (48.1350, 11.5837), (48.1350, 11.5813)]):
            session = ScanSession.objects.create(
                sensor=self.sensor, client_scan_id=f"gt-{i}",
                started_at="2026-08-15T10:00:00Z", completed_at="2026-08-15T10:00:05Z",
                latitude=lat, longitude=lng,
            )
            WiFiObservation.objects.create(
                scan_session=session, access_point=self.ap, rssi=-55, frequency_mhz=2437,
                channel=6, band="2.4GHz", capabilities_raw="[ESS]", observed_at="2026-08-15T10:00:03Z",
            )
            self.sessions.append(session)

    def test_pinned_observer_position_overrides_the_recorded_fix(self):
        session = self.sessions[0]
        before = self.client.get(f"/api/v1/access-points/{self.ap.bssid}/position/").json()

        GroundTruthPosition.objects.create(
            kind=GroundTruthPosition.Kind.OBSERVER, target_key=str(session.id),
            latitude=48.1400, longitude=11.5900,
        )
        after = self.client.get(f"/api/v1/access-points/{self.ap.bssid}/position/").json()
        self.assertNotEqual(before["lat"], after["lat"])

        # ...and the raw recorded fix is untouched, so the correction is an
        # overlay rather than a rewrite.
        session.refresh_from_db()
        self.assertEqual(session.latitude, 48.1360)

        # ...and it can be switched off to compare.
        off = self.client.get(f"/api/v1/access-points/{self.ap.bssid}/position/?use_ground_truth=0").json()
        self.assertAlmostEqual(off["lat"], before["lat"], places=9)

    def test_error_against_a_pinned_ap_is_reported(self):
        GroundTruthPosition.objects.create(
            kind=GroundTruthPosition.Kind.ACCESS_POINT, target_key=self.ap.bssid,
            latitude=48.1355, longitude=11.5825, label="Hall AP",
        )
        body = self.client.get(f"/api/v1/access-points/{self.ap.bssid}/position/?compare=1").json()
        self.assertIsNotNone(body["truth"])
        self.assertEqual(body["truth"]["label"], "Hall AP")
        for estimate in body["estimates"].values():
            if estimate.get("lat") is not None:
                self.assertIsNotNone(estimate["error_m"])
                self.assertGreaterEqual(estimate["error_m"], 0)

    def test_benchmark_scores_every_pinned_ap(self):
        GroundTruthPosition.objects.create(
            kind=GroundTruthPosition.Kind.ACCESS_POINT, target_key=self.ap.bssid,
            latitude=48.1355, longitude=11.5825,
        )
        body = self.client.get("/api/v1/localization/benchmark/").json()
        self.assertEqual(body["pinned_ap_count"], 1)
        self.assertEqual(body["scored_ap_count"], 1)
        self.assertIn("centroid", body["summary"])
        self.assertGreaterEqual(body["summary"]["centroid"]["mean_error_m"], 0)

    def test_repinning_the_same_target_updates_rather_than_erroring(self):
        payload = {
            "kind": "AP", "target_key": self.ap.bssid, "latitude": 48.1, "longitude": 11.5,
        }
        first = self.client.post("/api/v1/ground-truth/", payload, format="json")
        self.assertEqual(first.status_code, 201)
        second = self.client.post(
            "/api/v1/ground-truth/", {**payload, "latitude": 48.2}, format="json"
        )
        self.assertEqual(second.status_code, 200)
        self.assertEqual(GroundTruthPosition.objects.filter(kind="AP", target_key=self.ap.bssid).count(), 1)
        self.assertEqual(GroundTruthPosition.objects.get(kind="AP", target_key=self.ap.bssid).latitude, 48.2)

    def test_anonymous_access_is_rejected(self):
        self.assertEqual(APIClient().get("/api/v1/ground-truth/").status_code, 403)
        self.assertEqual(APIClient().get("/api/v1/localization/benchmark/").status_code, 403)


class ExportImportTests(TestCase):
    """Export must be re-importable, and importing twice must not duplicate."""

    def setUp(self):
        self.sensor = Sensor.objects.create(name="Field Phone")
        self.user = get_user_model().objects.create_user(username="io-operator", password="test-pass-123")
        self.client = APIClient()
        self.client.force_authenticate(user=self.user)

        ingest = APIClient()
        ingest.credentials(HTTP_AUTHORIZATION=f"Token {self.sensor.token}")
        ingest.post(
            "/api/v1/scan-sessions/",
            {
                "client_scan_id": "io-1",
                "started_at": "2026-08-15T10:00:00Z",
                "completed_at": "2026-08-15T10:00:03Z",
                "latitude": 48.1355, "longitude": 11.5825,
                "location_accuracy_meters": 8.0, "location_provider": "gps",
                "wifi_observations": [
                    {"bssid": "aa:bb:cc:dd:ee:ff", "ssid": "Target", "rssi": -55,
                     "frequency_mhz": 2437, "capabilities": "[WPA2-PSK-CCMP][ESS]"},
                ],
                "ftm_observations": [
                    {"bssid": "aa:bb:cc:dd:ee:ff", "success": True, "distance_mm": 4231,
                     "distance_std_dev_mm": 120, "status": "success"},
                ],
                "ble_observations": [
                    {"ble_mac": "11:22:33:44:55:66", "rssi": -70, "tx_power": -12,
                     "manufacturer_data": "4c00", "service_uuids": [], "device_type_guess": "UNKNOWN"},
                ],
            },
            format="json",
        )

    def test_round_trip_restores_the_data_and_is_idempotent(self):
        exported = self.client.get("/api/v1/scan-sessions/export/").json()
        self.assertEqual(exported["session_count"], 1)
        self.assertFalse(exported["truncated"])
        session = exported["sessions"][0]
        self.assertEqual(session["sensor_name"], "Field Phone")
        self.assertEqual(len(session["wifi_observations"]), 1)
        self.assertEqual(len(session["ftm_observations"]), 1)
        self.assertEqual(len(session["ble_observations"]), 1)

        # Wipe and restore.
        ScanSession.objects.all().delete()
        self.assertEqual(ScanSession.objects.count(), 0)

        restored = self.client.post("/api/v1/scan-sessions/import_sessions/", exported, format="json")
        self.assertEqual(restored.status_code, 200)
        self.assertEqual(restored.json()["created"], 1)
        self.assertEqual(restored.json()["failed_count"], 0)

        session = ScanSession.objects.get(client_scan_id="io-1")
        self.assertEqual(session.latitude, 48.1355)
        self.assertEqual(session.wifi_observations.count(), 1)
        self.assertEqual(session.ftm_observations.count(), 1)
        self.assertEqual(session.ble_observations.count(), 1)
        # Provenance survives: the sensor was matched by exported name.
        self.assertEqual(session.sensor.name, "Field Phone")
        # Derived fields are recomputed by the ingest serializer, not carried.
        self.assertEqual(session.wifi_observations.first().channel, 6)
        self.assertEqual(session.wifi_observations.first().security_type, SecurityType.WPA2)

        # Importing the same file again must be a no-op, not a duplicate.
        again = self.client.post("/api/v1/scan-sessions/import_sessions/", exported, format="json")
        self.assertEqual(again.json()["created"], 0)
        self.assertEqual(again.json()["skipped"], 1)
        self.assertEqual(ScanSession.objects.count(), 1)

    def test_export_honours_the_time_filter(self):
        empty = self.client.get("/api/v1/scan-sessions/export/?since=2030-01-01T00:00:00Z").json()
        self.assertEqual(empty["session_count"], 0)

    def test_import_rejects_a_malformed_body(self):
        response = self.client.post("/api/v1/scan-sessions/import_sessions/", {"nope": 1}, format="json")
        self.assertEqual(response.status_code, 400)

    def test_anonymous_export_is_rejected(self):
        self.assertEqual(APIClient().get("/api/v1/scan-sessions/export/").status_code, 403)


class FloorPlanTransformTests(TestCase):
    """Pixel <-> world mapping — see scans/floorplan.py.

    The y-flip is the failure this pins down: image y grows downward, world
    north grows upward. Get it wrong and the plan is mirrored, which looks
    entirely plausible right up until every measurement lands in the wrong
    room.
    """

    @staticmethod
    def _plan(**overrides):
        defaults = {
            "name": "Ground floor",
            "image_width_px": 1000,
            "image_height_px": 800,
            # 100px apart horizontally on the plan, 10m apart east in the world.
            "anchor1_image_x": 100.0, "anchor1_image_y": 700.0,
            "anchor1_lat": 48.1355, "anchor1_lng": 11.5825,
            "anchor2_image_x": 200.0, "anchor2_image_y": 700.0,
            "anchor2_lat": 48.1355, "anchor2_lng": 11.5825 + 10 / (111320 * 0.668),
        }
        defaults.update(overrides)
        plan = FloorPlan(**defaults)
        # Mirrors FloorPlanViewSet.calibrate: the anchors *derive* the
        # transform, which is then stored and is what every lookup reads.
        from .floorplan import derive_scale_and_bearing

        derived = derive_scale_and_bearing(plan)
        if derived is not None:
            plan.meters_per_pixel = derived["meters_per_pixel"]
            plan.bearing_deg = derived["bearing_deg"]
        return plan

    def test_scale_and_bearing_are_derived_from_the_anchor_pair(self):
        from .floorplan import derive_scale_and_bearing

        derived = derive_scale_and_bearing(self._plan())
        # 10m across 100px.
        self.assertAlmostEqual(derived["meters_per_pixel"], 0.1, places=3)
        # Anchors run due east across the image, so the plan's "up" is north.
        self.assertAlmostEqual(derived["bearing_deg"] % 360, 0.0, places=3)

    def test_bearing_can_be_adjusted_without_recalibrating(self):
        from .floorplan import image_to_world

        plan = self._plan()
        north_lat, _ = image_to_world(plan, 100.0, 600.0)
        # Rotate the plan 90 degrees: the same click should now read east of
        # the anchor rather than north of it.
        plan.bearing_deg = 90.0
        rotated_lat, rotated_lng = image_to_world(plan, 100.0, 600.0)
        self.assertGreater(north_lat, plan.anchor1_lat)
        self.assertAlmostEqual(rotated_lat, plan.anchor1_lat, places=6)
        self.assertGreater(rotated_lng, plan.anchor1_lng)

    def test_moving_up_the_image_moves_north(self):
        from .floorplan import image_to_world

        plan = self._plan()
        # Same x as anchor 1, 100px *up* the image (smaller y).
        lat, lng = image_to_world(plan, 100.0, 600.0)
        self.assertGreater(lat, plan.anchor1_lat)  # north, not south
        self.assertAlmostEqual(lng, plan.anchor1_lng, places=6)

    def test_moving_down_the_image_moves_south(self):
        from .floorplan import image_to_world

        plan = self._plan()
        lat, _ = image_to_world(plan, 100.0, 750.0)
        self.assertLess(lat, plan.anchor1_lat)

    def test_round_trips_through_world_and_back(self):
        from .floorplan import image_to_world, world_to_image

        plan = self._plan()
        for x, y in [(100.0, 700.0), (640.0, 120.0), (999.0, 799.0), (0.0, 0.0)]:
            with self.subTest(x=x, y=y):
                lat, lng = image_to_world(plan, x, y)
                back_x, back_y = world_to_image(plan, lat, lng)
                self.assertAlmostEqual(back_x, x, places=3)
                self.assertAlmostEqual(back_y, y, places=3)

    def test_round_trips_when_the_plan_is_rotated(self):
        from .floorplan import image_to_world, world_to_image

        # Anchors running diagonally: the plan is rotated relative to north,
        # which is the case a sign error in the rotation would survive.
        plan = self._plan(
            anchor2_image_x=200.0, anchor2_image_y=600.0,
            anchor2_lat=48.1355 + 8 / 111320, anchor2_lng=11.5825 + 8 / (111320 * 0.668),
        )
        for x, y in [(350.0, 400.0), (10.0, 780.0)]:
            with self.subTest(x=x, y=y):
                lat, lng = image_to_world(plan, x, y)
                back_x, back_y = world_to_image(plan, lat, lng)
                self.assertAlmostEqual(back_x, x, places=3)
                self.assertAlmostEqual(back_y, y, places=3)

    def test_uncalibrated_and_degenerate_plans_decline(self):
        from .floorplan import image_to_world, solve_transform

        self.assertIsNone(solve_transform(self._plan(anchor2_lat=None)))
        self.assertIsNone(image_to_world(self._plan(anchor2_lat=None), 10, 10))
        # Both anchors on the same pixel: no scale can be derived, so nothing
        # gets stored and the plan stays uncalibrated.
        coincident = self._plan(anchor2_image_x=100.0, anchor2_image_y=700.0)
        self.assertIsNone(solve_transform(coincident))


class FloorPlanCoverageTests(TestCase):
    """The weak-spot view."""

    def setUp(self):
        self.sensor = Sensor.objects.create(name="Test Phone")
        self.user = get_user_model().objects.create_user(username="fp-operator", password="test-pass-123")
        self.client = APIClient()
        self.client.force_authenticate(user=self.user)

        self.plan = FloorPlan.objects.create(
            name="Ground floor", image="floorplans/test.png",
            image_width_px=1000, image_height_px=800,
            anchor1_image_x=100.0, anchor1_image_y=700.0, anchor1_lat=48.1355, anchor1_lng=11.5825,
            anchor2_image_x=200.0, anchor2_image_y=700.0,
            anchor2_lat=48.1355, anchor2_lng=11.5825 + 10 / (111320 * 0.668),
            meters_per_pixel=0.1, bearing_deg=0.0,
        )
        self.ap = AccessPoint.objects.create(bssid="aa:bb:cc:dd:ee:01", ssid="HomeNet")
        self.ap2 = AccessPoint.objects.create(bssid="aa:bb:cc:dd:ee:02", ssid="HomeNet")

        # Three rooms: strong, weak, and one where the network wasn't heard.
        for i, (x, y, rssi) in enumerate([(150.0, 650.0, -45), (400.0, 300.0, -85), (800.0, 200.0, None)]):
            session = ScanSession.objects.create(
                sensor=self.sensor, client_scan_id=f"fp-{i}",
                started_at="2026-08-15T10:00:00Z", completed_at="2026-08-15T10:00:05Z",
                latitude=48.1355, longitude=11.5825,
            )
            GroundTruthPosition.objects.create(
                kind=GroundTruthPosition.Kind.OBSERVER, target_key=str(session.id),
                latitude=48.1355, longitude=11.5825,
                floor_plan=self.plan, image_x=x, image_y=y,
            )
            if rssi is not None:
                WiFiObservation.objects.create(
                    scan_session=session, access_point=self.ap, rssi=rssi, frequency_mhz=2437,
                    channel=6, band="2.4GHz", capabilities_raw="[ESS]", observed_at="2026-08-15T10:00:03Z",
                )

    def test_reports_pixel_coordinates_and_flags_weak_spots(self):
        body = self.client.get(
            f"/api/v1/floor-plans/{self.plan.id}/coverage/?ssid_exact=HomeNet"
        ).json()

        self.assertEqual(body["measured_count"], 3)
        self.assertEqual(body["weak_threshold_dbm"], -70.0)
        self.assertEqual(body["ssids"], ["HomeNet"])
        by_x = {p["image_x"]: p for p in body["points"]}

        self.assertFalse(by_x[150.0]["is_weak"])
        self.assertEqual(by_x[150.0]["rssi"], -45)
        self.assertTrue(by_x[400.0]["is_weak"])
        # Not heard at all is the worst weak spot, and must be distinguishable
        # from "heard faintly".
        self.assertTrue(by_x[800.0]["is_weak"])
        self.assertTrue(by_x[800.0]["no_coverage"])
        self.assertIsNone(by_x[800.0]["rssi"])
        self.assertEqual(body["weak_count"], 2)

    def test_uses_the_best_radio_at_each_point(self):
        # A second BSSID of the same SSID, stronger, at the weak spot: a mesh
        # hands you off, so what matters is the best available there.
        session = ScanSession.objects.get(client_scan_id="fp-1")
        WiFiObservation.objects.create(
            scan_session=session, access_point=self.ap2, rssi=-50, frequency_mhz=5180,
            channel=36, band="5GHz", capabilities_raw="[ESS]", observed_at="2026-08-15T10:00:03Z",
        )
        body = self.client.get(f"/api/v1/floor-plans/{self.plan.id}/coverage/?ssid_exact=HomeNet").json()
        point = next(p for p in body["points"] if p["image_x"] == 400.0)
        self.assertEqual(point["rssi"], -50)
        self.assertFalse(point["is_weak"])

    def test_threshold_is_adjustable(self):
        body = self.client.get(
            f"/api/v1/floor-plans/{self.plan.id}/coverage/?ssid_exact=HomeNet&weak_threshold_dbm=-40"
        ).json()
        # Everything is weak against a demanding threshold.
        self.assertEqual(body["weak_count"], 3)

    def test_coverage_on_a_plan_with_no_placements_yet_is_a_full_envelope(self):
        # The exact bug this pins: a freshly calibrated plan with zero
        # measurement points is a normal, expected state (the very first
        # thing after calibrating, before placing anything) — not an error.
        # The response shape must be identical to the populated case, or a
        # frontend that assumes a field exists (as this one did) crashes the
        # entire page the moment someone picks a network before placing any
        # measurements. Reproduced for real via a headless browser against
        # the actual deployed app before this fix existed.
        empty_plan = FloorPlan.objects.create(
            name="Freshly calibrated", image="floorplans/empty.png",
            image_width_px=1000, image_height_px=800,
            anchor1_image_x=0, anchor1_image_y=0, anchor1_lat=48.1, anchor1_lng=11.5,
            anchor2_image_x=500, anchor2_image_y=0, anchor2_lat=48.1, anchor2_lng=11.5007,
            meters_per_pixel=0.02, bearing_deg=0.0,
        )
        body = self.client.get(f"/api/v1/floor-plans/{empty_plan.id}/coverage/?ssid_exact=HomeNet").json()
        self.assertEqual(body["points"], [])
        self.assertIsNone(body["heatmap"])
        self.assertEqual(body["placed_aps"], [])
        self.assertEqual(body["suggestions"], [])
        self.assertEqual(body["weak_count"], 0)
        self.assertEqual(body["measured_count"], 0)
        self.assertEqual(body["ssids"], ["HomeNet"])

    def test_ssid_is_required(self):
        response = self.client.get(f"/api/v1/floor-plans/{self.plan.id}/coverage/")
        self.assertEqual(response.status_code, 400)

    def test_placing_a_pin_by_pixel_derives_a_real_position(self):
        session = ScanSession.objects.create(
            sensor=self.sensor, client_scan_id="fp-derive",
            started_at="2026-08-15T11:00:00Z", completed_at="2026-08-15T11:00:05Z",
        )
        response = self.client.post(
            "/api/v1/ground-truth/",
            {
                "kind": "OBSERVER", "target_key": str(session.id),
                "floor_plan": self.plan.id, "image_x": 100.0, "image_y": 600.0,
            },
            format="json",
        )
        self.assertEqual(response.status_code, 201)
        pin = GroundTruthPosition.objects.get(target_key=str(session.id))
        # 100px above anchor 1 at 0.1 m/px => ~10m north of it.
        self.assertAlmostEqual(pin.latitude, 48.1355 + 10 / 111320, places=6)
        self.assertEqual(pin.image_x, 100.0)

    def test_calibration_rejects_coincident_anchors(self):
        response = self.client.post(
            f"/api/v1/floor-plans/{self.plan.id}/calibrate/",
            {
                "anchor1_image_x": 10, "anchor1_image_y": 10, "anchor1_lat": 48.1, "anchor1_lng": 11.5,
                "anchor2_image_x": 10, "anchor2_image_y": 10, "anchor2_lat": 48.2, "anchor2_lng": 11.6,
            },
            format="json",
        )
        self.assertEqual(response.status_code, 400)

    def test_multiple_ssids_are_treated_as_one_network(self):
        # A router naming its 2.4 and 5GHz radios differently is still one
        # network for "do I have signal here" purposes.
        other = AccessPoint.objects.create(bssid="aa:bb:cc:dd:ee:03", ssid="HomeNet-5G")
        session = ScanSession.objects.get(client_scan_id="fp-1")  # the weak spot
        WiFiObservation.objects.create(
            scan_session=session, access_point=other, rssi=-48, frequency_mhz=5180,
            channel=36, band="5GHz", capabilities_raw="[ESS]", observed_at="2026-08-15T10:00:03Z",
        )

        alone = self.client.get(
            f"/api/v1/floor-plans/{self.plan.id}/coverage/?ssid_exact=HomeNet"
        ).json()
        together = self.client.get(
            f"/api/v1/floor-plans/{self.plan.id}/coverage/?ssid_exact=HomeNet&ssid_exact=HomeNet-5G"
        ).json()

        self.assertEqual(together["ssids"], ["HomeNet", "HomeNet-5G"])
        weak_alone = next(p for p in alone["points"] if p["image_x"] == 400.0)
        weak_together = next(p for p in together["points"] if p["image_x"] == 400.0)
        self.assertTrue(weak_alone["is_weak"])
        # The 5GHz radio covers that spot, so it isn't actually a weak spot.
        self.assertFalse(weak_together["is_weak"])
        self.assertEqual(weak_together["ssid"], "HomeNet-5G")

    def test_comma_separated_ssids_also_work(self):
        body = self.client.get(
            f"/api/v1/floor-plans/{self.plan.id}/coverage/?ssid_exact=HomeNet,HomeNet-5G"
        ).json()
        self.assertEqual(body["ssids"], ["HomeNet", "HomeNet-5G"])

    def test_bearing_can_be_adjusted_through_the_api(self):
        response = self.client.post(
            f"/api/v1/floor-plans/{self.plan.id}/adjust/", {"bearing_deg": 42.5}, format="json"
        )
        self.assertEqual(response.status_code, 200)
        self.plan.refresh_from_db()
        self.assertAlmostEqual(self.plan.bearing_deg, 42.5)
        # Still calibrated, and the transform now reflects the new bearing.
        self.assertTrue(self.plan.is_calibrated)

    def test_rotating_keeps_the_plan_centred(self):
        from .floorplan import image_to_world
        from .localization import haversine_m

        centre = (self.plan.image_width_px / 2, self.plan.image_height_px / 2)
        before = image_to_world(self.plan, *centre)

        self.client.post(
            f"/api/v1/floor-plans/{self.plan.id}/adjust/", {"bearing_deg": 90}, format="json"
        )
        self.plan.refresh_from_db()
        after = image_to_world(self.plan, *centre)

        # Rotation must spin the plan in place. Rotating about the anchor —
        # which is wherever calibration happened to be clicked, often a corner
        # — swings the whole plan sideways instead, so it can never be lined
        # up by adjusting the bearing.
        #
        # Asserted in metres rather than decimal places: correcting the anchor
        # shifts it slightly, which nudges the local longitude scale, so a
        # sub-millimetre residual is expected and meaningless. A centimetre is
        # far below anything that matters on a floor plan.
        self.assertLess(haversine_m(before[0], before[1], after[0], after[1]), 0.01)
        self.assertAlmostEqual(self.plan.bearing_deg, 90)

    def test_rescaling_keeps_the_plan_centred(self):
        from .floorplan import image_to_world
        from .localization import haversine_m

        centre = (self.plan.image_width_px / 2, self.plan.image_height_px / 2)
        before = image_to_world(self.plan, *centre)

        self.client.post(
            f"/api/v1/floor-plans/{self.plan.id}/adjust/", {"meters_per_pixel": 0.2}, format="json"
        )
        self.plan.refresh_from_db()
        after = image_to_world(self.plan, *centre)
        self.assertLess(haversine_m(before[0], before[1], after[0], after[1]), 0.01)

    def test_adjusting_the_plan_resyncs_placed_points(self):
        from .floorplan import image_to_world
        from .localization import haversine_m

        session = ScanSession.objects.create(
            sensor=self.sensor, client_scan_id="resync-1",
            started_at="2026-08-17T10:00:00Z", completed_at="2026-08-17T10:00:05Z",
        )
        placed = self.client.post(
            "/api/v1/ground-truth/",
            {"kind": "OBSERVER", "target_key": str(session.id),
             "floor_plan": self.plan.id, "image_x": 900.0, "image_y": 100.0},
            format="json",
        )
        self.assertEqual(placed.status_code, 201)
        pin = GroundTruthPosition.objects.get(target_key=str(session.id))
        before = (pin.latitude, pin.longitude)

        self.client.post(f"/api/v1/floor-plans/{self.plan.id}/adjust/", {"bearing_deg": 90}, format="json")
        self.plan.refresh_from_db()
        pin.refresh_from_db()

        # A pin's pixel position is the operator's assertion; its lat/lng is
        # derived. Rotating the plan must move the derived value, or every
        # estimator silently keeps using a position that is now metres out.
        self.assertGreater(haversine_m(*before, pin.latitude, pin.longitude), 1.0)
        expected = image_to_world(self.plan, pin.image_x, pin.image_y)
        self.assertLess(haversine_m(pin.latitude, pin.longitude, *expected), 0.01)

    def test_recalibrating_resyncs_placed_points(self):
        from .floorplan import image_to_world
        from .localization import haversine_m

        session = ScanSession.objects.create(
            sensor=self.sensor, client_scan_id="resync-2",
            started_at="2026-08-17T10:00:00Z", completed_at="2026-08-17T10:00:05Z",
        )
        self.client.post(
            "/api/v1/ground-truth/",
            {"kind": "OBSERVER", "target_key": str(session.id),
             "floor_plan": self.plan.id, "image_x": 300.0, "image_y": 400.0},
            format="json",
        )
        # Re-anchor somewhere else entirely; the placement must follow.
        self.client.post(
            f"/api/v1/floor-plans/{self.plan.id}/calibrate/",
            {
                "anchor1_image_x": 0, "anchor1_image_y": 0, "anchor1_lat": 49.0, "anchor1_lng": 10.0,
                "anchor2_image_x": 500, "anchor2_image_y": 0, "anchor2_lat": 49.0, "anchor2_lng": 10.0007,
            },
            format="json",
        )
        self.plan.refresh_from_db()
        pin = GroundTruthPosition.objects.get(target_key=str(session.id))
        expected = image_to_world(self.plan, pin.image_x, pin.image_y)
        self.assertLess(haversine_m(pin.latitude, pin.longitude, *expected), 0.01)
        self.assertAlmostEqual(pin.latitude, expected[0], places=6)

    def test_reset_calibration_clears_everything(self):
        response = self.client.post(f"/api/v1/floor-plans/{self.plan.id}/reset-calibration/")
        self.assertEqual(response.status_code, 200)
        self.assertFalse(response.json()["is_calibrated"])
        self.assertIsNone(response.json()["corners"])
        self.plan.refresh_from_db()
        self.assertIsNone(self.plan.anchor1_lat)
        self.assertIsNone(self.plan.meters_per_pixel)
        self.assertIsNone(self.plan.bearing_deg)

    def test_adjust_rejects_a_non_positive_scale(self):
        response = self.client.post(
            f"/api/v1/floor-plans/{self.plan.id}/adjust/", {"meters_per_pixel": 0}, format="json"
        )
        self.assertEqual(response.status_code, 400)

    def test_nearby_ssids_lists_what_is_audible_here(self):
        body = self.client.get(f"/api/v1/floor-plans/{self.plan.id}/nearby-ssids/").json()
        names = [r["ssid"] for r in body["results"]]
        self.assertIn("HomeNet", names)
        row = next(r for r in body["results"] if r["ssid"] == "HomeNet")
        self.assertGreaterEqual(row["reading_count"], 1)
        self.assertIn("aa:bb:cc:dd:ee:01", row["bssids"])

    def test_nearby_ssids_includes_networks_from_placed_scans(self):
        # Move the plan's anchor far from every recorded GPS fix. The radius
        # search now finds nothing, but the scans placed on the plan are still
        # unambiguously part of this survey — indoors the placements are the
        # trustworthy signal and the GPS fixes are not.
        self.plan.anchor1_lat, self.plan.anchor1_lng = 10.0, 10.0
        self.plan.save()

        body = self.client.get(f"/api/v1/floor-plans/{self.plan.id}/nearby-ssids/?radius_m=50").json()
        self.assertIn("HomeNet", [r["ssid"] for r in body["results"]])

    def test_heatmap_leaves_unmeasured_areas_blank(self):
        body = self.client.get(
            f"/api/v1/floor-plans/{self.plan.id}/coverage/"
            "?ssid_exact=HomeNet&include_heatmap=1&heatmap_steps=20"
        ).json()
        cells = body["heatmap"]["cells"]
        self.assertEqual(len(cells), 20 * 20)

        painted = [c for c in cells if c["rssi"] is not None]
        blank = [c for c in cells if c["rssi"] is None]
        # Some of the plan is covered, and some deliberately isn't —
        # extrapolating across the whole plan from three readings would be
        # inventing coverage nobody measured.
        self.assertGreater(len(painted), 0)
        self.assertGreater(len(blank), 0)
        # Every blank cell is blank *because* it's beyond the influence radius.
        self.assertTrue(all(c["distance_px"] > body["heatmap"]["max_influence_px"] for c in blank))

    def test_heatmap_is_opt_in(self):
        body = self.client.get(f"/api/v1/floor-plans/{self.plan.id}/coverage/?ssid_exact=HomeNet").json()
        self.assertIsNone(body["heatmap"])

    def test_plan_corners_describe_the_footprint(self):
        body = self.client.get(f"/api/v1/floor-plans/{self.plan.id}/").json()
        corners = body["corners"]
        self.assertEqual(len(corners), 4)
        # Bearing 0 at 0.1 m/px: the top edge runs due east, so the first two
        # corners share a latitude and the second is further east.
        self.assertAlmostEqual(corners[0]["lat"], corners[1]["lat"], places=6)
        self.assertGreater(corners[1]["lng"], corners[0]["lng"])
        # The bottom corners are south of the top ones.
        self.assertLess(corners[2]["lat"], corners[1]["lat"])

    def test_uncalibrated_plan_has_no_corners(self):
        from .models import FloorPlan

        bare = FloorPlan.objects.create(
            name="Bare", image="floorplans/b.png", image_width_px=100, image_height_px=100
        )
        body = self.client.get(f"/api/v1/floor-plans/{bare.id}/").json()
        self.assertIsNone(body["corners"])

    def test_suggests_adding_an_ap_when_none_is_placed(self):
        body = self.client.get(
            f"/api/v1/floor-plans/{self.plan.id}/coverage/?ssid_exact=HomeNet"
        ).json()
        self.assertEqual(body["placed_aps"], [])
        self.assertTrue(body["suggestions"])
        first = body["suggestions"][0]
        self.assertEqual(first["action"], "add")
        self.assertIsNone(first["nearest_ap_bssid"])
        self.assertGreaterEqual(first["weak_point_count"], 1)

    def test_suggests_moving_a_nearby_ap_rather_than_adding(self):
        # An access point close to the weak cluster: moving it is the cheaper
        # fix, and the suggestion should say so rather than demanding new
        # hardware.
        GroundTruthPosition.objects.create(
            kind=GroundTruthPosition.Kind.ACCESS_POINT, target_key="aa:bb:cc:dd:ee:01",
            latitude=48.1355, longitude=11.5825,
            floor_plan=self.plan, image_x=430.0, image_y=330.0,
        )
        body = self.client.get(
            f"/api/v1/floor-plans/{self.plan.id}/coverage/?ssid_exact=HomeNet"
        ).json()
        self.assertEqual(len(body["placed_aps"]), 1)
        moves = [s for s in body["suggestions"] if s["action"] == "move"]
        self.assertTrue(moves)
        self.assertEqual(moves[0]["nearest_ap_bssid"], "aa:bb:cc:dd:ee:01")
        self.assertLess(moves[0]["nearest_ap_distance_m"], 12)

    def test_a_dead_spot_stays_weak_at_any_threshold(self):
        # Lowering the bar far enough that every *measured* reading passes
        # still leaves the spot where the network wasn't heard at all — an
        # absent network isn't a matter of degree, so no threshold should
        # explain it away.
        body = self.client.get(
            f"/api/v1/floor-plans/{self.plan.id}/coverage/?ssid_exact=HomeNet&weak_threshold_dbm=-200"
        ).json()
        self.assertEqual(body["weak_count"], 1)
        weak = [p for p in body["points"] if p["is_weak"]]
        self.assertTrue(weak[0]["no_coverage"])
        # And it still earns a suggestion, since it's a real gap.
        self.assertTrue(body["suggestions"])

    def test_no_weak_spots_means_no_suggestions(self):
        # With the unheard-from spot removed and a permissive threshold there
        # is nothing to fix, and inventing advice anyway would be noise.
        GroundTruthPosition.objects.filter(image_x=800.0).delete()
        body = self.client.get(
            f"/api/v1/floor-plans/{self.plan.id}/coverage/?ssid_exact=HomeNet&weak_threshold_dbm=-200"
        ).json()
        self.assertEqual(body["weak_count"], 0)
        self.assertEqual(body["suggestions"], [])

    def test_anonymous_access_is_rejected(self):
        self.assertEqual(APIClient().get("/api/v1/floor-plans/").status_code, 403)


class FloorPlanOutlineTests(TestCase):
    """The traced building footprint.

    A plan image is a rectangle; the building inside it usually isn't. The
    outline is what makes the map footprint match an L-shaped house, and what
    stops the interpolated heatmap painting coverage over the part of the
    rectangle that is garden.
    """

    def setUp(self):
        self.sensor = Sensor.objects.create(name="Outline Phone")
        self.user = get_user_model().objects.create_user(username="outline-op", password="test-pass-123")
        self.client = APIClient()
        self.client.force_authenticate(user=self.user)

        # North-up, 0.1 m per pixel, so pixel offsets map to predictable
        # metres and the outline's world coordinates can be reasoned about.
        self.plan = FloorPlan.objects.create(
            name="L-shaped", image="floorplans/test.png",
            image_width_px=1000, image_height_px=800,
            anchor1_image_x=0.0, anchor1_image_y=800.0, anchor1_lat=48.1355, anchor1_lng=11.5825,
            anchor2_image_x=100.0, anchor2_image_y=800.0,
            anchor2_lat=48.1355, anchor2_lng=11.5825 + 10 / (111320 * 0.668),
            meters_per_pixel=0.1, bearing_deg=0.0,
        )
        self.ap = AccessPoint.objects.create(bssid="aa:bb:cc:dd:ee:11", ssid="HomeNet")

    def _url(self, action):
        return f"/api/v1/floor-plans/{self.plan.id}/{action}/"

    def _place(self, name, x, y, rssi):
        session = ScanSession.objects.create(
            sensor=self.sensor, client_scan_id=name,
            started_at="2026-08-15T10:00:00Z", completed_at="2026-08-15T10:00:05Z",
            latitude=48.1355, longitude=11.5825,
        )
        GroundTruthPosition.objects.create(
            kind=GroundTruthPosition.Kind.OBSERVER, target_key=str(session.id),
            latitude=48.1355, longitude=11.5825, floor_plan=self.plan, image_x=x, image_y=y,
        )
        WiFiObservation.objects.create(
            scan_session=session, access_point=self.ap, rssi=rssi, frequency_mhz=2437,
            channel=6, band="2.4GHz", capabilities_raw="[ESS]", observed_at="2026-08-15T10:00:03Z",
        )

    # --- storing a trace -------------------------------------------------

    def test_an_untraced_plan_reports_the_image_rectangle(self):
        corners = self.client.get("/api/v1/floor-plans/").json()["results"][0]["corners"]
        self.assertEqual(len(corners), 4)

    def test_storing_an_outline_changes_the_reported_footprint(self):
        # A six-vertex L.
        points = [
            {"x": 0, "y": 0}, {"x": 600, "y": 0}, {"x": 600, "y": 400},
            {"x": 1000, "y": 400}, {"x": 1000, "y": 800}, {"x": 0, "y": 800},
        ]
        response = self.client.post(self._url("outline"), {"points": points}, format="json")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(len(response.json()["outline_points"]), 6)
        self.assertEqual(len(response.json()["corners"]), 6)

    def test_an_empty_list_clears_the_outline_back_to_the_rectangle(self):
        self.plan.outline_points = [{"x": 0, "y": 0}, {"x": 10, "y": 0}, {"x": 10, "y": 10}]
        self.plan.save()
        body = self.client.post(self._url("outline"), {"points": []}, format="json").json()
        self.assertEqual(body["outline_points"], [])
        self.assertEqual(len(body["corners"]), 4)

    def test_vertices_are_clamped_to_the_image_not_rejected(self):
        # Dragging a vertex slightly past the edge while tracing is ordinary;
        # snapping to the border is what was meant.
        points = [{"x": -50, "y": -50}, {"x": 5000, "y": 10}, {"x": 10, "y": 5000}]
        body = self.client.post(self._url("outline"), {"points": points}, format="json").json()
        self.assertEqual(body["outline_points"][0], {"x": 0.0, "y": 0.0})
        self.assertEqual(body["outline_points"][1]["x"], 1000.0)
        self.assertEqual(body["outline_points"][2]["y"], 800.0)

    def test_a_degenerate_outline_is_rejected(self):
        for bad in ([{"x": 0, "y": 0}], [{"x": 0, "y": 0}, {"x": 5, "y": 5}]):
            with self.subTest(bad=bad):
                response = self.client.post(self._url("outline"), {"points": bad}, format="json")
                self.assertEqual(response.status_code, 400)

    def test_malformed_points_are_rejected_rather_than_stored(self):
        for bad in ("not-a-list", [{"x": "a", "y": 1}], [{"x": 1}], [1, 2, 3]):
            with self.subTest(bad=bad):
                response = self.client.post(self._url("outline"), {"points": bad}, format="json")
                self.assertEqual(response.status_code, 400)
        self.plan.refresh_from_db()
        self.assertEqual(self.plan.outline_points, [])

    # --- geometry --------------------------------------------------------

    def test_point_in_outline_handles_a_concave_shape(self):
        from scans.floorplan import point_in_outline

        # The same L: the notch (top-right) is outside, the arms are inside.
        l_shape = [(0, 0), (600, 0), (600, 400), (1000, 400), (1000, 800), (0, 800)]
        self.assertTrue(point_in_outline(300, 200, l_shape))   # upper-left arm
        self.assertTrue(point_in_outline(800, 600, l_shape))   # lower-right arm
        self.assertFalse(point_in_outline(800, 200, l_shape))  # the notch
        self.assertFalse(point_in_outline(-10, 200, l_shape))  # outside entirely

    def test_outline_pixels_ignores_a_half_finished_trace(self):
        from scans.floorplan import outline_pixels

        self.plan.outline_points = [{"x": 0, "y": 0}, {"x": 10, "y": 0}]
        self.assertIsNone(outline_pixels(self.plan))
        self.plan.outline_points = [{"x": 0, "y": 0}, {"x": 10, "y": 0}, {"x": 10, "y": 10}]
        self.assertEqual(len(outline_pixels(self.plan)), 3)

    # --- the point of it all: clipping the heatmap -----------------------

    def test_heatmap_is_clipped_to_the_traced_outline(self):
        # Two readings in the left half, so IDW would otherwise happily paint
        # cells across the right half of the rectangle too.
        self._place("clip-a", 100.0, 200.0, -50)
        self._place("clip-b", 200.0, 600.0, -60)

        url = f"{self._url('coverage')}?ssid_exact=HomeNet&include_heatmap=1&heatmap_steps=20"
        before = self.client.get(url).json()["heatmap"]["cells"]

        # Restrict the building to the left half of the image.
        self.client.post(
            self._url("outline"),
            {"points": [{"x": 0, "y": 0}, {"x": 500, "y": 0}, {"x": 500, "y": 800}, {"x": 0, "y": 800}]},
            format="json",
        )
        after = self.client.get(url).json()["heatmap"]["cells"]

        self.assertLess(len(after), len(before))
        self.assertTrue(after, "clipping must not empty the heatmap entirely")
        # Nothing survives outside the traced footprint.
        self.assertTrue(all(cell["image_x"] <= 500 for cell in after))
        # And the readings' own neighbourhood is still painted.
        self.assertTrue(any(cell["rssi"] is not None for cell in after))

    def test_clipping_does_not_change_the_measured_points_themselves(self):
        # The outline governs the *interpolated* surface. A measurement you
        # actually took is data, not inference, and must not be filtered out
        # by a trace drawn later.
        self._place("keep-a", 100.0, 200.0, -50)
        self._place("keep-b", 900.0, 700.0, -60)
        self.client.post(
            self._url("outline"),
            {"points": [{"x": 0, "y": 0}, {"x": 500, "y": 0}, {"x": 500, "y": 800}, {"x": 0, "y": 800}]},
            format="json",
        )
        body = self.client.get(f"{self._url('coverage')}?ssid_exact=HomeNet").json()
        self.assertEqual(body["measured_count"], 2)
        self.assertEqual(len(body["points"]), 2)

    def test_outline_survives_a_bearing_adjustment(self):
        # The trace is stored in pixels, so rotating the plan must move the
        # footprint with it rather than invalidating it.
        points = [{"x": 0, "y": 0}, {"x": 600, "y": 0}, {"x": 600, "y": 400},
                  {"x": 1000, "y": 400}, {"x": 1000, "y": 800}, {"x": 0, "y": 800}]
        self.client.post(self._url("outline"), {"points": points}, format="json")
        before = self.client.get("/api/v1/floor-plans/").json()["results"][0]["corners"]

        self.client.post(self._url("adjust"), {"bearing_deg": 90.0}, format="json")
        after = self.client.get("/api/v1/floor-plans/").json()["results"][0]["corners"]

        self.assertEqual(len(after), 6)
        self.assertNotEqual(
            [(round(c["lat"], 6), round(c["lng"], 6)) for c in before],
            [(round(c["lat"], 6), round(c["lng"], 6)) for c in after],
        )

    def test_anonymous_cannot_store_an_outline(self):
        response = APIClient().post(self._url("outline"), {"points": []}, format="json")
        self.assertEqual(response.status_code, 403)


class FloorPlanPredictedCoverageTests(TestCase):
    """Signal predicted outward from a placed access point.

    A different question from the interpolated surface: interpolation is
    anchored on where you walked and says nothing about a room you never
    entered; prediction is anchored on the transmitter and covers the whole
    floor. Both are useful, and confusing one for the other would mean
    treating a model output as an observation.
    """

    def setUp(self):
        self.sensor = Sensor.objects.create(name="Predict Phone")
        self.user = get_user_model().objects.create_user(username="predict-op", password="test-pass-123")
        self.client = APIClient()
        self.client.force_authenticate(user=self.user)

        # 0.1 m/px over 1000x800: a 10m x 8m floor.
        self.plan = FloorPlan.objects.create(
            name="Predicted", image="floorplans/test.png",
            image_width_px=1000, image_height_px=800,
            anchor1_image_x=0.0, anchor1_image_y=800.0, anchor1_lat=48.1355, anchor1_lng=11.5825,
            anchor2_image_x=100.0, anchor2_image_y=800.0,
            anchor2_lat=48.1355, anchor2_lng=11.5825 + 10 / (111320 * 0.668),
            meters_per_pixel=0.1, bearing_deg=0.0,
        )
        self.ap = AccessPoint.objects.create(bssid="aa:bb:cc:dd:ee:21", ssid="HomeNet")

    def _url(self, extra=""):
        return (
            f"/api/v1/floor-plans/{self.plan.id}/coverage/?ssid_exact=HomeNet"
            f"&include_prediction=1&heatmap_steps=20{extra}"
        )

    def _place_ap(self, x, y):
        GroundTruthPosition.objects.create(
            kind=GroundTruthPosition.Kind.ACCESS_POINT, target_key=self.ap.bssid,
            latitude=48.1355, longitude=11.5825, floor_plan=self.plan, image_x=x, image_y=y,
        )

    def _place_reading(self, name, x, y, rssi):
        session = ScanSession.objects.create(
            sensor=self.sensor, client_scan_id=name,
            started_at="2026-08-15T10:00:00Z", completed_at="2026-08-15T10:00:05Z",
            latitude=48.1355, longitude=11.5825,
        )
        GroundTruthPosition.objects.create(
            kind=GroundTruthPosition.Kind.OBSERVER, target_key=str(session.id),
            latitude=48.1355, longitude=11.5825, floor_plan=self.plan, image_x=x, image_y=y,
        )
        WiFiObservation.objects.create(
            scan_session=session, access_point=self.ap, rssi=rssi, frequency_mhz=2437,
            channel=6, band="2.4GHz", capabilities_raw="[ESS]", observed_at="2026-08-15T10:00:03Z",
        )

    def test_no_placed_ap_means_no_prediction(self):
        self._place_reading("p-none", 100.0, 100.0, -50)
        self.assertIsNone(self.client.get(self._url()).json()["prediction"])

    def test_prediction_is_absent_unless_asked_for(self):
        self._place_ap(500.0, 400.0)
        body = self.client.get(
            f"/api/v1/floor-plans/{self.plan.id}/coverage/?ssid_exact=HomeNet"
        ).json()
        self.assertIsNone(body["prediction"])

    def test_signal_falls_off_with_distance_from_the_access_point(self):
        # This is the whole point: strongest at the transmitter, weakening
        # outward — not strongest wherever somebody happened to stand.
        self._place_ap(500.0, 400.0)
        cells = self.client.get(self._url()).json()["prediction"]["cells"]
        self.assertTrue(cells)

        def distance_px(cell):
            return math.hypot(cell["image_x"] - 500.0, cell["image_y"] - 400.0)

        nearest = min(cells, key=distance_px)
        farthest = max(cells, key=distance_px)
        self.assertGreater(nearest["rssi"], farthest["rssi"])

        # And monotonically so, which a correlation-free spot check wouldn't catch.
        ordered = sorted(cells, key=distance_px)
        rssis = [c["rssi"] for c in ordered]
        self.assertEqual(rssis, sorted(rssis, reverse=True))

    def test_prediction_covers_ground_the_interpolation_will_not(self):
        # One reading in a corner. Interpolation must stay near it; prediction
        # should still describe the far side of the floor.
        self._place_ap(500.0, 400.0)
        self._place_reading("p-corner", 60.0, 60.0, -55)
        body = self.client.get(self._url() + "&include_heatmap=1").json()

        painted = [c for c in body["heatmap"]["cells"] if c["rssi"] is not None]
        predicted = body["prediction"]["cells"]
        self.assertGreater(len(predicted), len(painted))

    def test_each_cell_names_the_access_point_it_came_from(self):
        self._place_ap(500.0, 400.0)
        cells = self.client.get(self._url()).json()["prediction"]["cells"]
        self.assertTrue(all(c["bssid"] == self.ap.bssid for c in cells))

    def test_the_strongest_of_several_access_points_wins_each_cell(self):
        # What you actually experience in a room is whichever radio is
        # strongest there, not the average of them.
        other = AccessPoint.objects.create(bssid="aa:bb:cc:dd:ee:22", ssid="HomeNet")
        self._place_ap(100.0, 100.0)
        GroundTruthPosition.objects.create(
            kind=GroundTruthPosition.Kind.ACCESS_POINT, target_key=other.bssid,
            latitude=48.1355, longitude=11.5825, floor_plan=self.plan, image_x=900.0, image_y=700.0,
        )
        cells = self.client.get(self._url()).json()["prediction"]["cells"]
        near_first = min(cells, key=lambda c: math.hypot(c["image_x"] - 100, c["image_y"] - 100))
        near_second = min(cells, key=lambda c: math.hypot(c["image_x"] - 900, c["image_y"] - 700))
        self.assertEqual(near_first["bssid"], self.ap.bssid)
        self.assertEqual(near_second["bssid"], other.bssid)

    def test_falloff_is_fitted_to_real_readings_when_there_are_enough(self):
        self._place_ap(100.0, 400.0)
        # Eight readings spread over distance — the same bar the
        # deployment-wide path-loss calibration insists on, deliberately, since
        # this is the same least-squares fit.
        for i, (x, rssi) in enumerate(
            [(120.0, -38), (200.0, -52), (300.0, -60), (400.0, -66),
             (500.0, -70), (600.0, -74), (700.0, -77), (800.0, -80)]
        ):
            self._place_reading(f"p-fit-{i}", x, 400.0, rssi)
        source = self.client.get(self._url()).json()["prediction"]["sources"][0]
        self.assertEqual(source["source"], "fitted")
        self.assertEqual(source["sample_count"], 8)
        self.assertIsNotNone(source["r_squared"])

    def test_falloff_falls_back_to_the_model_with_too_few_readings(self):
        self._place_ap(100.0, 400.0)
        for i, x in enumerate([200.0, 400.0, 600.0]):
            self._place_reading(f"p-few-{i}", x, 400.0, -60 - i * 8)
        source = self.client.get(self._url()).json()["prediction"]["sources"][0]
        self.assertEqual(source["source"], "model")
        self.assertIsNone(source["r_squared"])

    def test_prediction_is_clipped_to_the_traced_outline(self):
        self._place_ap(500.0, 400.0)
        before = len(self.client.get(self._url()).json()["prediction"]["cells"])
        self.client.post(
            f"/api/v1/floor-plans/{self.plan.id}/outline/",
            {"points": [{"x": 0, "y": 0}, {"x": 500, "y": 0}, {"x": 500, "y": 800}, {"x": 0, "y": 800}]},
            format="json",
        )
        after = self.client.get(self._url()).json()["prediction"]["cells"]
        self.assertLess(len(after), before)
        self.assertTrue(all(c["image_x"] <= 500 for c in after))

    def test_no_cell_is_stronger_than_the_reference_at_one_metre(self):
        # The log-distance model diverges toward the transmitter; without a
        # floor on the distance it reports physically impossible strength in
        # the cell the AP sits in.
        self._place_ap(500.0, 400.0)
        body = self.client.get(self._url()).json()["prediction"]
        ref = body["sources"][0]["ref_rssi_at_1m"]
        self.assertTrue(all(c["rssi"] <= ref + 1e-6 for c in body["cells"]))


class FloorPlanOutlineFromMapTests(TestCase):
    """Tracing the footprint on the map rather than on the plan drawing.

    The map used to accept exactly two clicks in its entire life — the two
    calibration anchors — and silently ignore every one after that. Following
    the actual roof is often a better reference than the architect's drawing,
    which is frequently a crop or a sketch.
    """

    def setUp(self):
        self.user = get_user_model().objects.create_user(username="map-outline", password="test-pass-123")
        self.client = APIClient()
        self.client.force_authenticate(user=self.user)
        self.plan = FloorPlan.objects.create(
            name="Map traced", image="floorplans/test.png",
            image_width_px=1000, image_height_px=800,
            anchor1_image_x=0.0, anchor1_image_y=800.0, anchor1_lat=48.1355, anchor1_lng=11.5825,
            anchor2_image_x=100.0, anchor2_image_y=800.0,
            anchor2_lat=48.1355, anchor2_lng=11.5825 + 10 / (111320 * 0.668),
            meters_per_pixel=0.1, bearing_deg=0.0,
        )

    def _url(self):
        return f"/api/v1/floor-plans/{self.plan.id}/outline/"

    def test_world_points_are_converted_and_stored_as_pixels(self):
        # Round-trip: take four known pixel corners out to the world, send
        # those back as a map-traced outline, and they must land where they
        # started. Storing pixels is what makes an outline survive the plan
        # later being rotated or rescaled.
        from scans.floorplan import image_to_world

        pixels = [(100.0, 100.0), (700.0, 120.0), (680.0, 600.0), (120.0, 640.0)]
        world = [dict(zip(("lat", "lng"), image_to_world(self.plan, x, y))) for x, y in pixels]

        body = self.client.post(self._url(), {"points": world}, format="json").json()
        stored = body["outline_points"]
        self.assertEqual(len(stored), 4)
        for (want_x, want_y), got in zip(pixels, stored):
            self.assertAlmostEqual(got["x"], want_x, places=3)
            self.assertAlmostEqual(got["y"], want_y, places=3)

    def test_a_map_traced_outline_drives_the_footprint_and_the_heatmap_clip(self):
        from scans.floorplan import image_to_world, outline_pixels

        world = [
            dict(zip(("lat", "lng"), image_to_world(self.plan, x, y)))
            for x, y in [(0.0, 0.0), (500.0, 0.0), (500.0, 800.0), (0.0, 800.0)]
        ]
        body = self.client.post(self._url(), {"points": world}, format="json").json()
        self.assertEqual(len(body["corners"]), 4)
        self.plan.refresh_from_db()
        self.assertEqual(len(outline_pixels(self.plan)), 4)

    def test_mixing_pixel_and_world_points_is_rejected(self):
        response = self.client.post(
            self._url(),
            {"points": [{"x": 1, "y": 2}, {"lat": 48.1, "lng": 11.5}, {"x": 3, "y": 4}]},
            format="json",
        )
        self.assertEqual(response.status_code, 400)
        self.plan.refresh_from_db()
        self.assertEqual(self.plan.outline_points, [])

    def test_world_points_need_a_calibrated_plan(self):
        uncalibrated = FloorPlan.objects.create(
            name="Raw", image="floorplans/test.png", image_width_px=100, image_height_px=100,
        )
        response = self.client.post(
            f"/api/v1/floor-plans/{uncalibrated.id}/outline/",
            {"points": [{"lat": 48.1, "lng": 11.5}, {"lat": 48.2, "lng": 11.6}, {"lat": 48.3, "lng": 11.7}]},
            format="json",
        )
        self.assertEqual(response.status_code, 400)

    def test_world_points_outside_the_plan_are_clamped_to_it(self):
        # Clicking a roof corner slightly outside the plan's own rectangle is
        # ordinary — the drawing is often a crop — and snapping to the border
        # is more useful than refusing the trace.
        from scans.floorplan import image_to_world

        world = [
            dict(zip(("lat", "lng"), image_to_world(self.plan, x, y)))
            for x, y in [(-400.0, -300.0), (1500.0, 0.0), (1400.0, 1600.0)]
        ]
        stored = self.client.post(self._url(), {"points": world}, format="json").json()["outline_points"]
        self.assertEqual(stored[0], {"x": 0.0, "y": 0.0})
        self.assertEqual(stored[1]["x"], 1000.0)
        self.assertEqual(stored[2]["y"], 800.0)

    def test_malformed_world_points_are_rejected(self):
        for bad in ([{"lat": "x", "lng": 1}] * 3, [{"lat": 48.1}] * 3):
            with self.subTest(bad=bad):
                self.assertEqual(self.client.post(self._url(), {"points": bad}, format="json").status_code, 400)
