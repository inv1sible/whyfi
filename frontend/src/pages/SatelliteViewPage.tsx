import { api } from "../api/client";
import { SimpleBarChart } from "../components/SimpleBarChart";
import { usePolling } from "../hooks/usePolling";

const CONSTELLATION_COLORS: Record<string, string> = {
  GPS: "#0f766e",
  GLONASS: "#b45309",
  GALILEO: "#1d4ed8",
  BEIDOU: "#be123c",
  QZSS: "#7c3aed",
  SBAS: "#4b5563",
  IRNSS: "#059669",
};

const PROVIDER_LABELS: Record<string, string> = {
  gps: "GPS",
  network: "Network (WiFi/cell-based)",
  fused: "Fused",
  "": "Unknown",
};

export function SatelliteViewPage() {
  const satellites = usePolling(() => api.satelliteObservations("?limit=200"), 15000);
  const sessions = usePolling(() => api.scanSessions(), 15000);

  const satelliteResults = satellites.data?.results ?? [];
  const usedInFix = satelliteResults.filter((s) => s.used_in_fix).length;
  const latestSession = sessions.data?.results[0];

  return (
    <section>
      <h1>Location &amp; satellites</h1>

      <h2>Location</h2>
      {sessions.loading && !sessions.data && <p>Loading…</p>}
      {sessions.error && <p className="error-text">Could not reach the backend: {sessions.error.message}</p>}
      {sessions.data && !latestSession && <p className="empty-state">No scans yet.</p>}
      {latestSession && (
        <dl className="detail-list">
          <dt>Coordinates</dt>
          <dd>
            {latestSession.latitude != null && latestSession.longitude != null
              ? `${latestSession.latitude.toFixed(5)}, ${latestSession.longitude.toFixed(5)}`
              : "Not available (location permission or GPS may be off)"}
          </dd>
          <dt>Accuracy</dt>
          <dd>
            {latestSession.location_accuracy_meters != null
              ? `± ${latestSession.location_accuracy_meters.toFixed(1)} m`
              : "—"}
          </dd>
          <dt>Source</dt>
          <dd>{PROVIDER_LABELS[latestSession.location_provider] ?? latestSession.location_provider}</dd>
          <dt>As of</dt>
          <dd>{new Date(latestSession.started_at).toLocaleString()}</dd>
        </dl>
      )}

      <h2>Satellites</h2>
      <p className="page-hint">Per-satellite signal quality (Cn0) from the most recent GNSS reading.</p>

      {satellites.loading && !satellites.data && <p>Loading…</p>}
      {satellites.error && <p className="error-text">Could not reach the backend: {satellites.error.message}</p>}

      {satellites.data && satelliteResults.length === 0 && (
        <p className="empty-state">No satellite data yet. Run a scan from the Android app outdoors.</p>
      )}

      {satelliteResults.length > 0 && (
        <>
          <p>
            {usedInFix} of {satelliteResults.length} satellites used in fix
          </p>
          <SimpleBarChart
            unit=" dB-Hz"
            bars={satelliteResults.map((sat) => ({
              label: `${sat.constellation} SV${sat.svid}`,
              value: Math.round(sat.cn0_db_hz),
              color: CONSTELLATION_COLORS[sat.constellation],
            }))}
          />

          <table className="data-table">
            <thead>
              <tr>
                <th>Satellite</th>
                <th>Cn0</th>
                <th>Elevation</th>
                <th>Azimuth</th>
                <th>Freq band</th>
                <th>Ephemeris</th>
                <th>Almanac</th>
                <th>Used in fix</th>
              </tr>
            </thead>
            <tbody>
              {satelliteResults.map((sat) => (
                <tr key={sat.id}>
                  <td>
                    {sat.constellation} SV{sat.svid}
                  </td>
                  <td>{sat.cn0_db_hz.toFixed(1)} dB-Hz</td>
                  <td>{sat.elevation_degrees != null ? `${sat.elevation_degrees.toFixed(0)}°` : "—"}</td>
                  <td>{sat.azimuth_degrees != null ? `${sat.azimuth_degrees.toFixed(0)}°` : "—"}</td>
                  <td>{sat.carrier_frequency_hz != null ? `${(sat.carrier_frequency_hz / 1e6).toFixed(1)} MHz` : "—"}</td>
                  <td>{sat.has_ephemeris_data ? "Yes" : "No"}</td>
                  <td>{sat.has_almanac_data ? "Yes" : "No"}</td>
                  <td>{sat.used_in_fix ? "Yes" : "No"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </section>
  );
}
