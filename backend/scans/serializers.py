from django.db import transaction
from rest_framework import serializers

from .geocoding import GEOCODE_PRECISION
from .models import (
    AccessPoint,
    BLEDevice,
    BLEObservation,
    Band,
    CellObservation,
    CellTower,
    GeocodedLocation,
    LANDevice,
    LANObservation,
    SatelliteObservation,
    ScanSession,
    SecurityType,
    WiFiObservation,
)


def band_for_frequency(frequency_mhz: int) -> str:
    if 2400 <= frequency_mhz <= 2500:
        return Band.BAND_24
    if 4900 <= frequency_mhz <= 5900:
        return Band.BAND_5
    if 5925 <= frequency_mhz <= 7125:
        return Band.BAND_6
    return Band.BAND_24


def channel_for_frequency(frequency_mhz: int) -> int:
    if frequency_mhz == 2484:
        return 14
    if 2412 <= frequency_mhz <= 2472:
        return (frequency_mhz - 2407) // 5
    if 5000 <= frequency_mhz <= 5900:
        return (frequency_mhz - 5000) // 5
    if 5955 <= frequency_mhz <= 7115:
        return (frequency_mhz - 5950) // 5
    return 0


def security_type_from_capabilities(capabilities: str) -> str:
    """Classifies one AP from Android's raw `ScanResult.capabilities` string.

    Keyed on the *key management* token, not the protocol prefix. Android
    builds these strings as `[<protocol>-<key mgmt>-<cipher>]` and calls the
    protocol `RSN` — not `WPA3` — for everything SAE/OWE/Suite-B based, so a
    WPA3 network reads `[RSN-SAE-CCMP][ESS][MFPR][MFPC]` and contains the
    substring "WPA3" nowhere at all. Matching protocol names alone (what
    this did originally) therefore classified *every* WPA3, WPA2/WPA3
    transition and OWE network as UNKNOWN, which the PWA then rendered as a
    grey "Unknown" badge and `?security=WPA3` could never match.

    Key management is unambiguous by comparison: SAE means WPA3-Personal,
    SAE alongside PSK means a transition-mode BSS advertising both, and
    EAP_SUITE_B_192 means WPA3-Enterprise. SecurityParsingTests in
    scans/tests.py pins the exact strings this is expected to handle.
    """
    caps = capabilities.upper()

    has_sae = "SAE" in caps  # WPA3-Personal (also covers FT/SAE)
    has_psk = "PSK" in caps  # WPA/WPA2-Personal (also covers FT/PSK)
    has_suite_b = "EAP_SUITE_B_192" in caps  # WPA3-Enterprise 192-bit
    has_owe = "OWE" in caps  # Enhanced Open (also covers OWE_TRANSITION)

    if has_sae and has_psk:
        # One BSS advertising both so either generation of client can join.
        return SecurityType.WPA2_WPA3
    if has_sae or has_suite_b:
        return SecurityType.WPA3
    if has_owe:
        # Encrypted, but joinable with no credential — deliberately its own
        # value rather than folded into OPEN (which the UI flags red as
        # "unencrypted") or WPA2 (which implies a password).
        return SecurityType.OWE
    # "WPA2" is the legacy protocol spelling; "RSN" is what newer Android
    # builds emit for the same PSK/EAP networks. Both mean WPA2 here.
    if "WPA2" in caps or "RSN" in caps:
        return SecurityType.WPA2
    if "WPA" in caps:
        return SecurityType.WPA
    if "WEP" in caps:
        return SecurityType.WEP
    # Nothing above matched, so no security scheme was advertised. Any
    # infrastructure/ad-hoc BSS at that point is genuinely open — matching
    # the exact string "[ESS]" (as this used to) missed the very common
    # "[ESS][WPS]" and "[ESS][MFPC]" variants and called them UNKNOWN.
    if not caps or "ESS" in caps:
        return SecurityType.OPEN
    return SecurityType.UNKNOWN


# --- Read serializers ---

class AccessPointSerializer(serializers.ModelSerializer):
    latest_rssi = serializers.SerializerMethodField()
    latest_band = serializers.SerializerMethodField()
    latest_channel = serializers.SerializerMethodField()
    latest_security_type = serializers.SerializerMethodField()
    latest_has_location = serializers.SerializerMethodField()

    class Meta:
        model = AccessPoint
        fields = [
            "bssid", "ssid", "vendor_oui", "first_seen_at", "last_seen_at",
            "latest_rssi", "latest_band", "latest_channel", "latest_security_type", "latest_has_location",
        ]

    def _latest(self, obj):
        if not hasattr(obj, "_latest_observation_cache"):
            obj._latest_observation_cache = obj.observations.select_related("scan_session").order_by("-observed_at").first()
        return obj._latest_observation_cache

    def get_latest_rssi(self, obj):
        latest = self._latest(obj)
        return latest.rssi if latest else None

    def get_latest_band(self, obj):
        latest = self._latest(obj)
        return latest.band if latest else None

    def get_latest_channel(self, obj):
        latest = self._latest(obj)
        return latest.channel if latest else None

    def get_latest_security_type(self, obj):
        latest = self._latest(obj)
        return latest.security_type if latest else None

    def get_latest_has_location(self, obj):
        latest = self._latest(obj)
        return bool(latest and latest.scan_session.latitude is not None)


class WiFiObservationSerializer(serializers.ModelSerializer):
    # Pulled from the parent session, same pattern as BLEObservationSerializer
    # below — lets the network detail page plot a sighting map without a
    # second round-trip per observation.
    latitude = serializers.FloatField(source="scan_session.latitude", read_only=True)
    longitude = serializers.FloatField(source="scan_session.longitude", read_only=True)
    location_accuracy_meters = serializers.FloatField(source="scan_session.location_accuracy_meters", read_only=True)

    class Meta:
        model = WiFiObservation
        fields = [
            "id", "scan_session", "access_point", "rssi", "frequency_mhz", "channel", "band", "security_type",
            "observed_at", "channel_width_mhz", "center_freq0_mhz", "center_freq1_mhz", "wifi_standard",
            "is_80211mc_responder", "operator_friendly_name", "venue_name",
            "latitude", "longitude", "location_accuracy_meters",
        ]


class ScanSessionSerializer(serializers.ModelSerializer):
    sensor_name = serializers.CharField(source="sensor.name", read_only=True)
    wifi_count = serializers.SerializerMethodField()
    cell_count = serializers.SerializerMethodField()
    ble_count = serializers.SerializerMethodField()
    satellite_count = serializers.SerializerMethodField()
    lan_count = serializers.SerializerMethodField()
    resolved_address = serializers.SerializerMethodField()
    identifiers_summary = serializers.SerializerMethodField()

    class Meta:
        model = ScanSession
        fields = [
            "id", "sensor", "sensor_name", "started_at", "completed_at",
            "latitude", "longitude", "location_accuracy_meters", "location_provider",
            "fused_latitude", "fused_longitude", "fused_accuracy_meters",
            "created_at", "wifi_count", "cell_count", "ble_count", "satellite_count", "lan_count",
            "resolved_address", "identifiers_summary",
        ]

    # ScanSessionViewSet.get_queryset() annotates these counts so listing
    # many sessions (the scan-management page) doesn't run 5 extra queries
    # per row — but create() serializes a freshly-created, unannotated
    # instance, so fall back to a live count there.
    def _count(self, obj, annotation_name, related_name):
        if hasattr(obj, annotation_name):
            return getattr(obj, annotation_name)
        return getattr(obj, related_name).count()

    def get_wifi_count(self, obj):
        return self._count(obj, "wifi_count_annotated", "wifi_observations")

    def get_cell_count(self, obj):
        return self._count(obj, "cell_count_annotated", "cell_observations")

    def get_ble_count(self, obj):
        return self._count(obj, "ble_count_annotated", "ble_observations")

    def get_satellite_count(self, obj):
        return self._count(obj, "satellite_count_annotated", "satellite_observations")

    def get_lan_count(self, obj):
        return self._count(obj, "lan_count_annotated", "lan_observations")

    def get_resolved_address(self, obj):
        # Cache-only lookup — a live Nominatim call has no business running
        # inline during a list/retrieve response (see scans/geocoding.py).
        # ScanSessionViewSet.get_serializer_context() populates
        # "geocode_cache" once per request so this doesn't run one query
        # per row; fall back to a single direct query outside that context
        # (e.g. serializing the just-created session in create()).
        if obj.latitude is None or obj.longitude is None:
            return None
        key = (round(obj.latitude, GEOCODE_PRECISION), round(obj.longitude, GEOCODE_PRECISION))
        cache = self.context.get("geocode_cache")
        if cache is not None:
            return cache.get(key)
        entry = GeocodedLocation.objects.filter(lat_rounded=key[0], lng_rounded=key[1]).first()
        return entry.address if entry else None

    def get_identifiers_summary(self, obj):
        # A handful of the SSIDs/BLE names/LAN hostnames seen during this
        # session — lets the scan-management page's free-text search match
        # "delete every scan that saw my phone" without a separate UI for
        # it. obj.wifi_observations.all() etc. read from
        # ScanSessionViewSet's prefetch_related() when listing, so this
        # doesn't add per-row queries there.
        names = []
        for w in obj.wifi_observations.all():
            ssid = w.access_point.ssid
            if ssid and ssid not in names:
                names.append(ssid)
        for b in obj.ble_observations.all():
            name = b.device_name or b.ble_mac or b.stable_identifier
            if name and name not in names:
                names.append(name)
        for lan in obj.lan_observations.all():
            name = lan.hostname or lan.ip_address
            if name and name not in names:
                names.append(name)
        return ", ".join(names[:15])


class CellObservationSerializer(serializers.ModelSerializer):
    # Pulled from the parent session, same pattern as WiFi/BLE — lets the
    # cell tower detail page plot a sighting map without a second
    # round-trip per observation.
    latitude = serializers.FloatField(source="scan_session.latitude", read_only=True)
    longitude = serializers.FloatField(source="scan_session.longitude", read_only=True)
    location_accuracy_meters = serializers.FloatField(source="scan_session.location_accuracy_meters", read_only=True)

    class Meta:
        model = CellObservation
        fields = "__all__"


class CellTowerSerializer(serializers.ModelSerializer):
    latest_signal_dbm = serializers.SerializerMethodField()
    latest_arfcn = serializers.SerializerMethodField()
    latest_has_location = serializers.SerializerMethodField()

    class Meta:
        model = CellTower
        fields = [
            "tower_key", "mcc", "mnc", "tac_or_lac", "cell_id", "carrier_name", "radio_type",
            "first_seen_at", "last_seen_at", "latest_signal_dbm", "latest_arfcn", "latest_has_location",
        ]

    def _latest(self, obj):
        if not hasattr(obj, "_latest_observation_cache"):
            obj._latest_observation_cache = obj.observations.select_related("scan_session").order_by("-observed_at").first()
        return obj._latest_observation_cache

    def get_latest_signal_dbm(self, obj):
        latest = self._latest(obj)
        return latest.signal_dbm if latest else None

    def get_latest_arfcn(self, obj):
        latest = self._latest(obj)
        return latest.arfcn if latest else None

    def get_latest_has_location(self, obj):
        latest = self._latest(obj)
        return bool(latest and latest.scan_session.latitude is not None)


class BLEObservationSerializer(serializers.ModelSerializer):
    # Pulled from the parent session so the frontend's device-detail/map
    # view doesn't need a second round-trip per sighting.
    latitude = serializers.FloatField(source="scan_session.latitude", read_only=True)
    longitude = serializers.FloatField(source="scan_session.longitude", read_only=True)
    location_accuracy_meters = serializers.FloatField(source="scan_session.location_accuracy_meters", read_only=True)

    class Meta:
        model = BLEObservation
        fields = "__all__"


class BLEDeviceSerializer(serializers.ModelSerializer):
    latest_rssi = serializers.SerializerMethodField()
    latest_device_name = serializers.SerializerMethodField()
    latest_is_connectable = serializers.SerializerMethodField()
    latest_primary_phy = serializers.SerializerMethodField()
    latest_has_location = serializers.SerializerMethodField()

    class Meta:
        model = BLEDevice
        fields = [
            "device_key", "device_name", "device_type_guess", "first_seen_at", "last_seen_at",
            "latest_rssi", "latest_device_name", "latest_is_connectable", "latest_primary_phy",
            "latest_has_location",
        ]

    def _latest(self, obj):
        if not hasattr(obj, "_latest_observation_cache"):
            obj._latest_observation_cache = obj.observations.select_related("scan_session").order_by("-observed_at").first()
        return obj._latest_observation_cache

    def get_latest_rssi(self, obj):
        latest = self._latest(obj)
        return latest.rssi if latest else None

    def get_latest_device_name(self, obj):
        latest = self._latest(obj)
        return latest.device_name if latest else None

    def get_latest_is_connectable(self, obj):
        latest = self._latest(obj)
        return bool(latest and latest.is_connectable)

    def get_latest_primary_phy(self, obj):
        latest = self._latest(obj)
        return latest.primary_phy if latest else None

    def get_latest_has_location(self, obj):
        latest = self._latest(obj)
        return bool(latest and latest.scan_session.latitude is not None)


class SatelliteObservationSerializer(serializers.ModelSerializer):
    class Meta:
        model = SatelliteObservation
        fields = "__all__"


class LANObservationSerializer(serializers.ModelSerializer):
    # Same pattern as WiFi/BLE/Cell — lets the Dashboard's unified activity
    # view (and the LAN device detail page's accuracy circles) work without
    # a second round-trip.
    latitude = serializers.FloatField(source="scan_session.latitude", read_only=True)
    longitude = serializers.FloatField(source="scan_session.longitude", read_only=True)
    location_accuracy_meters = serializers.FloatField(source="scan_session.location_accuracy_meters", read_only=True)

    class Meta:
        model = LANObservation
        fields = "__all__"


class LANDeviceSerializer(serializers.ModelSerializer):
    latest_open_ports = serializers.SerializerMethodField()
    latest_device_type_guess = serializers.SerializerMethodField()
    latest_has_location = serializers.SerializerMethodField()
    # LAN has no RSSI/signal_dbm equivalent — response time is its closest
    # analog for the overview table's "signal strength" column.
    latest_response_time_ms = serializers.SerializerMethodField()
    # Unconditional — was this device seen in the single most recent LAN
    # scan, regardless of any filtering applied to the request.
    is_online = serializers.SerializerMethodField()
    # Only meaningfully non-False when LANDeviceViewSet.list() is filtering
    # by a session_limit of >=2 LAN scans — otherwise there's nothing
    # discrete to compare against (single scan, or a time-based window).
    is_new_in_window = serializers.SerializerMethodField()
    is_left_in_window = serializers.SerializerMethodField()

    class Meta:
        model = LANDevice
        fields = [
            "ip_address", "mac_address", "hostname", "vendor_oui", "device_type_guess",
            "first_seen_at", "last_seen_at", "latest_open_ports", "latest_device_type_guess", "latest_has_location",
            "latest_response_time_ms", "is_online", "is_new_in_window", "is_left_in_window",
        ]

    def _latest(self, obj):
        if not hasattr(obj, "_latest_observation_cache"):
            obj._latest_observation_cache = obj.observations.select_related("scan_session").order_by("-observed_at").first()
        return obj._latest_observation_cache

    def get_latest_open_ports(self, obj):
        latest = self._latest(obj)
        return latest.open_ports if latest else []

    def get_latest_device_type_guess(self, obj):
        latest = self._latest(obj)
        return latest.device_type_guess if latest else None

    def get_latest_has_location(self, obj):
        latest = self._latest(obj)
        return bool(latest and latest.scan_session.latitude is not None)

    def get_latest_response_time_ms(self, obj):
        latest = self._latest(obj)
        return latest.response_time_ms if latest else None

    def get_is_online(self, obj):
        return getattr(obj, "_is_online", False)

    def get_is_new_in_window(self, obj):
        return getattr(obj, "_is_new_in_window", False)

    def get_is_left_in_window(self, obj):
        return getattr(obj, "_is_left_in_window", False)


# --- Ingest (write) serializers ---
# One nested payload per scan pass, atomically, idempotent on client_scan_id.
# See AGENT.md: don't split this into per-radio endpoints. The atomicity is
# enforced by @transaction.atomic on create() below — don't remove it; the
# idempotency check makes a half-written session permanent (see there).

class WiFiObservationInputSerializer(serializers.Serializer):
    bssid = serializers.CharField(max_length=17)
    ssid = serializers.CharField(max_length=64, allow_blank=True, default="")
    rssi = serializers.IntegerField()
    frequency_mhz = serializers.IntegerField()
    capabilities = serializers.CharField(max_length=255, allow_blank=True, default="")
    channel_width_mhz = serializers.IntegerField(required=False, allow_null=True)
    center_freq0_mhz = serializers.IntegerField(required=False, allow_null=True)
    center_freq1_mhz = serializers.IntegerField(required=False, allow_null=True)
    wifi_standard = serializers.CharField(max_length=16, allow_blank=True, default="")
    is_80211mc_responder = serializers.BooleanField(default=False)
    operator_friendly_name = serializers.CharField(max_length=128, allow_blank=True, default="")
    venue_name = serializers.CharField(max_length=128, allow_blank=True, default="")
    observed_at = serializers.DateTimeField(required=False)


class CellObservationInputSerializer(serializers.Serializer):
    mcc = serializers.CharField(max_length=3, allow_blank=True, default="")
    mnc = serializers.CharField(max_length=3, allow_blank=True, default="")
    carrier_name = serializers.CharField(max_length=64, allow_blank=True, default="")
    radio_type = serializers.ChoiceField(choices=CellObservation.RadioType.choices)
    cell_id = serializers.CharField(max_length=32, allow_blank=True, default="")
    tac_or_lac = serializers.CharField(max_length=32, allow_blank=True, default="")
    band = serializers.CharField(max_length=16, allow_blank=True, default="")
    is_serving_cell = serializers.BooleanField(default=False)
    signal_dbm = serializers.IntegerField(required=False, allow_null=True)
    rsrp = serializers.IntegerField(required=False, allow_null=True)
    rsrq = serializers.IntegerField(required=False, allow_null=True)
    sinr = serializers.FloatField(required=False, allow_null=True)
    physical_cell_id = serializers.IntegerField(required=False, allow_null=True)
    arfcn = serializers.IntegerField(required=False, allow_null=True)
    bandwidth_khz = serializers.IntegerField(required=False, allow_null=True)
    timing_advance = serializers.IntegerField(required=False, allow_null=True)
    observed_at = serializers.DateTimeField(required=False)


class BLEObservationInputSerializer(serializers.Serializer):
    ble_mac = serializers.CharField(max_length=17, allow_blank=True, default="")
    stable_identifier = serializers.CharField(max_length=64, allow_blank=True, default="")
    rssi = serializers.IntegerField()
    tx_power = serializers.IntegerField(required=False, allow_null=True)
    manufacturer_data = serializers.CharField(max_length=255, allow_blank=True, default="")
    service_uuids = serializers.ListField(child=serializers.CharField(), default=list)
    device_type_guess = serializers.ChoiceField(
        choices=BLEObservation.DeviceType.choices, default=BLEObservation.DeviceType.UNKNOWN
    )
    device_name = serializers.CharField(max_length=64, allow_blank=True, default="")
    is_connectable = serializers.BooleanField(default=False)
    primary_phy = serializers.CharField(max_length=8, allow_blank=True, default="")
    observed_at = serializers.DateTimeField(required=False)


class SatelliteObservationInputSerializer(serializers.Serializer):
    constellation = serializers.ChoiceField(choices=SatelliteObservation.Constellation.choices)
    svid = serializers.IntegerField()
    cn0_db_hz = serializers.FloatField()
    elevation_degrees = serializers.FloatField(required=False, allow_null=True)
    azimuth_degrees = serializers.FloatField(required=False, allow_null=True)
    used_in_fix = serializers.BooleanField(default=False)
    carrier_frequency_hz = serializers.FloatField(required=False, allow_null=True)
    has_ephemeris_data = serializers.BooleanField(default=False)
    has_almanac_data = serializers.BooleanField(default=False)
    observed_at = serializers.DateTimeField(required=False)


class LANObservationInputSerializer(serializers.Serializer):
    ip_address = serializers.IPAddressField()
    mac_address = serializers.CharField(max_length=17, allow_blank=True, default="")
    hostname = serializers.CharField(max_length=255, allow_blank=True, default="")
    vendor_oui = serializers.CharField(max_length=8, allow_blank=True, default="")
    open_ports = serializers.ListField(child=serializers.IntegerField(), default=list)
    response_time_ms = serializers.FloatField(required=False, allow_null=True)
    banner = serializers.CharField(max_length=255, allow_blank=True, default="")
    device_type_guess = serializers.ChoiceField(
        choices=LANObservation.DeviceType.choices, default=LANObservation.DeviceType.UNKNOWN
    )
    observed_at = serializers.DateTimeField(required=False)


class ScanSessionIngestSerializer(serializers.Serializer):
    client_scan_id = serializers.CharField(max_length=64)
    started_at = serializers.DateTimeField()
    completed_at = serializers.DateTimeField()
    latitude = serializers.FloatField(required=False, allow_null=True)
    longitude = serializers.FloatField(required=False, allow_null=True)
    location_accuracy_meters = serializers.FloatField(required=False, allow_null=True)
    location_provider = serializers.CharField(max_length=16, allow_blank=True, default="")
    fused_latitude = serializers.FloatField(required=False, allow_null=True)
    fused_longitude = serializers.FloatField(required=False, allow_null=True)
    fused_accuracy_meters = serializers.FloatField(required=False, allow_null=True)
    wifi_observations = WiFiObservationInputSerializer(many=True, required=False, default=list)
    cell_observations = CellObservationInputSerializer(many=True, required=False, default=list)
    ble_observations = BLEObservationInputSerializer(many=True, required=False, default=list)
    satellite_observations = SatelliteObservationInputSerializer(many=True, required=False, default=list)
    lan_observations = LANObservationInputSerializer(many=True, required=False, default=list)

    @transaction.atomic
    def create(self, validated_data):
        """All-or-nothing, because the idempotency check below is what makes a
        partial write permanent rather than self-healing.

        Without this, a failure part-way through the observation loops (an
        over-long field, a lost connection, a constraint violation) left the
        ScanSession row committed with only some of its observations. The
        device's retry then hit the `if not created` branch, got a 201 back,
        deleted the payload from its outbox — and the missing observations
        were never inserted by anyone. A silently, permanently partial scan.

        Scoped here rather than via ATOMIC_REQUESTS so only this endpoint pays
        for it; the read endpoints don't need a transaction each. Note
        get_or_create takes its own savepoint internally, so the concurrent
        duplicate-POST recovery it does still works inside this block.
        """
        sensor = self.context["sensor"]
        session, created = ScanSession.objects.get_or_create(
            client_scan_id=validated_data["client_scan_id"],
            defaults={
                "sensor": sensor,
                "started_at": validated_data["started_at"],
                "completed_at": validated_data["completed_at"],
                "latitude": validated_data.get("latitude"),
                "longitude": validated_data.get("longitude"),
                "location_accuracy_meters": validated_data.get("location_accuracy_meters"),
                "location_provider": validated_data.get("location_provider", ""),
                "fused_latitude": validated_data.get("fused_latitude"),
                "fused_longitude": validated_data.get("fused_longitude"),
                "fused_accuracy_meters": validated_data.get("fused_accuracy_meters"),
            },
        )
        if not created:
            # Idempotent replay (e.g. Android's outbox retried after a
            # network blip) — return the existing session, don't duplicate.
            return session

        # Distinct from Sensor.last_seen_at, which SensorTokenAuthentication
        # bumps on *any* authenticated request — including remote-control
        # heartbeats every few seconds. This is the one that still means
        # "when did this device last actually contribute data".
        sensor.last_scan_upload_at = session.completed_at
        sensor.save(update_fields=["last_scan_upload_at"])

        default_observed_at = validated_data["completed_at"]

        for item in validated_data.get("wifi_observations", []):
            # ap_created, not `created` — that name belongs to the session
            # get_or_create above, and shadowing it here made the
            # idempotency branch above much harder to follow than it needs
            # to be. Matches tower_created/device_created below.
            access_point, ap_created = AccessPoint.objects.get_or_create(
                bssid=item["bssid"], defaults={"ssid": item.get("ssid", "")}
            )
            if not ap_created:
                # Always save (not just on SSID change) so last_seen_at
                # (auto_now) actually reflects this sighting — an AP seen
                # again with an unchanged SSID otherwise never updates it.
                if item.get("ssid") and access_point.ssid != item["ssid"]:
                    access_point.ssid = item["ssid"]
                access_point.save(update_fields=["ssid", "last_seen_at"])

            frequency = item["frequency_mhz"]
            WiFiObservation.objects.create(
                scan_session=session,
                access_point=access_point,
                rssi=item["rssi"],
                frequency_mhz=frequency,
                channel=channel_for_frequency(frequency),
                band=band_for_frequency(frequency),
                security_type=security_type_from_capabilities(item.get("capabilities", "")),
                capabilities_raw=item.get("capabilities", ""),
                channel_width_mhz=item.get("channel_width_mhz"),
                center_freq0_mhz=item.get("center_freq0_mhz"),
                center_freq1_mhz=item.get("center_freq1_mhz"),
                wifi_standard=item.get("wifi_standard", ""),
                is_80211mc_responder=item.get("is_80211mc_responder", False),
                operator_friendly_name=item.get("operator_friendly_name", ""),
                venue_name=item.get("venue_name", ""),
                observed_at=item.get("observed_at", default_observed_at),
            )

        for item in validated_data.get("cell_observations", []):
            cell_tower = None
            cell_id = item.get("cell_id", "")
            tac_or_lac = item.get("tac_or_lac", "")
            if cell_id and tac_or_lac:
                mcc = item.get("mcc", "")
                mnc = item.get("mnc", "")
                tower_key = f"{mcc}-{mnc}-{tac_or_lac}-{cell_id}"
                cell_tower, tower_created = CellTower.objects.get_or_create(
                    tower_key=tower_key,
                    defaults={
                        "mcc": mcc, "mnc": mnc, "tac_or_lac": tac_or_lac, "cell_id": cell_id,
                        "carrier_name": item.get("carrier_name", ""), "radio_type": item["radio_type"],
                    },
                )
                if not tower_created:
                    # Same always-save reasoning as AccessPoint: last_seen_at
                    # (auto_now) must actually fire on every sighting, not
                    # just when carrier_name/radio_type happen to change.
                    if item.get("carrier_name"):
                        cell_tower.carrier_name = item["carrier_name"]
                    cell_tower.radio_type = item["radio_type"]
                    cell_tower.save()

            CellObservation.objects.create(
                scan_session=session,
                cell_tower=cell_tower,
                mcc=item.get("mcc", ""),
                mnc=item.get("mnc", ""),
                carrier_name=item.get("carrier_name", ""),
                radio_type=item["radio_type"],
                cell_id=item.get("cell_id", ""),
                tac_or_lac=item.get("tac_or_lac", ""),
                band=item.get("band", ""),
                is_serving_cell=item.get("is_serving_cell", False),
                signal_dbm=item.get("signal_dbm"),
                rsrp=item.get("rsrp"),
                rsrq=item.get("rsrq"),
                sinr=item.get("sinr"),
                physical_cell_id=item.get("physical_cell_id"),
                arfcn=item.get("arfcn"),
                bandwidth_khz=item.get("bandwidth_khz"),
                timing_advance=item.get("timing_advance"),
                observed_at=item.get("observed_at", default_observed_at),
            )

        for item in validated_data.get("ble_observations", []):
            device_key = item.get("ble_mac") or item.get("stable_identifier")
            ble_device = None
            if device_key:
                ble_device, device_created = BLEDevice.objects.get_or_create(
                    device_key=device_key,
                    defaults={
                        "device_name": item.get("device_name", ""),
                        "device_type_guess": item.get("device_type_guess", BLEObservation.DeviceType.UNKNOWN),
                    },
                )
                if not device_created:
                    # Always save (not just on change) so last_seen_at
                    # (auto_now) actually reflects this sighting — same
                    # reasoning as AccessPoint/CellTower/LANDevice above.
                    ble_device.device_name = item.get("device_name", "") or ble_device.device_name
                    ble_device.device_type_guess = item.get("device_type_guess", ble_device.device_type_guess)
                    ble_device.save()

            BLEObservation.objects.create(
                scan_session=session,
                ble_device=ble_device,
                ble_mac=item.get("ble_mac", ""),
                stable_identifier=item.get("stable_identifier", ""),
                rssi=item["rssi"],
                tx_power=item.get("tx_power"),
                manufacturer_data_raw=item.get("manufacturer_data", ""),
                service_uuids=item.get("service_uuids", []),
                device_type_guess=item.get("device_type_guess", BLEObservation.DeviceType.UNKNOWN),
                device_name=item.get("device_name", ""),
                is_connectable=item.get("is_connectable", False),
                primary_phy=item.get("primary_phy", ""),
                observed_at=item.get("observed_at", default_observed_at),
            )

        for item in validated_data.get("satellite_observations", []):
            SatelliteObservation.objects.create(
                scan_session=session,
                constellation=item["constellation"],
                svid=item["svid"],
                cn0_db_hz=item["cn0_db_hz"],
                elevation_degrees=item.get("elevation_degrees"),
                azimuth_degrees=item.get("azimuth_degrees"),
                used_in_fix=item.get("used_in_fix", False),
                carrier_frequency_hz=item.get("carrier_frequency_hz"),
                has_ephemeris_data=item.get("has_ephemeris_data", False),
                has_almanac_data=item.get("has_almanac_data", False),
                observed_at=item.get("observed_at", default_observed_at),
            )

        for item in validated_data.get("lan_observations", []):
            lan_device, device_created = LANDevice.objects.get_or_create(
                ip_address=item["ip_address"],
                defaults={
                    "mac_address": item.get("mac_address", ""),
                    "hostname": item.get("hostname", ""),
                    "vendor_oui": item.get("vendor_oui", ""),
                    "device_type_guess": item.get("device_type_guess", LANObservation.DeviceType.UNKNOWN),
                },
            )
            if not device_created:
                # Always save (not just on change) so last_seen_at (auto_now)
                # actually reflects this sighting — same reasoning as
                # AccessPoint/CellTower above.
                lan_device.mac_address = item.get("mac_address", "") or lan_device.mac_address
                lan_device.hostname = item.get("hostname", "") or lan_device.hostname
                lan_device.vendor_oui = item.get("vendor_oui", "") or lan_device.vendor_oui
                lan_device.device_type_guess = item.get("device_type_guess", lan_device.device_type_guess)
                lan_device.save()

            LANObservation.objects.create(
                scan_session=session,
                lan_device=lan_device,
                ip_address=item["ip_address"],
                mac_address=item.get("mac_address", ""),
                hostname=item.get("hostname", ""),
                vendor_oui=item.get("vendor_oui", ""),
                open_ports=item.get("open_ports", []),
                response_time_ms=item.get("response_time_ms"),
                banner=item.get("banner", ""),
                device_type_guess=item.get("device_type_guess", LANObservation.DeviceType.UNKNOWN),
                observed_at=item.get("observed_at", default_observed_at),
            )

        return session
