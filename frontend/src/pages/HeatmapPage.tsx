import { useState } from "react";
import { api } from "../api/client";
import { RadioMap } from "../components/RadioMap";
import { usePolling } from "../hooks/usePolling";
import type { HeatmapSource } from "../api/types";

const SOURCES: { value: HeatmapSource; label: string }[] = [
  { value: "wifi", label: "WiFi signal" },
  { value: "cellular", label: "Cellular signal" },
  { value: "ble", label: "BLE devices" },
];

const RANGES: { label: string; hours: number | null }[] = [
  { label: "Last hour", hours: 1 },
  { label: "Last 24h", hours: 24 },
  { label: "Last 7 days", hours: 24 * 7 },
  { label: "All time", hours: null },
];

export function HeatmapPage() {
  const [source, setSource] = useState<HeatmapSource>("wifi");
  const [rangeIndex, setRangeIndex] = useState(1); // "Last 24h" by default

  const range = RANGES[rangeIndex];
  const since = range.hours !== null ? new Date(Date.now() - range.hours * 3600_000).toISOString() : undefined;

  const { data, error, loading } = usePolling(() => api.heatmap(source, { since }), 20000, [source, rangeIndex]);

  return (
    <section>
      <h1>Heatmap</h1>
      <p className="page-hint">
        Map tiles are fetched from public OpenStreetMap servers when this device has internet access — see
        docs/architecture.md for why v1 doesn't bundle a self-hosted tile server.
      </p>

      <div className="band-selector">
        {SOURCES.map((s) => (
          <button key={s.value} className={s.value === source ? "active" : ""} onClick={() => setSource(s.value)}>
            {s.label}
          </button>
        ))}
      </div>

      <div className="band-selector">
        {RANGES.map((r, i) => (
          <button key={r.label} className={i === rangeIndex ? "active" : ""} onClick={() => setRangeIndex(i)}>
            {r.label}
          </button>
        ))}
      </div>
      {range.hours === null && (
        <p className="page-hint">
          "All time" can span very different locations if you've traveled with the phone — the map zooms out to fit
          everything, which can make individual points hard to see. Narrow the range above for a clearer local view.
        </p>
      )}

      {loading && !data && <p>Loading…</p>}
      {error && <p className="error-text">Could not reach the backend: {error.message}</p>}
      {data && data.length === 0 && (
        <p className="empty-state">No geotagged observations yet for this source in this time range.</p>
      )}
      {data && data.length > 0 && data.length < 3 && (
        <p className="page-hint">
          Only {data.length} distinct location{data.length === 1 ? "" : "s"} in this range — one scan pass produces
          one point (everything detected in that pass shares the same location). Use "Start continuous scanning" in
          the Android app and walk around to build up a real heatmap.
        </p>
      )}
      {data && data.length > 0 && <RadioMap points={data} />}
    </section>
  );
}
