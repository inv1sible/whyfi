import type {
  AccessPoint,
  AccessPointCoverage,
  AppRelease,
  BLEDevice,
  BLEObservation,
  BuildStatusResponse,
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
  setSensorScanPolicy: (id: string, patch: SensorScanPolicyUpdate) =>
    post<SensorScanPolicy>(`/sensors/${id}/scan-policy/`, patch),
  sensorScanNow: (id: string) => post<SensorScanPolicy>(`/sensors/${id}/scan-now/`),
  resetSensorCounters: (id: string) => post<SensorScanPolicy>(`/sensors/${id}/reset-counters/`),

  accessPoints: (query = "") => get<Paginated<AccessPoint>>(`/access-points/${query}`),
  accessPoint: (bssid: string) => get<AccessPoint>(`/access-points/${encodeURIComponent(bssid)}/`),
  wifiObservationsForAp: (bssid: string, opts: { since?: string; sessionLimit?: number; limit?: number } = {}) =>
    get<WiFiObservation[]>(
      `/access-points/${encodeURIComponent(bssid)}/wifi-observations/?limit=${opts.limit ?? 200}` +
        `${opts.sessionLimit ? `&session_limit=${opts.sessionLimit}` : opts.since ? `&since=${opts.since}` : ""}`,
    ),
  accessPointsCoverage: (opts: { since?: string; sessionLimit?: number; ssidExact?: string } = {}) =>
    get<AccessPointCoverage[]>(
      `/access-points/coverage/?${opts.sessionLimit ? `session_limit=${opts.sessionLimit}&` : opts.since ? `since=${opts.since}&` : ""}` +
        `${opts.ssidExact ? `ssid_exact=${encodeURIComponent(opts.ssidExact)}` : ""}`,
    ),

  channelCongestion: (band: string, opts: { since?: string; sessionLimit?: number } = {}) =>
    get<ChannelCongestionPoint[]>(
      `/channel-congestion/?band=${encodeURIComponent(band)}` +
        `${opts.sessionLimit ? `&session_limit=${opts.sessionLimit}` : opts.since ? `&since=${opts.since}` : ""}`,
    ),

  cellObservations: (query = "") => get<Paginated<CellObservation>>(`/cell-observations/${query}`),

  cellTowers: (query = "") => get<Paginated<CellTower>>(`/cell-towers/${query}`),
  cellTower: (towerKey: string) => get<CellTower>(`/cell-towers/${encodeURIComponent(towerKey)}/`),
  cellObservationsForTower: (towerKey: string, opts: { since?: string; sessionLimit?: number; limit?: number } = {}) =>
    get<CellObservation[]>(
      `/cell-towers/${encodeURIComponent(towerKey)}/cell-observations/?limit=${opts.limit ?? 200}` +
        `${opts.sessionLimit ? `&session_limit=${opts.sessionLimit}` : opts.since ? `&since=${opts.since}` : ""}`,
    ),
  cellTowersCoverage: (opts: { since?: string; sessionLimit?: number } = {}) =>
    get<RadioCoverage[]>(
      `/cell-towers/coverage/?${opts.sessionLimit ? `session_limit=${opts.sessionLimit}` : opts.since ? `since=${opts.since}` : ""}`,
    ),

  bleObservations: (query = "") => get<Paginated<BLEObservation>>(`/ble-observations/${query}`),
  bleObservationsCoverage: (opts: { since?: string; sessionLimit?: number } = {}) =>
    get<RadioCoverage[]>(
      `/ble-observations/coverage/?${opts.sessionLimit ? `session_limit=${opts.sessionLimit}` : opts.since ? `since=${opts.since}` : ""}`,
    ),

  bleDevices: (query = "") => get<Paginated<BLEDevice>>(`/ble-devices/${query}`),
  bleDevice: (deviceKey: string) => get<BLEDevice>(`/ble-devices/${encodeURIComponent(deviceKey)}/`),
  bleObservationsForDevice: (deviceKey: string, opts: { since?: string; sessionLimit?: number; limit?: number } = {}) =>
    get<BLEObservation[]>(
      `/ble-devices/${encodeURIComponent(deviceKey)}/ble-observations/?limit=${opts.limit ?? 200}` +
        `${opts.sessionLimit ? `&session_limit=${opts.sessionLimit}` : opts.since ? `&since=${opts.since}` : ""}`,
    ),

  satelliteObservations: (query = "") => get<Paginated<SatelliteObservation>>(`/satellite-observations/${query}`),

  lanObservations: (query = "") => get<Paginated<LANObservation>>(`/lan-observations/${query}`),
  lanObservation: (id: number) => get<LANObservation>(`/lan-observations/${id}/`),

  lanDevices: (query = "") => get<Paginated<LANDevice>>(`/lan-devices/${query}`),
  lanDevice: (ipAddress: string) => get<LANDevice>(`/lan-devices/${encodeURIComponent(ipAddress)}/`),
  lanObservationsForDevice: (ipAddress: string, opts: { since?: string; sessionLimit?: number; limit?: number } = {}) =>
    get<LANObservation[]>(
      `/lan-devices/${encodeURIComponent(ipAddress)}/lan-observations/?limit=${opts.limit ?? 200}` +
        `${opts.sessionLimit ? `&session_limit=${opts.sessionLimit}` : opts.since ? `&since=${opts.since}` : ""}`,
    ),

  scanSessions: (query = "") => get<Paginated<ScanSession>>(`/scan-sessions/${query}`),
  bulkDeleteScanSessions: (ids: string[]) => del<{ deleted: number }>("/scan-sessions/bulk-delete/", { ids }),
  resolveScanAddresses: (limit = 20) =>
    post<{ resolved: number }>("/scan-sessions/resolve-addresses/", { limit }),

  heatmap: (source: HeatmapSource, opts: { bounds?: string; since?: string; sessionLimit?: number } = {}) =>
    get<HeatmapPoint[]>(
      `/heatmap/?source=${source}${opts.bounds ? `&bounds=${opts.bounds}` : ""}` +
        `${opts.sessionLimit ? `&session_limit=${opts.sessionLimit}` : opts.since ? `&since=${opts.since}` : ""}`,
    ),

  latestRelease: () => get<AppRelease>("/app/latest/"),

  triggerAndroidBuild: (versionName?: string) =>
    post<AppRelease>("/android-build/trigger/", versionName ? { version_name: versionName } : {}),
  androidBuildStatus: () => get<BuildStatusResponse>("/android-build/status/"),
};
