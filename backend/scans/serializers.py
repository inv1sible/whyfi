from rest_framework import serializers

from .models import (
    AccessPoint,
    BLEObservation,
    Band,
    CellObservation,
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
    caps = capabilities.upper()
    if "WPA3" in caps and "WPA2" in caps:
        return SecurityType.WPA2_WPA3
    if "WPA3" in caps:
        return SecurityType.WPA3
    if "WPA2" in caps:
        return SecurityType.WPA2
    if "WPA" in caps:
        return SecurityType.WPA
    if "WEP" in caps:
        return SecurityType.WEP
    if not caps or caps == "[ESS]":
        return SecurityType.OPEN
    return SecurityType.UNKNOWN


# --- Read serializers ---

class AccessPointSerializer(serializers.ModelSerializer):
    latest_rssi = serializers.SerializerMethodField()
    latest_band = serializers.SerializerMethodField()
    latest_channel = serializers.SerializerMethodField()
    latest_security_type = serializers.SerializerMethodField()

    class Meta:
        model = AccessPoint
        fields = [
            "bssid", "ssid", "vendor_oui", "first_seen_at", "last_seen_at",
            "latest_rssi", "latest_band", "latest_channel", "latest_security_type",
        ]

    def _latest(self, obj):
        if not hasattr(obj, "_latest_observation_cache"):
            obj._latest_observation_cache = obj.observations.order_by("-observed_at").first()
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


class WiFiObservationSerializer(serializers.ModelSerializer):
    class Meta:
        model = WiFiObservation
        fields = [
            "id", "access_point", "rssi", "frequency_mhz", "channel", "band", "security_type", "observed_at",
            "channel_width_mhz", "center_freq0_mhz", "center_freq1_mhz", "wifi_standard",
            "is_80211mc_responder", "operator_friendly_name", "venue_name",
        ]


class ScanSessionSerializer(serializers.ModelSerializer):
    sensor_name = serializers.CharField(source="sensor.name", read_only=True)

    class Meta:
        model = ScanSession
        fields = [
            "id", "sensor", "sensor_name", "started_at", "completed_at",
            "latitude", "longitude", "location_accuracy_meters", "location_provider",
            "created_at",
        ]


class CellObservationSerializer(serializers.ModelSerializer):
    class Meta:
        model = CellObservation
        fields = "__all__"


class BLEObservationSerializer(serializers.ModelSerializer):
    # Pulled from the parent session so the frontend's device-detail/map
    # view doesn't need a second round-trip per sighting.
    latitude = serializers.FloatField(source="scan_session.latitude", read_only=True)
    longitude = serializers.FloatField(source="scan_session.longitude", read_only=True)

    class Meta:
        model = BLEObservation
        fields = "__all__"


class SatelliteObservationSerializer(serializers.ModelSerializer):
    class Meta:
        model = SatelliteObservation
        fields = "__all__"


class LANObservationSerializer(serializers.ModelSerializer):
    class Meta:
        model = LANObservation
        fields = "__all__"


# --- Ingest (write) serializers ---
# One nested payload per scan pass, atomically, idempotent on client_scan_id.
# See AGENT.md: don't split this into per-radio endpoints.

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
    wifi_observations = WiFiObservationInputSerializer(many=True, required=False, default=list)
    cell_observations = CellObservationInputSerializer(many=True, required=False, default=list)
    ble_observations = BLEObservationInputSerializer(many=True, required=False, default=list)
    satellite_observations = SatelliteObservationInputSerializer(many=True, required=False, default=list)
    lan_observations = LANObservationInputSerializer(many=True, required=False, default=list)

    def create(self, validated_data):
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
            },
        )
        if not created:
            # Idempotent replay (e.g. Android's outbox retried after a
            # network blip) — return the existing session, don't duplicate.
            return session

        default_observed_at = validated_data["completed_at"]

        for item in validated_data.get("wifi_observations", []):
            access_point, created = AccessPoint.objects.get_or_create(
                bssid=item["bssid"], defaults={"ssid": item.get("ssid", "")}
            )
            if not created:
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
            CellObservation.objects.create(
                scan_session=session,
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
            BLEObservation.objects.create(
                scan_session=session,
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
            LANObservation.objects.create(
                scan_session=session,
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
