from rest_framework.authentication import SessionAuthentication
from rest_framework.decorators import api_view, authentication_classes, permission_classes
from rest_framework.permissions import IsAuthenticated
from rest_framework.response import Response

from sensors.authentication import SensorTokenAuthentication

from .models import AppRelease
from .serializers import AppReleaseSerializer
from .services import sync_build_status, trigger_build


@api_view(["GET"])
@authentication_classes([SessionAuthentication, SensorTokenAuthentication])
@permission_classes([IsAuthenticated])
def latest_release(request):
    # Accepts either a logged-in browser session (Download page) or a
    # sensor token (installed app checking for updates) — see MEMORY.md.
    # Only a completed, successful build with an attached APK counts.
    release = (
        AppRelease.objects.filter(build_status=AppRelease.BuildStatus.SUCCESS)
        .exclude(apk_file="")
        .order_by("-version_code")
        .first()
    )
    if not release:
        return Response({"detail": "No releases published yet."}, status=404)
    return Response(AppReleaseSerializer(release, context={"request": request}).data)


@api_view(["POST"])
@permission_classes([IsAuthenticated])
def trigger_build_view(request):
    # Session-only (human clicking the button in the PWA) — this is
    # deliberately not something the Android app itself can invoke.
    try:
        release = trigger_build(
            version_name=request.data.get("version_name", ""), notes=request.data.get("notes", "")
        )
    except ValueError as exc:
        return Response({"detail": str(exc)}, status=409)
    return Response(AppReleaseSerializer(release, context={"request": request}).data, status=202)


@api_view(["GET"])
@permission_classes([IsAuthenticated])
def build_status_view(request):
    release = AppRelease.objects.order_by("-created_at").first()
    if not release:
        return Response({"build_status": "NONE"})
    release = sync_build_status(release)
    return Response(AppReleaseSerializer(release, context={"request": request}).data)
