import L from "leaflet";
import "leaflet/dist/leaflet.css";
import "leaflet.heat";
import { useEffect, useRef } from "react";
import type { HeatmapPoint } from "../api/types";

// Public OSM tiles, fetched by the viewing browser when it has internet.
// Self-hosting a tile server was deliberately scoped out of v1 — see
// docs/architecture.md and MEMORY.md for the tradeoff.
const TILE_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png";
const DEFAULT_CENTER: [number, number] = [48.1351, 11.582];

interface RadioMapProps {
  points: HeatmapPoint[];
  // "heat" (default) renders a signal-strength intensity blur — good for
  // aggregating many networks/devices at once. "path" instead connects
  // the points with a line in the order given, plus a marker per point,
  // for tracing a single device's location history over time. Callers
  // using "path" are responsible for ordering points chronologically.
  mode?: "heat" | "path";
}

export function RadioMap({ points, mode = "heat" }: RadioMapProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<L.Map | null>(null);
  const layersRef = useRef<L.Layer[]>([]);
  // Only auto-fit/zoom once, the first time data arrives — HeatmapPage
  // polls every 20s, and re-fitting bounds on every poll yanked the view
  // out from under anyone actually looking at the map. Manual pan/zoom
  // after that is left alone.
  const hasFitOnceRef = useRef(false);

  useEffect(() => {
    if (!containerRef.current || mapRef.current) return;

    const map = L.map(containerRef.current).setView(DEFAULT_CENTER, 15);
    L.tileLayer(TILE_URL, {
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
      maxZoom: 19,
    }).addTo(map);
    mapRef.current = map;

    return () => {
      map.remove();
      mapRef.current = null;
    };
  }, []);

  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;

    layersRef.current.forEach((layer) => map.removeLayer(layer));
    layersRef.current = [];

    if (points.length === 0) return;

    const latlngs: [number, number][] = points.map((p) => [p.lat, p.lng]);

    if (mode === "path") {
      const line = L.polyline(latlngs, { color: "#2563eb", weight: 3, opacity: 0.7 }).addTo(map);
      layersRef.current.push(line);

      points.forEach((p, index) => {
        const isLatest = index === points.length - 1;
        const marker = L.circleMarker([p.lat, p.lng], {
          radius: isLatest ? 8 : 5,
          color: isLatest ? "#dc2626" : "#2563eb",
          fillColor: isLatest ? "#dc2626" : "#2563eb",
          fillOpacity: 0.85,
        }).addTo(map);
        layersRef.current.push(marker);
      });
    } else {
      const weights = points.map((p) => p.weight);
      const minWeight = Math.min(...weights);
      const maxWeight = Math.max(...weights);
      const spread = maxWeight - minWeight || 1;

      const heatLatLngs: Array<[number, number, number]> = points.map((p) => [
        p.lat,
        p.lng,
        // Normalize so stronger signal (less negative dBm) renders "hotter".
        (p.weight - minWeight) / spread,
      ]);

      const heatLayer = L.heatLayer(heatLatLngs, { radius: 25, blur: 20, maxZoom: 19 }).addTo(map);
      layersRef.current.push(heatLayer);
    }

    if (!hasFitOnceRef.current) {
      hasFitOnceRef.current = true;
      map.fitBounds(latlngs, { maxZoom: 17 });
    }
  }, [points, mode]);

  return <div ref={containerRef} className="map-container" />;
}
