"""Manual transfer of completed USB monitor missions."""
import uuid

from rest_framework import status
from rest_framework.authentication import SessionAuthentication
from rest_framework.decorators import api_view, authentication_classes, parser_classes, permission_classes
from rest_framework.parsers import MultiPartParser
from rest_framework.permissions import IsAuthenticated
from rest_framework.response import Response

from sensors.authentication import SensorTokenAuthentication
from sensors.models import Sensor

from .models import MonitorMission
from .monitor_import import import_safely

# A long mission writes a log of tens of MiB (13.5 MiB seen on 2026-11-11,
# which the earlier 10 MiB limit refused with 413).
MAX_LOG_BYTES = 200 * 1024 * 1024
MAX_REPORT_BYTES = 25 * 1024 * 1024
MAX_PCAP_BYTES = 200 * 1024 * 1024


def mission_data(mission):
    return {
        "id": str(mission.id),
        "sensor": mission.sensor.name if mission.sensor else "Deleted sensor",
        "uploaded_at": mission.uploaded_at,
        "report_url": mission.report.url if mission.report else None,
        "log_url": mission.log.url,
        "pcap_url": mission.pcap.url if mission.pcap else None,
        "report_bytes": mission.report.size if mission.report else 0,
        "log_bytes": mission.log.size,
        "pcap_bytes": mission.pcap.size if mission.pcap else 0,
        # USB AP readings stored as WiFi observations ("<phone>_USB").
        "imported_at": mission.imported_at,
        "import_summary": mission.import_summary,
    }


@api_view(["GET"])
@authentication_classes([SessionAuthentication, SensorTokenAuthentication])
@permission_classes([IsAuthenticated])
def monitor_missions(request):
    missions = MonitorMission.objects.select_related("sensor")
    if isinstance(request.user, Sensor):
        missions = missions.filter(sensor=request.user)
    return Response([mission_data(mission) for mission in missions])


@api_view(["POST"])
@authentication_classes([SensorTokenAuthentication])
@permission_classes([IsAuthenticated])
@parser_classes([MultiPartParser])
def upload_monitor_mission(request):
    if not isinstance(request.user, Sensor):
        return Response({"detail": "A sensor token is required."}, status=status.HTTP_403_FORBIDDEN)
    try:
        mission_id = uuid.UUID(str(request.data.get("id", "")))
    except ValueError:
        return Response({"detail": "A valid mission id is required."}, status=status.HTTP_400_BAD_REQUEST)
    existing = MonitorMission.objects.filter(id=mission_id).first()
    if existing and existing.sensor_id != request.user.id:
        return Response({"detail": "Mission id already belongs to another sensor."}, status=status.HTTP_409_CONFLICT)

    # The log is what the server needs (scans/monitor_import.py); the PDF and
    # PCAP are optional, so the app can upload after every Stop (2026-11-11).
    report = request.FILES.get("report")
    log = request.FILES.get("log")
    pcap = request.FILES.get("pcap")
    if not log:
        return Response({"detail": "The mission log is required."}, status=status.HTTP_400_BAD_REQUEST)
    for name, upload, limit in (("log", log, MAX_LOG_BYTES), ("report", report, MAX_REPORT_BYTES), ("PCAP", pcap, MAX_PCAP_BYTES)):
        if upload and upload.size > limit:
            return Response({"detail": f"The mission {name} is larger than the server accepts ({limit // (1024 * 1024)} MiB)."},
                            status=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE)
    if report:
        if report.read(4) != b"%PDF":
            return Response({"detail": "Invalid PDF report."}, status=status.HTTP_400_BAD_REQUEST)
        report.seek(0)
    if pcap:
        if pcap.read(4) not in (bytes.fromhex("d4c3b2a1"), bytes.fromhex("a1b2c3d4")):
            return Response({"detail": "Invalid PCAP capture."}, status=status.HTTP_400_BAD_REQUEST)
        pcap.seek(0)

    # A new upload of the same mission (continued, or re-sent) replaces the
    # files it carries; files it leaves out are kept.
    mission = existing or MonitorMission(id=mission_id, sensor=request.user)
    replaced, written = [], []
    try:
        for field_name, upload, filename in (("report", report, "report.pdf"), ("log", log, "mission.jsonl"),
                                             ("pcap", pcap, "capture.pcap")):
            if not upload:
                continue
            field = getattr(mission, field_name)
            if field.name:
                replaced.append(field.name)
            field.save(filename, upload, save=False)
            written.append(field)
        mission.save()
    except Exception:
        # Storage writes happen before the DB insert. Keep a failed transfer
        # retryable without leaving partially written files behind.
        for field in written:
            if field.name:
                field.delete(save=False)
        raise
    storage = mission.log.storage
    current = {mission.report.name, mission.log.name, mission.pcap.name}
    for name in replaced:
        if name not in current:
            storage.delete(name)
    import_safely(mission, force=existing is not None)
    mission.refresh_from_db()
    return Response(mission_data(mission), status=status.HTTP_200_OK if existing else status.HTTP_201_CREATED)
