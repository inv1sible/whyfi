from rest_framework import serializers

from .models import CrashReport, Sensor, SensorScanPolicy

# Anything tighter than this trips Android's own WiFi scan throttle (4 scans
# per 2 minutes), so the device would just sit in a back-off loop instead of
# scanning faster. Without WiFi there's no OS-level limit and the model's
# MinValueValidator(15) is the only floor.
MIN_INTERVAL_SECONDS_WITH_WIFI = 30

# Every field that ends up as a scan cadence, and so has to clear the WiFi
# throttle floor. Kept as one list so a future fifth motion state can't be
# added to the model and quietly skip validation.
INTERVAL_FIELDS = [
    "scan_interval_seconds",
    "stationary_interval_seconds",
    "walking_interval_seconds",
    "driving_interval_seconds",
]

POLICY_DESIRED_FIELDS = [
    "remote_scan_enabled",
    "scan_interval_seconds",
    "heartbeat_interval_seconds",
    "include_wifi",
    "include_cellular",
    "include_ble",
    "include_gnss",
    "adaptive_scan_enabled",
    "stationary_interval_seconds",
    "walking_interval_seconds",
    "driving_interval_seconds",
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
    "reported_motion_state",
    "reported_effective_interval_seconds",
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
        if not include_wifi:
            return attrs

        # Every cadence is checked, not just the one in use. An unused
        # interval that violates the floor is a trap set for whoever later
        # switches modes — better to refuse to store it at all.
        errors = {}
        for name in INTERVAL_FIELDS:
            value = attrs.get(name, getattr(instance, name) if instance else None)
            if value is not None and value < MIN_INTERVAL_SECONDS_WITH_WIFI:
                errors[name] = (
                    f"Must be at least {MIN_INTERVAL_SECONDS_WITH_WIFI} seconds while WiFi is "
                    "included — Android throttles WiFi scans to 4 per 2 minutes, so a shorter "
                    "interval does not produce more scans."
                )
        if errors:
            raise serializers.ValidationError(errors)
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


class CrashReportIngestSerializer(serializers.Serializer):
    """Device-facing write. Mirrors ScanSessionIngestSerializer's shape —
    a plain Serializer (not ModelSerializer) since `sensor` comes from the
    authenticated token via context, never from the request body."""

    occurred_at = serializers.DateTimeField()
    app_version = serializers.CharField(max_length=32, allow_blank=True, default="")
    device_model = serializers.CharField(max_length=64, allow_blank=True, default="")
    os_version = serializers.CharField(max_length=32, allow_blank=True, default="")
    stack_trace = serializers.CharField()

    def create(self, validated_data):
        return CrashReport.objects.create(sensor=self.context["sensor"], **validated_data)


class CrashReportSerializer(serializers.ModelSerializer):
    # SerializerMethodField, not a plain source="sensor.name" CharField —
    # same reasoning as ScanSessionSerializer.get_sensor_name: this must
    # stay null-safe for a report whose sensor was deleted with
    # on_conflict="keep_data".
    sensor_name = serializers.SerializerMethodField()

    class Meta:
        model = CrashReport
        fields = [
            "id", "sensor", "sensor_name", "occurred_at", "app_version",
            "device_model", "os_version", "stack_trace", "created_at",
        ]

    def get_sensor_name(self, obj):
        return obj.sensor.name if obj.sensor else None


class SensorTokenRevealSerializer(serializers.ModelSerializer):
    """Used only for the create and regenerate-token responses — the one
    moment the token is meant to be visible."""

    class Meta:
        model = Sensor
        fields = ["id", "name", "sensor_type", "is_active", "created_at", "last_seen_at", "token"]
        read_only_fields = ["id", "is_active", "created_at", "last_seen_at", "token"]
