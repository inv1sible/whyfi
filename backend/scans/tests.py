from unittest import mock

from django.contrib.auth import get_user_model
from django.db import DatabaseError
from django.test import TestCase
from rest_framework.test import APIClient

from sensors.models import Sensor

from .models import AccessPoint, ScanSession, SecurityType, WiFiObservation
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
