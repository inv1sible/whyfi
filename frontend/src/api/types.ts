export interface Paginated<T> {
  count: number;
  next: string | null;
  previous: string | null;
  results: T[];
}

/** Remote scanning control for one device.
 *
 * Split into what the device *should* be doing (set from this UI) and what it
 * reports it's *actually* doing. The device converges on the desired state —
 * nothing here is a queued command, so a click that lands while the phone is
 * offline simply takes effect whenever it comes back. */
export interface SensorScanPolicy {
  // Desired — written from the web UI
  remote_scan_enabled: boolean;
  scan_interval_seconds: number;
  heartbeat_interval_seconds: number;
  include_wifi: boolean;
  include_cellular: boolean;
  include_ble: boolean;
  include_gnss: boolean;
  scan_now_nonce: number;
  reset_counters_nonce: number;
  policy_revision: number;
  updated_at: string;
  // Reported — written by the device's heartbeat
  last_heartbeat_at: string | null;
  reported_is_continuous: boolean | null;
  reported_is_scanning: boolean | null;
  reported_phase: string;
  reported_completed_scans: number | null;
  reported_wifi_unavailable_reason: string;
  reported_cellular_unavailable_reason: string;
  reported_ble_unavailable_reason: string;
  reported_permissions_granted: boolean | null;
  reported_location_services_enabled: boolean | null;
  reported_pending_uploads: number | null;
  reported_outbox_bytes: number | null;
  reported_outbox_quota_mb: number | null;
  reported_battery_percent: number | null;
  reported_app_version: string;
  reported_policy_revision: number | null;
  reported_scan_now_nonce: number | null;
  reported_reset_counters_nonce: number | null;
  // Derived server-side
  agent_online: boolean;
  policy_pending: boolean;
}

/** The subset a browser may write — reported_* fields are device-only. */
export type SensorScanPolicyUpdate = Partial<
  Pick<
    SensorScanPolicy,
    | "remote_scan_enabled"
    | "scan_interval_seconds"
    | "heartbeat_interval_seconds"
    | "include_wifi"
    | "include_cellular"
    | "include_ble"
    | "include_gnss"
  >
>;

export interface Sensor {
  id: string;
  name: string;
  sensor_type: string;
  is_active: boolean;
  created_at: string;
  last_seen_at: string | null;
  /** When this device last actually uploaded a scan — distinct from
   * last_seen_at, which any authenticated request bumps, heartbeats included. */
  last_scan_upload_at: string | null;
  scan_policy: SensorScanPolicy;
}

export interface SensorWithToken extends Sensor {
  token: string;
}

export interface AccessPoint {
  bssid: string;
  ssid: string;
  vendor_oui: string;
  first_seen_at: string;
  last_seen_at: string;
  latest_rssi: number | null;
  latest_band: string | null;
  latest_channel: number | null;
  latest_security_type: string | null;
  latest_has_location: boolean;
}

export interface WiFiObservation {
  id: number;
  scan_session: string;
  access_point: string;
  rssi: number;
  frequency_mhz: number;
  channel: number;
  band: string;
  security_type: string;
  observed_at: string;
  channel_width_mhz: number | null;
  center_freq0_mhz: number | null;
  center_freq1_mhz: number | null;
  wifi_standard: string;
  is_80211mc_responder: boolean;
  operator_friendly_name: string;
  venue_name: string;
  latitude: number | null;
  longitude: number | null;
  location_accuracy_meters: number | null;
}

export interface ScanSession {
  id: string;
  sensor: string;
  sensor_name: string;
  started_at: string;
  completed_at: string;
  latitude: number | null;
  longitude: number | null;
  location_accuracy_meters: number | null;
  location_provider: string;
  fused_latitude: number | null;
  fused_longitude: number | null;
  fused_accuracy_meters: number | null;
  created_at: string;
  wifi_count: number;
  cell_count: number;
  ble_count: number;
  satellite_count: number;
  lan_count: number;
  resolved_address: string | null;
  identifiers_summary: string;
}

export interface CellObservation {
  id: number;
  scan_session: string;
  cell_tower: string | null;
  mcc: string;
  mnc: string;
  carrier_name: string;
  radio_type: string;
  cell_id: string;
  tac_or_lac: string;
  band: string;
  is_serving_cell: boolean;
  signal_dbm: number | null;
  rsrp: number | null;
  rsrq: number | null;
  sinr: number | null;
  physical_cell_id: number | null;
  arfcn: number | null;
  bandwidth_khz: number | null;
  timing_advance: number | null;
  observed_at: string;
  latitude: number | null;
  longitude: number | null;
  location_accuracy_meters: number | null;
}

export interface CellTower {
  tower_key: string;
  mcc: string;
  mnc: string;
  tac_or_lac: string;
  cell_id: string;
  carrier_name: string;
  radio_type: string;
  first_seen_at: string;
  last_seen_at: string;
  latest_signal_dbm: number | null;
  latest_arfcn: number | null;
  latest_has_location: boolean;
}

export interface BLEObservation {
  id: number;
  scan_session: string;
  ble_device: string | null;
  ble_mac: string;
  stable_identifier: string;
  rssi: number;
  tx_power: number | null;
  manufacturer_data_raw: string;
  service_uuids: string[];
  device_type_guess: string;
  device_name: string;
  is_connectable: boolean;
  primary_phy: string;
  observed_at: string;
  latitude: number | null;
  longitude: number | null;
  location_accuracy_meters: number | null;
}

export interface BLEDevice {
  device_key: string;
  device_name: string;
  device_type_guess: string;
  first_seen_at: string;
  last_seen_at: string;
  latest_rssi: number | null;
  latest_device_name: string | null;
  latest_is_connectable: boolean;
  latest_primary_phy: string | null;
  latest_has_location: boolean;
}

export interface SatelliteObservation {
  id: number;
  scan_session: string;
  constellation: string;
  svid: number;
  cn0_db_hz: number;
  elevation_degrees: number | null;
  azimuth_degrees: number | null;
  used_in_fix: boolean;
  carrier_frequency_hz: number | null;
  has_ephemeris_data: boolean;
  has_almanac_data: boolean;
  observed_at: string;
}

export interface LANObservation {
  id: number;
  scan_session: string;
  lan_device: string | null;
  ip_address: string;
  mac_address: string;
  hostname: string;
  vendor_oui: string;
  open_ports: number[];
  response_time_ms: number | null;
  banner: string;
  device_type_guess: string;
  observed_at: string;
  latitude: number | null;
  longitude: number | null;
  location_accuracy_meters: number | null;
}

export interface LANDevice {
  ip_address: string;
  mac_address: string;
  hostname: string;
  vendor_oui: string;
  device_type_guess: string;
  first_seen_at: string;
  last_seen_at: string;
  latest_open_ports: number[];
  latest_device_type_guess: string | null;
  latest_has_location: boolean;
  latest_response_time_ms: number | null;
  is_online: boolean;
  is_new_in_window: boolean;
  is_left_in_window: boolean;
}

export interface ChannelCongestionPoint {
  channel: number;
  ap_count: number;
}

export interface HeatmapPointSource {
  label: string;
  detail_path: string;
  extra_count: number;
}

export interface HeatmapPoint {
  lat: number;
  lng: number;
  weight: number;
  source?: HeatmapPointSource | null;
}

export type BuildStatus = "NONE" | "QUEUED" | "BUILDING" | "SUCCESS" | "FAILED";

export interface AppRelease {
  id: string;
  version_code: number;
  version_name: string;
  release_notes: string;
  created_at: string;
  download_url: string | null;
  apk_size: number | null;
  build_status: BuildStatus;
  build_started_at: string | null;
  build_finished_at: string | null;
  build_log_tail: string;
}

export interface BuildStatusResponse {
  build_status: BuildStatus;
  id?: string;
  version_code?: number;
  version_name?: string;
  build_started_at?: string | null;
  build_finished_at?: string | null;
  build_log_tail?: string;
}

export type HeatmapSource = "wifi" | "cellular" | "ble";

/**
 * The coverage and heatmap endpoints group raw observations server-side, so
 * they're bounded by an observation cap rather than paginated.
 *
 * `truncated` means that cap was hit and the payload is therefore an
 * incomplete answer. Surface it — these used to be bare arrays, silently
 * sliced, which made a partial map indistinguishable from a complete one on
 * a page whose whole purpose is showing what's out there. See
 * `capped_response()` in `backend/scans/views.py`.
 */
export interface CappedList<T> {
  results: T[];
  truncated: boolean;
  observation_limit: number;
}

export interface AccessPointCoverage {
  bssid: string;
  ssid: string;
  detail_path: string;
  // scan_session_id/accuracy_meters identify the one (almost always
  // exactly one, since buckets are rounded to ~1m) reading behind this
  // point — lets the frontend's "show device location pins" toggle mark
  // where the phone stood, same as the per-entity detail pages.
  points: {
    lat: number;
    lng: number;
    weight: number;
    scan_session_id?: string;
    accuracy_meters?: number | null;
    // Powers "current scan only" display mode's shared chronological
    // timeline across every active device on the combined Heatmap page.
    observed_at?: string;
  }[];
}

// Shared shape for the cellular/BLE coverage endpoints — unlike WiFi's
// bssid/ssid pair, these have one natural grouping identifier each
// (tower_key, MAC/stable_identifier), so a plain key/label suffices.
export interface RadioCoverage {
  key: string;
  label: string;
  detail_path: string;
  // BLE-only — lets the frontend treat HEADPHONES/WEARABLE as inherently
  // mobile regardless of measured sighting spread (see classifyCoverage).
  device_type_guess?: string;
  // scan_session_id/accuracy_meters identify the one (almost always
  // exactly one, since buckets are rounded to ~1m) reading behind this
  // point — lets the frontend's "show device location pins" toggle mark
  // where the phone stood, same as the per-entity detail pages.
  points: {
    lat: number;
    lng: number;
    weight: number;
    scan_session_id?: string;
    accuracy_meters?: number | null;
    // Powers "current scan only" display mode's shared chronological
    // timeline across every active device on the combined Heatmap page.
    observed_at?: string;
  }[];
}
