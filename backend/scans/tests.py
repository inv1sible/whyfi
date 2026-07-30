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
