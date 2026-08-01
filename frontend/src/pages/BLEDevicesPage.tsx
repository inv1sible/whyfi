import { Link } from "react-router-dom";
import { api, areaQuery } from "../api/client";
import { DeviceTypeBadge } from "../components/DeviceTypeBadge";
import { SortableTh } from "../components/SortableTh";
import { TableControls } from "../components/TableControls";
import { useFilter } from "../context/FilterContext";
import { useSortableData } from "../hooks/useSortableData";
import { usePolling } from "../hooks/usePolling";
import { filterBySearch } from "../searchFilter";
import type { BLEDevice } from "../api/types";

export function BLEDevicesPage() {
  const filter = useFilter();
  const { data, error, loading } = usePolling(
    () =>
      api.bleDevices(
        `?limit=200${filter.sessionLimit ? `&session_limit=${filter.sessionLimit}` : filter.since ? `&active_since=${filter.since}` : ""}${areaQuery(filter.area)}`,
      ),
    15000,
    [filter.since, filter.sessionLimit, filter.area?.lat, filter.area?.lng, filter.area?.radiusM],
  );
  const filtered = filterBySearch<BLEDevice>(data?.results ?? [], filter.searchQuery);
  const { sorted, sortKey, direction, requestSort } = useSortableData<BLEDevice>(
    filtered,
    "last_seen_at",
    "desc",
  );

  return (
    <section>
      <h1>BLE devices</h1>
      <p className="page-hint">
        Bluetooth Low Energy devices seen nearby, grouped by MAC address (like WiFi networks are grouped by BSSID) —
        one row per physical device, not one row per sighting. Device type is a best-effort guess shown for
        information only — there's no tracking, alerting, or "following you" logic here. Click a device for its
        sighting history and direction.
      </p>

      {loading && !data && <p>Loading…</p>}
      {error && <p className="error-text">Could not reach the backend: {error.message}</p>}

      {data && data.results.length === 0 && (
        <p className="empty-state">No BLE devices observed yet. Run a scan from the Android app.</p>
      )}

      {sorted.length > 0 && (
        <>
          <TableControls />
          <table className="data-table">
          <thead>
            <tr>
              <SortableTh label="Identifier" sortKey="device_key" currentKey={sortKey} direction={direction} onSort={requestSort} />
              <SortableTh label="Type" sortKey="device_type_guess" currentKey={sortKey} direction={direction} onSort={requestSort} hideMobile />
              <SortableTh label="Connectable" sortKey="latest_is_connectable" currentKey={sortKey} direction={direction} onSort={requestSort} hideMobile />
              <SortableTh label="PHY" sortKey="latest_primary_phy" currentKey={sortKey} direction={direction} onSort={requestSort} hideMobile />
              <SortableTh label="Signal" sortKey="latest_rssi" currentKey={sortKey} direction={direction} onSort={requestSort} />
              <SortableTh label="Last seen" sortKey="last_seen_at" currentKey={sortKey} direction={direction} onSort={requestSort} />
            </tr>
          </thead>
          <tbody>
            {sorted.map((device) => (
              <tr key={device.device_key}>
                <td className="mono">
                  <Link to={`/ble-devices/${encodeURIComponent(device.device_key)}`}>{device.device_key}</Link>
                  {device.latest_device_name && <div className="identifier-subtext">({device.latest_device_name})</div>}
                </td>
                <td className="hide-mobile">
                  <DeviceTypeBadge deviceType={device.device_type_guess} />
                </td>
                <td className="hide-mobile">{device.latest_is_connectable ? "Yes" : "No"}</td>
                <td className="hide-mobile">{device.latest_primary_phy || "—"}</td>
                <td>{device.latest_rssi != null ? `${device.latest_rssi} dBm` : "—"}</td>
                <td>{new Date(device.last_seen_at).toLocaleString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
        </>
      )}
    </section>
  );
}
