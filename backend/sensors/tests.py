import datetime
from unittest import mock

from django.contrib.auth import get_user_model
from django.test import Client, TestCase
from django.utils import timezone
from rest_framework.test import APIClient

from .models import Sensor, SensorScanPolicy


class SensorModelTests(TestCase):
    def test_token_is_generated_and_unique(self):
        a = Sensor.objects.create(name="Phone A")
        b = Sensor.objects.create(name="Phone B")
        self.assertTrue(a.token)
        self.assertNotEqual(a.token, b.token)

    def test_is_authenticated_property(self):
        sensor = Sensor.objects.create(name="Phone A")
        self.assertTrue(sensor.is_authenticated)


class SensorListEndpointTests(TestCase):
    def setUp(self):
        self.user = get_user_model().objects.create_user(username="operator", password="test-pass-123")

    def test_anonymous_request_is_rejected(self):
        # 403, not 401: SessionAuthentication defines no WWW-Authenticate
        # header, so DRF reports anonymous requests as 403 Forbidden — this
        # is standard DRF behavior, not a bug. See MEMORY.md.
        Sensor.objects.create(name="Phone A")
        response = APIClient().get("/api/v1/sensors/")
        self.assertEqual(response.status_code, 403)

    def test_logged_in_list_does_not_expose_token(self):
        Sensor.objects.create(name="Phone A")
        client = APIClient()
        client.force_authenticate(user=self.user)
        response = client.get("/api/v1/sensors/")
        self.assertEqual(response.status_code, 200)
        body = response.json()["results"][0]
        self.assertNotIn("token", body)


class SensorCreateAndRegenerateTests(TestCase):
    def setUp(self):
        self.user = get_user_model().objects.create_user(username="operator", password="test-pass-123")
        self.client = APIClient()
        self.client.force_authenticate(user=self.user)

    def test_anonymous_create_is_rejected(self):
        response = APIClient().post("/api/v1/sensors/", {"name": "Pixel 8"}, format="json")
        self.assertEqual(response.status_code, 403)

    def test_create_returns_token_once(self):
        response = self.client.post("/api/v1/sensors/", {"name": "Pixel 8"}, format="json")
        self.assertEqual(response.status_code, 201, response.content)
        body = response.json()
        self.assertEqual(body["name"], "Pixel 8")
        self.assertEqual(body["sensor_type"], "android")
        self.assertTrue(body["token"])

        sensor = Sensor.objects.get(name="Pixel 8")
        self.assertEqual(sensor.token, body["token"])

    def test_list_after_create_does_not_expose_token(self):
        self.client.post("/api/v1/sensors/", {"name": "Pixel 8"}, format="json")
        response = self.client.get("/api/v1/sensors/")
        self.assertNotIn("token", response.json()["results"][0])

    def test_regenerate_token_changes_it(self):
        sensor = Sensor.objects.create(name="Old Phone")
        old_token = sensor.token

        response = self.client.post(f"/api/v1/sensors/{sensor.id}/regenerate-token/")
        self.assertEqual(response.status_code, 200)
        new_token = response.json()["token"]

        self.assertNotEqual(old_token, new_token)
        sensor.refresh_from_db()
        self.assertEqual(sensor.token, new_token)

    def test_regenerate_token_requires_auth(self):
        sensor = Sensor.objects.create(name="Old Phone")
        response = APIClient().post(f"/api/v1/sensors/{sensor.id}/regenerate-token/")
        self.assertEqual(response.status_code, 403)


class SensorDeactivateAndDeleteTests(TestCase):
    """Deactivate (reversible, keeps history) and delete (irreversible,
    guarded against taking a device's scan history with it) — see
    SensorViewSet.set_active/perform_destroy."""

    def setUp(self):
        self.user = get_user_model().objects.create_user(username="operator", password="test-pass-123")
        self.client = APIClient()
        self.client.force_authenticate(user=self.user)

    def test_set_active_false_blocks_the_token(self):
        sensor = Sensor.objects.create(name="Retiring Phone")
        token = sensor.token

        response = self.client.post(f"/api/v1/sensors/{sensor.id}/set-active/", {"is_active": False}, format="json")
        self.assertEqual(response.status_code, 200, response.content)
        self.assertFalse(response.json()["is_active"])

        ingest_client = APIClient()
        ingest_client.credentials(HTTP_AUTHORIZATION=f"Token {token}")
        heartbeat = ingest_client.post("/api/v1/sensors/me/heartbeat/", {}, format="json")
        self.assertEqual(heartbeat.status_code, 401)

    def test_set_active_true_reactivates_it(self):
        sensor = Sensor.objects.create(name="Phone", is_active=False)
        response = self.client.post(f"/api/v1/sensors/{sensor.id}/set-active/", {"is_active": True}, format="json")
        self.assertEqual(response.status_code, 200)
        sensor.refresh_from_db()
        self.assertTrue(sensor.is_active)

    def test_set_active_requires_a_boolean(self):
        sensor = Sensor.objects.create(name="Phone")
        response = self.client.post(f"/api/v1/sensors/{sensor.id}/set-active/", {"is_active": "nope"}, format="json")
        self.assertEqual(response.status_code, 400)

    def test_set_active_requires_auth(self):
        sensor = Sensor.objects.create(name="Phone")
        response = APIClient().post(f"/api/v1/sensors/{sensor.id}/set-active/", {"is_active": False}, format="json")
        self.assertEqual(response.status_code, 403)

    def test_delete_a_sensor_with_no_scans_succeeds(self):
        sensor = Sensor.objects.create(name="Never Used")
        response = self.client.delete(f"/api/v1/sensors/{sensor.id}/")
        self.assertEqual(response.status_code, 204)
        self.assertFalse(Sensor.objects.filter(id=sensor.id).exists())

    def test_delete_a_sensor_with_scans_is_blocked(self):
        sensor = Sensor.objects.create(name="Has History")
        ingest = APIClient()
        ingest.credentials(HTTP_AUTHORIZATION=f"Token {sensor.token}")
        ingest.post(
            "/api/v1/scan-sessions/",
            {
                "client_scan_id": "scan-has-history",
                "started_at": "2026-07-16T10:00:00Z",
                "completed_at": "2026-07-16T10:00:03Z",
            },
            format="json",
        )

        response = self.client.delete(f"/api/v1/sensors/{sensor.id}/")
        self.assertEqual(response.status_code, 409, response.content)
        self.assertTrue(Sensor.objects.filter(id=sensor.id).exists())

    def test_delete_requires_auth(self):
        sensor = Sensor.objects.create(name="Phone")
        response = APIClient().delete(f"/api/v1/sensors/{sensor.id}/")
        self.assertEqual(response.status_code, 403)
        self.assertTrue(Sensor.objects.filter(id=sensor.id).exists())


class SensorCsrfProtectionTests(TestCase):
    """force_authenticate (used above) bypasses CSRF middleware entirely —
    this exercises the real login+cookie+header flow. See MEMORY.md."""

    def setUp(self):
        get_user_model().objects.create_user(username="operator", password="test-pass-123")
        self.client = Client(enforce_csrf_checks=True)
        self.client.login(username="operator", password="test-pass-123")
        self.client.get("/api/v1/auth/session/")  # sets csrftoken, mirrors the SPA's app-load check

    def test_create_without_csrf_token_is_rejected(self):
        response = self.client.post(
            "/api/v1/sensors/", data='{"name": "Pixel 8"}', content_type="application/json"
        )
        self.assertEqual(response.status_code, 403)

    def test_create_with_csrf_token_succeeds(self):
        csrf_token = self.client.cookies["csrftoken"].value
        response = self.client.post(
            "/api/v1/sensors/",
            data='{"name": "Pixel 8"}',
            content_type="application/json",
            HTTP_X_CSRFTOKEN=csrf_token,
        )
        self.assertEqual(response.status_code, 201)


HEARTBEAT_URL = "/api/v1/sensors/me/heartbeat/"


class SensorHeartbeatTests(TestCase):
    """The device half of remote scanning control."""

    def setUp(self):
        self.sensor = Sensor.objects.create(name="Phone A")
        self.other = Sensor.objects.create(name="Phone B")
        self.client = APIClient()
        self.client.credentials(HTTP_AUTHORIZATION=f"Token {self.sensor.token}")

    def test_anonymous_heartbeat_is_rejected(self):
        # 401 here, not 403 as with SessionAuthentication: token auth does
        # define a WWW-Authenticate header, so DRF reports it as unauthorized.
        response = APIClient().post(HEARTBEAT_URL, {}, format="json")
        self.assertEqual(response.status_code, 401)

    def test_bad_token_is_rejected(self):
        client = APIClient()
        client.credentials(HTTP_AUTHORIZATION="Token not-a-real-token")
        self.assertEqual(client.post(HEARTBEAT_URL, {}, format="json").status_code, 401)

    def test_inactive_sensor_is_rejected(self):
        self.sensor.is_active = False
        self.sensor.save(update_fields=["is_active"])
        self.assertEqual(self.client.post(HEARTBEAT_URL, {}, format="json").status_code, 401)

    def test_first_heartbeat_creates_policy_row_and_returns_defaults(self):
        self.assertFalse(SensorScanPolicy.objects.filter(sensor=self.sensor).exists())

        response = self.client.post(HEARTBEAT_URL, {}, format="json")
        self.assertEqual(response.status_code, 200, response.content)

        body = response.json()
        self.assertFalse(body["remote_scan_enabled"])
        self.assertEqual(body["scan_interval_seconds"], 60)
        self.assertTrue(body["include_wifi"])
        self.assertTrue(SensorScanPolicy.objects.filter(sensor=self.sensor).exists())

    def test_reported_fields_persist_and_stamp_heartbeat_time(self):
        response = self.client.post(
            HEARTBEAT_URL,
            {
                "reported_is_continuous": True,
                "reported_is_scanning": True,
                "reported_phase": "WIFI",
                "reported_completed_scans": 12,
                "reported_pending_uploads": 3,
                "reported_battery_percent": 64,
                "reported_app_version": "2026.07.26",
            },
            format="json",
        )
        self.assertEqual(response.status_code, 200, response.content)

        policy = SensorScanPolicy.objects.get(sensor=self.sensor)
        self.assertTrue(policy.reported_is_continuous)
        self.assertEqual(policy.reported_phase, "WIFI")
        self.assertEqual(policy.reported_completed_scans, 12)
        self.assertEqual(policy.reported_battery_percent, 64)
        self.assertIsNotNone(policy.last_heartbeat_at)

    def test_heartbeat_returns_desired_state_set_by_the_operator(self):
        policy = SensorScanPolicy.objects.create(sensor=self.sensor)
        policy.remote_scan_enabled = True
        policy.scan_interval_seconds = 120
        policy.include_ble = False
        policy.save()

        body = self.client.post(HEARTBEAT_URL, {}, format="json").json()
        self.assertTrue(body["remote_scan_enabled"])
        self.assertEqual(body["scan_interval_seconds"], 120)
        self.assertFalse(body["include_ble"])

    def test_heartbeat_cannot_reach_another_sensors_policy(self):
        SensorScanPolicy.objects.create(sensor=self.other, remote_scan_enabled=True)

        body = self.client.post(HEARTBEAT_URL, {"reported_completed_scans": 5}, format="json").json()

        # Got its own policy (defaults), not Phone B's enabled one...
        self.assertFalse(body["remote_scan_enabled"])
        # ...and left Phone B's reported state untouched.
        self.other.scan_policy.refresh_from_db()
        self.assertIsNone(self.other.scan_policy.reported_completed_scans)

    def test_unknown_and_omitted_fields_are_tolerated(self):
        # An older APK omits fields it doesn't know about, and a newer one may
        # send fields this backend hasn't learned yet. Neither may 400.
        response = self.client.post(
            HEARTBEAT_URL,
            {"reported_is_scanning": True, "some_future_field": "whatever"},
            format="json",
        )
        self.assertEqual(response.status_code, 200, response.content)

    def test_device_cannot_write_desired_state(self):
        # The heartbeat serializer only accepts reported_* fields — a
        # compromised or buggy device must not be able to enable itself.
        self.client.post(HEARTBEAT_URL, {"remote_scan_enabled": True}, format="json")
        policy = SensorScanPolicy.objects.get(sensor=self.sensor)
        self.assertFalse(policy.remote_scan_enabled)


class ScanPolicyEndpointTests(TestCase):
    """The operator half — session-authenticated writes from the PWA."""

    def setUp(self):
        self.user = get_user_model().objects.create_user(username="operator", password="test-pass-123")
        self.sensor = Sensor.objects.create(name="Phone A")
        self.client = APIClient()
        self.client.force_authenticate(user=self.user)
        self.url = f"/api/v1/sensors/{self.sensor.id}/scan-policy/"

    def test_anonymous_write_is_rejected(self):
        response = APIClient().post(self.url, {"remote_scan_enabled": True}, format="json")
        self.assertEqual(response.status_code, 403)

    def test_sensor_token_cannot_write_policy(self):
        client = APIClient()
        client.credentials(HTTP_AUTHORIZATION=f"Token {self.sensor.token}")
        self.assertEqual(
            client.post(self.url, {"remote_scan_enabled": True}, format="json").status_code, 403
        )

    def test_write_creates_row_updates_fields_and_bumps_revision(self):
        response = self.client.post(
            self.url, {"remote_scan_enabled": True, "scan_interval_seconds": 90}, format="json"
        )
        self.assertEqual(response.status_code, 200, response.content)

        policy = SensorScanPolicy.objects.get(sensor=self.sensor)
        self.assertTrue(policy.remote_scan_enabled)
        self.assertEqual(policy.scan_interval_seconds, 90)
        self.assertEqual(policy.policy_revision, 1)

        self.client.post(self.url, {"remote_scan_enabled": False}, format="json")
        policy.refresh_from_db()
        self.assertEqual(policy.policy_revision, 2)

    def test_partial_write_leaves_other_fields_alone(self):
        self.client.post(self.url, {"scan_interval_seconds": 300}, format="json")
        self.client.post(self.url, {"include_ble": False}, format="json")

        policy = SensorScanPolicy.objects.get(sensor=self.sensor)
        self.assertEqual(policy.scan_interval_seconds, 300)
        self.assertFalse(policy.include_ble)

    def test_short_interval_rejected_while_wifi_included(self):
        response = self.client.post(self.url, {"scan_interval_seconds": 20}, format="json")
        self.assertEqual(response.status_code, 400)
        self.assertIn("scan_interval_seconds", response.json())

    def test_short_interval_allowed_without_wifi(self):
        response = self.client.post(
            self.url, {"scan_interval_seconds": 20, "include_wifi": False}, format="json"
        )
        self.assertEqual(response.status_code, 200, response.content)

    def test_reenabling_wifi_against_a_stored_short_interval_is_rejected(self):
        # The check has to consider the resulting state, not just the payload.
        self.client.post(self.url, {"scan_interval_seconds": 20, "include_wifi": False}, format="json")
        response = self.client.post(self.url, {"include_wifi": True}, format="json")
        self.assertEqual(response.status_code, 400)

    def test_interval_below_hard_floor_is_rejected(self):
        response = self.client.post(
            self.url, {"scan_interval_seconds": 5, "include_wifi": False}, format="json"
        )
        self.assertEqual(response.status_code, 400)

    def test_policy_appears_in_sensor_list(self):
        self.client.post(self.url, {"remote_scan_enabled": True}, format="json")
        body = self.client.get("/api/v1/sensors/").json()["results"][0]
        self.assertTrue(body["scan_policy"]["remote_scan_enabled"])
        self.assertNotIn("token", body)

    def test_sensor_list_returns_defaults_without_creating_a_row(self):
        body = self.client.get("/api/v1/sensors/").json()["results"][0]
        self.assertFalse(body["scan_policy"]["remote_scan_enabled"])
        self.assertFalse(body["scan_policy"]["agent_online"])
        # A read must never write.
        self.assertFalse(SensorScanPolicy.objects.filter(sensor=self.sensor).exists())


class ScanNowNonceTests(TestCase):
    def setUp(self):
        self.user = get_user_model().objects.create_user(username="operator", password="test-pass-123")
        self.sensor = Sensor.objects.create(name="Phone A")
        self.client = APIClient()
        self.client.force_authenticate(user=self.user)

    def test_scan_now_increments_nonce_without_enabling_continuous(self):
        response = self.client.post(f"/api/v1/sensors/{self.sensor.id}/scan-now/")
        self.assertEqual(response.status_code, 200, response.content)
        self.assertEqual(response.json()["scan_now_nonce"], 1)
        self.assertFalse(response.json()["remote_scan_enabled"])

    def test_device_echo_marks_the_request_consumed(self):
        self.client.post(f"/api/v1/sensors/{self.sensor.id}/scan-now/")

        device = APIClient()
        device.credentials(HTTP_AUTHORIZATION=f"Token {self.sensor.token}")
        body = device.post(HEARTBEAT_URL, {"reported_scan_now_nonce": 1}, format="json").json()

        # Device has caught up: nothing further to run until the next click.
        self.assertEqual(body["scan_now_nonce"], body["reported_scan_now_nonce"])


class ResetCountersTests(TestCase):
    def setUp(self):
        self.user = get_user_model().objects.create_user(username="operator", password="test-pass-123")
        self.sensor = Sensor.objects.create(name="Phone A")
        self.client = APIClient()
        self.client.force_authenticate(user=self.user)
        self.url = f"/api/v1/sensors/{self.sensor.id}/reset-counters/"

    def test_anonymous_reset_is_rejected(self):
        self.assertEqual(APIClient().post(self.url).status_code, 403)

    def test_reset_increments_nonce_without_touching_policy_revision(self):
        # It's a one-off action, not a change to desired state — bumping the
        # revision would make the UI show "pending" for something that isn't
        # a policy change at all.
        response = self.client.post(self.url)
        self.assertEqual(response.status_code, 200, response.content)
        body = response.json()
        self.assertEqual(body["reset_counters_nonce"], 1)
        self.assertEqual(body["policy_revision"], 0)

    def test_device_echo_consumes_the_reset(self):
        self.client.post(self.url)

        device = APIClient()
        device.credentials(HTTP_AUTHORIZATION=f"Token {self.sensor.token}")
        body = device.post(
            HEARTBEAT_URL,
            {"reported_reset_counters_nonce": 1, "reported_completed_scans": 0},
            format="json",
        ).json()

        self.assertEqual(body["reset_counters_nonce"], body["reported_reset_counters_nonce"])


class HeartbeatIntervalDefaultTests(TestCase):
    def test_default_check_in_interval_is_ten_seconds(self):
        sensor = Sensor.objects.create(name="Phone A")
        self.assertEqual(SensorScanPolicy.objects.create(sensor=sensor).heartbeat_interval_seconds, 10)


class PolicyPendingTests(TestCase):
    def test_pending_until_device_echoes_the_revision(self):
        sensor = Sensor.objects.create(name="Phone A")
        policy = SensorScanPolicy.objects.create(sensor=sensor, policy_revision=4)

        self.assertTrue(policy.policy_pending)

        policy.reported_policy_revision = 4
        self.assertFalse(policy.policy_pending)

    def test_untouched_device_is_not_pending(self):
        # Nothing has ever been asked of this device, so there's nothing
        # outstanding — it must not show as "pending" just because it has
        # never reported a revision.
        sensor = Sensor.objects.create(name="Phone A")
        policy = SensorScanPolicy.objects.create(sensor=sensor)

        self.assertIsNone(policy.reported_policy_revision)
        self.assertEqual(policy.policy_revision, 0)
        self.assertFalse(policy.policy_pending)


class AgentOnlineTests(TestCase):
    """Staleness is judged against the cadence the device *actually* polls at,
    which is not `heartbeat_interval_seconds` unless it's scanning: while
    armed-but-idle RemoteControlAgent backs off to min(interval * 4, 60s).

    The original formula (`interval * 3 + 15`) was computed from the
    configured interval instead, which left only 5s of margin at the default
    10s (a 40s idle poll against a 45s window) and coincided *exactly* with
    the device's capped 60s idle poll at interval=15 — so a perfectly healthy
    armed device flapped between online and offline indefinitely.
    """

    def setUp(self):
        self.sensor = Sensor.objects.create(name="Phone A")

    def _at(self, policy, seconds_later, base):
        with mock.patch("sensors.models.timezone.now", return_value=base + datetime.timedelta(seconds=seconds_later)):
            return policy.agent_online

    def test_offline_when_never_heard_from(self):
        policy = SensorScanPolicy.objects.create(sensor=self.sensor)
        self.assertFalse(policy.agent_online)

    def test_online_just_after_a_heartbeat(self):
        policy = SensorScanPolicy.objects.create(sensor=self.sensor, last_heartbeat_at=timezone.now())
        self.assertTrue(policy.agent_online)

    def test_expected_interval_follows_the_devices_idle_backoff(self):
        policy = SensorScanPolicy(sensor=self.sensor, heartbeat_interval_seconds=10)
        self.assertEqual(policy.expected_heartbeat_interval_seconds, 40)

        # Capped at 60s, same as the agent's IDLE_MAX_MS.
        policy.heartbeat_interval_seconds = 30
        self.assertEqual(policy.expected_heartbeat_interval_seconds, 60)

        # Actively scanning: no backoff, so the full-rate interval applies.
        policy.heartbeat_interval_seconds = 10
        policy.remote_scan_enabled = True
        policy.reported_is_continuous = True
        self.assertEqual(policy.expected_heartbeat_interval_seconds, 10)

    def test_idle_device_survives_one_dropped_poll(self):
        # The case that used to flap: default interval, device idling at 40s.
        now = timezone.now()
        policy = SensorScanPolicy.objects.create(
            sensor=self.sensor, heartbeat_interval_seconds=10, last_heartbeat_at=now
        )
        self.assertTrue(self._at(policy, 45, now))  # old window ended here
        self.assertTrue(self._at(policy, 80, now))  # one poll missed entirely
        self.assertFalse(self._at(policy, 96, now))  # 40 * 2 + 15s grace

    def test_idle_device_at_a_15s_interval_does_not_flap(self):
        # interval 15 -> capped 60s idle poll, which exactly equalled the old
        # 60s staleness window.
        now = timezone.now()
        policy = SensorScanPolicy.objects.create(
            sensor=self.sensor, heartbeat_interval_seconds=15, last_heartbeat_at=now
        )
        self.assertTrue(self._at(policy, 61, now))
        self.assertFalse(self._at(policy, 136, now))  # 60 * 2 + 15s grace

    def test_scanning_device_is_judged_at_the_full_rate(self):
        # No backoff while scanning, so going quiet is noticed quickly rather
        # than after the idle device's much longer window.
        now = timezone.now()
        policy = SensorScanPolicy.objects.create(
            sensor=self.sensor,
            heartbeat_interval_seconds=10,
            remote_scan_enabled=True,
            reported_is_continuous=True,
            last_heartbeat_at=now,
        )
        self.assertTrue(self._at(policy, 30, now))
        self.assertFalse(self._at(policy, 36, now))  # 10 * 2 + 15s grace

    def test_freshly_enabled_scanning_keeps_the_idle_window(self):
        """Desired state alone must not tighten the window.

        Between the operator enabling scanning and the device confirming it,
        the phone is still on its idle cadence — judging it at the full rate
        in that gap would show a healthy device as offline for one poll.
        """
        now = timezone.now()
        policy = SensorScanPolicy.objects.create(
            sensor=self.sensor,
            heartbeat_interval_seconds=10,
            remote_scan_enabled=True,
            reported_is_continuous=False,
            last_heartbeat_at=now,
        )
        self.assertEqual(policy.expected_heartbeat_interval_seconds, 40)
        self.assertTrue(self._at(policy, 50, now))


class ScanPolicyCsrfTests(TestCase):
    """force_authenticate bypasses CSRF middleware entirely — these exercise
    the real login+cookie+header flow for the new write endpoints. See
    MEMORY.md."""

    def setUp(self):
        get_user_model().objects.create_user(username="operator", password="test-pass-123")
        self.sensor = Sensor.objects.create(name="Phone A")
        self.client = Client(enforce_csrf_checks=True)
        self.client.login(username="operator", password="test-pass-123")
        self.client.get("/api/v1/auth/session/")
        self.url = f"/api/v1/sensors/{self.sensor.id}/scan-policy/"

    def test_policy_write_without_csrf_token_is_rejected(self):
        response = self.client.post(
            self.url, data='{"remote_scan_enabled": true}', content_type="application/json"
        )
        self.assertEqual(response.status_code, 403)

    def test_policy_write_with_csrf_token_succeeds(self):
        response = self.client.post(
            self.url,
            data='{"remote_scan_enabled": true}',
            content_type="application/json",
            HTTP_X_CSRFTOKEN=self.client.cookies["csrftoken"].value,
        )
        self.assertEqual(response.status_code, 200, response.content)

    def test_scan_now_without_csrf_token_is_rejected(self):
        response = self.client.post(f"/api/v1/sensors/{self.sensor.id}/scan-now/")
        self.assertEqual(response.status_code, 403)


class AuthEndpointTests(TestCase):
    def setUp(self):
        self.user = get_user_model().objects.create_user(username="operator", password="test-pass-123")

    def test_session_reports_unauthenticated_by_default(self):
        response = APIClient().get("/api/v1/auth/session/")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"authenticated": False})

    def test_login_then_session_reports_authenticated(self):
        client = APIClient()
        login_response = client.post(
            "/api/v1/auth/login/", {"username": "operator", "password": "test-pass-123"}, format="json"
        )
        self.assertEqual(login_response.status_code, 200)

        session_response = client.get("/api/v1/auth/session/")
        self.assertEqual(session_response.json(), {"authenticated": True, "username": "operator"})

    def test_login_with_bad_credentials_rejected(self):
        response = APIClient().post(
            "/api/v1/auth/login/", {"username": "operator", "password": "wrong"}, format="json"
        )
        self.assertEqual(response.status_code, 401)

    def test_logout_clears_session(self):
        client = APIClient()
        client.post("/api/v1/auth/login/", {"username": "operator", "password": "test-pass-123"}, format="json")
        client.post("/api/v1/auth/logout/")
        session_response = client.get("/api/v1/auth/session/")
        self.assertEqual(session_response.json(), {"authenticated": False})


class AdaptiveScanPolicyTests(TestCase):
    """Per-motion-state cadence, configured from the web UI and obeyed by the
    device. Lives on the policy rather than only on the phone so both ends
    edit the same values — see SensorScanPolicy.adaptive_scan_enabled."""

    def setUp(self):
        self.user = get_user_model().objects.create_user(username="operator", password="test-pass-123")
        self.sensor = Sensor.objects.create(name="Phone A")
        self.client = APIClient()
        self.client.force_authenticate(user=self.user)
        self.url = f"/api/v1/sensors/{self.sensor.id}/scan-policy/"

    def test_defaults_are_the_documented_ones(self):
        body = self.client.get("/api/v1/sensors/").json()["results"][0]["scan_policy"]
        self.assertTrue(body["adaptive_scan_enabled"])
        self.assertEqual(body["stationary_interval_seconds"], 600)
        self.assertEqual(body["walking_interval_seconds"], 60)
        self.assertEqual(body["driving_interval_seconds"], 30)

    def test_operator_can_set_each_interval(self):
        response = self.client.post(
            self.url,
            {
                "adaptive_scan_enabled": True,
                "stationary_interval_seconds": 900,
                "walking_interval_seconds": 45,
                "driving_interval_seconds": 30,
            },
            format="json",
        )
        self.assertEqual(response.status_code, 200, response.content)

        policy = SensorScanPolicy.objects.get(sensor=self.sensor)
        self.assertEqual(policy.stationary_interval_seconds, 900)
        self.assertEqual(policy.walking_interval_seconds, 45)
        self.assertEqual(policy.driving_interval_seconds, 30)

    def test_every_interval_respects_the_wifi_floor(self):
        # Not just scan_interval_seconds — each motion state is a real cadence.
        for field in ("stationary_interval_seconds", "walking_interval_seconds", "driving_interval_seconds"):
            with self.subTest(field=field):
                response = self.client.post(self.url, {field: 20}, format="json")
                self.assertEqual(response.status_code, 400)
                self.assertIn(field, response.json())

    def test_short_state_intervals_allowed_without_wifi(self):
        response = self.client.post(
            self.url, {"driving_interval_seconds": 20, "include_wifi": False}, format="json"
        )
        self.assertEqual(response.status_code, 200, response.content)

    def test_reenabling_wifi_against_a_stored_short_state_interval_is_rejected(self):
        # Same resulting-state rule the single-interval check already had: an
        # unused-but-invalid cadence must not become live by toggling WiFi.
        self.client.post(self.url, {"driving_interval_seconds": 20, "include_wifi": False}, format="json")
        response = self.client.post(self.url, {"include_wifi": True}, format="json")
        self.assertEqual(response.status_code, 400)
        self.assertIn("driving_interval_seconds", response.json())

    def test_device_receives_adaptive_settings_in_its_heartbeat_response(self):
        self.client.post(
            self.url,
            {"adaptive_scan_enabled": True, "stationary_interval_seconds": 1200},
            format="json",
        )
        device = APIClient()
        device.credentials(HTTP_AUTHORIZATION=f"Token {self.sensor.token}")

        body = device.post("/api/v1/sensors/me/heartbeat/", {"reported_is_scanning": False}, format="json").json()

        self.assertTrue(body["adaptive_scan_enabled"])
        self.assertEqual(body["stationary_interval_seconds"], 1200)

    def test_device_reports_its_motion_state_and_effective_interval(self):
        device = APIClient()
        device.credentials(HTTP_AUTHORIZATION=f"Token {self.sensor.token}")

        device.post(
            "/api/v1/sensors/me/heartbeat/",
            {
                "reported_is_continuous": True,
                "reported_motion_state": "STATIONARY",
                "reported_effective_interval_seconds": 600,
            },
            format="json",
        )

        policy = SensorScanPolicy.objects.get(sensor=self.sensor)
        self.assertEqual(policy.reported_motion_state, "STATIONARY")
        self.assertEqual(policy.reported_effective_interval_seconds, 600)

    def test_device_cannot_write_adaptive_desired_state(self):
        # Same wall as every other desired field: a phone must not be able to
        # widen its own cadence and have the operator's UI agree with it.
        device = APIClient()
        device.credentials(HTTP_AUTHORIZATION=f"Token {self.sensor.token}")

        device.post(
            "/api/v1/sensors/me/heartbeat/",
            {"stationary_interval_seconds": 99, "adaptive_scan_enabled": False},
            format="json",
        )

        policy = SensorScanPolicy.objects.get(sensor=self.sensor)
        self.assertEqual(policy.stationary_interval_seconds, 600)
        self.assertTrue(policy.adaptive_scan_enabled)
