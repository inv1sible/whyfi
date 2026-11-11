import json
import tempfile
import uuid

from django.core.files.uploadedfile import SimpleUploadedFile
from django.test import TestCase, override_settings
from rest_framework.test import APIClient

from sensors.models import Sensor

from .models import MonitorMission, ScanSession, SecurityType, WiFiObservation
from .monitor_import import ap_readings, frequency_for_channel, import_mission, parse_log, security_for

AP = "aa:bb:cc:00:00:01"
CLIENT = "02:11:22:33:44:55"
T0 = 1_762_844_400_000  # wall ms
BOOT0 = 5_000_000       # elapsedRealtime at T0


def device_line(mac, roles, rssi, boot_ms, channel, ssid="HomeNet", security="WPA2", akms="PSK", pairwise="CCMP-128"):
    f = [mac, str(roles), "10", "0", "0", "0", str(rssi), str(channel), ssid, "", "0", "", "", "", "", "0", "1000",
         str(rssi), str(boot_ms), str(channel), "", "", "", "", "", "", security, akms, pairwise, "CCMP-128", ""]
    return "\t".join(f)


def snapshot(offset_ms, lines):
    return {"event": "devices_snapshot", "at_ms": T0 + offset_ms, "clock_ms": BOOT0 + offset_ms, "tsv": "\n".join(lines)}


def fix(offset_ms, lat, lng, accuracy=8.0, provider="gps"):
    return {"event": "receiver_fix", "at_ms": T0 + offset_ms, "boot_ms": BOOT0 + offset_ms, "provider": provider,
            "lat": lat, "lng": lng, "accuracy_m": accuracy, "speed_mps": 0.0}


def mission_log(rows):
    return ("\n".join(json.dumps(r) for r in rows) + "\n").encode()


ROWS = [
    {"event": "monitor_started", "at_ms": T0},
    fix(0, 52.5, 13.4),
    snapshot(1_000, [device_line(AP, 1, -61, BOOT0 + 900, 6), device_line(CLIENT, 2, -50, BOOT0 + 950, 6)]),
    # Same reading again in the next snapshot: deduplicated.
    snapshot(6_000, [device_line(AP, 1, -61, BOOT0 + 900, 6)]),
    fix(60_000, 52.51, 13.41),
    snapshot(61_000, [device_line(AP, 1, -55, BOOT0 + 60_500, 36)]),
]


class MonitorImportParsingTests(TestCase):
    def test_rebuilds_ap_readings_paired_with_the_nearest_fix(self):
        snapshots, fixes = parse_log(mission_log(ROWS).decode().splitlines())
        readings = ap_readings(snapshots, fixes)
        self.assertEqual([(r["mac"], r["rssi"], r["channel"]) for r in readings], [(AP, -61, 6), (AP, -55, 36)])
        self.assertEqual(readings[0]["at_ms"], T0 + 900)
        self.assertEqual((readings[1]["fix"]["lat"], readings[1]["fix"]["lng"]), (52.51, 13.41))

    def test_coarse_fixes_and_logs_without_boot_clock_are_skipped(self):
        coarse = [fix(0, 52.5, 13.4, accuracy=900.0, provider="network"),
                  snapshot(1_000, [device_line(AP, 1, -61, BOOT0 + 900, 6)])]
        self.assertEqual(ap_readings(*parse_log(mission_log(coarse).decode().splitlines())), [])
        old = [fix(0, 52.5, 13.4), {**snapshot(1_000, [device_line(AP, 1, -61, BOOT0 + 900, 6)]), "clock_ms": 0}]
        self.assertEqual(ap_readings(*parse_log(mission_log(old).decode().splitlines())), [])

    def test_channels_and_security(self):
        self.assertEqual((frequency_for_channel(6), frequency_for_channel(36), frequency_for_channel(14)), (2437, 5180, 2484))
        self.assertIsNone(frequency_for_channel(0))
        self.assertEqual(security_for("WPA2", "PSK", "CCMP-128")[0], SecurityType.WPA2)
        self.assertEqual(security_for("WPA2", "PSK,SAE", "CCMP-128")[0], SecurityType.WPA2_WPA3)
        self.assertEqual(security_for("Open", "", "")[0], SecurityType.OPEN)
        self.assertEqual(security_for("", "", "")[0], SecurityType.UNKNOWN)


class MonitorImportTests(TestCase):
    def setUp(self):
        self.media_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.media_dir.cleanup)
        override = override_settings(MEDIA_ROOT=self.media_dir.name)
        override.enable()
        self.addCleanup(override.disable)
        self.phone_sensor = Sensor.objects.create(name="Pixel 8")
        self.phone = APIClient()
        self.phone.credentials(HTTP_AUTHORIZATION=f"Token {self.phone_sensor.token}")

    def upload(self, log_bytes, mission_id=None):
        return self.phone.post("/api/v1/monitor-missions/upload/", {
            "id": str(mission_id or uuid.uuid4()),
            "report": SimpleUploadedFile("report.pdf", b"%PDF-1.4\nreport", content_type="application/pdf"),
            "log": SimpleUploadedFile("mission.jsonl", log_bytes, content_type="application/x-ndjson"),
        }, format="multipart")

    def test_upload_imports_ap_readings_under_the_usb_source(self):
        response = self.upload(mission_log(ROWS))
        self.assertEqual(response.status_code, 201)
        summary = response.json()["import_summary"]
        self.assertEqual((summary["status"], summary["source"], summary["sessions"], summary["observations"]),
                         ("imported", "Pixel 8_USB", 2, 2))
        usb = Sensor.objects.get(sensor_type=Sensor.SensorType.USB_ADAPTER)
        self.assertEqual((usb.name, usb.parent_id, usb.is_active), ("Pixel 8_USB", self.phone_sensor.id, False))
        sessions = ScanSession.objects.filter(sensor=usb).order_by("started_at")
        self.assertEqual([(s.latitude, s.longitude) for s in sessions], [(52.5, 13.4), (52.51, 13.41)])
        obs = WiFiObservation.objects.filter(scan_session__sensor=usb).order_by("observed_at")
        self.assertEqual([(o.access_point_id, o.rssi, o.frequency_mhz, o.band) for o in obs],
                         [(AP, -61, 2437, "2.4GHz"), (AP, -55, 5180, "5GHz")])
        self.assertEqual(obs[0].access_point.ssid, "HomeNet")
        self.assertFalse(WiFiObservation.objects.filter(access_point_id=CLIENT).exists())

    def test_reimport_replaces_and_reuses_the_source(self):
        mission_id = uuid.uuid4()
        self.upload(mission_log(ROWS), mission_id)
        mission = MonitorMission.objects.get(pk=mission_id)
        import_mission(mission, force=True)
        self.assertEqual(WiFiObservation.objects.count(), 2)
        self.assertEqual(Sensor.objects.filter(sensor_type=Sensor.SensorType.USB_ADAPTER).count(), 1)
        # A second mission of the same phone lands under the same source.
        self.upload(mission_log(ROWS))
        self.assertEqual(Sensor.objects.filter(sensor_type=Sensor.SensorType.USB_ADAPTER).count(), 1)
        self.assertEqual(WiFiObservation.objects.count(), 4)

    def test_phone_token_still_sees_only_its_own_sessions(self):
        self.upload(mission_log(ROWS))
        response = self.phone.get("/api/v1/scan-sessions/")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["count"], 0)

    def test_usb_source_cannot_authenticate(self):
        self.upload(mission_log(ROWS))
        usb = Sensor.objects.get(sensor_type=Sensor.SensorType.USB_ADAPTER)
        client = APIClient()
        client.credentials(HTTP_AUTHORIZATION=f"Token {usb.token}")
        self.assertIn(client.get("/api/v1/scan-sessions/").status_code, (401, 403))

    def test_unreadable_log_still_stores_the_upload(self):
        response = self.upload(b"not json\n{\"event\": \"devices_snapshot\"}\n")
        self.assertEqual(response.status_code, 201)
        self.assertEqual(response.json()["import_summary"]["sessions"], 0)
