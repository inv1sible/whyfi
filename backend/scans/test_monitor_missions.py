import tempfile
import uuid

from django.contrib.auth import get_user_model
from django.core.files.uploadedfile import SimpleUploadedFile
from django.test import TestCase, override_settings
from rest_framework.test import APIClient

from sensors.models import Sensor

from .models import MonitorMission


class MonitorMissionTests(TestCase):
    def setUp(self):
        self.media_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.media_dir.cleanup)
        override = override_settings(MEDIA_ROOT=self.media_dir.name)
        override.enable()
        self.addCleanup(override.disable)
        self.sensor = Sensor.objects.create(name="Field phone")
        self.phone = APIClient()
        self.phone.credentials(HTTP_AUTHORIZATION=f"Token {self.sensor.token}")
        self.operator = APIClient()
        self.operator.force_authenticate(user=get_user_model().objects.create_user("operator", password="pass"))
        self.mission_id = uuid.uuid4()

    def upload(self, client=None, mission_id=None, pdf=b"%PDF-1.4\nreport", pcap=True):
        data = {
            "id": str(mission_id or self.mission_id),
            "report": SimpleUploadedFile("report.pdf", pdf, content_type="application/pdf"),
            "log": SimpleUploadedFile("mission.jsonl", b'{"event":"monitor_finished"}\n', content_type="application/x-ndjson"),
        }
        if pcap:
            data["pcap"] = SimpleUploadedFile("capture.pcap", bytes.fromhex("d4c3b2a1") + b"\0" * 20,
                                               content_type="application/vnd.tcpdump.pcap")
        return (client or self.phone).post("/api/v1/monitor-missions/upload/", data, format="multipart")

    def test_upload_preserves_all_files_and_is_idempotent(self):
        response = self.upload()
        self.assertEqual(response.status_code, 201)
        mission = MonitorMission.objects.get(id=self.mission_id)
        self.assertEqual(mission.sensor, self.sensor)
        self.assertEqual(mission.report.read(4), b"%PDF")
        self.assertEqual(mission.log.read(), b'{"event":"monitor_finished"}\n')
        self.assertEqual(mission.pcap.read(4), bytes.fromhex("d4c3b2a1"))
        self.assertEqual(self.upload().status_code, 200)
        self.assertEqual(MonitorMission.objects.count(), 1)

    def test_upload_requires_sensor_token(self):
        self.assertIn(self.upload(APIClient()).status_code, (401, 403))
        self.assertIn(self.upload(self.operator).status_code, (401, 403))
        self.assertEqual(MonitorMission.objects.count(), 0)

    def test_rejects_invalid_pdf_and_oversized_pcap(self):
        self.assertEqual(self.upload(pdf=b"bad").status_code, 400)
        self.assertEqual(MonitorMission.objects.count(), 0)
        # No 200 MB allocation: lower the limit for this validation path.
        from unittest.mock import patch
        with patch("scans.monitor_missions.MAX_PCAP_BYTES", 2):
            self.assertEqual(self.upload().status_code, 413)

    def test_list_is_authenticated_and_sensor_scoped(self):
        self.upload()
        other_sensor = Sensor.objects.create(name="Other")
        other_phone = APIClient()
        other_phone.credentials(HTTP_AUTHORIZATION=f"Token {other_sensor.token}")
        self.assertEqual(APIClient().get("/api/v1/monitor-missions/").status_code, 403)
        self.assertEqual(other_phone.get("/api/v1/monitor-missions/").json(), [])
        result = self.operator.get("/api/v1/monitor-missions/")
        self.assertEqual(result.status_code, 200)
        self.assertEqual(result.json()[0]["id"], str(self.mission_id))
        self.assertIn("report.pdf", result.json()[0]["report_url"])

    def test_another_sensor_cannot_reuse_mission_id(self):
        self.upload()
        other_sensor = Sensor.objects.create(name="Other")
        other_phone = APIClient()
        other_phone.credentials(HTTP_AUTHORIZATION=f"Token {other_sensor.token}")
        self.assertEqual(self.upload(other_phone).status_code, 409)
        self.assertEqual(MonitorMission.objects.count(), 1)

    def test_uploaded_files_require_operator_session(self):
        self.upload()
        path = MonitorMission.objects.get(id=self.mission_id).report.url
        self.assertEqual(self.client.get(path).status_code, 403)
        self.client.force_login(get_user_model().objects.get(username="operator"))
        self.assertEqual(self.client.get(path).status_code, 200)
