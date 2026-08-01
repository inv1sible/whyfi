import datetime
import secrets
import uuid

from django.core.validators import MinValueValidator
from django.db import models
from django.utils import timezone


def generate_sensor_token() -> str:
    return secrets.token_hex(32)


# These two mirror RemoteControlAgent.IDLE_BACKOFF / IDLE_MAX_MS in the
# Android app: while armed but not scanning, the device polls at
# min(interval * 4, 60s) rather than at the configured interval. The server
# has to know that to judge whether a device has actually gone quiet — see
# SensorScanPolicy.expected_heartbeat_interval_seconds. Keep them in step.
AGENT_IDLE_BACKOFF_MULTIPLIER = 4
AGENT_IDLE_POLL_CEILING_SECONDS = 60

# Absorbs mobile-network jitter on top of one tolerated missed check-in.
AGENT_HEARTBEAT_GRACE_SECONDS = 15


class Sensor(models.Model):
    """A reporting device (in v1: an Android phone). Auth is a per-device
    token, managed via the PWA's Settings > Sensors tab (or Django admin) —
    there is no multi-user account system in v1 (see AGENT.md / MEMORY.md
    for why)."""

    class SensorType(models.TextChoices):
        ANDROID = "android", "Android"
        KALI_LINUX = "kali_linux", "Kali Linux (reserved for v-next)"
        OTHER = "other", "Other"

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    name = models.CharField(max_length=100)
    sensor_type = models.CharField(max_length=20, choices=SensorType.choices, default=SensorType.ANDROID)
    token = models.CharField(max_length=64, unique=True, editable=False, default=generate_sensor_token)
    is_active = models.BooleanField(default=True)
    created_at = models.DateTimeField(auto_now_add=True)
    # Any authenticated request at all — SensorTokenAuthentication bumps this
    # on every call, so once a device is running the remote-control agent it
    # reads "seconds ago" continuously. Use last_scan_upload_at when you want
    # "when did this device last actually contribute data".
    last_seen_at = models.DateTimeField(null=True, blank=True)
    last_scan_upload_at = models.DateTimeField(null=True, blank=True)

    def __str__(self):
        return f"{self.name} ({self.sensor_type})"

    @property
    def is_authenticated(self):
        # Lets DRF's IsAuthenticated permission treat a Sensor instance
        # (returned by SensorTokenAuthentication in place of a Django user)
        # as authenticated without pulling in a full user-account system.
        return True


class SensorScanPolicy(models.Model):
    """Remote scanning control for one device, as desired-state reconciliation
    rather than a command queue.

    The backend can't reach a phone — it sits behind carrier NAT, and Android
    forbids a server-initiated start of a location foreground service anyway
    (deliberately: that restriction exists to stop covert remote activation).
    So the direction is inverted: the phone polls
    ``POST /sensors/me/heartbeat/``, reports what it's actually doing, and
    receives what it *should* be doing. Nothing here is a command; it's all
    state the device converges on.

    That makes "stop then start again quickly" two writes where the last one
    wins, and makes an offline device catch up correctly whenever it returns,
    with no queue to drain and no stale command to replay.

    ``policy_revision`` / ``reported_policy_revision`` are the Kubernetes
    ``metadata.generation`` / ``status.observedGeneration`` trick: any desired
    change bumps the former, the device echoes it into the latter. Without
    that pair the UI can only say "sent"; with it, it can tell *pending*
    (device hasn't picked it up) apart from *applied but not scanning* —
    which is the state the reported_* fields below then explain.
    """

    sensor = models.OneToOneField(Sensor, on_delete=models.CASCADE, related_name="scan_policy")

    # --- Desired state (written by the web UI) ---
    remote_scan_enabled = models.BooleanField(default=False)
    # 30s is the floor whenever WiFi is included: Android allows 4 WiFi scans
    # per 2 minutes, so anything tighter just trips the OS throttle. Without
    # WiFi there's no such limit, hence the lower hard floor here and the
    # conditional check in SensorScanPolicyUpdateSerializer.
    scan_interval_seconds = models.PositiveIntegerField(default=60, validators=[MinValueValidator(15)])
    heartbeat_interval_seconds = models.PositiveIntegerField(default=10, validators=[MinValueValidator(5)])
    # --- Adaptive cadence ---
    # A phone sitting on a desk re-scans the same airwaves forever; a phone in
    # a car covers new ground every second. When enabled, the device picks its
    # interval from its own motion state and `scan_interval_seconds` is
    # ignored (the device reports which interval it actually used, so the UI
    # can say so rather than looking broken).
    #
    # These live on the policy rather than only on the phone so the web UI and
    # the app configure the same thing. While remote control is armed the
    # policy wins, exactly like every other field here.
    adaptive_scan_enabled = models.BooleanField(default=True)
    stationary_interval_seconds = models.PositiveIntegerField(default=600, validators=[MinValueValidator(15)])
    walking_interval_seconds = models.PositiveIntegerField(default=60, validators=[MinValueValidator(15)])
    # Fastest of the three: a vehicle covers the most unmapped ground per
    # minute. 30s is the practical floor when WiFi is included — Android
    # allows 4 WiFi scans per 2 minutes.
    driving_interval_seconds = models.PositiveIntegerField(default=30, validators=[MinValueValidator(15)])
    include_wifi = models.BooleanField(default=True)
    include_cellular = models.BooleanField(default=True)
    include_ble = models.BooleanField(default=True)
    include_gnss = models.BooleanField(default=True)
    # Incremented to ask for exactly one pass now. The device echoes it into
    # reported_scan_now_nonce once run, giving at-most-once semantics without
    # a command queue.
    scan_now_nonce = models.PositiveIntegerField(default=0)
    # Same nonce trick, for zeroing the device's session counters. Without
    # this they only reset when the scan service stops — which, once remote
    # control is armed, is never.
    reset_counters_nonce = models.PositiveIntegerField(default=0)
    policy_revision = models.PositiveIntegerField(default=0)
    updated_at = models.DateTimeField(auto_now=True)

    # --- Reported state (written by the device's heartbeat) ---
    # Every field nullable/blank with a default, per AGENT.md: an older APK
    # simply omits what it doesn't know about, and must keep working.
    last_heartbeat_at = models.DateTimeField(null=True, blank=True)
    reported_is_continuous = models.BooleanField(null=True, blank=True)
    reported_is_scanning = models.BooleanField(null=True, blank=True)
    reported_phase = models.CharField(max_length=32, blank=True)
    reported_completed_scans = models.PositiveIntegerField(null=True, blank=True)
    reported_wifi_unavailable_reason = models.CharField(max_length=200, blank=True)
    reported_cellular_unavailable_reason = models.CharField(max_length=200, blank=True)
    reported_ble_unavailable_reason = models.CharField(max_length=200, blank=True)
    reported_permissions_granted = models.BooleanField(null=True, blank=True)
    reported_location_services_enabled = models.BooleanField(null=True, blank=True)
    reported_pending_uploads = models.PositiveIntegerField(null=True, blank=True)
    reported_outbox_bytes = models.PositiveBigIntegerField(null=True, blank=True)
    reported_outbox_quota_mb = models.PositiveIntegerField(null=True, blank=True)
    reported_battery_percent = models.PositiveIntegerField(null=True, blank=True)
    reported_app_version = models.CharField(max_length=32, blank=True)
    reported_policy_revision = models.PositiveIntegerField(null=True, blank=True)
    reported_scan_now_nonce = models.PositiveIntegerField(null=True, blank=True)
    reported_reset_counters_nonce = models.PositiveIntegerField(null=True, blank=True)
    # What the device's motion detector currently believes, and the interval it
    # picked as a result. Without these a stationary phone scanning every ten
    # minutes is indistinguishable from a broken one — the UI needs to be able
    # to say "10 min because it's stationary", not just show a stale timestamp.
    # Blank when the device is too old to report it, or adaptive is off.
    reported_motion_state = models.CharField(max_length=16, blank=True)
    reported_effective_interval_seconds = models.PositiveIntegerField(null=True, blank=True)

    class Meta:
        verbose_name_plural = "sensor scan policies"

    def __str__(self):
        return f"scan policy for {self.sensor.name}"

    @property
    def expected_heartbeat_interval_seconds(self) -> int:
        """How often this device is actually expected to check in right now.

        The device does not poll at ``heartbeat_interval_seconds`` unless it's
        scanning: while armed-but-idle it backs off (there's nothing to be
        responsive to, and it's a phone on battery). That backoff lives in
        ``RemoteControlAgent.run()`` and the constants below mirror it — if
        you change one side, change the other, because staleness is judged
        against this number.

        Keyed on desired *and* reported state agreeing, not desired alone.
        Right after the operator enables scanning the device is still on its
        idle cadence for one more poll, so trusting desired state by itself
        would tighten the window before the device could possibly meet it.
        """
        scanning = bool(self.remote_scan_enabled and self.reported_is_continuous)
        if scanning:
            return self.heartbeat_interval_seconds
        return min(
            self.heartbeat_interval_seconds * AGENT_IDLE_BACKOFF_MULTIPLIER,
            AGENT_IDLE_POLL_CEILING_SECONDS,
        )

    @property
    def agent_online(self) -> bool:
        """Whether the device's agent is currently reachable.

        One fully missed check-in at the device's *actual* cadence, plus a
        grace margin for network jitter. Measured against the server's own
        clock; a device-supplied timestamp is never trusted, which keeps clock
        skew out of the picture entirely.

        This used to be ``heartbeat_interval_seconds * 3 + 15``, which sounds
        generous but is computed from the wrong number: an idle device polls
        at four times that interval. At the default 10s it left 5s of margin
        (a 40s poll against a 45s window), and at 15s the window and the
        device's capped 60s idle poll coincided exactly — so a perfectly
        healthy armed device flapped between online and offline forever. See
        expected_heartbeat_interval_seconds.
        """
        if self.last_heartbeat_at is None:
            return False
        stale_after = datetime.timedelta(
            seconds=self.expected_heartbeat_interval_seconds * 2 + AGENT_HEARTBEAT_GRACE_SECONDS
        )
        return timezone.now() - self.last_heartbeat_at < stale_after

    @property
    def policy_pending(self) -> bool:
        """True while the device hasn't yet confirmed the latest desired state.

        A device that has never reported (None) counts as being at revision 0,
        so a fresh device nobody has given an instruction to reads as settled
        rather than perpetually "pending".
        """
        return (self.reported_policy_revision or 0) != self.policy_revision
