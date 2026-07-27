from rest_framework import serializers

from .models import Sensor, SensorScanPolicy

# Anything tighter than this trips Android's own WiFi scan throttle (4 scans
# per 2 minutes), so the device would just sit in a back-off loop instead of
# scanning faster. Without WiFi there's no OS-level limit and the model's
# MinValueValidator(15) is the only floor.
MIN_INTERVAL_SECONDS_WITH_WIFI = 30

POLICY_DESIRED_FIELDS = [
    "remote_scan_enabled",
    "scan_interval_seconds",
    "heartbeat_interval_seconds",
    "include_wifi",
    "include_cellular",
    "include_ble",
    "include_gnss",
]

POLICY_REPORTED_FIELDS = [
    "reported_is_continuous",
    "reported_is_scanning",
    "reported_phase",
    "reported_completed_scans",
    "reported_wifi_unavailable_reason",
    "reported_cellular_unavailable_reason",
    "reported_ble_unavailable_reason",
    "reported_permissions_granted",
    "reported_location_services_enabled",
    "reported_pending_uploads",
    "reported_outbox_bytes",
    "reported_outbox_quota_mb",
    "reported_battery_percent",
    "reported_app_version",
    "reported_policy_revision",
    "reported_scan_now_nonce",
    "reported_reset_counters_nonce",
]


class SensorScanPolicySerializer(serializers.ModelSerializer):
    """Read view of one device's remote-scanning state — both what it should
    be doing and what it says it's actually doing."""

    agent_online = serializers.BooleanField(read_only=True)
    policy_pending = serializers.BooleanField(read_only=True)

    class Meta:
        model = SensorScanPolicy
        fields = (
            POLICY_DESIRED_FIELDS
            + POLICY_REPORTED_FIELDS
            + [
                "scan_now_nonce",
                "reset_counters_nonce",
                "policy_revision",
                "updated_at",
                "last_heartbeat_at",
                "agent_online",
                "policy_pending",
            ]
        )


class SensorScanPolicyUpdateSerializer(serializers.ModelSerializer):
    """Write view for the web UI. Desired fields only — a browser must never
    be able to fake what a device reported."""

    class Meta:
        model = SensorScanPolicy
        fields = POLICY_DESIRED_FIELDS
        # Partial updates are the norm here (the UI toggles one thing at a
        # time), so every field is optional and the current value stands in.
        extra_kwargs = {name: {"required": False} for name in POLICY_DESIRED_FIELDS}

    def validate(self, attrs):
        # Cross-field, and it has to consider the *resulting* state rather
        # than just what was sent: toggling WiFi back on while an existing
        # 20s interval is stored must be rejected too.
        instance = self.instance
        include_wifi = attrs.get("include_wifi", instance.include_wifi if instance else True)
        interval = attrs.get(
            "scan_interval_seconds",
            instance.scan_interval_seconds if instance else 60,
        )
        if include_wifi and interval < MIN_INTERVAL_SECONDS_WITH_WIFI:
            raise serializers.ValidationError(
                {
                    "scan_interval_seconds": (
                        f"Must be at least {MIN_INTERVAL_SECONDS_WITH_WIFI} seconds while WiFi is "
                        "included — Android throttles WiFi scans to 4 per 2 minutes, so a shorter "
                        "interval does not produce more scans."
                    )
                }
            )
        return attrs


class SensorHeartbeatSerializer(serializers.ModelSerializer):
    """Write view for the device. Reported fields only, all optional — an
    older APK that doesn't know about a newer field just omits it, and must
    keep working rather than 400."""

    class Meta:
        model = SensorScanPolicy
        fields = POLICY_REPORTED_FIELDS
        extra_kwargs = {name: {"required": False} for name in POLICY_REPORTED_FIELDS}


class SensorSerializer(serializers.ModelSerializer):
    scan_policy = serializers.SerializerMethodField()

    class Meta:
        model = Sensor
        fields = [
            "id",
            "name",
            "sensor_type",
            "is_active",
            "created_at",
            "last_seen_at",
            "last_scan_upload_at",
            "scan_policy",
        ]
        # token is deliberately excluded here — it's only ever shown once,
        # in the create/regenerate-token response (SensorTokenRevealSerializer
        # below), never through list/retrieve. If you lose it, regenerate.

    def get_scan_policy(self, obj):
        """Returns the stored policy, or unsaved defaults for a device that
        has never been controlled or heard from.

        Deliberately does not create the row: a GET must never write. The row
        appears on the first heartbeat or the first policy write instead (see
        _policy_for in views.py), which also means no backfill migration.
        """
        policy = getattr(obj, "scan_policy", None)
        if policy is None:
            policy = SensorScanPolicy(sensor=obj)
        return SensorScanPolicySerializer(policy).data


class SensorTokenRevealSerializer(serializers.ModelSerializer):
    """Used only for the create and regenerate-token responses — the one
    moment the token is meant to be visible."""

    class Meta:
        model = Sensor
        fields = ["id", "name", "sensor_type", "is_active", "created_at", "last_seen_at", "token"]
        read_only_fields = ["id", "is_active", "created_at", "last_seen_at", "token"]
