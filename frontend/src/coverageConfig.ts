import { circlePolygon, conePolygon, smoothPolygon } from "./geo";
import { classifyCoverage } from "./geo";
import type { CoverageShape, LatLng, WeightedLatLng } from "./geo";
import type { MapIconType } from "./mapIcons";
import { signalStrengthColor } from "./signalColor";

// How far a device's sightings can spread before "coverage area" stops
// being a meaningful thing to draw — beyond this it's treated as mobile (a
// WiFi hotspot in a car, BLE headphones/wearables, someone carrying a
// laptop around) and shown as plain points instead. Cellular has no cap: a
// sector legitimately covers km-scale areas, so spread alone can't tell
// stationary from moving there. LAN reuses WiFi's cap since discovery
// happens over the same physical medium/range.
export const RADIUS_CAP_METERS: Record<MapIconType, number> = {
  wifi: 75,
  ble: 20,
  cellular: Infinity,
  lan: 75,
};

// Worn on a person by definition — always treated as mobile regardless of
// measured spread, no distance check needed.
export const ALWAYS_MOBILE_BLE_TYPES = new Set(["HEADPHONES", "WEARABLE"]);

// Outline stroke for every coverage polygon — the gradient fill already
// carries the green(strong)→orange(weak) signal information, so the
// stroke doesn't need to vary; kept as one shared constant so it can't
// drift between the Heatmap page and the per-entity detail pages.
export const COVERAGE_STROKE_COLOR = "#f97316";

export function classifyDeviceCoverage(
  points: WeightedLatLng[],
  iconType: MapIconType,
  forceMobile = false,
): CoverageShape {
  if (forceMobile) return { kind: "points" };
  return classifyCoverage(points, RADIUS_CAP_METERS[iconType]);
}

// Log-distance path loss, the standard single-reading range estimate:
//
//   distance = 10 ^ ((refRssiAt1m - rssi) / (10 * pathLossExponent))
//
// refRssiAt1m is the signal you'd measure 1 m from the transmitter, and the
// exponent is how fast it decays (2 = free space, 3+ = walls/clutter). The
// values below are the usual indoor-survey defaults per radio type, not
// per-device calibration — this is an order-of-magnitude estimate, which is
// exactly why the shape it produces is drawn soft-edged rather than as a
// crisp boundary. LAN is absent on purpose: its weight is response time in
// ms, not a dBm signal, so no distance can be inferred from it.
// Cellular's reference is positive because a macro cell transmits orders of
// magnitude harder than a WiFi AP or a BLE beacon: calibrating it like the
// short-range radios put -100 dBm at ~50 m, when that reading really means
// well over a kilometer. Sanity check for these numbers — WiFi: -70 ≈ 13 m,
// -85 ≈ 46 m. BLE: -85 ≈ 15 m. Cellular: -70 ≈ 190 m, -100 ≈ 1.4 km.
const RANGE_MODEL: Partial<Record<MapIconType, { refRssiAt1m: number; pathLossExponent: number }>> = {
  wifi: { refRssiAt1m: -40, pathLossExponent: 2.7 },
  ble: { refRssiAt1m: -59, pathLossExponent: 2.2 },
  cellular: { refRssiAt1m: 10, pathLossExponent: 3.5 },
};

// Never collapse to a dot on a very strong reading, and never exceed the
// same cap that governs whether a device counts as stationary at all — so
// an estimated range can't contradict RADIUS_CAP_METERS above. Cellular has
// no such cap (Infinity, since a sector legitimately covers km-scale
// areas), so it gets its own ceiling to keep the map usable.
const MIN_RANGE_METERS = 3;
const UNCAPPED_RANGE_CEILING_METERS = 1500;

export function estimateRangeMeters(rssi: number, iconType: MapIconType): number | null {
  const model = RANGE_MODEL[iconType];
  if (model == null || !Number.isFinite(rssi)) return null;

  const distance = 10 ** ((model.refRssiAt1m - rssi) / (10 * model.pathLossExponent));
  const cap = Number.isFinite(RADIUS_CAP_METERS[iconType])
    ? RADIUS_CAP_METERS[iconType]
    : UNCAPPED_RANGE_CEILING_METERS;
  return Math.min(Math.max(distance, MIN_RANGE_METERS), cap);
}

// Flat fill for the no-known-AP-location blob fallback — visible enough
// that the color (the whole point of drawing it) actually reads, while
// still translucent since it's an estimate, not a surveyed area.
const SOLO_BLOB_FILL_OPACITY = 0.4;
// Cones use the gradient path (see RadioMap.tsx), which already defaults
// to a fairly opaque 0.75 — dialed back slightly so the underlying map
// stays legible along a possibly-long cone.
const SOLO_CONE_FILL_OPACITY = 0.6;

export interface SoloShape {
  polygon: LatLng[];
  color: string;
  fillOpacity: number;
  // Set only for cones (see below) — the AP's known position, which the
  // caller places a radio-type icon marker at and uses as the gradient's
  // green end.
  gradientCenter?: LatLng;
  gradientEdgeColor?: string;
}

/** Shapes for Solo mode — one per reading. When `apEstimatedLocation` is
 * known (the weighted centroid of this device's *entire* sighting
 * history — see weightedCentroid in geo.ts — independent of whatever the
 * time/scan slider currently narrows the map to), each reading draws a
 * cone from that real, known position to wherever the phone stood: green
 * at the AP, fading to that one reading's own signalStrengthColor at the
 * phone's position. Because the AP's location is already known here, this
 * is a real measured distance (haversineDistanceMeters), not a guess —
 * unlike the fallback below, no path-loss model is involved.
 *
 * Falls back to an RSSI-derived range blob (log-distance path loss, see
 * estimateRangeMeters) when there's no known AP location yet (a brand-new
 * device with only this one sighting ever) or the AP and the reading are
 * too close together to have a meaningful direction between them
 * (conePolygon returns null). Returns [] entirely for radio types with no
 * usable signal model (LAN — response time isn't a dBm signal), leaving
 * the caller to fall back to plain points. */
export function soloShapes(points: WeightedLatLng[], apEstimatedLocation: LatLng | null, iconType: MapIconType): SoloShape[] {
  return points.flatMap((point) => {
    if (RANGE_MODEL[iconType] == null) return [];
    const edgeColor = signalStrengthColor(point.weight);

    const cone = apEstimatedLocation ? conePolygon(apEstimatedLocation, point) : null;
    if (cone && apEstimatedLocation) {
      return [
        {
          polygon: smoothPolygon(cone, 1),
          color: COVERAGE_STROKE_COLOR,
          fillOpacity: SOLO_CONE_FILL_OPACITY,
          gradientCenter: apEstimatedLocation,
          gradientEdgeColor: edgeColor,
        },
      ];
    }

    const radius = estimateRangeMeters(point.weight, iconType);
    if (radius == null) return [];
    return [{ polygon: smoothPolygon(circlePolygon(point, radius), 1), color: edgeColor, fillOpacity: SOLO_BLOB_FILL_OPACITY }];
  });
}
