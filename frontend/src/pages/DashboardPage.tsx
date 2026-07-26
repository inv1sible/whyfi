import { useMemo } from "react";
import { Link } from "react-router-dom";
import { api } from "../api/client";
import { SortableTh } from "../components/SortableTh";
import { TableControls } from "../components/TableControls";
import { useFilter } from "../context/FilterContext";
import { useSortableData } from "../hooks/useSortableData";
import { usePolling } from "../hooks/usePolling";
import { filterBySearch } from "../searchFilter";
import { groupBySsid } from "../ssidGrouping";

interface ActivityRow {
  key: string;
  identifier: string;
  detailPath: string;
  typeLabel: string;
  lastSeen: string;
  hasLocation: boolean;
  signal: number | null;
}

export function DashboardPage() {
  const filter = useFilter();
  const rangeQuery = (activeSinceParam: "active_since" | "since") =>
    filter.sessionLimit
      ? `?session_limit=${filter.sessionLimit}`
      : filter.since
        ? `?${activeSinceParam}=${filter.since}`
        : "";

  const wifi = usePolling(() => api.accessPoints(rangeQuery("active_since")), 15000, [filter.since, filter.sessionLimit]);
  const ble = usePolling(() => api.bleDevices(rangeQuery("active_since")), 15000, [filter.since, filter.sessionLimit]);
  const cell = usePolling(() => api.cellTowers(rangeQuery("active_since")), 15000, [filter.since, filter.sessionLimit]);
  const lan = usePolling(() => api.lanDevices(rangeQuery("active_since")), 15000, [filter.since, filter.sessionLimit]);

  const loading = wifi.loading && ble.loading && cell.loading && lan.loading;
  const anyError = wifi.error || ble.error || cell.error || lan.error;
  const anyData = wifi.data || ble.data || cell.data || lan.data;

  const rows = useMemo<ActivityRow[]>(() => {
    const result: ActivityRow[] = [];

    // Grouped by SSID (mesh networks share one name across several
    // BSSIDs), same as the WiFi page — one row per network, not one per
    // radio.
    groupBySsid(wifi.data?.results ?? []).forEach((group) => {
      result.push({
        key: `wifi-${group.key}`,
        identifier: group.ssid,
        detailPath: group.linkPath,
        typeLabel: "WiFi",
        lastSeen: group.lastSeen,
        hasLocation: group.hasLocation,
        signal: group.strongestRssi,
      });
    });

    (ble.data?.results ?? []).forEach((device) => {
      result.push({
        key: `ble-${device.device_key}`,
        identifier: device.latest_device_name || device.device_key,
        detailPath: `/ble-devices/${encodeURIComponent(device.device_key)}`,
        typeLabel: "BLE",
        lastSeen: device.last_seen_at,
        hasLocation: device.latest_has_location,
        signal: device.latest_rssi,
      });
    });

    (cell.data?.results ?? []).forEach((tower) => {
      result.push({
        key: `cell-${tower.tower_key}`,
        identifier: tower.carrier_name || tower.cell_id || tower.tower_key,
        detailPath: `/cellular/${encodeURIComponent(tower.tower_key)}`,
        typeLabel: "Cell",
        lastSeen: tower.last_seen_at,
        hasLocation: tower.latest_has_location,
        signal: tower.latest_signal_dbm,
      });
    });

    (lan.data?.results ?? []).forEach((device) => {
      result.push({
        key: `lan-${device.ip_address}`,
        identifier: device.hostname || device.ip_address,
        detailPath: `/lan-devices/${encodeURIComponent(device.ip_address)}`,
        typeLabel: "LAN",
        lastSeen: device.last_seen_at,
        hasLocation: device.latest_has_location,
        signal: null,
      });
    });

    return result.sort((a, b) => new Date(b.lastSeen).getTime() - new Date(a.lastSeen).getTime()).slice(0, 100);
  }, [wifi.data, ble.data, cell.data, lan.data]);

  const filteredRows = filterBySearch<ActivityRow>(rows, filter.searchQuery);
  const { sorted, sortKey, direction, requestSort } = useSortableData<ActivityRow>(filteredRows, "lastSeen", "desc");

  return (
    <section>
      <h1>Recent activity</h1>
      <p className="page-hint">
        Everything your sensors have picked up recently — WiFi, BLE, cellular, and LAN — one row per device/network,
        most recently seen first. Click through to a detail page for the full picture.
      </p>

      {loading && !anyData && <p>Loading…</p>}
      {anyError && <p className="error-text">Could not reach the backend: {anyError.message}</p>}
      <TableControls />

      {anyData && sorted.length === 0 && (
        <p className="empty-state">No scans yet. Run a scan from the Android app.</p>
      )}

      {sorted.length > 0 && (
        <table className="data-table">
          <thead>
            <tr>
              <SortableTh label="Identifier" sortKey="identifier" currentKey={sortKey} direction={direction} onSort={requestSort} />
              <SortableTh label="Type" sortKey="typeLabel" currentKey={sortKey} direction={direction} onSort={requestSort} hideMobile />
              <SortableTh label="Location" sortKey="hasLocation" currentKey={sortKey} direction={direction} onSort={requestSort} hideMobile />
              <SortableTh label="Signal" sortKey="signal" currentKey={sortKey} direction={direction} onSort={requestSort} />
              <SortableTh label="Last seen" sortKey="lastSeen" currentKey={sortKey} direction={direction} onSort={requestSort} />
            </tr>
          </thead>
          <tbody>
            {sorted.map((row) => (
              <tr key={row.key}>
                <td>
                  <Link to={row.detailPath}>{row.identifier}</Link>
                </td>
                <td className="hide-mobile">{row.typeLabel}</td>
                <td className="hide-mobile">{row.hasLocation ? "📍" : "—"}</td>
                <td>{row.signal != null ? `${row.signal} dBm` : "—"}</td>
                <td>{new Date(row.lastSeen).toLocaleString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
