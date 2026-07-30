import { Link } from "react-router-dom";
import { api, areaQuery } from "../api/client";
import { SortableTh } from "../components/SortableTh";
import { TableControls } from "../components/TableControls";
import { useFilter } from "../context/FilterContext";
import { useSortableData } from "../hooks/useSortableData";
import { usePolling } from "../hooks/usePolling";
import { filterBySearch } from "../searchFilter";
import type { CellTower } from "../api/types";

export function CellularPage() {
  const filter = useFilter();
  const { data, error, loading } = usePolling(
    () =>
      api.cellTowers(
        `?limit=200${filter.sessionLimit ? `&session_limit=${filter.sessionLimit}` : filter.since ? `&active_since=${filter.since}` : ""}${areaQuery(filter.area)}`,
      ),
    15000,
    [filter.since, filter.sessionLimit, filter.area?.lat, filter.area?.lng, filter.area?.radiusM],
  );
  const filtered = filterBySearch<CellTower>(data?.results ?? [], filter.searchQuery);
  const { sorted, sortKey, direction, requestSort } = useSortableData<CellTower>(
    filtered,
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

      <TableControls />

      {data && data.results.length === 0 && (
        <p className="empty-state">No cellular observations yet. Run a scan from the Android app.</p>
      )}

      {sorted.length > 0 && (
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
      )}
    </section>
  );
}
