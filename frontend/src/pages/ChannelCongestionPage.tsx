import { useState } from "react";
import { api } from "../api/client";
import { SecurityBadge } from "../components/SecurityBadge";
import { SimpleBarChart } from "../components/SimpleBarChart";
import { usePolling } from "../hooks/usePolling";

const BANDS = ["2.4GHz", "5GHz", "6GHz"];

export function ChannelCongestionPage() {
  const [band, setBand] = useState("2.4GHz");
  const congestion = usePolling(() => api.channelCongestion(band), 15000, [band]);
  const accessPoints = usePolling(() => api.accessPoints(`?band=${encodeURIComponent(band)}`), 15000, [band]);

  const sortedAps = accessPoints.data
    ? [...accessPoints.data.results].sort((a, b) => (a.latest_channel ?? 0) - (b.latest_channel ?? 0))
    : [];

  return (
    <section>
      <h1>Channels</h1>
      <p className="page-hint">Which access points are on which channel, and how crowded each channel is.</p>

      <div className="band-selector">
        {BANDS.map((b) => (
          <button key={b} className={b === band ? "active" : ""} onClick={() => setBand(b)}>
            {b}
          </button>
        ))}
      </div>

      <h2>Networks by channel</h2>
      {accessPoints.loading && !accessPoints.data && <p>Loading…</p>}
      {accessPoints.error && (
        <p className="error-text">Could not reach the backend: {accessPoints.error.message}</p>
      )}
      {accessPoints.data && sortedAps.length === 0 && (
        <p className="empty-state">No networks seen on {band} yet.</p>
      )}
      {sortedAps.length > 0 && (
        <table className="data-table">
          <thead>
            <tr>
              <th>Channel</th>
              <th>SSID</th>
              <th>BSSID</th>
              <th>Signal</th>
              <th>Security</th>
            </tr>
          </thead>
          <tbody>
            {sortedAps.map((ap) => (
              <tr key={ap.bssid}>
                <td>{ap.latest_channel ?? "—"}</td>
                <td>{ap.ssid || "(hidden)"}</td>
                <td className="mono">{ap.bssid}</td>
                <td>{ap.latest_rssi !== null ? `${ap.latest_rssi} dBm` : "—"}</td>
                <td>
                  <SecurityBadge securityType={ap.latest_security_type} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <h2>Congestion (last 24h)</h2>
      {congestion.loading && !congestion.data && <p>Loading…</p>}
      {congestion.error && <p className="error-text">Could not reach the backend: {congestion.error.message}</p>}
      {congestion.data && (
        <SimpleBarChart
          unit=" APs"
          bars={congestion.data.map((point) => ({ label: `Ch ${point.channel}`, value: point.ap_count }))}
        />
      )}
    </section>
  );
}
