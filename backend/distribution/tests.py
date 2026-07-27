import tempfile
from pathlib import Path
from unittest import mock

from django.contrib.auth import get_user_model
from django.core.files.uploadedfile import SimpleUploadedFile
from django.test import Client, TestCase, override_settings
from rest_framework.test import APIClient

from sensors.models import Sensor

from .download_tokens import sign_media_path
from .models import AppRelease
from .services import sync_build_status, trigger_build


def _fake_apk(name):
    return SimpleUploadedFile(name, b"fake-apk-bytes", content_type="application/vnd.android.package-archive")


@override_settings(MEDIA_ROOT="/tmp/whyfi-test-media")
class LatestReleaseTests(TestCase):
    """Accepts either a logged-in browser session or a sensor token — see
    MEMORY.md — but never an anonymous request."""

    def setUp(self):
        self.user = get_user_model().objects.create_user(username="operator", password="test-pass-123")
        self.session_client = APIClient()
        self.session_client.force_authenticate(user=self.user)

        self.sensor = Sensor.objects.create(name="Test Phone")
        self.sensor_client = APIClient()
        self.sensor_client.credentials(HTTP_AUTHORIZATION=f"Token {self.sensor.token}")

    def test_anonymous_request_rejected(self):
        # 403, not 401: SessionAuthentication is listed first among this
        # view's authenticators, and it sets no WWW-Authenticate header —
        # see MEMORY.md.
        response = APIClient().get("/api/v1/app/latest/")
        self.assertEqual(response.status_code, 403)

    def test_no_releases_yet(self):
        response = self.session_client.get("/api/v1/app/latest/")
        self.assertEqual(response.status_code, 404)

    def test_latest_release_returned_for_logged_in_browser(self):
        AppRelease.objects.create(
            version_code=1, version_name="0.1.0", release_notes="Initial", apk_file=_fake_apk("v1.apk")
        )
        AppRelease.objects.create(
            version_code=2, version_name="0.2.0", release_notes="Second", apk_file=_fake_apk("v2.apk")
        )

        response = self.session_client.get("/api/v1/app/latest/")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["version_code"], 2)

    def test_latest_release_returned_for_sensor_token(self):
        AppRelease.objects.create(version_code=1, version_name="0.1.0", apk_file=_fake_apk("v1.apk"))

        response = self.sensor_client.get("/api/v1/app/latest/")
        self.assertEqual(response.status_code, 200)

    def test_in_progress_build_is_not_returned_as_latest(self):
        # No apk_file yet, build_status defaults to SUCCESS on .create() —
        # explicitly mark it QUEUED to simulate a build that hasn't finished.
        AppRelease.objects.create(version_code=1, version_name="0.1.0", build_status=AppRelease.BuildStatus.QUEUED)
        response = self.session_client.get("/api/v1/app/latest/")
        self.assertEqual(response.status_code, 404)


@override_settings(MEDIA_ROOT=tempfile.mkdtemp())
class TriggerBuildTests(TestCase):
    """Covers the file-signal protocol between the backend and the
    android-builder watcher — see distribution/services.py's module
    docstring and MEMORY.md for why there's no Docker socket involved."""

    def setUp(self):
        self.signal_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.signal_dir.cleanup)
        patcher = mock.patch("distribution.services.BUILD_SIGNAL_DIR", Path(self.signal_dir.name))
        patcher.start()
        self.addCleanup(patcher.stop)

        self.user = get_user_model().objects.create_user(username="operator", password="test-pass-123")
        self.client = APIClient()
        self.client.force_authenticate(user=self.user)

    def test_trigger_creates_queued_release_and_signal_file(self):
        release = trigger_build()
        self.assertEqual(release.build_status, AppRelease.BuildStatus.QUEUED)
        self.assertEqual(release.version_code, 1)

        signal_file = Path(self.signal_dir.name) / "request.txt"
        self.assertTrue(signal_file.exists())
        self.assertEqual(signal_file.read_text(), str(release.id))

    def test_version_code_auto_increments(self):
        AppRelease.objects.create(version_code=5, version_name="old", build_status=AppRelease.BuildStatus.SUCCESS)
        release = trigger_build()
        self.assertEqual(release.version_code, 6)

    def test_trigger_rejects_concurrent_build(self):
        trigger_build()
        with self.assertRaises(ValueError):
            trigger_build()

    def test_trigger_view_returns_409_when_build_in_progress(self):
        first = self.client.post("/api/v1/android-build/trigger/")
        self.assertEqual(first.status_code, 202)
        second = self.client.post("/api/v1/android-build/trigger/")
        self.assertEqual(second.status_code, 409)

    def test_sync_build_status_reads_building_state_and_log(self):
        release = trigger_build()
        (Path(self.signal_dir.name) / f"{release.id}.state").write_text("BUILDING")
        (Path(self.signal_dir.name) / f"{release.id}.log").write_text("> Task :app:compileReleaseKotlin")

        updated = sync_build_status(release)
        self.assertEqual(updated.build_status, AppRelease.BuildStatus.BUILDING)
        self.assertIn("compileReleaseKotlin", updated.build_log_tail)

    def test_sync_build_status_success_attaches_apk(self):
        release = trigger_build()
        (Path(self.signal_dir.name) / f"{release.id}.state").write_text("SUCCESS")
        (Path(self.signal_dir.name) / f"{release.id}.apk").write_bytes(b"fake-apk-bytes")

        updated = sync_build_status(release)
        self.assertEqual(updated.build_status, AppRelease.BuildStatus.SUCCESS)
        self.assertTrue(updated.apk_file)
        self.assertIsNotNone(updated.build_finished_at)

    def test_sync_build_status_failed(self):
        release = trigger_build()
        (Path(self.signal_dir.name) / f"{release.id}.state").write_text("FAILED")

        updated = sync_build_status(release)
        self.assertEqual(updated.build_status, AppRelease.BuildStatus.FAILED)

    def test_status_view_reports_no_release(self):
        response = self.client.get("/api/v1/android-build/status/")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"build_status": "NONE"})

    def test_status_view_syncs_and_returns_current_release(self):
        self.client.post("/api/v1/android-build/trigger/")
        release = AppRelease.objects.get()
        (Path(self.signal_dir.name) / f"{release.id}.state").write_text("BUILDING")

        response = self.client.get("/api/v1/android-build/status/")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["build_status"], "BUILDING")


@override_settings(MEDIA_ROOT=tempfile.mkdtemp())
class MediaAccessTests(TestCase):
    """MEDIA (the built APKs) is behind the same login as every read endpoint.

    It was previously wired straight to django.views.static.serve with no auth
    at all, so the APK was fetchable by anyone who knew the URL — and the
    filenames are predictable (whyfi-<version_name>.apk, version_name
    defaulting to a timestamp). That quietly contradicted the project-wide
    "only /health/ and /auth/* are public" rule; see MEMORY.md.
    """

    def setUp(self):
        get_user_model().objects.create_user(username="operator", password="test-pass-123")
        release = AppRelease.objects.create(
            version_code=1, version_name="0.1.0", apk_file=_fake_apk("v1.apk")
        )
        self.apk_name = release.apk_file.name
        self.media_url = release.apk_file.url
        self.assertTrue(self.media_url.startswith("/media/"))

    def test_anonymous_download_is_rejected(self):
        response = Client().get(self.media_url)
        self.assertEqual(response.status_code, 403)

    def test_logged_in_download_succeeds(self):
        client = Client()
        client.login(username="operator", password="test-pass-123")
        response = client.get(self.media_url)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(b"".join(response.streaming_content), b"fake-apk-bytes")

    def test_traversal_outside_media_root_is_refused(self):
        # static_serve's own safe_join still applies — this is here so that
        # protection is asserted, not just assumed, now that the view is ours.
        client = Client()
        client.login(username="operator", password="test-pass-123")
        self.assertIn(client.get("/media/../settings.py").status_code, (400, 404))

    def test_signed_token_grants_access_without_a_session(self):
        # This is the QR-code flow: the phone being sideloaded scans a URL and
        # has no whyfi session at all.
        url = f"{self.media_url}?t={sign_media_path(self.apk_name)}"
        response = Client().get(url)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(b"".join(response.streaming_content), b"fake-apk-bytes")

    def test_download_url_from_the_api_works_anonymously(self):
        # End-to-end: whatever /app/latest/ hands the page must be a URL that
        # the phone can actually fetch.
        session_client = APIClient()
        session_client.force_authenticate(user=get_user_model().objects.get(username="operator"))
        download_url = session_client.get("/api/v1/app/latest/").json()["download_url"]
        self.assertIn("?t=", download_url)

        path = download_url.split("testserver", 1)[1]
        self.assertEqual(Client().get(path).status_code, 200)

    def test_tampered_and_foreign_tokens_are_refused(self):
        token = sign_media_path(self.apk_name)
        self.assertEqual(Client().get(f"{self.media_url}?t={token}x").status_code, 403)
        # Path-scoped: a token for one file must not fetch another.
        self.assertEqual(
            Client().get(f"{self.media_url}?t={sign_media_path('some/other.apk')}").status_code, 403
        )

    def test_expired_token_is_refused(self):
        token = sign_media_path(self.apk_name)
        with mock.patch("distribution.download_tokens.MEDIA_TOKEN_MAX_AGE_SECONDS", -1):
            self.assertEqual(Client().get(f"{self.media_url}?t={token}").status_code, 403)


@override_settings(MEDIA_ROOT=tempfile.mkdtemp())
class CsrfProtectionTests(TestCase):
    """`force_authenticate` (used above) bypasses CSRF middleware entirely,
    which is exactly how the first version of this endpoint shipped with a
    CSRF bug that only showed up against a real browser/curl session — see
    MEMORY.md. This exercises the actual login + cookie + header flow the
    SPA relies on, with CSRF enforcement genuinely turned on."""

    def setUp(self):
        self.signal_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.signal_dir.cleanup)
        patcher = mock.patch("distribution.services.BUILD_SIGNAL_DIR", Path(self.signal_dir.name))
        patcher.start()
        self.addCleanup(patcher.stop)

        get_user_model().objects.create_user(username="operator", password="test-pass-123")
        self.client = Client(enforce_csrf_checks=True)
        self.client.login(username="operator", password="test-pass-123")
        # Mirrors what the SPA does on every load, before anything else.
        self.client.get("/api/v1/auth/session/")

    def test_trigger_without_csrf_token_is_rejected(self):
        response = self.client.post(
            "/api/v1/android-build/trigger/", data="{}", content_type="application/json"
        )
        self.assertEqual(response.status_code, 403)

    def test_trigger_with_csrf_token_succeeds(self):
        csrf_token = self.client.cookies["csrftoken"].value
        response = self.client.post(
            "/api/v1/android-build/trigger/",
            data="{}",
            content_type="application/json",
            HTTP_X_CSRFTOKEN=csrf_token,
        )
        self.assertEqual(response.status_code, 202)
