export interface Paginated<T> {
  count: number;
  next: string | null;
  previous: string | null;
  results: T[];
}

export interface Sensor {
  id: string;
  name: string;
  sensor_type: string;
  is_active: boolean;
  created_at: string;
  last_seen_at: string | null;
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
}

export interface WiFiObservation {
  id: number;
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
  created_at: string;
}

export interface CellObservation {
  id: number;
  scan_session: string;
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
}

export interface BLEObservation {
  id: number;
  scan_session: string;
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
  ip_address: string;
  mac_address: string;
  hostname: string;
  vendor_oui: string;
  open_ports: number[];
  response_time_ms: number | null;
  banner: string;
  device_type_guess: string;
  observed_at: string;
}

export interface ChannelCongestionPoint {
  channel: number;
  ap_count: number;
}

export interface HeatmapPoint {
  lat: number;
  lng: number;
  weight: number;
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
