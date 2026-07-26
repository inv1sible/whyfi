export interface ScanTimelinePoint {
  lat: number;
  lng: number;
  weight: number;
  scanSessionId: string;
  observedAt?: string;
}

export interface CurrentScanResult {
  // Empty when there's nothing geotagged yet. Otherwise exactly the
  // reading(s) belonging to one scan session — normalizedWeight is
  // pre-computed against the *full* set of points passed in (not just
  // this one scan), so a single visible cell's size/color still means the
  // same thing as you scrub between scans. See RadioMap.tsx's MapPoint.
  points: { lat: number; lng: number; weight: number; normalizedWeight: number }[];
  label: string | null;
  // The one scan session the slider is currently resolved to — null when
  // there's nothing geotagged to scrub through. "Current scan only" mode
  // filters the map/chart/table down to exactly this session's row(s).
  scanSessionId: string | null;
  // That scan's timestamp — "Accumulate" mode includes every observation
  // at or before this moment (a timestamp cutoff rather than a session-id
  // set, so rows from sessions with no geotagged reading of their own are
  // still included/excluded sensibly). Null when there's no timeline;
  // callers treat null as "show everything".
  cutoffObservedAt: string | null;
}

// "Current scan only" display mode (see MapDisplayMode in
// FilterContext.tsx) — resolves the shared 0-100 scanIndexPercent slider
// position against this page's own chronological list of distinct scans,
// and returns just that one scan's reading(s) plus a human-readable label
// for the slider control rendered directly under the map.
export function resolveCurrentScan(points: ScanTimelinePoint[], percent: number): CurrentScanResult {
  if (points.length === 0) return { points: [], label: null, scanSessionId: null, cutoffObservedAt: null };

  const seen = new Set<string>();
  const distinct: ScanTimelinePoint[] = [];
  points.forEach((p) => {
    if (seen.has(p.scanSessionId)) return;
    seen.add(p.scanSessionId);
    distinct.push(p);
  });

  const weights = points.map((p) => p.weight);
  const minWeight = Math.min(...weights);
  const maxWeight = Math.max(...weights);
  const spread = maxWeight - minWeight || 1;

  const activeIndex = Math.round((percent / 100) * (distinct.length - 1));
  const current = distinct[activeIndex];

  const currentScanPoints = points.filter((p) => p.scanSessionId === current.scanSessionId);
  const timestamp = current.observedAt ? ` — ${new Date(current.observedAt).toLocaleString()}` : "";

  return {
    points: currentScanPoints.map((p) => ({
      lat: p.lat,
      lng: p.lng,
      weight: p.weight,
      normalizedWeight: (p.weight - minWeight) / spread,
    })),
    label: `Scan ${activeIndex + 1} of ${distinct.length}${timestamp}`,
    scanSessionId: current.scanSessionId,
    cutoffObservedAt: current.observedAt ?? null,
  };
}

interface DeviceCoveragePoint {
  lat: number;
  lng: number;
  weight: number;
  scan_session_id?: string;
  observed_at?: string;
}

// Multi-device variant for the combined Heatmap page — builds ONE shared
// chronological timeline of distinct scan sessions across every currently
// active device (a single real scan pass can observe several APs/towers/
// BLE devices at once, so "scan 3 of 12" means the same physical scan for
// all of them), then returns just that one scan's reading(s) per device.
// Each device's own contribution is normalized against that device's own
// full signal range (not the combined set — WiFi RSSI and cellular dBm
// aren't on the same scale).
export function resolveCurrentScanMultiDevice(
  devices: { points: DeviceCoveragePoint[] }[],
  percent: number,
): CurrentScanResult {
  const tagged = devices.flatMap((d) => d.points.filter((p): p is DeviceCoveragePoint & { scan_session_id: string } => !!p.scan_session_id));
  if (tagged.length === 0) return { points: [], label: null, scanSessionId: null, cutoffObservedAt: null };

  const sorted = [...tagged].sort((a, b) => (a.observed_at ?? "").localeCompare(b.observed_at ?? ""));
  const seen = new Set<string>();
  const distinct: typeof sorted = [];
  sorted.forEach((p) => {
    if (seen.has(p.scan_session_id)) return;
    seen.add(p.scan_session_id);
    distinct.push(p);
  });

  const activeIndex = Math.round((percent / 100) * (distinct.length - 1));
  const current = distinct[activeIndex];
  const timestamp = current.observed_at ? ` — ${new Date(current.observed_at).toLocaleString()}` : "";

  const points: CurrentScanResult["points"] = [];
  devices.forEach((device) => {
    const matching = device.points.filter((p) => p.scan_session_id === current.scan_session_id);
    if (matching.length === 0) return;
    const weights = device.points.map((p) => p.weight);
    const minWeight = Math.min(...weights);
    const maxWeight = Math.max(...weights);
    const spread = maxWeight - minWeight || 1;
    matching.forEach((p) => {
      points.push({ lat: p.lat, lng: p.lng, weight: p.weight, normalizedWeight: (p.weight - minWeight) / spread });
    });
  });

  return {
    points,
    label: `Scan ${activeIndex + 1} of ${distinct.length}${timestamp}`,
    scanSessionId: current.scan_session_id,
    cutoffObservedAt: current.observed_at ?? null,
  };
}
