from django.utils import timezone
from rest_framework import mixins, status, viewsets
from rest_framework.decorators import action, api_view, authentication_classes, permission_classes
from rest_framework.exceptions import APIException
from rest_framework.permissions import IsAuthenticated
from rest_framework.response import Response

from .authentication import SensorTokenAuthentication
from .models import Sensor, SensorScanPolicy, generate_sensor_token
from .serializers import (
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

    def perform_destroy(self, instance):
        # ScanSession.sensor is on_delete=CASCADE, so deleting a sensor that
        # has ever uploaded anything would silently take its entire scan
        # history with it — every WiFi/cellular/BLE/satellite/LAN
        # observation it ever contributed, gone with no confirmation of
        # *that*, just "delete this device". Deactivating already covers
        # "stop this phone from doing anything"; delete is reserved for
        # cleaning up a sensor that was created by mistake or never used —
        # clear its scans via Manage Scans first if you actually want both gone.
        if instance.scan_sessions.exists():
            error = APIException(
                "This sensor has scan sessions. Delete its scans from Manage Scans first, "
                "or deactivate it instead to keep the history but stop it from reporting."
            )
            error.status_code = status.HTTP_409_CONFLICT
            raise error
        instance.delete()

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
