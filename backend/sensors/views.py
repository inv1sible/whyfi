from django.utils import timezone
from rest_framework import mixins, status, viewsets
from rest_framework.authentication import SessionAuthentication
from rest_framework.decorators import action, api_view, authentication_classes, permission_classes
from rest_framework.permissions import IsAuthenticated
from rest_framework.response import Response

from .authentication import SensorTokenAuthentication
from .models import CrashReport, Sensor, SensorScanPolicy, generate_sensor_token
from .serializers import (
    CrashReportIngestSerializer,
    CrashReportSerializer,
    SensorHeartbeatSerializer,
    SensorScanPolicySerializer,
    SensorScanPolicyUpdateSerializer,
    SensorSerializer,
    SensorTokenRevealSerializer,
)


def _policy_for(sensor: Sensor) -> SensorScanPolicy:
    """The single place a policy row comes into existence.

    Created lazily rather than by a signal or a backfill migration — there
    are no signals anywhere in this backend and this isn't the place to
    introduce the pattern. Funnelling both writers (heartbeat and the web
    UI) through one get_or_create also confines the create race to one spot.
    """
    policy, _ = SensorScanPolicy.objects.get_or_create(sensor=sensor)
    return policy


class SensorViewSet(
    mixins.ListModelMixin,
    mixins.RetrieveModelMixin,
    mixins.CreateModelMixin,
    mixins.DestroyModelMixin,
    viewsets.GenericViewSet,
):
    """Create/list/regenerate/deactivate/delete — session-authenticated (a
    human managing devices via Settings > Sensors), not a public
    self-registration endpoint. Uses the project-wide SessionAuthentication +
    IsAuthenticated default (see config/settings.py) — no override needed
    here.

    Note this viewset is deliberately kept 100% session-auth even though the
    remote-control feature has a device-facing counterpart: that one lives in
    sensor_heartbeat below as a standalone view. This viewset is what mints
    and reveals tokens, so mixing token auth into its authenticator
    resolution would put sensor creation one mistake away from any token
    holder.
    """

    queryset = Sensor.objects.select_related("scan_policy").order_by("name")

    def get_serializer_class(self):
        if self.action == "create":
            return SensorTokenRevealSerializer
        return SensorSerializer

    @action(detail=True, methods=["post"], url_path="regenerate-token")
    def regenerate_token(self, request, pk=None):
        sensor = self.get_object()
        sensor.token = generate_sensor_token()
        sensor.save(update_fields=["token"])
        return Response(SensorTokenRevealSerializer(sensor).data)

    @action(detail=True, methods=["post"], url_path="set-active")
    def set_active(self, request, pk=None):
        """Toggling this off is the "retire this phone" button: the token
        stops authenticating immediately (SensorTokenAuthentication already
        filters on is_active=True), but every scan session it ever uploaded
        stays put — unlike delete below, this is fully reversible.

        POST rather than PATCH for the same reason as scan_policy above: the
        PWA's api client only has get/post/del helpers.
        """
        sensor = self.get_object()
        is_active = request.data.get("is_active")
        if not isinstance(is_active, bool):
            return Response({"detail": "is_active must be a boolean."}, status=status.HTTP_400_BAD_REQUEST)
        sensor.is_active = is_active
        sensor.save(update_fields=["is_active"])
        return Response(SensorSerializer(sensor).data)

    def destroy(self, request, *args, **kwargs):
        """Overrides DestroyModelMixin's default rather than using
        perform_destroy: the 409 conflict response below needs a real
        integer scan_session_count field for the PWA to display ("this
        sensor has 4 scan sessions"), and routing an APIException through
        DRF's exception handler stringifies every value in its detail
        payload (ErrorDetail wraps everything, ints included) — a plain
        Response here just returns the number as a number.

        Deleting a sensor that has ever uploaded anything (scan sessions,
        crash reports) is ambiguous on its own — does "delete this device"
        mean its history too, or not? Bare DELETE stays guarded (409) so
        that's never assumed; the caller has to say which via `on_conflict`:
          - "delete_data": cascades — every scan session (and so every
            WiFi/cellular/BLE/satellite/LAN observation) and every crash
            report this sensor ever contributed is deleted along with it.
          - "keep_data": the sensor row is deleted, but neither its scan
            sessions nor its crash reports are touched. Both FKs are
            on_delete=SET_NULL (not CASCADE) specifically so this is
            enforced at the DB level, not just an application-layer
            promise — deleting the sensor here just detaches its history,
            which then shows up with no sensor name attached rather than
            disappearing.
        """
        instance = self.get_object()
        scan_count = instance.scan_sessions.count()
        crash_count = instance.crash_reports.count()
        if scan_count > 0 or crash_count > 0:
            on_conflict = request.data.get("on_conflict")
            if on_conflict == "delete_data":
                instance.scan_sessions.all().delete()
                instance.crash_reports.all().delete()
            elif on_conflict != "keep_data":
                return Response(
                    {
                        "detail": "This sensor has scan sessions or crash reports.",
                        "scan_session_count": scan_count,
                        "crash_report_count": crash_count,
                    },
                    status=status.HTTP_409_CONFLICT,
                )
        instance.delete()
        return Response(status=status.HTTP_204_NO_CONTENT)

    @action(detail=True, methods=["post"], url_path="scan-policy")
    def scan_policy(self, request, pk=None):
        """Set desired scanning state for one device.

        POST rather than PATCH because the PWA's api client only has
        get/post/del helpers, and post() already handles the CSRF header —
        adding a patch() helper for this one call isn't worth it.
        """
        sensor = self.get_object()
        policy = _policy_for(sensor)
        serializer = SensorScanPolicyUpdateSerializer(policy, data=request.data, partial=True)
        serializer.is_valid(raise_exception=True)
        serializer.save()
        # Bumped on every desired-state write so the device can echo it back
        # and the UI can tell "not picked up yet" from "picked up and applied".
        policy.policy_revision += 1
        policy.save(update_fields=["policy_revision", "updated_at"])
        policy.refresh_from_db()
        return Response(SensorScanPolicySerializer(policy).data)

    @action(detail=True, methods=["post"], url_path="scan-now")
    def scan_now(self, request, pk=None):
        """Ask for exactly one scan pass, without changing the running mode."""
        sensor = self.get_object()
        policy = _policy_for(sensor)
        policy.scan_now_nonce += 1
        policy.save(update_fields=["scan_now_nonce", "updated_at"])
        return Response(SensorScanPolicySerializer(policy).data)

    @action(detail=True, methods=["post"], url_path="reset-counters")
    def reset_counters(self, request, pk=None):
        """Zero the device's session counters (completed passes).

        Deliberately not a `policy_revision` bump: this is a one-off action,
        not a change to what the device should be doing, so it uses the same
        nonce/echo shape as scan-now.
        """
        sensor = self.get_object()
        policy = _policy_for(sensor)
        policy.reset_counters_nonce += 1
        policy.save(update_fields=["reset_counters_nonce", "updated_at"])
        return Response(SensorScanPolicySerializer(policy).data)


class CrashReportViewSet(
    mixins.ListModelMixin,
    mixins.RetrieveModelMixin,
    mixins.CreateModelMixin,
    mixins.DestroyModelMixin,
    viewsets.GenericViewSet,
):
    """Create is machine-to-machine (the app's Settings > Diagnostics "Send
    to server" button, authenticated with the same per-device sensor token
    used for scan-session ingest); list/retrieve/destroy are human PWA
    actions using the admin session. Mirrors ScanSessionViewSet's auth split
    exactly, including the initialize_request/get_authenticators trick —
    self.action isn't set yet when get_authenticators() runs, but
    self.action_map already has the HTTP-method-to-action mapping DRF
    resolved from the URL, which is what that trick relies on."""

    serializer_class = CrashReportSerializer

    def get_queryset(self):
        # apply_search lives in scans.views — imported here rather than at
        # module level to avoid sensors/scans forming an import cycle at
        # load time (scans.views already imports sensors.authentication).
        from scans.views import apply_search

        qs = CrashReport.objects.select_related("sensor").all()
        return apply_search(
            qs,
            self.request,
            {
                "device_model": "device_model",
                "os_version": "os_version",
                "app_version": "app_version",
                "sensor_name": "sensor__name",
            },
        )

    def initialize_request(self, request, *args, **kwargs):
        self._resolved_action = self.action_map.get(request.method.lower())
        return super().initialize_request(request, *args, **kwargs)

    def get_authenticators(self):
        if getattr(self, "_resolved_action", None) == "create":
            return [SensorTokenAuthentication()]
        return [SessionAuthentication()]

    def get_permissions(self):
        return [IsAuthenticated()]

    def get_serializer_class(self):
        if self.action == "create":
            return CrashReportIngestSerializer
        return CrashReportSerializer

    def create(self, request, *args, **kwargs):
        serializer = CrashReportIngestSerializer(data=request.data, context={"sensor": request.user})
        serializer.is_valid(raise_exception=True)
        report = serializer.save()
        return Response(CrashReportSerializer(report).data, status=status.HTTP_201_CREATED)


@api_view(["POST"])
@authentication_classes([SensorTokenAuthentication])
@permission_classes([IsAuthenticated])
def sensor_heartbeat(request):
    """The device half of remote scanning control.

    One round trip: the body is what the device is actually doing, the
    response is what it should be doing. Combining them avoids a
    read-modify-write race and halves the request count on a poll loop.

    A standalone view rather than an action on SensorViewSet — see the note
    there. request.user is a Sensor (SensorTokenAuthentication), which also
    means there's no CSRF to plumb and no way for one device to address
    another: the token *is* the identity.
    """
    sensor = request.user
    policy = _policy_for(sensor)

    serializer = SensorHeartbeatSerializer(policy, data=request.data, partial=True)
    serializer.is_valid(raise_exception=True)
    serializer.save()

    # Server clock, never the device's — keeps clock skew out of the
    # online/offline determination entirely.
    policy.last_heartbeat_at = timezone.now()
    policy.save(update_fields=["last_heartbeat_at"])

    return Response(SensorScanPolicySerializer(policy).data)
