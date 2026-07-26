// Small great-circle helpers for "how far and which way" from the
// browser's own geolocation to a sighting's coordinates. No mapping
// library needed for this — just the standard haversine/bearing formulas.

const EARTH_RADIUS_M = 6371000;

function toRad(deg: number): number {
  return (deg * Math.PI) / 180;
}

export function haversineDistanceMeters(lat1: number, lng1: number, lat2: number, lng2: number): number {
  const dLat = toRad(lat2 - lat1);
  const dLng = toRad(lng2 - lng1);
  const a =
    Math.sin(dLat / 2) ** 2 + Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLng / 2) ** 2;
  return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(a));
}

export function initialBearingDegrees(lat1: number, lng1: number, lat2: number, lng2: number): number {
  const y = Math.sin(toRad(lng2 - lng1)) * Math.cos(toRad(lat2));
  const x =
    Math.cos(toRad(lat1)) * Math.sin(toRad(lat2)) -
    Math.sin(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.cos(toRad(lng2 - lng1));
  return (Math.atan2(y, x) * 180) / Math.PI;
}

const COMPASS_POINTS = ["N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"];

export function bearingToCompass(bearingDegrees: number): string {
  const normalized = ((bearingDegrees % 360) + 360) % 360;
  const index = Math.round(normalized / 22.5) % 16;
  return COMPASS_POINTS[index];
}

export function formatDistance(meters: number): string {
  return meters < 1000 ? `${Math.round(meters)} m` : `${(meters / 1000).toFixed(2)} km`;
}

export interface LatLng {
  lat: number;
  lng: number;
}

export interface WeightedLatLng extends LatLng {
  weight: number;
}

export function weightedCentroid(points: WeightedLatLng[]): LatLng {
  const weights = points.map((p) => p.weight);
  const minW = Math.min(...weights);
  const maxW = Math.max(...weights);
  const spread = maxW - minW || 1;
  // Weakest reading in the set still counts for something (0.1) so a
  // handful of very strong readings can't collapse the center down onto
  // just themselves.
  const w = points.map((p) => 0.1 + 0.9 * ((p.weight - minW) / spread));
  const totalW = w.reduce((sum, x) => sum + x, 0);
  return {
    lat: points.reduce((sum, p, i) => sum + w[i] * p.lat, 0) / totalW,
    lng: points.reduce((sum, p, i) => sum + w[i] * p.lng, 0) / totalW,
  };
}

// Andrew's monotone-chain convex hull over lat/lng treated as a flat plane
// (same simplification the ellipse fit above makes — fine at the tens-to-
// hundreds-of-meters scale one device's coverage actually spans).
//
// Returns [] when the input is degenerate (fewer than 3 distinct points, or
// all of them collinear) — there's no polygon to draw in that case, and the
// caller falls back to plain points rather than inventing area that wasn't
// measured.
export function convexHull(points: LatLng[]): LatLng[] {
  if (points.length < 3) return [];

  const sorted = [...points].sort((a, b) => a.lng - b.lng || a.lat - b.lat);
  const distinct: LatLng[] = [];
  sorted.forEach((p) => {
    const last = distinct[distinct.length - 1];
    if (!last || last.lat !== p.lat || last.lng !== p.lng) distinct.push(p);
  });
  if (distinct.length < 3) return [];

  // >0 = counter-clockwise turn. Points exactly on an edge (cross === 0) are
  // dropped too, so the hull carries only actual corners.
  const cross = (o: LatLng, a: LatLng, b: LatLng) =>
    (a.lng - o.lng) * (b.lat - o.lat) - (a.lat - o.lat) * (b.lng - o.lng);

  const build = (ordered: LatLng[]): LatLng[] => {
    const chain: LatLng[] = [];
    ordered.forEach((p) => {
      while (chain.length >= 2 && cross(chain[chain.length - 2], chain[chain.length - 1], p) <= 0) chain.pop();
      chain.push(p);
    });
    chain.pop(); // last point is the first point of the opposite chain
    return chain;
  };

  const hull = build(distinct).concat(build([...distinct].reverse()));
  return hull.length >= 3 ? hull : [];
}

// Chaikin corner-cutting. A convex hull's straight edges and sharp vertices
// read as a hard boundary, which is wrong for radio — coverage fades, it
// doesn't stop at a line. Two passes turns each corner into a short curve
// (4 vertices -> 16) without needing a spline library. Each pass also pulls
// the outline very slightly inward, which is fine: it only ever makes the
// claimed area smaller than what was measured.
export function smoothPolygon(points: LatLng[], iterations = 2): LatLng[] {
  if (points.length < 3) return points;

  let ring = points;
  for (let pass = 0; pass < iterations; pass++) {
    const next: LatLng[] = [];
    ring.forEach((a, i) => {
      const b = ring[(i + 1) % ring.length];
      next.push({ lat: a.lat * 0.75 + b.lat * 0.25, lng: a.lng * 0.75 + b.lng * 0.25 });
      next.push({ lat: a.lat * 0.25 + b.lat * 0.75, lng: a.lng * 0.25 + b.lng * 0.75 });
    });
    ring = next;
  }
  return ring;
}

// Rough flat-plane conversion. Longitude degrees shrink with latitude, so
// that axis is divided by cos(lat) — without it a "circle" renders as a
// noticeably squashed oval away from the equator.
const METERS_PER_DEGREE_LAT = 111320;

export function circlePolygon(center: LatLng, radiusMeters: number, segments = 48): LatLng[] {
  const latRadians = (center.lat * Math.PI) / 180;
  const dLat = radiusMeters / METERS_PER_DEGREE_LAT;
  const dLng = radiusMeters / (METERS_PER_DEGREE_LAT * Math.max(Math.cos(latRadians), 0.01));

  const ring: LatLng[] = [];
  for (let i = 0; i < segments; i++) {
    const t = (2 * Math.PI * i) / segments;
    ring.push({ lat: center.lat + dLat * Math.sin(t), lng: center.lng + dLng * Math.cos(t) });
  }
  return ring;
}

function offsetPoint(origin: LatLng, bearingDegrees: number, distanceMeters: number): LatLng {
  const bearingRad = toRad(bearingDegrees);
  const dLat = (distanceMeters * Math.cos(bearingRad)) / METERS_PER_DEGREE_LAT;
  const dLng =
    (distanceMeters * Math.sin(bearingRad)) / (METERS_PER_DEGREE_LAT * Math.max(Math.cos(toRad(origin.lat)), 0.01));
  return { lat: origin.lat + dLat, lng: origin.lng + dLng };
}

// A wedge from a known `apex` (e.g. an AP's estimated position) toward
// `target` (where a single reading was taken), fanning out at
// `halfAngleDegrees` on each side and overshooting slightly past `target`
// so it sits inside the shape rather than exactly on its far edge. Returns
// null when apex and target are too close to have a meaningful bearing
// between them (nothing to point toward).
export function conePolygon(apex: LatLng, target: LatLng, halfAngleDegrees = 22, overshoot = 1.15): LatLng[] | null {
  const distance = haversineDistanceMeters(apex.lat, apex.lng, target.lat, target.lng);
  if (distance < 5) return null;

  const bearing = initialBearingDegrees(apex.lat, apex.lng, target.lat, target.lng);
  const farDistance = distance * overshoot;
  const left = offsetPoint(apex, bearing - halfAngleDegrees, farDistance);
  const right = offsetPoint(apex, bearing + halfAngleDegrees, farDistance);
  const tip = offsetPoint(apex, bearing, farDistance);
  return [apex, left, tip, right];
}

export type CoverageShape = { kind: "polygon"; center: LatLng; polygon: LatLng[] } | { kind: "points" };

// Decides whether a device's sightings look like a fixed thing worth
// drawing a coverage shape for, or something that moved around too much
// for "coverage" to mean anything (a WiFi hotspot in a car, someone
// wandering around while wearing BLE headphones). Pass Infinity for
// maxRadiusMeters on radio types with no plausible cap (cellular — a sector
// legitimately covers km-scale areas).
//
// The shape itself is the convex hull of the *actual* measurement points.
// This replaced an earlier weighted-covariance ellipse fit: the hull can
// never claim coverage anywhere you didn't physically take a reading,
// whereas a 95%-confidence ellipse extrapolates outward from the spread and
// regularly ballooned past anything real. The weighted centroid is still
// what marks the estimated device location inside it (gradient center +
// icon), so the "where is it" and "where did I measure it" answers stay
// visually distinct. See MEMORY.md.
export function classifyCoverage(points: WeightedLatLng[], maxRadiusMeters: number): CoverageShape {
  if (points.length < 3) return { kind: "points" };

  const center = weightedCentroid(points);
  const maxDistance = Math.max(...points.map((p) => haversineDistanceMeters(center.lat, center.lng, p.lat, p.lng)));
  if (maxDistance > maxRadiusMeters) return { kind: "points" };

  const polygon = convexHull(points);
  // Collinear or near-duplicate readings have no area to fill — showing the
  // individual points is more honest than drawing a sliver.
  if (polygon.length < 3) return { kind: "points" };

  return { kind: "polygon", center, polygon: smoothPolygon(polygon) };
}
