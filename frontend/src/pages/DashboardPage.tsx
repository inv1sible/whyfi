import { useMemo } from "react";
import { Link } from "react-router-dom";
import { api, areaQuery } from "../api/client";
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
  // Built from parts rather than nested ternaries now that the focus area is
  // orthogonal to the time window: the area can be set while neither
  // session_limit nor since is, so where the "?" goes can't be assumed.
  const rangeQuery = () => {
    // Requested explicitly (rather than trusting the default PAGE_SIZE of
    // 50) because this page merges four sources and keeps only the newest
    // 100 rows overall — a source capped at 50 could be starved out of that
    // merge entirely by a busier source, hiding a device that really is more
    // recent than what made the cut. See TruncationNotice below for the
    // rarer remaining case where even this isn't enough.
    const parts: string[] = ["limit=200"];
    if (filter.sessionLimit) {
      parts.push(`session_limit=${filter.sessionLimit}`);
    } else {
      if (filter.since) parts.push(`active_since=${filter.since}`);
      if (filter.until) parts.push(`active_until=${filter.until}`);
    }
    const area = areaQuery(filter.area).replace(/^&/, "");
    if (area) parts.push(area);
    return `?${parts.join("&")}`;
  };

  const rangeDeps = [
    filter.since,
    filter.until,
    filter.sessionLimit,
    filter.area?.lat,
    filter.area?.lng,
    filter.area?.radiusM,
  ];
  const wifi = usePolling(() => api.accessPoints(rangeQuery()), 15000, rangeDeps);
  const ble = usePolling(() => api.bleDevices(rangeQuery()), 15000, rangeDeps);
  const cell = usePolling(() => api.cellTowers(rangeQuery()), 15000, rangeDeps);
  const lan = usePolling(() => api.lanDevices(rangeQuery()), 15000, rangeDeps);

  const loading = wifi.loading && ble.loading && cell.loading && lan.loading;
  const anyError = wifi.error || ble.error || cell.error || lan.error;
  const anyData = wifi.data || ble.data || cell.data || lan.data;
  const truncatedSourceCount = [wifi.data, ble.data, cell.data, lan.data].filter(
    (d) => d && d.count > d.results.length,
  ).length;

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
      {truncatedSourceCount > 0 && (
        <p className="warning-text">
          {truncatedSourceCount} source{truncatedSourceCount === 1 ? "" : "s"} returned more matches than fit here —
          narrow the time range above, or visit that radio's own page, for the rest.
        </p>
      )}
      {anyData && rows.length === 0 && (
        <p className="empty-state">No scans yet. Run a scan from the Android app.</p>
      )}

      {rows.length > 0 && (
        <>
          <TableControls />
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
            {sorted.length === 0 && (
              <tr><td colSpan={5} className="empty-state">No rows match your search.</td></tr>
            )}
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
        </>
      )}
    </section>
  );
}
