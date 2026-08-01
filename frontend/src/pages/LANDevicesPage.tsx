import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { api, areaQuery, searchQueryPart } from "../api/client";
import { DeviceTypeBadge, LAN_DEVICE_LABELS } from "../components/DeviceTypeBadge";
import { Pager } from "../components/Pager";
import { SortableTh } from "../components/SortableTh";
import { TableControls } from "../components/TableControls";
import { useFilter } from "../context/FilterContext";
import { useDebouncedValue } from "../hooks/useDebouncedValue";
import { useSortableData } from "../hooks/useSortableData";
import { usePolling } from "../hooks/usePolling";
import { activeWindowQuery } from "../activeWindowQuery";

// A LAN scan is bounded by subnet size — a /24 sweep tops out at 254 hosts —
// so this comfortably fits the overwhelmingly common case on one page, unlike
// BLE/WiFi/cellular where "everything seen this week" can run into the
// thousands. Still real Prev/Next below for the rare larger network.
const PAGE_SIZE = 250;

export function LANDevicesPage() {
  const filter = useFilter();
  const debouncedSearch = useDebouncedValue(filter.searchQuery);
  const [page, setPage] = useState(1);

  const rangeDeps = [
    debouncedSearch,
    filter.since,
    filter.until,
    filter.sessionLimit,
    filter.area?.lat,
    filter.area?.lng,
    filter.area?.radiusM,
  ];
  useEffect(() => {
    setPage(1);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, rangeDeps);

  const { data, error, loading } = usePolling(
    () =>
      api.lanDevices(
        `?limit=${PAGE_SIZE}&page=${page}${searchQueryPart(debouncedSearch)}${activeWindowQuery(filter)}${areaQuery(filter.area)}`,
      ),
    15000,
    [page, ...rangeDeps],
  );
  const { sorted, sortKey, direction, requestSort } = useSortableData(
    data?.results ?? [],
    "last_seen_at",
    "desc",
  );

  // "New"/"left" only mean something when comparing at least two distinct
  // LAN scans — the backend only ever sets these flags in that situation,
  // but gate the column on the client too so it doesn't flicker between
  // renders.
  const showChangeBadges = filter.mode === "last-n-scans" && filter.scanCount >= 2;
  const multiPage = data != null && data.count > PAGE_SIZE;

  // Computed from whichever page is currently loaded — accurate whenever
  // everything fits on one page (the common case, see PAGE_SIZE above), but
  // undercounts the true total once there's more than one page, hence the
  // "on this page" qualifier below in that case.
  const onlineCount = sorted.filter((d) => d.is_online).length;
  const newCount = sorted.filter((d) => d.is_new_in_window).length;
  const leftCount = sorted.filter((d) => d.is_left_in_window).length;

  return (
    <section>
      <h1>LAN devices</h1>
      <p className="page-hint">
        Devices discovered on the phone's current WiFi subnet, grouped by IP address (like WiFi networks are grouped
        by BSSID) — one row per physical device, not one row per scan. A separate, longer-running action in the
        Android app (Scan screen → "Scan LAN"), not part of the regular WiFi/cellular/BLE/GNSS pass. Click a device
        for its full sighting history, open ports, banner, and quick links to any web services it exposes.
      </p>

      {loading && !data && <p>Loading…</p>}
      {error && <p className="error-text">Could not reach the backend: {error.message}</p>}

      {data && data.count === 0 && (
        <p className="empty-state">
          {debouncedSearch.trim() ? "No LAN devices match your search." : 'No LAN scan results yet. Run "Scan LAN" from the Android app.'}
        </p>
      )}

      {data && data.count > 0 && (
        <p className="page-hint">
          {data.count} device{data.count === 1 ? "" : "s"}{multiPage ? " total" : ""} · {onlineCount} online
          {multiPage ? " on this page" : ""} · {sorted.length - onlineCount} offline{multiPage ? " on this page" : ""}
          {showChangeBadges && (
            <>
              {" "}
              · {newCount} new · {leftCount} left since the previous scan{multiPage ? " (this page)" : ""}
            </>
          )}
        </p>
      )}

      {showChangeBadges && (
        <p className="page-hint">
          Comparing the last {filter.scanCount} LAN scans: <span className="badge badge-ok">New</span> devices only
          showed up in the most recent scan; <span className="badge badge-danger">Left</span> devices were seen
          before but are missing from it. <span className="badge badge-ok">Online</span>/
          <span className="badge badge-neutral">Offline</span> always reflects just the single most recent scan.
        </p>
      )}

      {data && data.count > 0 && (
        <>
          <TableControls />
          <table className="data-table">
            <thead>
              <tr>
                <SortableTh label="IP address" sortKey="ip_address" currentKey={sortKey} direction={direction} onSort={requestSort} />
                <SortableTh label="Name" sortKey="hostname" currentKey={sortKey} direction={direction} onSort={requestSort} hideMobile />
                <SortableTh label="Type" sortKey="latest_device_type_guess" currentKey={sortKey} direction={direction} onSort={requestSort} hideMobile />
                <SortableTh label="MAC" sortKey="mac_address" currentKey={sortKey} direction={direction} onSort={requestSort} hideMobile />
                <th className="hide-mobile">Status</th>
                <SortableTh label="Response time" sortKey="latest_response_time_ms" currentKey={sortKey} direction={direction} onSort={requestSort} />
                <SortableTh label="Last seen" sortKey="last_seen_at" currentKey={sortKey} direction={direction} onSort={requestSort} />
              </tr>
            </thead>
            <tbody>
              {sorted.map((device) => (
                <tr key={device.ip_address}>
                  <td className="mono">
                    <Link to={`/lan-devices/${encodeURIComponent(device.ip_address)}`}>{device.ip_address}</Link>
                  </td>
                  <td className="hide-mobile">{device.hostname || "—"}</td>
                  <td className="hide-mobile">
                    {device.latest_device_type_guess ? (
                      <DeviceTypeBadge deviceType={device.latest_device_type_guess} labels={LAN_DEVICE_LABELS} />
                    ) : (
                      "—"
                    )}
                  </td>
                  <td className="mono hide-mobile">{device.mac_address || "—"}</td>
                  <td className="hide-mobile">
                    <span className={`badge ${device.is_online ? "badge-ok" : "badge-neutral"}`}>
                      {device.is_online ? "Online" : "Offline"}
                    </span>{" "}
                    {device.is_new_in_window && <span className="badge badge-ok">New</span>}
                    {device.is_left_in_window && <span className="badge badge-danger">Left</span>}
                  </td>
                  <td>{device.latest_response_time_ms != null ? `${device.latest_response_time_ms.toFixed(0)} ms` : "—"}</td>
                  <td>{new Date(device.last_seen_at).toLocaleString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <Pager page={page} pageSize={PAGE_SIZE} total={data.count} onPageChange={setPage} noun="LAN devices" />
        </>
      )}
    </section>
  );
}
