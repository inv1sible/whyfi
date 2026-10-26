import type {
  AccessPoint,
  AccessPointCoverage,
  AppRelease,
  BLEDevice,
  BLEObservation,
  BuildStatusResponse,
  CappedList,
  CellObservation,
  CellTower,
  ChannelCongestionPoint,
  CalibrationFit,
  CalibrationState,
  CrashReport,
  DevicePosition,
  ExportBundle,
  FloorPlan,
  FloorPlanCoverage,
  NearbySsid,
  FtmPosition,
  GroundTruthPosition,
  ImportResult,
  LocalizationBenchmark,
  HeatmapPoint,
  HeatmapSource,
  LANDevice,
  LANObservation,
  MeshHypothesis,
  MonitorMission,
  Paginated,
  PositionComparison,
  ProbabilityGrid,
  RadioCoverage,
  SatelliteObservation,
  ScanSession,
  Sensor,
  SensorScanPolicy,
  SensorScanPolicyUpdate,
  SensorWithToken,
  WiFiObservation,
} from "./types";

// A page anywhere in the app can show "last updated" without prop-drilling
// a timestamp through every component — get() fires this on every
// successful read; NavBar (see components/layout/NavBar.tsx) listens.
export const LAST_UPDATED_EVENT = "whyfi:last-updated";

function notifyLastUpdated() {
  window.dispatchEvent(new CustomEvent<number>(LAST_UPDATED_EVENT, { detail: Date.now() }));
}

const STORAGE_KEY = "whyfi.backendUrl";

// An installed PWA has no build-time knowledge of which self-hosted backend
// it should talk to — the Settings page lets the user point it at a LAN
// host at runtime. Same-origin '/api/v1' is the default because the
// docker-compose deployment serves the SPA and the API from one origin.
//
// Note: session-cookie login (see login()/getSession() below) only works
// for that same-origin default. Pointing this PWA at a *different* backend
// origin here means requests become cross-origin, and the browser won't
// carry the session cookie there without CORS+credentials configured on
// that backend, which whyfi doesn't set up in v1 — see MEMORY.md.
export function getBackendUrlOverride(): string | null {
  return localStorage.getItem(STORAGE_KEY);
}

export function setBackendUrlOverride(url: string | null) {
  if (url) {
    localStorage.setItem(STORAGE_KEY, url.replace(/\/$/, ""));
  } else {
    localStorage.removeItem(STORAGE_KEY);
  }
}

function baseUrl(): string {
  const override = getBackendUrlOverride();
  return override ? `${override}/api/v1` : "/api/v1";
}

export class UnauthorizedError extends Error {}

// Carries the actual status + response body so callers can show something
// more useful than "something went wrong" — a generic catch-all message
// here was actively unhelpful for diagnosing a real failure once (see
// MEMORY.md), don't reintroduce one.
export class ApiError extends Error {
  status: number;
  body: unknown;

  constructor(status: number, body: unknown, path: string) {
    const detail = typeof body === "string" ? body : JSON.stringify(body);
    super(`whyfi API error ${status} for ${path}: ${detail}`);
    this.status = status;
    this.body = body;
  }
}

async function parseErrorBody(response: Response): Promise<unknown> {
  const text = await response.text();
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

// Django sets this cookie (see @ensure_csrf_cookie on /auth/session/, which
// the app calls on every load before anything else) — read it back for any
// session-authenticated POST. Login/logout don't need it (they run before
// a session exists), but the Android-build trigger does.
function getCsrfToken(): string | null {
  const match = document.cookie.match(/(?:^|; )csrftoken=([^;]*)/);
  return match ? decodeURIComponent(match[1]) : null;
}

async function get<T>(path: string, cache: RequestCache = "default"): Promise<T> {
  const response = await fetch(`${baseUrl()}${path}`, { credentials: "same-origin", cache });
  if (response.status === 401) throw new UnauthorizedError(`Not logged in for ${path}`);
  if (!response.ok) throw new ApiError(response.status, await parseErrorBody(response), path);
  notifyLastUpdated();
  return response.json() as Promise<T>;
}

async function post<T>(path: string, body?: unknown): Promise<T> {
  const csrfToken = getCsrfToken();
  const response = await fetch(`${baseUrl()}${path}`, {
    method: "POST",
    credentials: "same-origin",
    headers: {
      ...(body ? { "Content-Type": "application/json" } : {}),
      ...(csrfToken ? { "X-CSRFToken": csrfToken } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (response.status === 401) throw new UnauthorizedError(`Not logged in for ${path}`);
  if (!response.ok) throw new ApiError(response.status, await parseErrorBody(response), path);
  return response.json() as Promise<T>;
}

/** Multipart POST, for the one endpoint that takes a file (floor-plan
 * upload). Deliberately does NOT set Content-Type — the browser has to
 * generate the multipart boundary itself, and setting it by hand produces a
 * body Django can't parse. */
async function postForm<T>(path: string, form: FormData): Promise<T> {
  const csrfToken = getCsrfToken();
  const response = await fetch(`${baseUrl()}${path}`, {
    method: "POST",
    credentials: "same-origin",
    headers: { ...(csrfToken ? { "X-CSRFToken": csrfToken } : {}) },
    body: form,
  });
  if (response.status === 401) throw new UnauthorizedError(`Not logged in for ${path}`);
  if (!response.ok) throw new ApiError(response.status, await parseErrorBody(response), path);
  return response.json() as Promise<T>;
}

async function del<T>(path: string, body?: unknown): Promise<T> {
  const csrfToken = getCsrfToken();
  const response = await fetch(`${baseUrl()}${path}`, {
    method: "DELETE",
    credentials: "same-origin",
    headers: {
      ...(body ? { "Content-Type": "application/json" } : {}),
      ...(csrfToken ? { "X-CSRFToken": csrfToken } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (response.status === 401) throw new UnauthorizedError(`Not logged in for ${path}`);
  if (!response.ok) throw new ApiError(response.status, await parseErrorBody(response), path);
  // DRF's default destroy() (used by e.g. DELETE /sensors/{id}/) returns 204
  // with no body — response.json() on that throws a JSON parse error, so
  // callers expecting Promise<void> get undefined instead of a crash.
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

/**
 * Downloads a file (the APK) with progress reporting, and returns the
 * fully-assembled Blob rather than handing the browser a bare `<a href>`
 * navigation. Two concrete reasons, not just for the progress bar:
 * 1. A plain anchor download gives the page no way to know if the transfer
 *    was truncated/corrupted (e.g. by a flaky mobile connection or a
 *    reverse proxy mishandling a large binary response) — Android's
 *    installer would just fail with an unhelpful "app not installed" and
 *    you'd have no idea why. Here we compare the received byte count
 *    against the server-reported size before treating it as done.
 * 2. `url` is an absolute URL (from the API's `download_url` field, already
 *    including scheme+host) — deliberately not run through `baseUrl()`.
 */
export async function downloadWithProgress(
  url: string,
  onProgress: (receivedBytes: number, totalBytes: number) => void,
): Promise<Blob> {
  const response = await fetch(url, { credentials: "same-origin" });
  if (!response.ok || !response.body) {
    throw new Error(`Download failed: HTTP ${response.status}`);
  }

  const totalBytes = Number(response.headers.get("Content-Length") ?? 0);
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let receivedBytes = 0;

  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    chunks.push(value);
    receivedBytes += value.length;
    onProgress(receivedBytes, totalBytes);
  }

  return new Blob(chunks as BlobPart[], { type: "application/vnd.android.package-archive" });
}

/** The map's focus circle. Devices are kept when their *estimated* position
 * (the signal-weighted centre of everywhere they were heard — see
 * weightedCentroid in geo.ts) falls inside it, not when a single reading
 * happens to. */
export interface FocusArea {
  lat: number;
  lng: number;
  radiusM: number;
}

/** Query fragment for the focus circle, or "" when none is set.
 *
 * Always emitted as a suffix with a leading "&" so it can be appended to any
 * of the hand-built query strings across the pages without each of them
 * having to reason about whether it's the first parameter. Callers that start
 * a query with it are responsible for the "?".
 */
export function areaQuery(area: FocusArea | null | undefined): string {
  if (!area) return "";
  return `&area_lat=${area.lat}&area_lng=${area.lng}&area_radius_m=${Math.round(area.radiusM)}`;
}

/** Query fragment for the server-side search box (see apply_search() in
 * scans/views.py) — `&`-prefixed, or "" for an empty query, same contract
 * as areaQuery. */
export function searchQueryPart(query: string): string {
  const trimmed = query.trim();
  return trimmed ? `&q=${encodeURIComponent(trimmed)}` : "";
}

/** `session_limit` and an explicit time window are mutually exclusive (as
 * they are in every backend view that accepts both) — only one is ever sent. */
function sinceUntilParts(opts: { since?: string; until?: string; sessionLimit?: number }): string[] {
  if (opts.sessionLimit) return [`session_limit=${opts.sessionLimit}`];
  const parts: string[] = [];
  if (opts.since) parts.push(`since=${encodeURIComponent(opts.since)}`);
  if (opts.until) parts.push(`until=${encodeURIComponent(opts.until)}`);
  return parts;
}

/** The since/until/session_limit trio as a query-string suffix, `&`-prefixed
 * (or empty) so it can be appended directly after an existing `?...`. Used by
 * the per-entity observation-history endpoints, which — unlike the
 * coverage/list endpoints `windowQuery` serves — take no `area`. */
function sinceUntilQuery(opts: { since?: string; until?: string; sessionLimit?: number }): string {
  const parts = sinceUntilParts(opts);
  return parts.length > 0 ? `&${parts.join("&")}` : "";
}

/** The shared time window + focus area, as query parameters.
 *
 * `session_limit` and an explicit time window are mutually exclusive here (as
 * they are in several of the backend views), so only one is sent — but the
 * area is orthogonal to both and always goes along.
 */
function windowQuery(opts: {
  since?: string;
  until?: string;
  sessionLimit?: number;
  area?: FocusArea | null;
  estimator?: string;
  use_ground_truth?: string;
}): string {
  const parts = sinceUntilParts(opts);
  if (opts.area) {
    parts.push(`area_lat=${opts.area.lat}`, `area_lng=${opts.area.lng}`, `area_radius_m=${Math.round(opts.area.radiusM)}`);
  }
  // Which estimator produced each result's `estimated_position`. Sent by the
  // coverage callers so the map's estimated-location markers honour the
  // user's global setting (see estimatorPreference.ts).
  if (opts.estimator) parts.push(`estimator=${encodeURIComponent(opts.estimator)}`);
  // Only ever sent as "0" to opt *out* of pinned observer positions; omitted
  // means the corrected positions apply (see ground_truth_overrides).
  if (opts.use_ground_truth) parts.push(`use_ground_truth=${opts.use_ground_truth}`);
  return parts.join("&");
}

export interface PositionOpts {
  since?: string;
  until?: string;
  sessionLimit?: number;
  estimator?: string;
  use_ground_truth?: string;
}

function positionQuery(opts: PositionOpts): string {
  return windowQuery(opts);
}

export const api = {
  monitorMissions: () => get<MonitorMission[]>("/monitor-missions/"),
  health: () => get<{ status: string }>("/health/"),

  session: () => get<{ authenticated: boolean; username?: string }>("/auth/session/"),
  login: (username: string, password: string) =>
    post<{ authenticated: boolean; username: string }>("/auth/login/", { username, password }),
  logout: () => post<{ detail: string }>("/auth/logout/"),

  sensors: () => get<Paginated<Sensor>>("/sensors/"),
  createSensor: (name: string, sensorType = "android") =>
    post<SensorWithToken>("/sensors/", { name, sensor_type: sensorType }),
  regenerateSensorToken: (id: string) => post<SensorWithToken>(`/sensors/${id}/regenerate-token/`),
  setSensorActive: (id: string, isActive: boolean) =>
    post<Sensor>(`/sensors/${id}/set-active/`, { is_active: isActive }),
  deleteSensor: (id: string, opts: { onConflict?: "delete_data" | "keep_data" } = {}) =>
    del<void>(`/sensors/${id}/`, opts.onConflict ? { on_conflict: opts.onConflict } : undefined),

  crashReports: (query = "") => get<Paginated<CrashReport>>(`/crash-reports/${query}`),
  deleteCrashReport: (id: string) => del<void>(`/crash-reports/${id}/`),
  setSensorScanPolicy: (id: string, patch: SensorScanPolicyUpdate) =>
    post<SensorScanPolicy>(`/sensors/${id}/scan-policy/`, patch),
  sensorScanNow: (id: string) => post<SensorScanPolicy>(`/sensors/${id}/scan-now/`),
  resetSensorCounters: (id: string) => post<SensorScanPolicy>(`/sensors/${id}/reset-counters/`),

  accessPoints: (query = "") => get<Paginated<AccessPoint>>(`/access-points/${query}`),
  accessPoint: (bssid: string) => get<AccessPoint>(`/access-points/${encodeURIComponent(bssid)}/`),
  wifiObservationsForAp: (
    bssid: string,
    opts: { since?: string; until?: string; sessionLimit?: number; limit?: number } = {},
  ) =>
    get<WiFiObservation[]>(
      `/access-points/${encodeURIComponent(bssid)}/wifi-observations/?limit=${opts.limit ?? 200}` +
        sinceUntilQuery(opts),
    ),
  // Coverage/heatmap responses are CappedList envelopes, not bare arrays —
  // read `.results`, and show the user something when `.truncated` is set.
  accessPointsCoverage: (
    opts: { since?: string; until?: string; sessionLimit?: number; area?: FocusArea | null; ssidExact?: string; estimator?: string; use_ground_truth?: string } = {},
  ) =>
    get<CappedList<AccessPointCoverage>>(
      `/access-points/coverage/?${windowQuery(opts)}` +
        `${opts.ssidExact ? `&ssid_exact=${encodeURIComponent(opts.ssidExact)}` : ""}`,
    ),

  // Estimated position of one device under a chosen estimator, or every
  // estimator side by side (`compare`). See backend/scans/estimators.py.
  accessPointPosition: (bssid: string, opts: PositionOpts = {}) =>
    get<DevicePosition>(`/access-points/${encodeURIComponent(bssid)}/position/?${positionQuery(opts)}`),
  accessPointPositionGrid: (bssid: string, opts: PositionOpts & { gridSteps?: number; gridSpanM?: number } = {}) =>
    get<DevicePosition & { probability_grid?: ProbabilityGrid | null }>(
      `/access-points/${encodeURIComponent(bssid)}/position/?include_grid=1&${positionQuery(opts)}` +
        `${opts.gridSteps ? `&grid_steps=${opts.gridSteps}` : ""}${opts.gridSpanM ? `&grid_span_m=${opts.gridSpanM}` : ""}`,
    ),
  accessPointPositionComparison: (bssid: string, opts: PositionOpts = {}) =>
    get<PositionComparison>(`/access-points/${encodeURIComponent(bssid)}/position/?compare=1&${positionQuery(opts)}`),
  cellTowerPosition: (towerKey: string, opts: PositionOpts = {}) =>
    get<DevicePosition>(`/cell-towers/${encodeURIComponent(towerKey)}/position/?${positionQuery(opts)}`),
  bleDevicePosition: (deviceKey: string, opts: PositionOpts = {}) =>
    get<DevicePosition>(`/ble-devices/${encodeURIComponent(deviceKey)}/position/?${positionQuery(opts)}`),

  // AP localization (ap-localization-design.md V4-V8). The probability grid
  // is opt-in because it's by far the biggest part of the payload — a 25x25
  // grid is 625 cells, and nothing needs it unless the heatmap is on screen.
  ftmPosition: (bssid: string, opts: { includeGrid?: boolean; gridSteps?: number; gridSpanM?: number } = {}) =>
    get<FtmPosition>(
      `/access-points/${encodeURIComponent(bssid)}/ftm-position/?` +
        `${opts.includeGrid ? "include_grid=1" : ""}` +
        `${opts.gridSteps ? `&grid_steps=${opts.gridSteps}` : ""}` +
        `${opts.gridSpanM ? `&grid_span_m=${opts.gridSpanM}` : ""}`,
    ),
  meshGroups: (opts: { since?: string; until?: string; sessionLimit?: number; ssidExact?: string } = {}) =>
    get<CappedList<MeshHypothesis>>(
      `/access-points/mesh-groups/?${windowQuery(opts)}` +
        `${opts.ssidExact ? `&ssid_exact=${encodeURIComponent(opts.ssidExact)}` : ""}`,
    ),

  floorPlans: () => get<Paginated<FloorPlan>>("/floor-plans/"),
  uploadFloorPlan: (form: FormData) => postForm<FloorPlan>("/floor-plans/", form),
  deleteFloorPlan: (id: number) => del<void>(`/floor-plans/${id}/`),
  calibrateFloorPlan: (id: number, anchors: Record<string, number>) =>
    post<FloorPlan>(`/floor-plans/${id}/calibrate/`, anchors),
  resetFloorPlanCalibration: (id: number) => post<FloorPlan>(`/floor-plans/${id}/reset-calibration/`),
  adjustFloorPlan: (id: number, patch: { bearing_deg?: number; meters_per_pixel?: number }) =>
    post<FloorPlan>(`/floor-plans/${id}/adjust/`, patch),
  /** Stores the traced building footprint. Points are either image pixels
   * ({x, y}, traced on the plan drawing) or real coordinates ({lat, lng},
   * traced on the map) — the backend converts the latter, so the transform
   * has one implementation and the stored form is always pixels. An empty
   * array clears it, reverting to the image's own rectangle. */
  saveFloorPlanOutline: (
    id: number,
    points: { x: number; y: number }[] | { lat: number; lng: number }[],
  ) => post<FloorPlan>(`/floor-plans/${id}/outline/`, { points }),
  floorPlanNearbySsids: (id: number, radiusM = 150) =>
    get<{ radius_m: number; results: NearbySsid[] }>(`/floor-plans/${id}/nearby-ssids/?radius_m=${radiusM}`),
  floorPlanCoverage: (
    id: number,
    ssids: string[],
    weakThresholdDbm: number,
    opts: { includeHeatmap?: boolean; includePrediction?: boolean; heatmapSteps?: number } = {},
  ) =>
    get<FloorPlanCoverage>(
      `/floor-plans/${id}/coverage/?` +
        ssids.map((s) => `ssid_exact=${encodeURIComponent(s)}`).join("&") +
        `&weak_threshold_dbm=${weakThresholdDbm}` +
        `${opts.includeHeatmap ? "&include_heatmap=1" : ""}` +
        `${opts.includePrediction ? "&include_prediction=1" : ""}` +
        `${opts.heatmapSteps ? `&heatmap_steps=${opts.heatmapSteps}` : ""}`,
    ),

  groundTruth: (params = "") => get<Paginated<GroundTruthPosition>>(`/ground-truth/${params}`),
  // Either an explicit lat/lng (map click) or floor-plan pixel coordinates,
  // in which case the backend derives the real position — see
  // GroundTruthViewSet.create.
  saveGroundTruth: (pin: {
    kind: "AP" | "OBSERVER";
    target_key: string;
    latitude?: number;
    longitude?: number;
    label?: string;
    note?: string;
    floor_plan?: number;
    image_x?: number;
    image_y?: number;
  }) => post<GroundTruthPosition>("/ground-truth/", pin),
  deleteGroundTruth: (id: number) => del<void>(`/ground-truth/${id}/`),

  localizationBenchmark: () => get<LocalizationBenchmark>("/localization/benchmark/"),
  calibration: () => get<CalibrationState>("/calibration/"),
  fitCalibration: (activate: boolean) =>
    post<{ fit: CalibrationFit }>("/calibration/", { radio_kind: "wifi", activate }),
  setCalibrationActive: (isActive: boolean) =>
    post<{ is_active: boolean }>("/calibration/activate/", { radio_kind: "wifi", is_active: isActive }),

  exportScanSessions: (opts: { since?: string; until?: string; sessionLimit?: number; ssidExact?: string } = {}) =>
    get<ExportBundle>(
      `/scan-sessions/export/?${windowQuery(opts)}` +
        `${opts.ssidExact ? `&ssid_exact=${encodeURIComponent(opts.ssidExact)}` : ""}`,
    ),
  importScanSessions: (bundle: unknown) => post<ImportResult>("/scan-sessions/import_sessions/", bundle),

  channelCongestion: (band: string, opts: { since?: string; until?: string; sessionLimit?: number } = {}) =>
    get<ChannelCongestionPoint[]>(
      `/channel-congestion/?band=${encodeURIComponent(band)}` + sinceUntilQuery(opts),
    ),

  cellObservations: (query = "") => get<Paginated<CellObservation>>(`/cell-observations/${query}`),

  cellTowers: (query = "") => get<Paginated<CellTower>>(`/cell-towers/${query}`),
  cellTower: (towerKey: string) => get<CellTower>(`/cell-towers/${encodeURIComponent(towerKey)}/`),
  cellObservationsForTower: (
    towerKey: string,
    opts: { since?: string; until?: string; sessionLimit?: number; limit?: number } = {},
  ) =>
    get<CellObservation[]>(
      `/cell-towers/${encodeURIComponent(towerKey)}/cell-observations/?limit=${opts.limit ?? 200}` +
        sinceUntilQuery(opts),
    ),
  cellTowersCoverage: (opts: { since?: string; until?: string; sessionLimit?: number; area?: FocusArea | null; estimator?: string } = {}) =>
    get<CappedList<RadioCoverage>>(`/cell-towers/coverage/?${windowQuery(opts)}`),

  bleObservations: (query = "") => get<Paginated<BLEObservation>>(`/ble-observations/${query}`),
  bleObservationsCoverage: (
    opts: { since?: string; until?: string; sessionLimit?: number; area?: FocusArea | null; estimator?: string } = {},
  ) => get<CappedList<RadioCoverage>>(`/ble-observations/coverage/?${windowQuery(opts)}`),

  bleDevices: (query = "") => get<Paginated<BLEDevice>>(`/ble-devices/${query}`),
  bleDevice: (deviceKey: string) => get<BLEDevice>(`/ble-devices/${encodeURIComponent(deviceKey)}/`),
  bleObservationsForDevice: (
    deviceKey: string,
    opts: { since?: string; until?: string; sessionLimit?: number; limit?: number } = {},
  ) =>
    get<BLEObservation[]>(
      `/ble-devices/${encodeURIComponent(deviceKey)}/ble-observations/?limit=${opts.limit ?? 200}` +
        sinceUntilQuery(opts),
    ),

  satelliteObservations: (query = "") => get<Paginated<SatelliteObservation>>(`/satellite-observations/${query}`),

  lanObservations: (query = "") => get<Paginated<LANObservation>>(`/lan-observations/${query}`),
  lanObservation: (id: number) => get<LANObservation>(`/lan-observations/${id}/`),

  lanDevices: (query = "") => get<Paginated<LANDevice>>(`/lan-devices/${query}`),
  lanDevice: (ipAddress: string) => get<LANDevice>(`/lan-devices/${encodeURIComponent(ipAddress)}/`),
  lanObservationsForDevice: (
    ipAddress: string,
    opts: { since?: string; until?: string; sessionLimit?: number; limit?: number } = {},
  ) =>
    get<LANObservation[]>(
      `/lan-devices/${encodeURIComponent(ipAddress)}/lan-observations/?limit=${opts.limit ?? 200}` +
        sinceUntilQuery(opts),
    ),

  scanSessions: (query = "") => get<Paginated<ScanSession>>(`/scan-sessions/${query}`),
  bulkDeleteScanSessions: (ids: string[]) => del<{ deleted: number }>("/scan-sessions/bulk-delete/", { ids }),
  resolveScanAddresses: (limit = 20) =>
    post<{ resolved: number }>("/scan-sessions/resolve-addresses/", { limit }),

  heatmap: (source: HeatmapSource, opts: { bounds?: string; since?: string; sessionLimit?: number } = {}) =>
    get<CappedList<HeatmapPoint>>(
      `/heatmap/?source=${source}${opts.bounds ? `&bounds=${opts.bounds}` : ""}` +
        `${opts.sessionLimit ? `&session_limit=${opts.sessionLimit}` : opts.since ? `&since=${opts.since}` : ""}`,
    ),

  latestRelease: () => get<AppRelease>("/app/latest/", "no-store"),

  triggerAndroidBuild: (versionName?: string) =>
    post<AppRelease>("/android-build/trigger/", versionName ? { version_name: versionName } : {}),
  androidBuildStatus: () => get<BuildStatusResponse>("/android-build/status/"),
};
