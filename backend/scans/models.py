import uuid

from django.db import models

from sensors.models import Sensor


class ScanSession(models.Model):
    """One physical scan pass on the phone (or one LAN scan). Every
    radio-type Observation table FKs to this — it's the shared
    geotag+timestamp anchor across WiFi/cellular/BLE/GNSS/LAN.

    location_accuracy_meters/location_provider come straight from Android's
    Location object (.accuracy/.provider) — captured alongside lat/lon so
    the frontend can show "how much to trust this fix" and where it came
    from (gps vs network), not just a bare coordinate pair."""

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    # Nullable so a sensor can be deleted while explicitly choosing to keep
    # its scan history (SensorViewSet.destroy()'s on_conflict="keep_data")
    # — SET_NULL rather than CASCADE means that choice is enforced at the DB
    # level, not just by an application-layer promise. Ingest always sets a
    # real sensor (see ScanSessionIngestSerializer); a null here only ever
    # means "the device that recorded this was deleted, deliberately, with
    # its data kept".
    sensor = models.ForeignKey(Sensor, null=True, blank=True, on_delete=models.SET_NULL, related_name="scan_sessions")
    client_scan_id = models.CharField(max_length=64, unique=True)
    started_at = models.DateTimeField()
    completed_at = models.DateTimeField()
    latitude = models.FloatField(null=True, blank=True)
    longitude = models.FloatField(null=True, blank=True)
    location_accuracy_meters = models.FloatField(null=True, blank=True)
    location_provider = models.CharField(max_length=16, blank=True)
    # Populated only when the Android app's location-source setting is
    # "Fused" or "Both" (see SettingsRepository.LocationSourcePreference) —
    # a *second*, independent reading alongside latitude/longitude above,
    # not a replacement. Lets the two be compared for positioning-accuracy
    # analysis rather than only ever recording whichever one "won".
    fused_latitude = models.FloatField(null=True, blank=True)
    fused_longitude = models.FloatField(null=True, blank=True)
    fused_accuracy_meters = models.FloatField(null=True, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ["-started_at"]

    def __str__(self):
        return f"ScanSession {self.id} ({self.sensor.name if self.sensor else 'sensor deleted'})"


class AccessPoint(models.Model):
    """A WiFi network identity, deduplicated by BSSID across all sessions."""

    bssid = models.CharField(max_length=17, primary_key=True)
    ssid = models.CharField(max_length=64, blank=True)
    vendor_oui = models.CharField(max_length=8, blank=True)
    first_seen_at = models.DateTimeField(auto_now_add=True)
    last_seen_at = models.DateTimeField(auto_now=True)

    def __str__(self):
        return self.ssid or self.bssid


class SecurityType(models.TextChoices):
    OPEN = "OPEN", "Open"
    WEP = "WEP", "WEP"
    WPA = "WPA", "WPA"
    WPA2 = "WPA2", "WPA2"
    WPA3 = "WPA3", "WPA3"
    WPA2_WPA3 = "WPA2_WPA3", "WPA2/WPA3"
    # Enhanced Open: no credential to join, but the link is still encrypted
    # per-client. Its own value rather than OPEN (which the UI flags red as
    # unencrypted) or WPA2 (which implies a password) — see
    # security_type_from_capabilities() in serializers.py.
    OWE = "OWE", "Enhanced Open (OWE)"
    UNKNOWN = "UNKNOWN", "Unknown"


class Band(models.TextChoices):
    BAND_24 = "2.4GHz", "2.4 GHz"
    BAND_5 = "5GHz", "5 GHz"
    BAND_6 = "6GHz", "6 GHz"


class WiFiObservation(models.Model):
    scan_session = models.ForeignKey(ScanSession, on_delete=models.CASCADE, related_name="wifi_observations")
    access_point = models.ForeignKey(AccessPoint, on_delete=models.CASCADE, related_name="observations")
    rssi = models.IntegerField()
    frequency_mhz = models.IntegerField()
    channel = models.IntegerField()
    band = models.CharField(max_length=10, choices=Band.choices)
    security_type = models.CharField(max_length=12, choices=SecurityType.choices, default=SecurityType.UNKNOWN)
    capabilities_raw = models.CharField(max_length=255, blank=True)
    # Everything below is best-effort extra detail from ScanResult — most
    # requires API 23+/28+/30+ so may be blank/null on older phones or
    # Passpoint-less networks. See ScanResultMapper.kt for the source.
    channel_width_mhz = models.IntegerField(null=True, blank=True)
    center_freq0_mhz = models.IntegerField(null=True, blank=True)
    center_freq1_mhz = models.IntegerField(null=True, blank=True)
    wifi_standard = models.CharField(max_length=16, blank=True)
    is_80211mc_responder = models.BooleanField(default=False)
    operator_friendly_name = models.CharField(max_length=128, blank=True)
    venue_name = models.CharField(max_length=128, blank=True)
    observed_at = models.DateTimeField()

    class Meta:
        indexes = [
            models.Index(fields=["access_point", "observed_at"]),
            models.Index(fields=["scan_session", "access_point"]),
        ]
        ordering = ["-observed_at"]

    def __str__(self):
        return f"{self.access_point_id} @ {self.rssi}dBm"


class CellTower(models.Model):
    """A physical cell tower/sector, deduplicated by MCC+MNC+LAC/TAC+CellID
    across all sessions — mirrors AccessPoint's role for WiFi. Readings
    lacking cell_id/tac_or_lac can't be grouped meaningfully, so
    CellObservation.cell_tower is nullable rather than inventing a bogus
    key for those."""

    tower_key = models.CharField(max_length=64, primary_key=True)
    mcc = models.CharField(max_length=3, blank=True)
    mnc = models.CharField(max_length=3, blank=True)
    tac_or_lac = models.CharField(max_length=32, blank=True)
    cell_id = models.CharField(max_length=32, blank=True)
    carrier_name = models.CharField(max_length=64, blank=True)
    radio_type = models.CharField(max_length=8, blank=True)
    first_seen_at = models.DateTimeField(auto_now_add=True)
    last_seen_at = models.DateTimeField(auto_now=True)

    class Meta:
        ordering = ["-last_seen_at"]

    def __str__(self):
        return f"{self.carrier_name or f'{self.mcc}/{self.mnc}'} cell {self.cell_id}"


class CellObservation(models.Model):
    """Phone's own serving/neighboring cell info (TelephonyManager). No SDR,
    no spectrum scanning — only what the phone's modem already exposes."""

    class RadioType(models.TextChoices):
        GSM = "GSM", "GSM"
        CDMA = "CDMA", "CDMA"
        UMTS = "UMTS", "UMTS"
        LTE = "LTE", "LTE"
        NR = "NR", "5G NR"

    scan_session = models.ForeignKey(ScanSession, on_delete=models.CASCADE, related_name="cell_observations")
    cell_tower = models.ForeignKey(
        CellTower, null=True, blank=True, on_delete=models.CASCADE, related_name="observations"
    )
    mcc = models.CharField(max_length=3, blank=True)
    mnc = models.CharField(max_length=3, blank=True)
    carrier_name = models.CharField(max_length=64, blank=True)
    radio_type = models.CharField(max_length=8, choices=RadioType.choices)
    cell_id = models.CharField(max_length=32, blank=True)
    tac_or_lac = models.CharField(max_length=32, blank=True)
    band = models.CharField(max_length=16, blank=True)
    is_serving_cell = models.BooleanField(default=False)
    signal_dbm = models.IntegerField(null=True, blank=True)
    rsrp = models.IntegerField(null=True, blank=True)
    rsrq = models.IntegerField(null=True, blank=True)
    sinr = models.FloatField(null=True, blank=True)
    # Best-effort extras from CellIdentity/CellSignalStrength — not every
    # radio type/API level exposes all of these, so all nullable.
    physical_cell_id = models.IntegerField(null=True, blank=True)
    arfcn = models.IntegerField(null=True, blank=True)
    bandwidth_khz = models.IntegerField(null=True, blank=True)
    timing_advance = models.IntegerField(null=True, blank=True)  # rough distance-to-tower proxy, LTE only
    observed_at = models.DateTimeField()

    class Meta:
        indexes = [models.Index(fields=["scan_session", "observed_at"])]
        ordering = ["-observed_at"]

    def __str__(self):
        return f"{self.carrier_name or self.mcc + '/' + self.mnc} ({self.radio_type})"


class BLEObservation(models.Model):
    """Passive BLE advertisement log. device_type_guess is purely
    informational (e.g. "possible AirTag") — there is deliberately no
    derived alerting/correlation state here. See MEMORY.md."""

    class DeviceType(models.TextChoices):
        AIRTAG = "AIRTAG", "AirTag"
        TILE = "TILE", "Tile"
        SMARTTAG = "SMARTTAG", "Samsung SmartTag"
        CHIPOLO = "CHIPOLO", "Chipolo"
        HEADPHONES = "HEADPHONES", "Headphones"
        WEARABLE = "WEARABLE", "Wearable"
        UNKNOWN = "UNKNOWN", "Unknown"
        OTHER = "OTHER", "Other"

    scan_session = models.ForeignKey(ScanSession, on_delete=models.CASCADE, related_name="ble_observations")
    # Nullable only to allow backfilling rows that existed before this FK was
    # added, and for the rare observation with neither ble_mac nor
    # stable_identifier set (see BLEDevice.device_key below) — always
    # populated otherwise.
    ble_device = models.ForeignKey(
        "BLEDevice", null=True, blank=True, on_delete=models.CASCADE, related_name="observations"
    )
    ble_mac = models.CharField(max_length=17, blank=True)
    stable_identifier = models.CharField(max_length=64, blank=True)
    rssi = models.IntegerField()
    tx_power = models.IntegerField(null=True, blank=True)
    manufacturer_data_raw = models.CharField(max_length=255, blank=True)
    service_uuids = models.JSONField(default=list, blank=True)
    device_type_guess = models.CharField(max_length=12, choices=DeviceType.choices, default=DeviceType.UNKNOWN)
    device_name = models.CharField(max_length=64, blank=True)
    is_connectable = models.BooleanField(default=False)
    primary_phy = models.CharField(max_length=8, blank=True)
    observed_at = models.DateTimeField()

    class Meta:
        indexes = [models.Index(fields=["scan_session", "observed_at"])]
        ordering = ["-observed_at"]

    def __str__(self):
        return f"{self.ble_mac or self.stable_identifier} ({self.device_type_guess})"


class BLEDevice(models.Model):
    """A BLE device, deduplicated across all sessions — mirrors AccessPoint/
    CellTower/LANDevice's grouping role for the other radio types.

    device_key is ble_mac when present, falling back to stable_identifier
    (matching the precedent already used for grouping BLE sightings
    elsewhere — the heatmap's "ble" source and the coverage-ellipse
    endpoint both key on `ble_mac or stable_identifier`, kept consistent
    here rather than inverting it). Note this means a MAC-rotating device
    (e.g. an AirTag) that was ever seen with a captured ble_mac will keep
    grouping by that specific MAC — stable_identifier only takes over as
    the key on observations where ble_mac was never captured at all."""

    device_key = models.CharField(max_length=64, primary_key=True)
    device_name = models.CharField(max_length=64, blank=True)
    device_type_guess = models.CharField(
        max_length=12, choices=BLEObservation.DeviceType.choices, default=BLEObservation.DeviceType.UNKNOWN
    )
    first_seen_at = models.DateTimeField(auto_now_add=True)
    last_seen_at = models.DateTimeField(auto_now=True)

    class Meta:
        ordering = ["-last_seen_at"]

    def __str__(self):
        return self.device_name or self.device_key


class SatelliteObservation(models.Model):
    """One row per satellite per scan session — per-satellite Cn0 is what
    the satellite-view chart needs; a summary is a query-time aggregate,
    not a stored row."""

    class Constellation(models.TextChoices):
        GPS = "GPS", "GPS"
        GLONASS = "GLONASS", "GLONASS"
        GALILEO = "GALILEO", "Galileo"
        BEIDOU = "BEIDOU", "BeiDou"
        QZSS = "QZSS", "QZSS"
        SBAS = "SBAS", "SBAS"
        IRNSS = "IRNSS", "IRNSS"

    scan_session = models.ForeignKey(ScanSession, on_delete=models.CASCADE, related_name="satellite_observations")
    constellation = models.CharField(max_length=10, choices=Constellation.choices)
    svid = models.IntegerField()
    cn0_db_hz = models.FloatField()
    elevation_degrees = models.FloatField(null=True, blank=True)
    azimuth_degrees = models.FloatField(null=True, blank=True)
    used_in_fix = models.BooleanField(default=False)
    carrier_frequency_hz = models.FloatField(null=True, blank=True)  # identifies L1/L5 etc — API 26+
    has_ephemeris_data = models.BooleanField(default=False)
    has_almanac_data = models.BooleanField(default=False)
    observed_at = models.DateTimeField()

    class Meta:
        indexes = [models.Index(fields=["scan_session"])]
        ordering = ["-observed_at"]

    def __str__(self):
        return f"{self.constellation} SV{self.svid} ({self.cn0_db_hz} dB-Hz)"


class LANObservation(models.Model):
    """One discovered device on the phone's current WiFi subnet, from a
    dedicated LAN scan (not part of the WiFi/cellular/BLE/GNSS batch pass —
    a subnet sweep + port scan takes longer, so it's its own action/session,
    same pattern NFC used to be before it was removed)."""

    class DeviceType(models.TextChoices):
        ROUTER = "ROUTER", "Router/Gateway"
        PRINTER = "PRINTER", "Printer"
        NAS = "NAS", "NAS/File server"
        MEDIA = "MEDIA", "Media/streaming device"
        CAMERA = "CAMERA", "Camera"
        WINDOWS_HOST = "WINDOWS_HOST", "Windows host"
        LINUX_HOST = "LINUX_HOST", "Linux/Unix host"
        IOT = "IOT", "IoT device"
        UNKNOWN = "UNKNOWN", "Unknown"

    scan_session = models.ForeignKey(ScanSession, on_delete=models.CASCADE, related_name="lan_observations")
    # Nullable only to allow backfilling rows that existed before this FK was
    # added — always populated for new observations since ip_address (the
    # grouping key) is required at ingest time.
    lan_device = models.ForeignKey(
        "LANDevice", null=True, blank=True, on_delete=models.CASCADE, related_name="observations"
    )
    ip_address = models.GenericIPAddressField()
    mac_address = models.CharField(max_length=17, blank=True)
    hostname = models.CharField(max_length=255, blank=True)
    vendor_oui = models.CharField(max_length=8, blank=True)
    open_ports = models.JSONField(default=list, blank=True)
    response_time_ms = models.FloatField(null=True, blank=True)
    banner = models.CharField(max_length=255, blank=True)
    device_type_guess = models.CharField(max_length=16, choices=DeviceType.choices, default=DeviceType.UNKNOWN)
    observed_at = models.DateTimeField()

    class Meta:
        indexes = [models.Index(fields=["scan_session", "observed_at"])]
        ordering = ["-observed_at"]

    def __str__(self):
        return f"{self.ip_address} ({self.hostname or 'unknown'})"


class LANDevice(models.Model):
    """A physical device on the LAN, deduplicated by IP address across all
    sessions — mirrors AccessPoint/CellTower's grouping role for WiFi/
    cellular. IP (not MAC) is the natural key: a subnet sweep discovers and
    groups devices by IP, and LAN devices typically hold a stable IP (DHCP
    reservation or static assignment) even as other observed fields
    (hostname, open ports, banner) change from scan to scan."""

    ip_address = models.GenericIPAddressField(primary_key=True)
    mac_address = models.CharField(max_length=17, blank=True)
    hostname = models.CharField(max_length=255, blank=True)
    vendor_oui = models.CharField(max_length=8, blank=True)
    device_type_guess = models.CharField(
        max_length=16, choices=LANObservation.DeviceType.choices, default=LANObservation.DeviceType.UNKNOWN
    )
    first_seen_at = models.DateTimeField(auto_now_add=True)
    last_seen_at = models.DateTimeField(auto_now=True)

    class Meta:
        ordering = ["-last_seen_at"]

    def __str__(self):
        return f"{self.ip_address} ({self.hostname or self.mac_address or 'unknown'})"


class GeocodedLocation(models.Model):
    """Reverse-geocoded place name cache, keyed on lat/lng rounded to
    GEOCODE_PRECISION (~111m at 3 decimals) — see scans/geocoding.py.
    Many scan sessions cluster at nearly the same spot, and Nominatim's
    usage policy requires caching results and capping request rate (max
    ~1/sec), so this avoids re-geocoding the same area repeatedly.
    Populated lazily by ScanSessionViewSet's resolve-addresses action, not
    synchronously at scan ingest time — an external HTTP dependency has no
    business blocking or failing a scan upload."""

    lat_rounded = models.FloatField()
    lng_rounded = models.FloatField()
    address = models.CharField(max_length=255, blank=True)
    resolved_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        constraints = [
            models.UniqueConstraint(fields=["lat_rounded", "lng_rounded"], name="unique_geocoded_location"),
        ]

    def __str__(self):
        return self.address or f"{self.lat_rounded},{self.lng_rounded}"
