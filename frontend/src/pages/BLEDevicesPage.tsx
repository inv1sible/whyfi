import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { api, areaQuery, searchQueryPart } from "../api/client";
import { DeviceTypeBadge } from "../components/DeviceTypeBadge";
import { Pager } from "../components/Pager";
import { SortableTh } from "../components/SortableTh";
import { TableControls } from "../components/TableControls";
import { useFilter } from "../context/FilterContext";
import { useDebouncedValue } from "../hooks/useDebouncedValue";
import { useSortableData } from "../hooks/useSortableData";
import { usePolling } from "../hooks/usePolling";
import { activeWindowQuery } from "../activeWindowQuery";

const PAGE_SIZE = 50;

export function BLEDevicesPage() {
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
  // A stale page number after the query changes underneath it would show
  // "page 3" of a result set that might only have one page now — reset
  // whenever anything that reshapes the match set changes, not on every
  // poll tick.
  useEffect(() => {
    setPage(1);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, rangeDeps);

  const { data, error, loading } = usePolling(
    () =>
      api.bleDevices(
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

      {data && (
        <>
          <TableControls />

          {data.count === 0 && (
            <p className="empty-state">
              {debouncedSearch.trim()
                ? "No BLE devices match your search."
                : "No BLE devices observed yet. Run a scan from the Android app."}
            </p>
          )}

          {data.count > 0 && (
            <>
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
              <Pager page={page} pageSize={PAGE_SIZE} total={data.count} onPageChange={setPage} noun="BLE devices" />
            </>
          )}
        </>
      )}
    </section>
  );
}
