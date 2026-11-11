/** The focus area: a circle or a polygon that every list, map and report is
 * narrowed to (user, 2026-11-11: "location picker and a region circle or
 * polygon"). Devices are kept when their *estimated* position (the
 * signal-weighted centre of everywhere they were heard, see weightedCentroid
 * in geo.ts) falls inside it — see the backend's scans/area.py.
 *
 * Pure helpers only; the map draws it (RadioMap) and FilterContext keeps it. */

export interface LatLng {
  lat: number;
  lng: number;
}

export type FocusArea =
  | { kind: "circle"; lat: number; lng: number; radiusM: number }
  | { kind: "polygon"; points: LatLng[] };

export const DEFAULT_AREA_RADIUS_M = 250;
export const MIN_POLYGON_CORNERS = 3;
export const MAX_POLYGON_CORNERS = 200;

const round6 = (n: number) => Number(n.toFixed(6));

export function circleArea(lat: number, lng: number, radiusM: number): FocusArea {
  return { kind: "circle", lat: round6(lat), lng: round6(lng), radiusM: Math.max(1, Math.round(radiusM)) };
}

export function polygonArea(points: LatLng[]): FocusArea | null {
  if (points.length < MIN_POLYGON_CORNERS || points.length > MAX_POLYGON_CORNERS) return null;
  return { kind: "polygon", points: points.map((p) => ({ lat: round6(p.lat), lng: round6(p.lng) })) };
}

/** Query fragment, "&"-prefixed, or "" when no area is set — appendable to
 * any hand-built query string. The backend reads area_lat/area_lng/
 * area_radius_m for a circle and area_polygon=lat,lng;… for a polygon. */
export function areaQuery(area: FocusArea | null | undefined): string {
  if (!area) return "";
  if (area.kind === "circle") return `&area_lat=${area.lat}&area_lng=${area.lng}&area_radius_m=${Math.round(area.radiusM)}`;
  return `&area_polygon=${area.points.map((p) => `${p.lat},${p.lng}`).join(";")}`;
}

/** Plain average of the corners — where to centre the map, not a true centroid. */
export function areaCenter(area: FocusArea): LatLng {
  if (area.kind === "circle") return { lat: area.lat, lng: area.lng };
  const n = area.points.length;
  return {
    lat: area.points.reduce((s, p) => s + p.lat, 0) / n,
    lng: area.points.reduce((s, p) => s + p.lng, 0) / n,
  };
}

export function areaBounds(area: FocusArea): { south: number; west: number; north: number; east: number } {
  if (area.kind === "circle") {
    const dLat = area.radiusM / 111_320;
    const dLng = area.radiusM / (111_320 * Math.max(Math.cos((area.lat * Math.PI) / 180), 0.01));
    return { south: area.lat - dLat, west: area.lng - dLng, north: area.lat + dLat, east: area.lng + dLng };
  }
  const lats = area.points.map((p) => p.lat);
  const lngs = area.points.map((p) => p.lng);
  return { south: Math.min(...lats), west: Math.min(...lngs), north: Math.max(...lats), east: Math.max(...lngs) };
}

function coords(lat: number, lng: number): string {
  return `${lat.toFixed(5)}, ${lng.toFixed(5)}`;
}

/** One line for hints and report headers: "250 m around 52.52000, 13.40500"
 * or "polygon with 5 corners around …". */
export function describeArea(area: FocusArea): string {
  if (area.kind === "circle") return `${Math.round(area.radiusM)} m around ${coords(area.lat, area.lng)}`;
  const c = areaCenter(area);
  return `polygon with ${area.points.length} corners around ${coords(c.lat, c.lng)}`;
}

/** Compact text for the URL (?area=) and local storage:
 * "c:lat,lng,radius" or "p:lat,lng;lat,lng;…". */
export function encodeArea(area: FocusArea): string {
  if (area.kind === "circle") return `c:${area.lat},${area.lng},${Math.round(area.radiusM)}`;
  return `p:${area.points.map((p) => `${p.lat},${p.lng}`).join(";")}`;
}

function validLatLng(lat: number, lng: number): boolean {
  return Number.isFinite(lat) && Number.isFinite(lng) && Math.abs(lat) <= 90 && Math.abs(lng) <= 180;
}

export function decodeArea(text: string | null | undefined): FocusArea | null {
  if (!text) return null;
  if (text.startsWith("c:")) {
    const [lat, lng, radius] = text.slice(2).split(",").map(Number);
    return validLatLng(lat, lng) && radius > 0 ? circleArea(lat, lng, radius) : null;
  }
  if (text.startsWith("p:")) {
    const points: LatLng[] = [];
    for (const pair of text.slice(2).split(";")) {
      const [lat, lng] = pair.split(",").map(Number);
      if (!validLatLng(lat, lng)) return null;
      points.push({ lat, lng });
    }
    return polygonArea(points);
  }
  return null;
}

/** "52.52, 13.405", "52.52 13.405", "52.52N 13.405E", "N 52.52 E 13.405" or
 * "52°31'12\"N 13°24'18\"E" → a point, else null. Typed or pasted coordinates
 * are resolved here, without asking anyone. */
export function parseCoordinates(text: string): LatLng | null {
  const t = text.trim().toUpperCase();
  if (!t) return null;
  const dms = /(\d{1,3})°\s*(\d{1,2})?'?\s*(\d{1,2}(?:\.\d+)?)?"?\s*([NSEW])/g;
  const parts: { value: number; hemi: string }[] = [];
  let m: RegExpExecArray | null;
  while ((m = dms.exec(t)) !== null) {
    const value = Number(m[1]) + Number(m[2] ?? 0) / 60 + Number(m[3] ?? 0) / 3600;
    parts.push({ value, hemi: m[4] });
  }
  if (parts.length === 2) {
    const lat = parts.find((p) => p.hemi === "N" || p.hemi === "S");
    const lng = parts.find((p) => p.hemi === "E" || p.hemi === "W");
    if (lat && lng) {
      const point = { lat: lat.hemi === "S" ? -lat.value : lat.value, lng: lng.hemi === "W" ? -lng.value : lng.value };
      return validLatLng(point.lat, point.lng) ? point : null;
    }
  }
  const decimal = /^\s*([NS])?\s*(-?\d{1,2}(?:\.\d+)?)\s*°?\s*([NS])?\s*[,;\s]\s*([EW])?\s*(-?\d{1,3}(?:\.\d+)?)\s*°?\s*([EW])?\s*$/;
  const d = decimal.exec(t);
  if (!d) return null;
  let lat = Number(d[2]);
  let lng = Number(d[5]);
  if (d[1] === "S" || d[3] === "S") lat = -Math.abs(lat);
  if (d[4] === "W" || d[6] === "W") lng = -Math.abs(lng);
  return validLatLng(lat, lng) ? { lat, lng } : null;
}

/** A circle that covers a place's bounding box (Nominatim's [south, north,
 * west, east]): a town gets kilometres, a house number 100 m. */
export function radiusForBoundingBox(box: [number, number, number, number] | undefined, lat: number): number {
  if (!box) return DEFAULT_AREA_RADIUS_M;
  const [south, north, west, east] = box;
  const dLat = (north - south) * 111_320;
  const dLng = (east - west) * 111_320 * Math.max(Math.cos((lat * Math.PI) / 180), 0.01);
  const half = Math.sqrt(dLat * dLat + dLng * dLng) / 2;
  if (!Number.isFinite(half)) return DEFAULT_AREA_RADIUS_M;
  return Math.round(Math.min(20_000, Math.max(100, half)));
}
