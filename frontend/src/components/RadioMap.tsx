import L from "leaflet";
import "leaflet/dist/leaflet.css";
import { useCallback, useEffect, useRef, useState } from "react";
import type { FocusArea } from "../api/client";
import type { HeatmapPoint } from "../api/types";
import { areaCenterIcon, calloutBadgeIcon, radioMarkerIcon, scanPointPinIcon } from "../mapIcons";
import type { MapIconType } from "../mapIcons";

// Public OSM tiles, fetched by the viewing browser when it has internet.
// Self-hosting a tile server was deliberately scoped out of v1 — see
// docs/architecture.md and MEMORY.md for the tradeoff.
const TILE_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png";
const DEFAULT_CENTER: [number, number] = [48.1351, 11.582];

// The backend buckets coverage points to ~1.1m precision (5 decimal
// places) — that's too small to see on a map, so cells are drawn at a
// fixed, visible base size instead of literally to-scale.
const GRID_CELL_METERS = 8;

export interface CoveragePolygon {
  points: { lat: number; lng: number }[];
  color: string;
  label?: string;
  // Click-through link to the network/tower/device's own detail page,
  // shown in a popup alongside the label (the tooltip alone is
  // hover-only, so it can't carry a link).
  detailPath?: string;
  // When set, fills with a green(strong)→orange(weak) radial gradient
  // centered on this point (the weighted signal centroid — see
  // classifyCoverage in geo.ts) instead of a flat color, and drops a small
  // radio-type icon marker there to mark the estimated AP/tower/device
  // location. `color` is still used for the outline stroke either way.
  //
  // Deliberately NOT set for Solo mode's range blobs: that gradient means
  // "green = strong signal here = the device is near this exact spot",
  // which only makes sense when the center is an actual estimated device
  // location derived from multiple readings. A Solo blob's center is just
  // wherever the *phone* stood for that one reading — it could be right
  // next to the device or, just as often (as with a weak reading taken
  // from far away), nowhere near it. Anchoring the gradient there made a
  // weak reading render *green at the phone's position*, backwards from
  // what the color is supposed to mean. Solo blobs instead use `color` as
  // a flat fill (see `fillOpacity` below) equal to that one reading's own
  // signalStrengthColor, so the color reports "how strong was this
  // specific reading", and the blob's radius (not its color) is what
  // carries "how far away the device could be".
  gradientCenter?: { lat: number; lng: number };
  centerIconType?: MapIconType;
  // Overrides the default flat-fill opacity (0.15) when gradientCenter
  // isn't set — Solo blobs want to be more visible than that, since the
  // color is the only signal-strength cue they carry.
  fillOpacity?: number;
  // Solo mode's "cone" shapes (see soloShapes in coverageConfig.ts) reuse
  // the gradient machinery but need two things the accumulate-mode hull
  // gradient doesn't: the gradient's far color isn't always the same fixed
  // orange (it's that one reading's own signalStrengthColor), and the
  // apex sits at one corner of the shape rather than its middle, so the
  // gradient can't just assume it's centered. Setting this switches
  // ensureGradientDef to a 2-stop green→edgeColor gradient positioned at
  // gradientCenter's actual fractional position within the polygon's own
  // bounding box, instead of the fixed 3-stop hull gradient at a flat 50%.
  gradientEdgeColor?: string;
  // Draws a small numbered badge on the shape, keyed to a matching `#`
  // column in the page's table. This is what replaces hover in a printed
  // report — on paper there's no tooltip to identify which shape is which.
  calloutNumber?: number;
}

export interface MapPointSource {
  label: string;
  detailPath: string;
  // How many other sources were bucketed into this same cell, if any.
  extraCount?: number;
}

export interface MapPoint extends Omit<HeatmapPoint, "source"> {
  // Draws a translucent accuracy-radius circle around this point. Used in
  // "path" mode always, and in "heat" mode only for scanSessionId-tagged
  // (device-location) points — the aggregate bucketed points from the
  // combined Heatmap/SSID-group pages don't set this (no single accuracy
  // value applies to a bucket of several merged sightings).
  accuracyMeters?: number | null;
  // Only meaningful in "heat" mode, for aggregate bucketed points (no
  // scanSessionId) — a representative contributor for this cell, shown in
  // a popup with a link through to its detail page.
  source?: MapPointSource | null;
  // Only meaningful in "heat" mode, for combined multi-radio aggregate
  // views — when set, this fixed hue is used instead of the weight-based
  // blue→red scale (so WiFi/cellular/BLE stay visually distinct by color),
  // and cell size is normalized against other points sharing the same hue
  // rather than across the whole combined set (their dBm scales aren't
  // comparable).
  typeHue?: number;
  // Only meaningful in "heat" mode, for aggregate bucketed points (no
  // scanSessionId) — pre-computed 0-1 normalization to use instead of
  // deriving it from the min/max of whatever's in the current render
  // batch. "Current scan only" display mode passes just one point per
  // device at a time, which has nothing else in its batch to normalize
  // against locally — the caller pre-normalizes it against that device's
  // full signal range instead, so the single visible cell's size/color
  // still means the same thing as you scrub between scans.
  normalizedWeight?: number;
  // The scan this single sighting came from. When set, this point IS one
  // exact reading (not an aggregate bucket) — a detail page's own sighting
  // history — so in "heat" mode it renders as a location pin (where the
  // phone stood) rather than a signal-colored grid cell, and, when the
  // caller also passes onDeleteScanSession, its popup offers a "Delete
  // this scan" action for cleaning up a stray/outlier GPS fix.
  scanSessionId?: string;
  observedAt?: string;
}

function escapeHtml(text: string): string {
  const div = document.createElement("div");
  div.textContent = text;
  return div.innerHTML;
}

function heatColor(normalizedWeight: number): string {
  // Blue (weak) through to red (strong) — standard heatmap convention.
  const hue = 240 * (1 - normalizedWeight);
  return `hsl(${hue}, 85%, 50%)`;
}

const SVG_NS = "http://www.w3.org/2000/svg";

// Turns an arbitrary device key (BSSID, cell tower key, BLE MAC — all of
// which contain ":" and other characters invalid in an SVG/XML id) into a
// safe, stable gradient element id.
function gradientId(key: string): string {
  return `coverage-gradient-${key.replace(/[^a-zA-Z0-9_-]/g, "_")}`;
}

// A true SVG radial gradient (rather than the concentric-rings
// approximation) — Leaflet's SVG renderer just sets `fill="url(#id)"` on
// the path verbatim, so as long as the gradient def exists somewhere in
// the same <svg> document, referencing it from a polygon's fillColor
// works. gradientUnits defaults to objectBoundingBox, which conveniently
// means cx/cy/r expressed as fractions of the polygon's own bounding box
// auto-conform to it — no per-shape rotation/aspect-ratio math needed.
//
// cx/cy default to 50%/50% (the hull/blob case, where the anchor point
// really is the shape's own center by construction). Cone shapes pass
// their apex's actual fractional position instead, since the apex sits at
// one corner, not the middle — see fractionalPosition below. r is fixed at
// 150%: for an off-center anchor, the farthest corner of a unit bounding
// box can be up to sqrt(2) (~141%) away, so 150% guarantees full coverage
// with a little margin either way.
function ensureGradientDef(map: L.Map, id: string, cx = 0.5, cy = 0.5, edgeColor?: string): void {
  const svg = map.getPanes().overlayPane.querySelector("svg");
  if (!svg) return; // shouldn't happen — the map-init effect forces an SVG renderer to exist
  let defs = svg.querySelector("defs");
  if (!defs) {
    defs = document.createElementNS(SVG_NS, "defs");
    svg.insertBefore(defs, svg.firstChild);
  }
  if (defs.querySelector(`#${id}`)) return;

  const gradient = document.createElementNS(SVG_NS, "radialGradient");
  gradient.setAttribute("id", id);
  gradient.setAttribute("cx", `${(cx * 100).toFixed(1)}%`);
  gradient.setAttribute("cy", `${(cy * 100).toFixed(1)}%`);
  gradient.setAttribute("r", "150%");
  // Solo cones: green at the known AP position fading to that one
  // reading's own signal-strength color at the edge — a real measured
  // distance, not the fixed 3-stop hull convention below.
  const stops: [string, string, string][] = edgeColor
    ? [
        ["0%", "#22c55e", "0.85"],
        ["100%", edgeColor, "0.45"],
      ]
    : [
        ["0%", "#22c55e", "0.85"], // solid green — estimated device location
        ["55%", "#eab308", "0.5"],
        ["100%", "#f97316", "0.15"], // fading orange at the edge
      ];
  stops.forEach(([offset, color, opacity]) => {
    const stop = document.createElementNS(SVG_NS, "stop");
    stop.setAttribute("offset", offset);
    stop.setAttribute("stop-color", color);
    stop.setAttribute("stop-opacity", opacity);
    gradient.appendChild(stop);
  });
  defs.appendChild(gradient);
}

// Where a point falls within a ring's own bounding box, as 0-1 fractions —
// used to position a radial gradient's cx/cy so it's anchored at an actual
// vertex (the cone's apex) rather than assuming the shape is symmetric
// around it. The y-axis is inverted (north = smaller fraction) because
// Leaflet's SVG renderer draws in screen/pixel space, where higher latitude
// is further *up* the screen (smaller y), not larger.
function fractionalPosition(point: { lat: number; lng: number }, ring: { lat: number; lng: number }[]) {
  const lats = ring.map((p) => p.lat);
  const lngs = ring.map((p) => p.lng);
  const minLat = Math.min(...lats);
  const maxLat = Math.max(...lats);
  const minLng = Math.min(...lngs);
  const maxLng = Math.max(...lngs);
  const x = maxLng > minLng ? (point.lng - minLng) / (maxLng - minLng) : 0.5;
  const y = maxLat > minLat ? (maxLat - point.lat) / (maxLat - minLat) : 0.5;
  return { x, y };
}

// Built from real DOM nodes (not an HTML string via bindPopup(string)) so
// the delete button can carry a real onclick handler. Two-step in-popup
// confirm rather than window.confirm() — confirm() is unreliable (silently
// no-ops with no dialog) in installed-PWA/WebView contexts, which is how
// this app is actually used on a phone. See MEMORY.md.
function buildDeleteScanPopup(observedAt: string | undefined, onConfirm: () => void): HTMLElement {
  const container = document.createElement("div");

  function renderIdle() {
    container.innerHTML = "";
    if (observedAt) {
      const label = document.createElement("div");
      label.textContent = new Date(observedAt).toLocaleString();
      label.style.marginBottom = "0.4rem";
      container.appendChild(label);
    }
    const button = document.createElement("button");
    button.textContent = "Delete this scan";
    button.onclick = renderConfirm;
    container.appendChild(button);
  }

  function renderConfirm() {
    container.innerHTML = "";
    const message = document.createElement("div");
    message.textContent = "Delete this scan? Removes every observation from it. Cannot be undone.";
    message.style.marginBottom = "0.4rem";
    container.appendChild(message);

    const confirmButton = document.createElement("button");
    confirmButton.textContent = "Confirm delete";
    confirmButton.className = "danger-button";
    confirmButton.style.marginRight = "0.4rem";
    confirmButton.onclick = onConfirm;
    container.appendChild(confirmButton);

    const cancelButton = document.createElement("button");
    cancelButton.textContent = "Cancel";
    cancelButton.onclick = renderIdle;
    container.appendChild(cancelButton);
  }

  renderIdle();
  return container;
}

interface RadioMapProps {
  points: MapPoint[];
  // "heat" (default) renders a signal-strength intensity blur — good for
  // aggregating many networks/devices at once. "path" instead connects
  // the points with a line in the order given, plus a marker per point,
  // for tracing a single device's location history over time. Callers
  // using "path" are responsible for ordering points chronologically.
  mode?: "heat" | "path";
  // Drawn additively on top of whatever `mode` renders — one filled
  // polygon per entry (e.g. a per-AP coverage-area hull on the heatmap).
  polygons?: CoveragePolygon[];
  // Path-mode only — when provided, each sighting marker gets a popup with
  // a "Delete this scan" action for cleaning up outlier GPS fixes. Callers
  // are responsible for actually calling the delete API and refetching;
  // this only reports which scan_session_id was picked.
  onDeleteScanSession?: (scanSessionId: string) => void;
  // Hands the parent an imperative handle once the map exists. Only used for
  // printing, which needs to reach past React into Leaflet — see
  // prepareForPrint below.
  onReady?: (handle: RadioMapHandle) => void;
  // The focus circle. Drawn when set; when onAreaChange is also provided the
  // map grows a "Focus area" control for placing and resizing it.
  area?: FocusArea | null;
  onAreaChange?: (area: FocusArea | null) => void;
}

export interface RadioMapHandle {
  /** Re-measure, re-frame to fit all data, and resolve once tiles have
   * settled. Print-only; see the comment on the implementation. */
  prepareForPrint: () => Promise<void>;
}

/** Plain average of a ring's vertices. Only used to place a callout badge on
 * a shape that has no gradientCenter, where "roughly the middle" is all
 * that's wanted — not a true area centroid. */
function ringCenter(ring: { lat: number; lng: number }[]): { lat: number; lng: number } {
  const lat = ring.reduce((sum, p) => sum + p.lat, 0) / ring.length;
  const lng = ring.reduce((sum, p) => sum + p.lng, 0) / ring.length;
  return { lat, lng };
}

const DEFAULT_AREA_RADIUS_M = 250;

// Tile-settling budget for printing. MIN gives Leaflet time to queue requests
// after a re-frame (checking sooner reads "not loading" and prints a blank
// basemap); MAX stops an unreachable tile server wedging the report.
const TILE_POLL_MS = 80;
const TILE_SETTLE_MS = 350;
const TILE_MIN_WAIT_MS = 450;
const TILE_MAX_WAIT_MS = 8000;

export function RadioMap({
  points,
  mode = "heat",
  polygons = [],
  onDeleteScanSession,
  onReady,
  area = null,
  onAreaChange,
}: RadioMapProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<L.Map | null>(null);
  const tileLayerRef = useRef<L.TileLayer | null>(null);
  const layersRef = useRef<L.Layer[]>([]);
  // Only auto-fit/zoom once, the first time data arrives — HeatmapPage
  // polls every 20s, and re-fitting bounds on every poll yanked the view
  // out from under anyone actually looking at the map. Manual pan/zoom
  // after that is left alone; the "Fit to data" button (below) re-triggers
  // it on demand.
  const hasFitOnceRef = useRef(false);
  const lastBoundsRef = useRef<[number, number][] | null>(null);
  // Coverage shapes only, tracked separately from lastBoundsRef for printing
  // — see prepareForPrint for why the report frames on these rather than on
  // everything.
  const polygonBoundsRef = useRef<[number, number][] | null>(null);
  const areaLayersRef = useRef<L.Layer[]>([]);
  // Kept in a ref as well as state: the map's click handler is registered
  // once, so a captured `placing` value would be stale forever after.
  const placingRef = useRef(false);
  const [placing, setPlacing] = useState(false);
  const onAreaChangeRef = useRef(onAreaChange);
  onAreaChangeRef.current = onAreaChange;
  const areaRef = useRef(area);
  areaRef.current = area;

  useEffect(() => {
    if (!containerRef.current || mapRef.current) return;

    const map = L.map(containerRef.current).setView(DEFAULT_CENTER, 15);
    tileLayerRef.current = L.tileLayer(TILE_URL, {
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
      maxZoom: 19,
    }).addTo(map);
    // Forces the SVG renderer (and its <svg> element) to exist from the
    // start, regardless of whether any vector layer has been added yet —
    // gradient coverage polygons need to inject a <defs> block into that
    // <svg>, which can't happen until it exists.
    L.svg().addTo(map);
    mapRef.current = map;

    // Leaflet measures its container's pixel size once and caches it —
    // zoom controls and fitBounds silently misbehave (miscalculated tile
    // positions/zoom levels) if that size goes stale after a later layout
    // change (e.g. the page's content width changing, a sidebar toggling,
    // or the container being 0-sized for a tick during initial mount).
    // invalidateSize() forces Leaflet to re-measure.
    // Registered once for the map's lifetime, so it reads placingRef rather
    // than a captured value. Placing is one-shot: click, circle appears,
    // arming turns itself off.
    map.on("click", (event: L.LeafletMouseEvent) => {
      if (!placingRef.current) return;
      placingRef.current = false;
      setPlacing(false);
      onAreaChangeRef.current?.({
        lat: Number(event.latlng.lat.toFixed(6)),
        lng: Number(event.latlng.lng.toFixed(6)),
        radiusM: areaRef.current?.radiusM ?? DEFAULT_AREA_RADIUS_M,
      });
    });

    const resizeObserver = new ResizeObserver(() => map.invalidateSize());
    resizeObserver.observe(containerRef.current);

    return () => {
      resizeObserver.disconnect();
      map.remove();
      mapRef.current = null;
    };
  }, []);

  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;

    layersRef.current.forEach((layer) => map.removeLayer(layer));
    layersRef.current = [];
    // Gradient <defs> aren't Leaflet layers, so they don't get cleaned up
    // by the removeLayer loop above — wipe and let the polygon loop below
    // recreate whichever ones this render pass actually needs, or stale
    // defs for devices that scrolled out of the current data would just
    // accumulate forever.
    map.getPanes().overlayPane.querySelector("svg defs")?.remove();

    if (points.length === 0 && polygons.length === 0) return;

    const latlngs: [number, number][] = points.map((p) => [p.lat, p.lng]);

    polygons.forEach((polygon, index) => {
      let fillColor = polygon.color;
      let fillOpacity = polygon.fillOpacity ?? 0.15;
      if (polygon.gradientCenter) {
        const id = gradientId(polygon.label || String(index));
        const { x, y } = polygon.gradientEdgeColor
          ? fractionalPosition(polygon.gradientCenter, polygon.points)
          : { x: 0.5, y: 0.5 };
        ensureGradientDef(map, id, x, y, polygon.gradientEdgeColor);
        fillColor = `url(#${id})`;
        fillOpacity = polygon.fillOpacity ?? 0.75;
      }

      const layer = L.polygon(
        polygon.points.map((p) => [p.lat, p.lng] as [number, number]),
        // Blurred via CSS (see .coverage-soft in index.css) on top of the
        // already-smoothed outline — radio coverage fades out, so a crisp
        // edge would overstate how precisely the boundary is known.
        { color: polygon.color, weight: 2, opacity: 0.7, fillColor, fillOpacity, className: "coverage-soft" },
      );
      if (polygon.label) layer.bindTooltip(polygon.label);
      if (polygon.detailPath) {
        layer.bindPopup(
          `<strong>${escapeHtml(polygon.label ?? "")}</strong><br/><a href="${polygon.detailPath}">View details</a>`,
        );
      }
      layer.addTo(map);
      layersRef.current.push(layer);

      if (polygon.gradientCenter && polygon.centerIconType) {
        const marker = L.marker([polygon.gradientCenter.lat, polygon.gradientCenter.lng], {
          icon: radioMarkerIcon(polygon.centerIconType),
        });
        if (polygon.label) marker.bindTooltip(polygon.label);
        if (polygon.detailPath) {
          marker.bindPopup(
            `<strong>${escapeHtml(polygon.label ?? "")}</strong><br/><a href="${polygon.detailPath}">View details</a>`,
          );
        }
        marker.addTo(map);
        layersRef.current.push(marker);
      }

      if (polygon.calloutNumber != null) {
        const at = polygon.gradientCenter ?? ringCenter(polygon.points);
        const badge = L.marker([at.lat, at.lng], {
          icon: calloutBadgeIcon(polygon.calloutNumber),
          // Above the coverage fills and the centre icon, so the number
          // stays readable where shapes overlap.
          zIndexOffset: 1000,
          interactive: false,
        }).addTo(map);
        layersRef.current.push(badge);
      }
    });

    if (mode === "path" && points.length > 0) {
      const line = L.polyline(latlngs, { color: "#2563eb", weight: 3, opacity: 0.7 }).addTo(map);
      layersRef.current.push(line);

      points.forEach((p, index) => {
        const isLatest = index === points.length - 1;
        const color = isLatest ? "#dc2626" : "#2563eb";

        if (p.accuracyMeters != null && p.accuracyMeters > 0) {
          const accuracyCircle = L.circle([p.lat, p.lng], {
            radius: p.accuracyMeters,
            color,
            weight: 1,
            fillColor: color,
            fillOpacity: 0.08,
          }).addTo(map);
          layersRef.current.push(accuracyCircle);
        }

        const marker = L.circleMarker([p.lat, p.lng], {
          radius: isLatest ? 8 : 5,
          color,
          fillColor: color,
          fillOpacity: 0.85,
        }).addTo(map);

        if (onDeleteScanSession && p.scanSessionId) {
          const scanSessionId = p.scanSessionId;
          marker.bindPopup(buildDeleteScanPopup(p.observedAt, () => onDeleteScanSession(scanSessionId)));
        }

        layersRef.current.push(marker);
      });
    } else if (mode === "heat" && points.length > 0) {
      // Device-location points (scanSessionId set) — each one is exactly
      // where the phone stood for one exact reading, not a bucketed
      // aggregate, so there's nothing to size/color by signal strength.
      // Rendered as a GPS-accuracy circle plus a location-pin marker
      // instead of a signal-colored grid cell.
      const devicePoints = points.filter((p) => p.scanSessionId);
      const aggregatePoints = points.filter((p) => !p.scanSessionId);

      devicePoints.forEach((p) => {
        if (p.accuracyMeters != null && p.accuracyMeters > 0) {
          const accuracyCircle = L.circle([p.lat, p.lng], {
            radius: p.accuracyMeters,
            color: "#2563eb",
            weight: 1,
            fillColor: "#2563eb",
            fillOpacity: 0.08,
          }).addTo(map);
          layersRef.current.push(accuracyCircle);
        }

        const marker = L.marker([p.lat, p.lng], { icon: scanPointPinIcon() }).addTo(map);
        if (onDeleteScanSession && p.scanSessionId) {
          const scanSessionId = p.scanSessionId;
          marker.bindPopup(buildDeleteScanPopup(p.observedAt, () => onDeleteScanSession(scanSessionId)));
        }
        layersRef.current.push(marker);
      });

      // Aggregate bucketed points (combined Heatmap/SSID-group pages) —
      // binned grid cells, not a soft circular gradient blur, at the same
      // resolution the backend already aggregated the data to, so what you
      // see on the map matches what was actually measured rather than an
      // arbitrary blur radius.
      //
      // Grouped by typeHue (undefined = one ungrouped layer) so each radio
      // type's own signal range gets its own min/max — combining WiFi RSSI
      // with cellular dBm on one scale would make one type always look
      // "weak" relative to the other in a combined view.
      const groups = new Map<number | undefined, MapPoint[]>();
      aggregatePoints.forEach((p) => {
        const list = groups.get(p.typeHue) ?? [];
        list.push(p);
        groups.set(p.typeHue, list);
      });

      groups.forEach((groupPoints) => {
        const weights = groupPoints.map((p) => p.weight);
        const minWeight = Math.min(...weights);
        const maxWeight = Math.max(...weights);
        const spread = maxWeight - minWeight || 1;

        groupPoints.forEach((p) => {
          const normalized = p.normalizedWeight ?? (p.weight - minWeight) / spread;
          const color = p.typeHue != null ? `hsl(${p.typeHue}, 85%, 50%)` : heatColor(normalized);
          // Cell grows with signal strength — 0.5x at the weakest reading
          // in its group up to 2x at the strongest, on top of the base
          // cell size.
          const sizeScale = 0.5 + normalized * 1.5;
          const radius = (GRID_CELL_METERS * sizeScale) / 2;

          const cell = L.circle([p.lat, p.lng], { radius, color, weight: 1, fillColor: color, fillOpacity: 0.55 }).addTo(
            map,
          );

          if (p.source) {
            // A point here can be an average of several scans bucketed
            // together, so there's no single scan to delete; link through
            // to the device's own page instead.
            const { label, detailPath, extraCount } = p.source;
            const extra = extraCount ? ` <span style="opacity:0.65">+${extraCount} more here</span>` : "";
            cell.bindPopup(`<strong>${escapeHtml(label)}</strong>${extra}<br/><a href="${detailPath}">View details</a>`);
          }

          layersRef.current.push(cell);
        });
      });
    }

    const polygonLatLngs = polygons.flatMap((p) => p.points.map((pt) => [pt.lat, pt.lng] as [number, number]));
    const allLatLngs = latlngs.concat(polygonLatLngs);
    polygonBoundsRef.current = polygonLatLngs.length > 0 ? polygonLatLngs : null;
    lastBoundsRef.current = allLatLngs.length > 0 ? allLatLngs : null;
    if (!hasFitOnceRef.current && allLatLngs.length > 0) {
      hasFitOnceRef.current = true;
      map.invalidateSize();
      map.fitBounds(allLatLngs, { maxZoom: 17 });
    }
  }, [points, mode, polygons, onDeleteScanSession]);

  function handleFitToData() {
    const map = mapRef.current;
    if (!map || !lastBoundsRef.current) return;
    map.invalidateSize();
    map.fitBounds(lastBoundsRef.current, { maxZoom: 17 });
  }

  /**
   * Resolves once the basemap has actually finished drawing.
   *
   * Polls rather than waiting on a single `load` event, because both of the
   * obvious approaches are wrong on their own:
   *
   *  - Waiting for `load` hangs forever when the re-frame needed no new tiles,
   *    since the event never fires.
   *  - Checking `isLoading()` once, immediately after `fitBounds`, reads false
   *    *before Leaflet has queued the new requests* — so it resolved instantly
   *    and printed a half-drawn map. That race is why the printed map was
   *    unreliable.
   *
   * So: give Leaflet a beat to start, then require it to be idle continuously
   * for [TILE_SETTLE_MS] before believing it. Capped, because an unreachable
   * tile server must not wedge the report.
   */
  const waitForTiles = useCallback(
    () =>
      new Promise<void>((resolve) => {
        const tiles = tileLayerRef.current;
        if (!tiles) {
          setTimeout(resolve, TILE_MIN_WAIT_MS);
          return;
        }
        const startedAt = Date.now();
        let idleSince: number | null = null;

        const tick = () => {
          const now = Date.now();
          if (tiles.isLoading()) {
            idleSince = null;
          } else if (idleSince === null) {
            idleSince = now;
          }
          const settled = idleSince !== null && now - idleSince >= TILE_SETTLE_MS;
          const waitedLongEnough = now - startedAt >= TILE_MIN_WAIT_MS;
          if ((settled && waitedLongEnough) || now - startedAt >= TILE_MAX_WAIT_MS) {
            resolve();
            return;
          }
          setTimeout(tick, TILE_POLL_MS);
        };
        tick();
      }),
    [],
  );

  /**
   * Get the map ready to be captured by the browser's print renderer.
   *
   * Three separate things make this impossible to do in CSS alone:
   *  - hasFitOnceRef means the view is wherever the user last panned it, so
   *    without re-fitting the print would crop the coverage.
   *  - Leaflet caches its container's pixel size, so the print stylesheet's
   *    mm-based height leaves it addressing tiles for the old screen size
   *    until invalidateSize() forces a re-measure.
   *  - Tiles load asynchronously, and onbeforeprint is synchronous — there is
   *    nowhere in the native print flow to wait for them.
   *
   * Note this deliberately changes *framing only*. It never refetches and
   * never touches the filter or slider, so the printed map shows exactly the
   * data that was on screen — just fitted to the page.
   */
  const prepareForPrint = useCallback(async () => {
    const map = mapRef.current;
    if (!map) return;

    // Fit against the *printed* geometry, not the on-screen one. The map is
    // far wider on screen (full page width) than on paper (an A4 column), and
    // Leaflet keeps centre+zoom when its container resizes — so fitting at
    // screen width and then printing crops ~40% off the sides, which is
    // exactly how the focus circle ended up running off the edge of the page.
    // .map-print-fit pins the container to the same mm box the print
    // stylesheet uses; the afterprint listener below takes it off again.
    containerRef.current?.classList.add("map-print-fit");
    // Read a layout property to force the mm-based resize to apply before
    // Leaflet re-measures — otherwise invalidateSize() can pick up the old
    // pixel size and address tiles for a box that no longer exists.
    void containerRef.current?.offsetHeight;
    map.invalidateSize({ animate: false });
    // A focus circle outranks everything: it's the declared subject of the
    // report, so the page should show that area rather than wherever the
    // surviving devices happen to sprawl.
    if (area) {
      // LatLng.toBounds, not L.circle(...).getBounds(): a Circle only knows
      // its bounds once it's been added to a map and projected, so calling
      // getBounds() on a detached one throws — which silently aborted the
      // whole prepare step and printed the un-reframed map.
      map.fitBounds(L.latLng(area.lat, area.lng).toBounds(area.radiusM * 2), {
        padding: [24, 24],
        animate: false,
      });
      await waitForTiles();
      return;
    }

    // Otherwise frame on the coverage shapes, not on every point. A survey
    // walked between towns leaves mobile-device points strung out over tens of
    // kilometres; fitting all of them shrinks the coverage to an unreadable
    // speck in one corner, which was exactly what the first test render
    // produced. The shapes are the substance of the report, so they set the
    // frame; stray points outside it are already listed in the table with
    // their coordinates. Falls back to everything when there are no shapes.
    const bounds = polygonBoundsRef.current ?? lastBoundsRef.current;
    if (bounds) {
      // Padding keeps the outermost shapes off the page edge — "centred, not
      // cut off" is the whole point of the report map.
      // animate:false so the view is final when this returns — an in-flight
      // pan would otherwise still be moving while the tiles are counted.
      map.fitBounds(bounds, { padding: [24, 24], maxZoom: 17, animate: false });
    }
    await waitForTiles();
  }, [area, waitForTiles]);

  useEffect(() => {
    onReady?.({ prepareForPrint });
  }, [onReady, prepareForPrint]);

  // Undo the print-geometry pin once the dialog closes, and put the map back
  // to the size it actually occupies on screen.
  useEffect(() => {
    const restore = () => {
      containerRef.current?.classList.remove("map-print-fit");
      mapRef.current?.invalidateSize();
    };
    window.addEventListener("afterprint", restore);
    return () => {
      window.removeEventListener("afterprint", restore);
      restore();
    };
  }, []);

  // (Clicks fall through coverage shapes while placing the focus circle —
  // handled in CSS via .map-placing, see index.css for why it can't be done
  // by setting pointer-events on the pane.)

  // The focus circle, drawn in its own effect so redrawing it doesn't tear
  // down and rebuild every coverage layer on each radius nudge.
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;

    areaLayersRef.current.forEach((layer) => map.removeLayer(layer));
    areaLayersRef.current = [];
    if (!area) return;

    const circle = L.circle([area.lat, area.lng], {
      radius: area.radiusM,
      color: "#2563eb",
      weight: 2,
      dashArray: "6 5",
      fillColor: "#2563eb",
      fillOpacity: 0.05,
      // Non-interactive so it never swallows a click meant for the coverage
      // shape underneath it.
      interactive: false,
    }).addTo(map);
    areaLayersRef.current.push(circle);

    if (onAreaChange) {
      // Dragging the centre is the fast way to nudge the circle onto a
      // building; the radius field handles size.
      const handle = L.marker([area.lat, area.lng], { draggable: true, icon: areaCenterIcon() })
        .on("dragend", (event) => {
          const { lat, lng } = (event.target as L.Marker).getLatLng();
          onAreaChangeRef.current?.({
            lat: Number(lat.toFixed(6)),
            lng: Number(lng.toFixed(6)),
            radiusM: areaRef.current?.radiusM ?? DEFAULT_AREA_RADIUS_M,
          });
        })
        .addTo(map);
      areaLayersRef.current.push(handle);
    }
  }, [area, onAreaChange]);

  return (
    // Classed so the print stylesheet can hoist the map above the report
    // header — the coverage map is the substance of the report, and on paper
    // it should be the first thing on the page.
    <div className="radio-map-block" style={{ position: "relative" }}>
      <div ref={containerRef} className={`map-container${placing ? " map-placing" : ""}`} />
      {(points.length > 0 || polygons.length > 0) && (
        <button
          onClick={handleFitToData}
          title="Fit map to all points"
          className="print-hide"
          style={{ position: "absolute", top: "0.6rem", right: "0.6rem", zIndex: 1000 }}
        >
          ⤢ Fit to data
        </button>
      )}

      {onAreaChange && (
        <div className="map-area-controls print-hide">
          <button
            className={placing ? "active" : ""}
            onClick={() => {
              const next = !placing;
              placingRef.current = next;
              setPlacing(next);
            }}
            title="Restrict every page to devices estimated to be inside a circle"
          >
            {placing ? "Click the map…" : area ? "Move focus area" : "◎ Focus area"}
          </button>
          {area && (
            <>
              <label>
                <span>Radius (m)</span>
                <input
                  type="number"
                  min={10}
                  step={50}
                  value={Math.round(area.radiusM)}
                  onChange={(e) => {
                    const radiusM = Number(e.target.value);
                    if (Number.isFinite(radiusM) && radiusM > 0) onAreaChange({ ...area, radiusM });
                  }}
                />
              </label>
              <button onClick={() => onAreaChange(null)}>Clear</button>
            </>
          )}
        </div>
      )}
    </div>
  );
}
