import { useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { api, areaQuery } from "../api/client";
import { SecurityBadge } from "../components/SecurityBadge";
import { SimpleBarChart } from "../components/SimpleBarChart";
import { SortableTh } from "../components/SortableTh";
import { TableControls } from "../components/TableControls";
import { useFilter } from "../context/FilterContext";
import { useSortableData } from "../hooks/useSortableData";
import { usePolling } from "../hooks/usePolling";
import { filterBySearch } from "../searchFilter";
import { groupBySsid } from "../ssidGrouping";
import type { SsidGroupRow } from "../ssidGrouping";
import type { AccessPoint } from "../api/types";

const BANDS = ["2.4GHz", "5GHz", "6GHz"];

export function ChannelCongestionPage() {
  const [band, setBand] = useState("2.4GHz");
  const filter = useFilter();

  const congestion = usePolling(
    () => api.channelCongestion(band, { since: filter.since, sessionLimit: filter.sessionLimit }),
    15000,
    [band, filter.since, filter.sessionLimit],
  );
  const accessPoints = usePolling(
    () =>
      api.accessPoints(
        `?band=${encodeURIComponent(band)}` +
          `${filter.sessionLimit ? `&session_limit=${filter.sessionLimit}` : filter.since ? `&active_since=${filter.since}` : ""}` +
          areaQuery(filter.area),
      ),
    15000,
    [band, filter.since, filter.sessionLimit, filter.area?.lat, filter.area?.lng, filter.area?.radiusM],
  );

  const filtered = filterBySearch<AccessPoint>(accessPoints.data?.results ?? [], filter.searchQuery);
  const groupedRows = useMemo(() => groupBySsid(filtered), [filtered]);
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
      <TableControls />

      {accessPoints.data && sortedGroups.length === 0 && (
        <p className="empty-state">No networks seen on {band} in this time range.</p>
      )}
      {sortedGroups.length > 0 && (
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
      )}
    </section>
  );
}
