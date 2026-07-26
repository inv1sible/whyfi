import { api } from "../api/client";
import { RadioMap } from "../components/RadioMap";
import { SatelliteSkyPlot } from "../components/SatelliteSkyPlot";
import { SortableTh } from "../components/SortableTh";
import { useSortableData } from "../hooks/useSortableData";
import { usePolling } from "../hooks/usePolling";
import type { SatelliteObservation } from "../api/types";

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

  const { sorted: sortedSatellites, sortKey, direction, requestSort } = useSortableData<SatelliteObservation>(
    satelliteResults,
    "svid",
    "asc",
  );

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

      {latestSession && latestSession.fused_latitude != null && latestSession.fused_longitude != null && (
        <dl className="detail-list">
          <dt>Fused coordinates</dt>
          <dd>
            {latestSession.fused_latitude.toFixed(5)}, {latestSession.fused_longitude.toFixed(5)}
          </dd>
          <dt>Fused accuracy</dt>
          <dd>{latestSession.fused_accuracy_meters != null ? `± ${latestSession.fused_accuracy_meters.toFixed(1)} m` : "—"}</dd>
        </dl>
      )}

      {latestSession && latestSession.latitude != null && latestSession.longitude != null && (
        <>
          <RadioMap
            points={[
              {
                lat: latestSession.latitude,
                lng: latestSession.longitude,
                weight: 0,
                accuracyMeters: latestSession.location_accuracy_meters,
              },
              ...(latestSession.fused_latitude != null && latestSession.fused_longitude != null
                ? [
                    {
                      lat: latestSession.fused_latitude,
                      lng: latestSession.fused_longitude,
                      weight: 0,
                      accuracyMeters: latestSession.fused_accuracy_meters,
                    },
                  ]
                : []),
            ]}
            mode="path"
          />
          {latestSession.fused_latitude != null && (
            <p className="page-hint">
              Blue marker: {PROVIDER_LABELS[latestSession.location_provider] ?? latestSession.location_provider} reading.
              Red marker: fused reading. The line and shaded circles show the offset and accuracy radius of each.
            </p>
          )}
        </>
      )}

      <h2>Satellites</h2>
      <p className="page-hint">
        The sky plot shows each satellite's actual position — center is straight up (zenith), edge is the horizon.
        Both dot size and opacity encode signal strength (Cn0); a heavier ring means it's used in the current fix.
        The number next to each dot is its satellite ID (SVID), matching the "#" column in the table below.
      </p>

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
          <SatelliteSkyPlot satellites={satelliteResults} />

          <table className="data-table">
            <thead>
              <tr>
                <SortableTh label="#" sortKey="svid" currentKey={sortKey} direction={direction} onSort={requestSort} />
                <SortableTh label="Constellation" sortKey="constellation" currentKey={sortKey} direction={direction} onSort={requestSort} />
                <SortableTh label="Cn0" sortKey="cn0_db_hz" currentKey={sortKey} direction={direction} onSort={requestSort} />
                <SortableTh label="Elevation" sortKey="elevation_degrees" currentKey={sortKey} direction={direction} onSort={requestSort} />
                <SortableTh label="Azimuth" sortKey="azimuth_degrees" currentKey={sortKey} direction={direction} onSort={requestSort} />
                <SortableTh label="Freq band" sortKey="carrier_frequency_hz" currentKey={sortKey} direction={direction} onSort={requestSort} />
                <SortableTh label="Ephemeris" sortKey="has_ephemeris_data" currentKey={sortKey} direction={direction} onSort={requestSort} />
                <SortableTh label="Almanac" sortKey="has_almanac_data" currentKey={sortKey} direction={direction} onSort={requestSort} />
                <SortableTh label="Used in fix" sortKey="used_in_fix" currentKey={sortKey} direction={direction} onSort={requestSort} />
              </tr>
            </thead>
            <tbody>
              {sortedSatellites.map((sat) => (
                <tr key={sat.id}>
                  <td className="mono">#{sat.svid}</td>
                  <td>
                    <span
                      style={{
                        display: "inline-block",
                        width: "0.6rem",
                        height: "0.6rem",
                        borderRadius: "50%",
                        background: CONSTELLATION_COLORS[sat.constellation] ?? "#94a3b8",
                        marginRight: "0.4rem",
                      }}
                    />
                    {sat.constellation}
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
