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

MAX_PCAP_BYTES = 200 * 1024 * 1024
MAX_SMALL_FILE_BYTES = 10 * 1024 * 1024


def mission_data(mission):
    return {
        "id": str(mission.id),
        "sensor": mission.sensor.name if mission.sensor else "Deleted sensor",
        "uploaded_at": mission.uploaded_at,
        "report_url": mission.report.url,
        "log_url": mission.log.url,
        "pcap_url": mission.pcap.url if mission.pcap else None,
        "report_bytes": mission.report.size,
        "log_bytes": mission.log.size,
        "pcap_bytes": mission.pcap.size if mission.pcap else 0,
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
    if existing:
        if existing.sensor_id != request.user.id:
            return Response({"detail": "Mission id already belongs to another sensor."}, status=status.HTTP_409_CONFLICT)
        return Response(mission_data(existing), status=status.HTTP_200_OK)

    report = request.FILES.get("report")
    log = request.FILES.get("log")
    pcap = request.FILES.get("pcap")
    if not report or not log:
        return Response({"detail": "Completed report and mission log are required."}, status=status.HTTP_400_BAD_REQUEST)
    if report.size > MAX_SMALL_FILE_BYTES or log.size > MAX_SMALL_FILE_BYTES or (pcap and pcap.size > MAX_PCAP_BYTES):
        return Response({"detail": "Mission file exceeds the size limit."}, status=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE)
    if report.read(4) != b"%PDF":
        return Response({"detail": "Invalid PDF report."}, status=status.HTTP_400_BAD_REQUEST)
    report.seek(0)
    if pcap:
        if pcap.read(4) not in (bytes.fromhex("d4c3b2a1"), bytes.fromhex("a1b2c3d4")):
            return Response({"detail": "Invalid PCAP capture."}, status=status.HTTP_400_BAD_REQUEST)
        pcap.seek(0)

    mission = MonitorMission(id=mission_id, sensor=request.user)
    try:
        mission.report.save("report.pdf", report, save=False)
        mission.log.save("mission.jsonl", log, save=False)
        if pcap:
            mission.pcap.save("capture.pcap", pcap, save=False)
        mission.save()
    except Exception:
        # Storage writes happen before the DB insert. Keep a failed transfer
        # retryable without leaving partially written files behind.
        for field in (mission.report, mission.log, mission.pcap):
            if field.name:
                field.delete(save=False)
        raise
    return Response(mission_data(mission), status=status.HTTP_201_CREATED)
