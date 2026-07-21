import { Link } from "react-router-dom";
import { api } from "../api/client";
import { DeviceTypeBadge } from "../components/DeviceTypeBadge";
import { usePolling } from "../hooks/usePolling";

export function BLEDevicesPage() {
  const { data, error, loading } = usePolling(() => api.bleObservations("?limit=100"), 15000);

  return (
    <section>
      <h1>BLE devices</h1>
      <p className="page-hint">
        Bluetooth Low Energy devices seen nearby. Device type is a best-effort guess shown for information only —
        there's no tracking, alerting, or "following you" logic here. Click a device for its sighting history and
        direction.
      </p>

      {loading && !data && <p>Loading…</p>}
      {error && <p className="error-text">Could not reach the backend: {error.message}</p>}

      {data && data.results.length === 0 && (
        <p className="empty-state">No BLE devices observed yet. Run a scan from the Android app.</p>
      )}

      {data && data.results.length > 0 && (
        <table className="data-table">
          <thead>
            <tr>
              <th>Identifier</th>
              <th>Name</th>
              <th>Type</th>
              <th>Signal</th>
              <th>Connectable</th>
              <th>PHY</th>
              <th>Observed</th>
            </tr>
          </thead>
          <tbody>
            {data.results.map((device) => {
              const identifier = device.ble_mac || device.stable_identifier || "";
              return (
                <tr key={device.id}>
                  <td className="mono">
                    {identifier ? (
                      <Link to={`/ble-devices/${encodeURIComponent(identifier)}`}>{identifier}</Link>
                    ) : (
                      "(unknown)"
                    )}
                  </td>
                  <td>{device.device_name || "—"}</td>
                  <td>
                    <DeviceTypeBadge deviceType={device.device_type_guess} />
                  </td>
                  <td>{device.rssi} dBm</td>
                  <td>{device.is_connectable ? "Yes" : "No"}</td>
                  <td>{device.primary_phy || "—"}</td>
                  <td>{new Date(device.observed_at).toLocaleString()}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
    </section>
  );
}
