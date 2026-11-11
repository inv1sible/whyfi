/** Place search for the focus area (user, 2026-11-11).
 *
 * Typed coordinates are resolved locally (focusArea.ts). Anything else is
 * sent — only when the user presses Enter or Go, never per keystroke — to
 * OpenStreetMap's Nominatim, the same project that serves the map tiles. The
 * query is the only thing sent; Nominatim's usage policy asks for no
 * autocomplete and at most one request per second, which the explicit search
 * respects. */
import { parseCoordinates, radiusForBoundingBox, type LatLng } from "./focusArea";

export const NOMINATIM_URL = "https://nominatim.openstreetmap.org/search";

export interface Place extends LatLng {
  label: string;
  radiusM: number;
}

interface NominatimRow {
  lat: string;
  lon: string;
  display_name?: string;
  name?: string;
  boundingbox?: [string, string, string, string];
}

export function placeFromRow(row: NominatimRow): Place | null {
  const lat = Number(row.lat);
  const lng = Number(row.lon);
  if (!Number.isFinite(lat) || !Number.isFinite(lng)) return null;
  const box = row.boundingbox?.map(Number) as [number, number, number, number] | undefined;
  return {
    lat,
    lng,
    label: (row.display_name || row.name || `${lat}, ${lng}`).slice(0, 160),
    radiusM: radiusForBoundingBox(box && box.every(Number.isFinite) ? box : undefined, lat),
  };
}

/** Up to five places for a query. Coordinates never leave the browser. */
export async function searchPlaces(query: string, signal?: AbortSignal): Promise<Place[]> {
  const point = parseCoordinates(query);
  if (point) return [{ ...point, label: `${point.lat.toFixed(5)}, ${point.lng.toFixed(5)}`, radiusM: 250 }];
  const url = `${NOMINATIM_URL}?format=jsonv2&limit=5&q=${encodeURIComponent(query.trim())}`;
  const response = await fetch(url, { signal, headers: { Accept: "application/json" } });
  if (!response.ok) throw new Error(`Place search answered with error ${response.status}.`);
  const rows = (await response.json()) as NominatimRow[];
  if (!Array.isArray(rows)) throw new Error("Place search gave an unreadable answer.");
  return rows.map(placeFromRow).filter((p): p is Place => p !== null);
}
