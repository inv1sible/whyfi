from django.contrib.auth import get_user_model
from django.test import Client, TestCase
from rest_framework.test import APIClient

from .models import Sensor


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
