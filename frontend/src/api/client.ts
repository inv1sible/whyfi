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
  HeatmapPoint,
  HeatmapSource,
  LANDevice,
  LANObservation,
  Paginated,
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

async function get<T>(path: string): Promise<T> {
  const response = await fetch(`${baseUrl()}${path}`, { credentials: "same-origin" });
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
}): string {
  const parts = sinceUntilParts(opts);
  if (opts.area) {
    parts.push(`area_lat=${opts.area.lat}`, `area_lng=${opts.area.lng}`, `area_radius_m=${Math.round(opts.area.radiusM)}`);
  }
  return parts.join("&");
}

export const api = {
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
  deleteSensor: (id: string, opts: { deleteData?: boolean } = {}) =>
    del<void>(`/sensors/${id}/`, opts.deleteData ? { delete_data: true } : undefined),
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
    opts: { since?: string; until?: string; sessionLimit?: number; area?: FocusArea | null; ssidExact?: string } = {},
  ) =>
    get<CappedList<AccessPointCoverage>>(
      `/access-points/coverage/?${windowQuery(opts)}` +
        `${opts.ssidExact ? `&ssid_exact=${encodeURIComponent(opts.ssidExact)}` : ""}`,
    ),

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
  cellTowersCoverage: (opts: { since?: string; until?: string; sessionLimit?: number; area?: FocusArea | null } = {}) =>
    get<CappedList<RadioCoverage>>(`/cell-towers/coverage/?${windowQuery(opts)}`),

  bleObservations: (query = "") => get<Paginated<BLEObservation>>(`/ble-observations/${query}`),
  bleObservationsCoverage: (
    opts: { since?: string; until?: string; sessionLimit?: number; area?: FocusArea | null } = {},
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

  latestRelease: () => get<AppRelease>("/app/latest/"),

  triggerAndroidBuild: (versionName?: string) =>
    post<AppRelease>("/android-build/trigger/", versionName ? { version_name: versionName } : {}),
  androidBuildStatus: () => get<BuildStatusResponse>("/android-build/status/"),
};
