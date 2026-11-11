import { useEffect, useRef, useState } from "react";
import { searchPlaces, type Place } from "../placeSearch";

interface PlaceSearchProps {
  // A place was chosen: where the focus circle goes and how big it should be
  // to cover the place.
  onPick: (lat: number, lng: number, radiusM: number) => void;
}

/** The location picker on the map (user, 2026-11-11): type a place or
 * coordinates, press Enter, pick a hit. Nothing is sent while typing; see
 * placeSearch.ts for what leaves the browser. */
export function PlaceSearch({ onPick }: PlaceSearchProps) {
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<Place[] | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  useEffect(() => () => abortRef.current?.abort(), []);

  const search = async () => {
    const q = query.trim();
    if (!q) return;
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    setBusy(true);
    setError(null);
    try {
      const places = await searchPlaces(q, controller.signal);
      if (controller.signal.aborted) return;
      if (places.length === 1 && places[0].label === q) {
        // Coordinates: no choice to make.
        setResults(null);
        onPick(places[0].lat, places[0].lng, places[0].radiusM);
      } else {
        setResults(places);
      }
    } catch (e) {
      if (controller.signal.aborted) return;
      setError(e instanceof Error ? e.message : "Place search did not answer.");
      setResults(null);
    } finally {
      if (!controller.signal.aborted) setBusy(false);
    }
  };

  return (
    <div className="map-place-search">
      <input
        type="search"
        value={query}
        placeholder="Place or 52.52, 13.405"
        title="Find a place for the focus circle. Coordinates stay in the browser; a place name is looked up at OpenStreetMap (Nominatim) when you press Enter."
        onChange={(e) => setQuery(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter") {
            e.preventDefault();
            void search();
          }
          if (e.key === "Escape") setResults(null);
        }}
      />
      <button type="button" disabled={busy || !query.trim()} onClick={() => void search()}>
        {busy ? "…" : "Go"}
      </button>
      {(results || error) && (
        <div className="map-place-results">
          {error && <div className="map-place-note">{error}</div>}
          {results && results.length === 0 && <div className="map-place-note">No place found. Try a street and town, or paste coordinates.</div>}
          {results?.map((place, index) => (
            <button
              key={`${place.lat},${place.lng},${index}`}
              type="button"
              onClick={() => {
                setResults(null);
                onPick(place.lat, place.lng, place.radiusM);
              }}
            >
              {place.label}
            </button>
          ))}
          {results && results.length > 0 && <div className="map-place-note">Results from OpenStreetMap (Nominatim).</div>}
        </div>
      )}
    </div>
  );
}
