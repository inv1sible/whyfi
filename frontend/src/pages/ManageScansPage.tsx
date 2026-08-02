import { useEffect, useState } from "react";
import { api } from "../api/client";
import { SortableTh } from "../components/SortableTh";
import { TableControls } from "../components/TableControls";
import { TruncationNotice } from "../components/TruncationNotice";
import { useSortableData } from "../hooks/useSortableData";
import { usePolling } from "../hooks/usePolling";
import { filterBySearch } from "../searchFilter";
import type { ScanSession } from "../api/types";

// Free-text search runs on this instead of the raw ScanSession so a typed
// date/time (in whatever format is actually shown in the table) matches,
// not just the raw ISO string underneath.
interface SearchableRow extends ScanSession {
  started_display: string;
}

// One compact column instead of five (WiFi/Cell/BLE/Sat/LAN each had their
// own) — this is a management/cleanup table, not a per-radio browser (those
// already exist on their own pages), so the per-scan breakdown only needs
// to be scannable at a glance, not sortable column-by-column. Zero counts
// are omitted rather than shown as "WiFi 0" — a LAN scan pass legitimately
// has nothing in the other four, and spelling that out on every single row
// is exactly the clutter this is meant to remove.
function formatObservationCounts(s: ScanSession): string {
  const parts = [
    s.wifi_count > 0 && `WiFi ${s.wifi_count}`,
    s.cell_count > 0 && `Cell ${s.cell_count}`,
    s.ble_count > 0 && `BLE ${s.ble_count}`,
    s.satellite_count > 0 && `Sat ${s.satellite_count}`,
    s.lan_count > 0 && `LAN ${s.lan_count}`,
  ].filter(Boolean);
  return parts.length > 0 ? parts.join(" · ") : "—";
}

export function ManageScansPage() {
  // Bumped after a successful delete/resolve to force an immediate refetch
  // rather than waiting out the rest of the 15s poll interval — usePolling
  // has no public refetch(), but changing a dep re-fires its effect right
  // away.
  const [refreshKey, setRefreshKey] = useState(0);
  const { data, error, loading } = usePolling(() => api.scanSessions("?limit=1000"), 15000, [refreshKey]);
  const [query, setQuery] = useState("");
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [armed, setArmed] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const [resolving, setResolving] = useState(false);
  const [resolveError, setResolveError] = useState<string | null>(null);

  const rows: SearchableRow[] = (data?.results ?? []).map((s) => ({
    ...s,
    started_display: new Date(s.started_at).toLocaleString(),
  }));
  const filtered = filterBySearch<SearchableRow>(rows, query);
  const { sorted, sortKey, direction, requestSort } = useSortableData<SearchableRow>(filtered, "started_at", "desc");

  const allFilteredSelected = sorted.length > 0 && sorted.every((s) => selected.has(s.id));
  const hasActiveFilter = query.trim().length > 0;
  const missingAddressCount = rows.filter((s) => s.latitude != null && s.resolved_address == null).length;

  // A stale "confirm delete?" prompt applying to a since-changed selection
  // would be actively dangerous — disarm the moment the selection changes.
  useEffect(() => {
    setArmed(false);
  }, [selected]);

  function toggleOne(id: string) {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  function toggleAllFiltered() {
    setSelected((prev) => {
      if (allFilteredSelected) return new Set();
      const next = new Set(prev);
      sorted.forEach((s) => next.add(s.id));
      return next;
    });
  }

  // "Delete everything except X": search for X, then select its complement
  // in one click instead of hand-picking every other row.
  function selectAllExceptFiltered() {
    const filteredIds = new Set(filtered.map((s) => s.id));
    const next = new Set<string>();
    rows.forEach((s) => {
      if (!filteredIds.has(s.id)) next.add(s.id);
    });
    setSelected(next);
  }

  function clearSelection() {
    setSelected(new Set());
  }

  async function handleDelete() {
    if (!armed) {
      setArmed(true);
      return;
    }
    setDeleting(true);
    setDeleteError(null);
    try {
      await api.bulkDeleteScanSessions([...selected]);
      setSelected(new Set());
      setRefreshKey((k) => k + 1);
    } catch (err) {
      setDeleteError(err instanceof Error ? err.message : "Delete failed.");
    } finally {
      setDeleting(false);
      setArmed(false);
    }
  }

  async function handleResolveAddresses() {
    setResolving(true);
    setResolveError(null);
    try {
      await api.resolveScanAddresses(20);
      setRefreshKey((k) => k + 1);
    } catch (err) {
      setResolveError(err instanceof Error ? err.message : "Could not resolve addresses.");
    } finally {
      setResolving(false);
    }
  }

  return (
    <section>
      <h1>Manage scans</h1>
      <p className="page-hint">
        Every scan session recorded, most recent first. Deleting a scan removes it and every WiFi/cellular/BLE/
        satellite/LAN observation tied to it — the aggregate network/tower/device rows themselves (BSSIDs, cell
        towers, LAN devices) are left in place even if this was their only sighting.
      </p>

      <TableControls
        searchValue={query}
        onSearchChange={setQuery}
        searchPlaceholder="Search sensor, address, device, timestamp…"
      />

      {missingAddressCount > 0 && (
        <p className="page-hint">
          {missingAddressCount} scan{missingAddressCount === 1 ? "" : "s"} without a resolved address.{" "}
          <button onClick={handleResolveAddresses} disabled={resolving}>
            {resolving ? "Resolving…" : "Resolve addresses"}
          </button>{" "}
          (looks up up to 20 at a time via OpenStreetMap, rate-limited — click again for more)
        </p>
      )}
      {resolveError && <p className="error-text">{resolveError}</p>}

      {loading && !data && <p>Loading…</p>}
      {error && <p className="error-text">Could not reach the backend: {error.message}</p>}
      {data && <TruncationNotice shown={data.results.length} total={data.count} noun="scans" />}
      {data && rows.length === 0 && <p className="empty-state">No scan sessions recorded yet.</p>}

      {rows.length > 0 && (
        <>
          <div style={{ display: "flex", alignItems: "center", gap: "0.75rem", margin: "0.75rem 0", flexWrap: "wrap" }}>
            <button onClick={selectAllExceptFiltered} disabled={!hasActiveFilter}>
              Select all except filtered
            </button>
            {selected.size > 0 && <button onClick={clearSelection}>Clear selection</button>}
          </div>

          <table className="data-table">
            <thead>
              <tr>
                <th>
                  <input
                    type="checkbox"
                    checked={allFilteredSelected}
                    onChange={toggleAllFiltered}
                    aria-label="Select all filtered"
                  />
                </th>
                <SortableTh label="Started" sortKey="started_at" currentKey={sortKey} direction={direction} onSort={requestSort} />
                <SortableTh label="Sensor" sortKey="sensor_name" currentKey={sortKey} direction={direction} onSort={requestSort} />
                <th>Location</th>
                <th className="hide-mobile">Data</th>
              </tr>
            </thead>
            <tbody>
              {sorted.length === 0 && (
                <tr>
                  <td colSpan={5} className="empty-state">No scans match your search.</td>
                </tr>
              )}
              {sorted.map((s) => (
                <tr key={s.id}>
                  <td>
                    <input
                      type="checkbox"
                      checked={selected.has(s.id)}
                      onChange={() => toggleOne(s.id)}
                      aria-label={`Select scan ${s.id}`}
                    />
                  </td>
                  <td>{s.started_display}</td>
                  <td>{s.sensor_name ?? "(sensor deleted)"}</td>
                  <td>
                    {s.resolved_address ? (
                      <>
                        {s.resolved_address}
                        {s.latitude != null && s.longitude != null && (
                          <div className="identifier-subtext mono">
                            {s.latitude.toFixed(4)}, {s.longitude.toFixed(4)}
                          </div>
                        )}
                      </>
                    ) : s.latitude != null && s.longitude != null ? (
                      <span className="mono">
                        {s.latitude.toFixed(4)}, {s.longitude.toFixed(4)}
                      </span>
                    ) : (
                      "—"
                    )}
                  </td>
                  <td className="hide-mobile">{formatObservationCounts(s)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}

      {selected.size > 0 && (
        <div className="floating-action-bar">
          {deleteError && <p className="error-text">{deleteError}</p>}
          {!armed ? (
            <>
              <span>{selected.size} scan{selected.size === 1 ? "" : "s"} selected</span>
              <button onClick={handleDelete}>Delete selected</button>
              <button onClick={clearSelection}>Cancel</button>
            </>
          ) : (
            <>
              <span>
                Delete {selected.size} scan{selected.size === 1 ? "" : "s"}? This also deletes every WiFi/cellular/
                BLE/satellite/LAN observation in {selected.size === 1 ? "it" : "them"} — cannot be undone.
              </span>
              <button onClick={handleDelete} disabled={deleting} className="danger-button">
                {deleting ? "Deleting…" : "Confirm delete"}
              </button>
              <button onClick={() => setArmed(false)} disabled={deleting}>
                Cancel
              </button>
            </>
          )}
        </div>
      )}
    </section>
  );
}
