import { Link } from "react-router-dom";
import type { AccessPoint } from "../api/types";
import { SecurityBadge } from "./SecurityBadge";

export function NetworkTable({ accessPoints }: { accessPoints: AccessPoint[] }) {
  if (accessPoints.length === 0) {
    return <p className="empty-state">No WiFi networks observed yet. Run a scan from the Android app.</p>;
  }

  return (
    <table className="data-table">
      <thead>
        <tr>
          <th>SSID</th>
          <th>BSSID</th>
          <th>Signal</th>
          <th>Band</th>
          <th>Security</th>
          <th>Last seen</th>
        </tr>
      </thead>
      <tbody>
        {accessPoints.map((ap) => (
          <tr key={ap.bssid}>
            <td>
              <Link to={`/networks/${encodeURIComponent(ap.bssid)}`}>{ap.ssid || "(hidden)"}</Link>
            </td>
            <td className="mono">{ap.bssid}</td>
            <td>{ap.latest_rssi !== null ? `${ap.latest_rssi} dBm` : "—"}</td>
            <td>{ap.latest_band ?? "—"}</td>
            <td>
              <SecurityBadge securityType={ap.latest_security_type} />
            </td>
            <td>{new Date(ap.last_seen_at).toLocaleString()}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
