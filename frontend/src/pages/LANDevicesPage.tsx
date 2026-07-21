import { api } from "../api/client";
import { DeviceTypeBadge, LAN_DEVICE_LABELS } from "../components/DeviceTypeBadge";
import { usePolling } from "../hooks/usePolling";

const PORT_NAMES: Record<number, string> = {
  21: "FTP",
  22: "SSH",
  23: "Telnet",
  25: "SMTP",
  53: "DNS",
  80: "HTTP",
  139: "NetBIOS",
  443: "HTTPS",
  445: "SMB",
  554: "RTSP",
  631: "IPP",
  3389: "RDP",
  5000: "UPnP",
  5353: "mDNS",
  7000: "AirPlay",
  8000: "HTTP-alt",
  8008: "HTTP-alt",
  8009: "Chromecast",
  8080: "HTTP-alt",
  8443: "HTTPS-alt",
  9100: "JetDirect",
  32400: "Plex",
  62078: "iOS sync",
};

function portLabel(port: number): string {
  const name = PORT_NAMES[port];
  return name ? `${port} (${name})` : `${port}`;
}

export function LANDevicesPage() {
  const { data, error, loading } = usePolling(() => api.lanObservations("?limit=200"), 15000);

  return (
    <section>
      <h1>LAN devices</h1>
      <p className="page-hint">
        Devices discovered on the phone's current WiFi subnet — a separate, longer-running action in the Android app
        (Scan screen → "Scan LAN"), not part of the regular WiFi/cellular/BLE/GNSS pass.
      </p>

      {loading && !data && <p>Loading…</p>}
      {error && <p className="error-text">Could not reach the backend: {error.message}</p>}

      {data && data.results.length === 0 && (
        <p className="empty-state">No LAN scan results yet. Run "Scan LAN" from the Android app.</p>
      )}

      {data && data.results.length > 0 && (
        <table className="data-table">
          <thead>
            <tr>
              <th>IP address</th>
              <th>Type</th>
              <th>Hostname</th>
              <th>MAC</th>
              <th>Vendor</th>
              <th>Open ports</th>
              <th>Banner</th>
              <th>Response</th>
              <th>Observed</th>
            </tr>
          </thead>
          <tbody>
            {data.results.map((device) => (
              <tr key={device.id}>
                <td className="mono">{device.ip_address}</td>
                <td>
                  <DeviceTypeBadge deviceType={device.device_type_guess} labels={LAN_DEVICE_LABELS} />
                </td>
                <td>{device.hostname || "—"}</td>
                <td className="mono">{device.mac_address || "—"}</td>
                <td>{device.vendor_oui || "—"}</td>
                <td>{device.open_ports.length > 0 ? device.open_ports.map(portLabel).join(", ") : "—"}</td>
                <td className="mono">{device.banner || "—"}</td>
                <td>{device.response_time_ms != null ? `${device.response_time_ms.toFixed(0)} ms` : "—"}</td>
                <td>{new Date(device.observed_at).toLocaleString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
