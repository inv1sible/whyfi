import { useState } from "react";
import { api } from "../api/client";
import { SortableTh } from "../components/SortableTh";
import { TableControls } from "../components/TableControls";
import { useSortableData } from "../hooks/useSortableData";
import { usePolling } from "../hooks/usePolling";
import { filterBySearch } from "../searchFilter";
import type { MonitorImportSummary, MonitorMission } from "../api/types";

function size(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KiB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MiB`;
}

function count(n: number | undefined, noun: string): string {
  const value = n ?? 0;
  return `${value.toLocaleString()} ${noun}${value === 1 ? "" : "s"}`;
}

/** One line of words for the free-text search, matching what the Import
 * column shows (the summary itself is an object, which the search skips). */
function importText(mission: MonitorMission): string {
  const s = mission.import_summary ?? {};
  if (!mission.imported_at || !s.status) return "pending";
  if (s.status !== "imported") return `${s.status} ${s.reason ?? ""}`;
  return `imported ${s.observations ?? 0} observations ${s.access_points ?? 0} access points`;
}

interface SearchableRow extends MonitorMission {
  uploaded_display: string;
  import_text: string;
}

/** What the server kept from the capture and, since 2026-11-12, what the
 * position rule dropped — the earlier summaries only said "0 observations"
 * after a day of indoor captures without a usable fix, with no hint why. */
function ImportCell({ mission }: { mission: MonitorMission }) {
  const s: MonitorImportSummary = mission.import_summary ?? {};
  if (!mission.imported_at || !s.status) return <span className="badge badge-neutral">Pending</span>;
  if (s.status === "failed") {
    return (
      <>
        <span className="badge badge-danger">Failed</span>
        <div className="identifier-subtext">{s.reason ?? "see the server log"}</div>
      </>
    );
  }
  if (s.status === "skipped") {
    return (
      <>
        <span className="badge badge-neutral">Skipped</span>
        <div className="identifier-subtext">{s.reason}</div>
      </>
    );
  }
  const noFix = s.dropped_no_fix ?? 0;
  const poorFix = s.dropped_poor_fix ?? 0;
  const unpaired = s.dropped_unpaired ?? 0;
  const dropped = noFix + poorFix + unpaired;
  const kept = s.readings ?? s.observations ?? 0;
  const limit = s.position_limit_m ?? 100;
  const badge =
    (s.observations ?? 0) === 0 ? (dropped > 0 ? "badge-danger" : "badge-neutral") : dropped > kept ? "badge-warning" : "badge-ok";
  const reasons = [
    noFix > 0 && `${noFix.toLocaleString()} without a fix within 3 min`,
    poorFix > 0 && `${poorFix.toLocaleString()} with a fix worse than ${limit} m`,
    unpaired > 0 && `${unpaired.toLocaleString()} from an app version without the boot clock`,
  ].filter(Boolean);
  return (
    <>
      <span className={`badge ${badge}`}>{count(s.observations, "observation")}</span>
      <div className="identifier-subtext">
        {count(s.access_points, "access point")} at {count(s.sessions, "position")} · {count(s.receiver_fixes, "receiver fix")}
      </div>
      {s.readings == null ? (
        <div className="identifier-subtext">Imported before dropped readings were counted.</div>
      ) : kept === 0 && dropped === 0 ? (
        <div className="identifier-subtext">No access-point readings in this capture's log.</div>
      ) : (
        <div className="identifier-subtext" title={reasons.join(" · ")}>
          {kept.toLocaleString()} readings positioned
          {dropped > 0 && `, ${dropped.toLocaleString()} dropped (${reasons.join(", ")})`}
        </div>
      )}
      {kept === 0 && dropped > 0 && (
        <div className="identifier-subtext warning-text">
          {s.best_fix_accuracy_m != null
            ? `The best fix in this capture was ±${Math.round(s.best_fix_accuracy_m)} m; `
            : "No receiver fix was recorded; "}
          the server positions readings only with a fix better than {limit} m. Capture outdoors with GPS, or wait for the
          phone's Live tab to show a green fix.
        </div>
      )}
    </>
  );
}

export function MonitorMissionsPage() {
  const { data, error, loading } = usePolling(() => api.monitorMissions(), 15000, []);
  const [query, setQuery] = useState("");
  const rows: SearchableRow[] = (data ?? []).map((m) => ({
    ...m,
    uploaded_display: new Date(m.uploaded_at).toLocaleString(),
    import_text: importText(m),
  }));
  const filtered = filterBySearch<SearchableRow>(rows, query);
  const { sorted, sortKey, direction, requestSort } = useSortableData<SearchableRow>(filtered, "uploaded_at", "desc");

  return (
    <section>
      <h1>USB monitoring</h1>
      <p className="page-hint">
        Captures from whyfi USB appear here after you choose Upload to backend on the phone, or automatically when
        automatic upload is on. The phone keeps its own copy. Each capture's access-point readings are imported as
        WiFi observations of the phone's "_USB" sensor and show up on the Heatmap and the WiFi pages; the Import column
        says what was kept and what the position rule dropped.
      </p>

      <TableControls searchValue={query} onSearchChange={setQuery} searchPlaceholder="Search sensor, mission id, status…" />

      {loading && !data && <p>Loading…</p>}
      {error && <p className="error-text">Could not load missions: {error.message}</p>}
      {data?.length === 0 && <p className="empty-state">No USB captures uploaded yet.</p>}
      {rows.length > 0 && (
        <table className="data-table">
          <thead>
            <tr>
              <SortableTh label="Uploaded" sortKey="uploaded_at" currentKey={sortKey} direction={direction} onSort={requestSort} />
              <SortableTh label="Sensor" sortKey="sensor" currentKey={sortKey} direction={direction} onSort={requestSort} />
              <th>Import</th>
              <th>Files</th>
            </tr>
          </thead>
          <tbody>
            {sorted.length === 0 && (
              <tr>
                <td colSpan={4} className="empty-state">No captures match your search.</td>
              </tr>
            )}
            {sorted.map((mission) => (
              <tr key={mission.id}>
                <td>
                  {mission.uploaded_display}
                  <div className="identifier-subtext mono" title={mission.id}>{mission.id.slice(0, 8)}</div>
                </td>
                <td>{mission.sensor}</td>
                <td><ImportCell mission={mission} /></td>
                <td>
                  {mission.report_url ? (
                    <a href={mission.report_url} target="_blank" rel="noreferrer">PDF ({size(mission.report_bytes)})</a>
                  ) : (
                    <span className="muted">no PDF yet</span>
                  )}
                  {" · "}
                  <a href={mission.log_url} download>Log ({size(mission.log_bytes)})</a>
                  {mission.pcap_url && (
                    <>
                      {" · "}
                      <a href={mission.pcap_url} download>PCAP ({size(mission.pcap_bytes)})</a>
                    </>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
