export interface Paginated<T> {
  count: number;
  next: string | null;
  previous: string | null;
  results: T[];
}

export interface MonitorMission {
  id: string;
  sensor: string;
  uploaded_at: string;
  report_url: string;
  log_url: string;
  pcap_url: string | null;
  report_bytes: number;
  log_bytes: number;
  pcap_bytes: number;
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
  // Adaptive cadence — one interval per motion state. When enabled the device
  // picks from these and ignores scan_interval_seconds.
  adaptive_scan_enabled: boolean;
  stationary_interval_seconds: number;
  walking_interval_seconds: number;
  driving_interval_seconds: number;
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
  // Why the device is scanning at the cadence it is. Empty/null when the
  // device is older than this feature, or has adaptive cadence off.
  reported_motion_state: string;
  reported_effective_interval_seconds: number | null;
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
    | "adaptive_scan_enabled"
    | "stationary_interval_seconds"
    | "walking_interval_seconds"
    | "driving_interval_seconds"
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

/** Sent from the Android app's Settings > Diagnostics "Send to server"
 * button — see WhyfiApplication.kt's crash logger. sensor/sensor_name are
 * both null once the sensor that recorded this crash has been deleted with
 * "keep data" (same reasoning as ScanSession). */
export interface CrashReport {
  id: string;
  sensor: string | null;
  sensor_name: string | null;
  occurred_at: string;
  app_version: string;
  device_model: string;
  os_version: string;
  stack_trace: string;
  created_at: string;
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
  // Both null once the sensor that recorded this session has been deleted
  // with "keep data" (see SensorViewSet.destroy()'s on_conflict="keep_data")
  // — the scan session and its observations survive, just detached.
  sensor: string | null;
  sensor_name: string | null;
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

/** Where the backend estimated this device actually is, under the estimator
 * the caller asked for. `available: false` means that estimator couldn't run
 * for this device (no FTM readings, or a radio type with no signal model) and
 * `fell_back_to` names what produced the coordinates instead — so the UI can
 * say which algorithm a dot really came from. */
export interface EstimatedPosition {
  lat: number;
  lng: number;
  estimator: string;
  available: boolean;
  fell_back_to?: string | null;
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
  estimated_position?: EstimatedPosition | null;
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
  estimated_position?: EstimatedPosition | null;
}

// --- AP localization (ap-localization-design.md V4-V8) ---------------------

/** 95% confidence ellipse around a solved AP position. Null when there
 * aren't enough readings left over to estimate spread from (see
 * position_covariance in backend/scans/localization.py) — an honest "can't
 * say", not a zero-size ellipse. */
export interface PositionUncertainty {
  semi_major_m: number;
  semi_minor_m: number;
  /** Compass bearing of the semi-major axis, 0-180. */
  orientation_deg: number;
  confidence: number;
  rms_uncertainty_m: number;
}

/** One suggested next measurement spot, from the V6 geometric heuristic. */
export interface NextMeasurement {
  lat: number;
  lng: number;
  bearing_deg: number;
  distance_m: number;
  gap_deg: number;
  rationale: string;
}

/** AP *position* probability surface — distinct from the RSSI coverage
 * heatmap, which answers a different question (see HeatmapPage). Values are
 * peak-normalized relative likelihood, not absolute probability mass. */
export interface ProbabilityGrid {
  span_m: number;
  steps: number;
  cells: { lat: number; lng: number; relative_likelihood: number }[];
}

export interface FtmPosition {
  bssid: string;
  ssid?: string;
  available: boolean;
  sample_count: number;
  /** Only present when `available` — the solver needs 2+ distinct spots. */
  distinct_position_count?: number;
  reason?: string;
  lat?: number;
  lng?: number;
  rms_residual_m?: number;
  iterations?: number;
  uncertainty?: PositionUncertainty | null;
  probability_grid?: ProbabilityGrid | null;
  next_measurements?: NextMeasurement[];
  observations?: {
    scan_session_id: string;
    lat: number;
    lng: number;
    distance_m: number;
    weight: number;
  }[];
}

/** A group of BSSIDs that may be radios of one physical access point.
 * Deliberately a hypothesis with evidence and a confidence, never a hard
 * claim — see cluster_ap_hypotheses in backend/scans/localization.py. */
export interface MeshHypothesis {
  hypothesis_id: number;
  lat: number;
  lng: number;
  radio_count: number;
  confidence: number;
  is_multi_radio: boolean;
  evidence: string[];
  ssids: string[];
  bssids: string[];
}

/** One reading's agreement with the fitted position. `kind` distinguishes a
 * true fit residual (multilateration estimators, which predict a distance)
 * from plain distance-to-estimate (centroid/strongest, which have no distance
 * model) — the two are not comparable and must not be shown as if they were. */
export interface PositionResidual {
  lat: number;
  lng: number;
  weight: number;
  observed_at?: string | null;
  scan_session_id?: string | null;
  distance_to_estimate_m: number;
  measured_distance_m?: number;
  residual_m: number | null;
  kind: "fit_residual" | "distance_only";
}

export interface DevicePosition {
  identifier: string;
  radio_kind: string;
  ssid?: string;
  label?: string;
  estimator: string;
  available: boolean;
  reason?: string;
  fell_back_to?: string | null;
  lat: number | null;
  lng: number | null;
  sample_count: number;
  /** Readings dropped as geographic outliers before estimating — see
   * reject_outlying_readings. Non-zero means one identifier was recorded in
   * places too far apart to be the same transmitter. */
  discarded_outliers?: number;
  /** Distance from the operator-pinned true position, when one exists — the
   * number that says which estimator is actually right rather than merely
   * different. */
  error_m?: number | null;
  rms_residual_m?: number;
  residuals: PositionResidual[];
}

/** `?compare=1` — every estimator's answer for one device, plus how far apart
 * they land. The disagreement is the product: two estimators 3m apart means
 * the position is well determined; 200m apart means at least one is being
 * fooled. */
export interface PositionComparison {
  identifier: string;
  radio_kind: string;
  estimates: Record<string, DevicePosition>;
  disagreements: { a: string; b: string; distance_m: number }[];
  labels: Record<string, string>;
}

// --- Ground truth, benchmarking and calibration ---------------------------

/** An operator-asserted true position. `AP` pins where an access point really
 * is (used to score estimators and fit the path-loss model); `OBSERVER` pins
 * where the phone really was for one scan session, correcting a bad GPS fix.
 * Overlays the recorded data — never rewrites it. */
export interface GroundTruthPosition {
  id: number;
  kind: "AP" | "OBSERVER";
  target_key: string;
  latitude: number;
  longitude: number;
  label: string;
  note: string;
  /** Set when the pin was placed on a floor plan; latitude/longitude are
   * derived from these. Kept so the plan can redraw the pin where it was
   * clicked without inverting the transform. */
  floor_plan: number | null;
  image_x: number | null;
  image_y: number | null;
  created_at: string;
  updated_at: string;
}

export interface BenchmarkRow {
  bssid: string;
  ssid: string;
  label: string;
  sample_count: number;
  errors: Record<string, number | null>;
}

export interface BenchmarkSummaryEntry {
  mean_error_m: number;
  median_error_m: number;
  best_error_m: number;
  worst_error_m: number;
  scored_aps: number;
}

export interface LocalizationBenchmark {
  pinned_ap_count: number;
  scored_ap_count: number;
  results: BenchmarkRow[];
  summary: Record<string, BenchmarkSummaryEntry | null>;
  range_model_is_calibrated: boolean;
}

export interface FittedRangeModel {
  id: number;
  radio_kind: string;
  ref_rssi_at_1m: number;
  path_loss_exponent: number;
  r_squared: number;
  sample_count: number;
  min_distance_m: number;
  max_distance_m: number;
  is_active: boolean;
  fitted_at: string;
}

export interface CalibrationState {
  generic: Record<string, { ref_rssi_at_1m: number; path_loss_exponent: number }>;
  fitted: FittedRangeModel[];
}

export interface CalibrationFit {
  available: boolean;
  reason?: string;
  ref_rssi_at_1m?: number;
  path_loss_exponent?: number;
  plausible?: boolean;
  r_squared?: number;
  sample_count?: number;
  min_distance_m?: number;
  max_distance_m?: number;
}

export interface ExportBundle {
  sessions: unknown[];
  session_count: number;
  truncated: boolean;
  session_limit: number;
  exported_at: string;
}

export interface ImportResult {
  created: number;
  skipped: number;
  failed: { client_scan_id?: string; errors: unknown }[];
  failed_count: number;
}


// --- Floor-plan surveying -------------------------------------------------

/** An uploaded floor plan. `is_calibrated` means both anchor pairs are set,
 * which is what makes a click on the image mean a real position. */
export interface FloorPlan {
  id: number;
  name: string;
  image: string;
  image_width_px: number;
  image_height_px: number;
  anchor1_image_x: number | null;
  anchor1_image_y: number | null;
  anchor1_lat: number | null;
  anchor1_lng: number | null;
  anchor2_image_x: number | null;
  anchor2_image_y: number | null;
  anchor2_lat: number | null;
  anchor2_lng: number | null;
  /** The transform itself. Derived from the anchors on calibration, then
   * directly adjustable — two clicks on a map at house scale aren't precise
   * enough to get rotation right first time. */
  meters_per_pixel: number | null;
  /** Compass bearing of the plan's "up" direction; 0 = top points north. */
  bearing_deg: number | null;
  is_calibrated: boolean;
  placement_count: number;
  /** The building's footprint traced on the plan, as image-pixel vertices.
   * Empty means untraced, and everything falls back to the image rectangle.
   *
   * The image itself stays rectangular — this is not a crop. It's what the
   * building occupies *inside* that rectangle, which is what lets the map
   * footprint match an L-shaped house and keeps the interpolated heatmap off
   * the pixels that are garden. */
  outline_points: { x: number; y: number }[];
  /** Footprint in real coordinates: the traced outline when there is one,
   * otherwise the image's four corners clockwise from the top-left. Null
   * until calibrated. Drawn on the map so the calibration can be judged
   * against the building rather than by reading a bearing. */
  corners: { lat: number; lng: number }[] | null;
  created_at: string;
  updated_at: string;
}

/** Signal at each placed measurement point, in *pixel* coordinates so the
 * plan image can be drawn on directly. `no_coverage` distinguishes "the
 * network wasn't heard here at all" from "heard, but faint" — the former is
 * the worse finding. */
export interface FloorPlanCoveragePoint {
  scan_session_id: string;
  image_x: number;
  image_y: number;
  label: string;
  rssi: number | null;
  bssid: string | null;
  ssid: string | null;
  observed_at: string | null;
  is_weak: boolean;
  no_coverage: boolean;
}

/** Interpolated signal surface. A cell with `rssi: null` is beyond the
 * influence of any measurement and must be left unpainted — colouring it
 * would be claiming coverage that was never measured. */
export interface FloorPlanHeatmapCell {
  image_x: number;
  image_y: number;
  rssi: number | null;
  distance_px: number;
}

export interface FloorPlanHeatmap {
  cells: FloorPlanHeatmapCell[];
  steps: number;
  max_influence_px: number;
}

/** Where to move or add an access point to fix measured weak spots. A
 * heuristic over weak-spot clusters, not an optimiser — `rationale` says why,
 * and `action` distinguishes "move the one you have" from "you need another",
 * which is a decision about power and cabling the backend shouldn't make. */
export interface ApPlacementSuggestion {
  rank: number;
  action: "move" | "add";
  image_x: number;
  image_y: number;
  weak_point_count: number;
  dead_point_count: number;
  worst_rssi: number | null;
  nearest_ap_bssid: string | null;
  nearest_ap_distance_m: number | null;
  rationale: string;
}

/** One cell of the *predicted* surface. Unlike FloorPlanHeatmapCell this is
 * a model output, not an inference from nearby observations: it says where
 * the signal from `bssid` should reach, given how it fell off everywhere that
 * was measured. It therefore covers rooms nobody walked through — which is
 * both the reason to want it and the reason it must never be rendered as if
 * it were measured data. */
export interface FloorPlanPredictionCell {
  image_x: number;
  image_y: number;
  rssi: number;
  bssid: string;
  distance_m: number;
}

/** A transmitter the prediction radiates from, and how its falloff was
 * arrived at. `source: "fitted"` means the curve was fitted to real readings
 * on this plan; `"model"` means there weren't enough and generic indoor
 * constants were used instead. Worth surfacing: the two deserve different
 * amounts of trust. */
export interface FloorPlanPredictionSource {
  bssid: string;
  image_x: number;
  image_y: number;
  ref_rssi_at_1m: number;
  path_loss_exponent: number;
  source: "fitted" | "model";
  sample_count: number;
  r_squared: number | null;
}

export interface FloorPlanPrediction {
  cells: FloorPlanPredictionCell[];
  steps: number;
  max_range_m: number;
  sources: FloorPlanPredictionSource[];
}

export interface FloorPlanCoverage {
  ssids: string[];
  weak_threshold_dbm: number;
  points: FloorPlanCoveragePoint[];
  heatmap: FloorPlanHeatmap | null;
  /** Predicted coverage from placed access points. Null unless asked for, or
   * when no access point has been placed. */
  prediction: FloorPlanPrediction | null;
  placed_aps: { bssid: string; image_x: number; image_y: number; label: string }[];
  suggestions: ApPlacementSuggestion[];
  weak_count: number;
  measured_count: number;
}

/** A network actually audible at the plan's location, for the SSID picker. */
export interface NearbySsid {
  ssid: string;
  reading_count: number;
  best_rssi: number;
  bssid_count: number;
  bssids: string[];
  bands: string[];
}
