import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { api, areaQuery, searchQueryPart } from "../api/client";
import { Pager } from "../components/Pager";
import { SortableTh } from "../components/SortableTh";
import { TableControls } from "../components/TableControls";
import { useFilter } from "../context/FilterContext";
import { useDebouncedValue } from "../hooks/useDebouncedValue";
import { useSortableData } from "../hooks/useSortableData";
import { usePolling } from "../hooks/usePolling";
import { activeWindowQuery } from "../activeWindowQuery";

const PAGE_SIZE = 50;

export function CellularPage() {
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
      api.cellTowers(
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
      <h1>Cellular</h1>
      <p className="page-hint">
        Cell towers your phone has seen, grouped by MCC/MNC/LAC/Cell ID (like WiFi networks are grouped by BSSID) —
        one row per physical tower, not one row per reading. Click a tower for signal history, technical detail, and
        a map of where you've seen it from.
      </p>

      {loading && !data && <p>Loading…</p>}
      {error && <p className="error-text">Could not reach the backend: {error.message}</p>}

      {data && (
        <>
          <TableControls />

          {data.count === 0 && (
            <p className="empty-state">
              {debouncedSearch.trim() ? "No towers match your search." : "No cellular observations yet. Run a scan from the Android app."}
            </p>
          )}

          {data.count > 0 && (
            <>
              <table className="data-table">
                <thead>
                  <tr>
                    <SortableTh label="Cell ID" sortKey="cell_id" currentKey={sortKey} direction={direction} onSort={requestSort} />
                    <SortableTh label="MNC" sortKey="mnc" currentKey={sortKey} direction={direction} onSort={requestSort} hideMobile />
                    <SortableTh label="LAC" sortKey="tac_or_lac" currentKey={sortKey} direction={direction} onSort={requestSort} hideMobile />
                    <SortableTh label="Radio type" sortKey="radio_type" currentKey={sortKey} direction={direction} onSort={requestSort} hideMobile />
                    <SortableTh label="ARFCN" sortKey="latest_arfcn" currentKey={sortKey} direction={direction} onSort={requestSort} hideMobile />
                    <SortableTh label="Signal" sortKey="latest_signal_dbm" currentKey={sortKey} direction={direction} onSort={requestSort} />
                    <SortableTh label="Last seen" sortKey="last_seen_at" currentKey={sortKey} direction={direction} onSort={requestSort} />
                  </tr>
                </thead>
                <tbody>
                  {sorted.map((tower) => (
                    <tr key={tower.tower_key}>
                      <td className="mono">
                        <Link to={`/cellular/${encodeURIComponent(tower.tower_key)}`}>{tower.cell_id || "—"}</Link>
                        {tower.carrier_name && <div className="identifier-subtext">({tower.carrier_name})</div>}
                      </td>
                      <td className="mono hide-mobile">{tower.mnc || "—"}</td>
                      <td className="mono hide-mobile">{tower.tac_or_lac || "—"}</td>
                      <td className="hide-mobile">{tower.radio_type || "—"}</td>
                      <td className="mono hide-mobile">{tower.latest_arfcn ?? "—"}</td>
                      <td>{tower.latest_signal_dbm != null ? `${tower.latest_signal_dbm} dBm` : "—"}</td>
                      <td>{new Date(tower.last_seen_at).toLocaleString()}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <Pager page={page} pageSize={PAGE_SIZE} total={data.count} onPageChange={setPage} noun="cell towers" />
            </>
          )}
        </>
      )}
    </section>
  );
}
