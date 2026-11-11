import { useEffect, useState } from "react";
import { api, areaQuery } from "../api/client";
import { SortableTh } from "../components/SortableTh";
import { TableControls } from "../components/TableControls";
import { TruncationNotice } from "../components/TruncationNotice";
import { useFilter } from "../context/FilterContext";
import { describeArea } from "../focusArea";
import { useSortableData } from "../hooks/useSortableData";
import { formatDuration } from "../hooks/useTimeScanFilter";
import { usePolling } from "../hooks/usePolling";
import { filterBySearch } from "../searchFilter";
import type { ScanSession } from "../api/types";

/** The window and area in words, for the line above the table and the export
 * message: "the last 5 scans inside 250 m around 52.52000, 13.40500". */
function describeScope(filter: ReturnType<typeof useFilter>): string {
  let scope: string;
  if (filter.mode === "last-n-scans") {
    scope = filter.isAllScans ? "all scans" : `the last ${filter.scanCount} scan${filter.scanCount === 1 ? "" : "s"}`;
  } else if (filter.mode === "range") {
    const from = filter.rangeFrom ? new Date(filter.rangeFrom).toLocaleString() : "the beginning";
    const to = filter.rangeTo ? new Date(filter.rangeTo).toLocaleString() : "now";
    scope = `scans started between ${from} and ${to}`;
  } else {
    scope = filter.isAllTime ? "all scans" : `scans started ${formatDuration(filter.minutes)} or later`;
  }
  return filter.area ? `${scope} inside ${describeArea(filter.area)}` : scope;
}

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
  // The shared window / date range / focus area from the filter bar narrows
  // this list like every other page (user, 2026-11-11), and the export below
  // writes exactly the scans listed.
  const filter = useFilter();
  const windowOpts = { since: filter.since, until: filter.until, sessionLimit: filter.sessionLimit, area: filter.area };
  const { data, error, loading } = usePolling(
    () => api.scanSessionsIn(windowOpts),
    15000,
    [refreshKey, filter.since, filter.until, filter.sessionLimit, areaQuery(filter.area)],
  );
  const scope = describeScope(filter);
  const [query, setQuery] = useState("");
  const [selected, setSelected] = useState<Set<string>>(new Set());
  // Set the moment "Delete selected" is clicked — a snapshot of exactly what
  // was selected then, not a re-read of the live `selected` state. Confirm
  // acts on this snapshot, not on `selected` itself: the previous version
  // used one shared handler that branched on a boolean `armed` flag re-read
  // at click time, which meant "arm" and "confirm" could only be told apart
  // by that flag's value at the exact moment each click's handler ran. This
  // version makes the two clicks structurally distinct (arm sets a value,
  // confirm consumes it) so there's no shared state whose staleness matters.
  const [armedIds, setArmedIds] = useState<Set<string> | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const [resolving, setResolving] = useState(false);
  const [ioBusy, setIoBusy] = useState(false);
  const [ioMessage, setIoMessage] = useState<string | null>(null);
  const [ioError, setIoError] = useState<string | null>(null);

  /** Downloads the current view's sessions as a re-importable JSON file.
   *
   * The payload is the same shape the ingest endpoint accepts, so this file is
   * a restore point rather than a report — importing it later recreates the
   * data exactly, and importing it twice is a no-op. */
  async function handleExport() {
    setIoBusy(true);
    setIoError(null);
    setIoMessage(null);
    try {
      const bundle = await api.exportScanSessions(windowOpts);
      const blob = new Blob([JSON.stringify(bundle, null, 2)], { type: "application/json" });
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = `whyfi-scans-${new Date().toISOString().slice(0, 10)}.json`;
      link.click();
      URL.revokeObjectURL(url);
      setIoMessage(
        bundle.truncated
          ? `Exported ${bundle.session_count} scans — this hit the ${bundle.session_limit} cap, so it is NOT a complete backup. Narrow the range and export again.`
          : `Exported ${bundle.session_count} scans (${scope}).`,
      );
    } catch {
      setIoError("Export failed.");
    } finally {
      setIoBusy(false);
    }
  }

  async function handleImport(file: File) {
    setIoBusy(true);
    setIoError(null);
    setIoMessage(null);
    try {
      const bundle = JSON.parse(await file.text());
      const result = await api.importScanSessions(bundle);
      setIoMessage(
        `Imported ${result.created} scan(s); ${result.skipped} already present` +
          (result.failed_count ? `; ${result.failed_count} failed` : "") +
          ".",
      );
      setRefreshKey((k) => k + 1);
    } catch {
      setIoError("Import failed — is that a whyfi export file?");
    } finally {
      setIoBusy(false);
    }
  }
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
    setArmedIds(null);
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

  function handleArmDelete() {
    setArmedIds(new Set(selected));
  }

  function handleCancelArm() {
    setArmedIds(null);
  }

  async function handleConfirmDelete() {
    if (!armedIds) return;
    setDeleting(true);
    setDeleteError(null);
    try {
      await api.bulkDeleteScanSessions([...armedIds]);
      setSelected(new Set());
      setArmedIds(null);
      setRefreshKey((k) => k + 1);
    } catch (err) {
      setDeleteError(err instanceof Error ? err.message : "Delete failed.");
    } finally {
      setDeleting(false);
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

      <div className="control-row">
        <button onClick={handleExport} disabled={ioBusy}>
          {ioBusy ? "Working…" : "Export scans"}
        </button>
        <label className="import-label">
          Import scans{" "}
          <input
            type="file"
            accept="application/json,.json"
            disabled={ioBusy}
            onChange={(e) => {
              const file = e.target.files?.[0];
              // Reset so re-picking the same file fires change again.
              e.target.value = "";
              if (file) handleImport(file);
            }}
          />
        </label>
      </div>
      <p className="page-hint">
        Export writes the scans listed below ({scope}) with their observations to a JSON file in the same format the
        app uploads with, so it's a restore point rather than a report. For a full backup choose "All scans" in the
        filter bar and clear the focus area first. Importing it recreates the data; importing the same file twice
        changes nothing, because scans are matched on their original id.
      </p>
      {ioMessage && <p className={ioMessage.includes("NOT a complete") ? "warning-text" : "page-hint"}>{ioMessage}</p>}
      {ioError && <p className="error-text">{ioError}</p>}

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
      {data && (
        <p className="page-hint">
          Showing {scope}
          {data.count > 0 ? ` — ${data.count} scan${data.count === 1 ? "" : "s"}` : ""}. Change the window, date range or
          focus area in the bar above.
        </p>
      )}
      {data && rows.length === 0 && (
        <p className="empty-state">
          {filter.area || filter.sessionLimit || filter.since
            ? "No scans match the current window and focus area."
            : "No scan sessions recorded yet."}
        </p>
      )}

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
                    {s.location_correction && (
                      <div
                        className="identifier-subtext"
                        title={`${s.location_correction_reason ?? ""}. Original: ${
                          s.raw_latitude != null && s.raw_longitude != null
                            ? `${s.raw_latitude.toFixed(4)}, ${s.raw_longitude.toFixed(4)}`
                            : "none"
                        }${s.raw_location_provider ? ` (${s.raw_location_provider})` : ""}`}
                      >
                        {s.location_correction === "fused"
                          ? "Position corrected to the fused fix"
                          : "Implausible position left off the map"}
                      </div>
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
          {!armedIds ? (
            <>
              <span>{selected.size} scan{selected.size === 1 ? "" : "s"} selected</span>
              <button onClick={handleArmDelete}>Delete selected</button>
              <button onClick={clearSelection}>Cancel</button>
            </>
          ) : (
            <>
              <span>
                Delete {armedIds.size} scan{armedIds.size === 1 ? "" : "s"}? This also deletes every WiFi/cellular/
                BLE/satellite/LAN observation in {armedIds.size === 1 ? "it" : "them"} — cannot be undone.
              </span>
              <button onClick={handleConfirmDelete} disabled={deleting} className="danger-button">
                {deleting ? "Deleting…" : "Confirm delete"}
              </button>
              <button onClick={handleCancelArm} disabled={deleting}>
                Cancel
              </button>
            </>
          )}
        </div>
      )}
    </section>
  );
}
