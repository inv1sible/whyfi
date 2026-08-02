import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { api, areaQuery, searchQueryPart } from "../api/client";
import { Pager } from "../components/Pager";
import { SecurityBadge } from "../components/SecurityBadge";
import { SimpleBarChart } from "../components/SimpleBarChart";
import { SortableTh } from "../components/SortableTh";
import { TableControls } from "../components/TableControls";
import { useFilter } from "../context/FilterContext";
import { useDebouncedValue } from "../hooks/useDebouncedValue";
import { useSortableData } from "../hooks/useSortableData";
import { usePolling } from "../hooks/usePolling";
import { activeWindowQuery } from "../activeWindowQuery";
import { groupBySsid } from "../ssidGrouping";
import type { SsidGroupRow } from "../ssidGrouping";

const BANDS = ["2.4GHz", "5GHz", "6GHz"];
// Same page size as BLE/Cellular/LAN. Grouping-by-SSID runs per page rather
// than over the whole match set, so a mesh network whose BSSIDs straddle a
// page boundary could in principle show as two partial rows — in practice
// this doesn't happen because all of the AccessPoint list endpoints (this
// one included) order by last_seen_at, and a mesh's BSSIDs are normally
// heard within the same scan pass, landing on the same page together.
const PAGE_SIZE = 50;

export function ChannelCongestionPage() {
  const [band, setBand] = useState("2.4GHz");
  const filter = useFilter();
  const debouncedSearch = useDebouncedValue(filter.searchQuery);
  const [page, setPage] = useState(1);

  const congestion = usePolling(
    () => api.channelCongestion(band, { since: filter.since, until: filter.until, sessionLimit: filter.sessionLimit }),
    15000,
    [band, filter.since, filter.until, filter.sessionLimit],
  );

  const rangeDeps = [
    band,
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

  const accessPoints = usePolling(
    () =>
      api.accessPoints(
        `?band=${encodeURIComponent(band)}&limit=${PAGE_SIZE}&page=${page}` +
          searchQueryPart(debouncedSearch) +
          activeWindowQuery(filter) +
          areaQuery(filter.area),
      ),
    15000,
    [page, ...rangeDeps],
  );

  const groupedRows = useMemo(() => groupBySsid(accessPoints.data?.results ?? []), [accessPoints.data]);
  const { sorted: sortedGroups, sortKey, direction, requestSort } = useSortableData<SsidGroupRow>(
    groupedRows,
    "lastSeen",
    "desc",
  );

  return (
    <section>
      <h1>WiFi</h1>
      <p className="page-hint">Which access points are on which channel, and how crowded each channel is.</p>

      <div className="band-selector">
        {BANDS.map((b) => (
          <button key={b} className={b === band ? "active" : ""} onClick={() => setBand(b)}>
            {b}
          </button>
        ))}
      </div>

      <h2>Congestion</h2>
      {congestion.loading && !congestion.data && <p>Loading…</p>}
      {congestion.error && <p className="error-text">Could not reach the backend: {congestion.error.message}</p>}
      {congestion.data && congestion.data.length === 0 && (
        <p className="empty-state">No WiFi observations on {band} in this time range.</p>
      )}
      {congestion.data && congestion.data.length > 0 && (
        <SimpleBarChart
          unit=" APs"
          bars={congestion.data.map((point) => ({ label: `Ch ${point.channel}`, value: point.ap_count }))}
        />
      )}

      <h2>Networks by channel</h2>
      <p className="page-hint">Grouped by SSID — a mesh network's individual BSSIDs are listed on its detail page.</p>
      {accessPoints.loading && !accessPoints.data && <p>Loading…</p>}
      {accessPoints.error && <p className="error-text">Could not reach the backend: {accessPoints.error.message}</p>}

      {accessPoints.data && (
        <>
          <TableControls />

          {accessPoints.data.count === 0 && (
            <p className="empty-state">
              {debouncedSearch.trim() ? "No networks match your search." : `No networks seen on ${band} in this time range.`}
            </p>
          )}

          {accessPoints.data.count > 0 && (
            <>
              <table className="data-table">
                <thead>
                  <tr>
                    <SortableTh label="SSID" sortKey="ssid" currentKey={sortKey} direction={direction} onSort={requestSort} />
                    <SortableTh label="Channel" sortKey="channels" currentKey={sortKey} direction={direction} onSort={requestSort} hideMobile />
                    <SortableTh label="Security" sortKey="security" currentKey={sortKey} direction={direction} onSort={requestSort} hideMobile />
                    <SortableTh label="Vendor" sortKey="vendor" currentKey={sortKey} direction={direction} onSort={requestSort} hideMobile />
                    <SortableTh label="Signal" sortKey="strongestRssi" currentKey={sortKey} direction={direction} onSort={requestSort} />
                    <SortableTh label="Last seen" sortKey="lastSeen" currentKey={sortKey} direction={direction} onSort={requestSort} />
                  </tr>
                </thead>
                <tbody>
                  {sortedGroups.map((row) => (
                    <tr key={row.key}>
                      <td>
                        <Link to={row.linkPath}>{row.ssid}</Link>
                        <div className="identifier-subtext mono">
                          {row.singleBssid ? `(${row.singleBssid})` : `(${row.bssidCount} access points)`}
                        </div>
                      </td>
                      <td className="hide-mobile">{row.channels}</td>
                      <td className="hide-mobile">
                        <SecurityBadge securityType={row.security} />
                      </td>
                      <td className="hide-mobile">{row.vendor}</td>
                      <td>{row.strongestRssi !== null ? `${row.strongestRssi} dBm` : "—"}</td>
                      <td>{new Date(row.lastSeen).toLocaleString()}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <Pager
                page={page}
                pageSize={PAGE_SIZE}
                total={accessPoints.data.count}
                onPageChange={setPage}
                noun={`access points on ${band}`}
              />
              <p className="page-hint">
                Paginated by access point (BSSID); rows above group them by SSID, so a page can show fewer rows than
                its access-point count when several BSSIDs share one network name.
              </p>
            </>
          )}
        </>
      )}
    </section>
  );
}
