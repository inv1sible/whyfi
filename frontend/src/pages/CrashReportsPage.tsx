import { Fragment, useEffect, useState } from "react";
import { api, searchQueryPart } from "../api/client";
import { Pager } from "../components/Pager";
import { TableControls } from "../components/TableControls";
import { useDebouncedValue } from "../hooks/useDebouncedValue";
import { usePolling } from "../hooks/usePolling";
import type { CrashReport } from "../api/types";

const PAGE_SIZE = 50;

function buildClaudeCodePrompt(report: CrashReport): string {
  return `The whyfi Android app crashed. Please investigate the root cause and fix it.

Device: ${report.device_model || "unknown"} — Android ${report.os_version || "unknown"}
App version: ${report.app_version || "unknown"}
Sensor: ${report.sensor_name ?? "(sensor deleted)"}
Crashed at: ${report.occurred_at}

Stack trace:
${report.stack_trace}

Find the underlying bug in the Android app source (android/app/src/main/java/com/whyfi/app),
fix it, and check whether the same class of issue could affect other radios/managers in
the scan pipeline (WiFi/Cellular/BLE/GNSS/LAN) before considering it done. Verify the fix
by building the APK through the existing android-builder pipeline and confirming the
change is present in the built classes.dex.`;
}

export function CrashReportsPage() {
  const [query, setQuery] = useState("");
  const debouncedQuery = useDebouncedValue(query);
  const [page, setPage] = useState(1);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [copyMessage, setCopyMessage] = useState<string | null>(null);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const [refreshKey, setRefreshKey] = useState(0);

  useEffect(() => {
    setPage(1);
  }, [debouncedQuery]);

  const { data, error, loading } = usePolling(
    () => api.crashReports(`?limit=${PAGE_SIZE}&page=${page}${searchQueryPart(debouncedQuery)}`),
    15000,
    [page, debouncedQuery, refreshKey],
  );
  const reports = data?.results ?? [];

  async function copyText(label: string, text: string) {
    try {
      await navigator.clipboard.writeText(text);
      setCopyMessage(`${label} copied to clipboard.`);
    } catch (err) {
      setCopyMessage(`Could not copy — ${err instanceof Error ? err.message : "clipboard access denied"}.`);
    }
  }

  async function handleDelete(id: string) {
    setDeleteError(null);
    try {
      await api.deleteCrashReport(id);
      if (expandedId === id) setExpandedId(null);
      setRefreshKey((k) => k + 1);
    } catch (err) {
      setDeleteError(err instanceof Error ? err.message : "Delete failed.");
    }
  }

  return (
    <section>
      <h1>Crash reports</h1>
      <p className="page-hint">
        Sent from the Android app's Settings → Diagnostics screen after a crash. Each report carries the exact
        stack trace and device info needed to diagnose it — copy it as-is to read, or copy it as a ready-to-paste
        prompt for fixing the underlying bug.
      </p>

      <TableControls searchValue={query} onSearchChange={setQuery} searchPlaceholder="Search device, sensor, app version…" />

      {loading && !data && <p>Loading…</p>}
      {error && <p className="error-text">Could not reach the backend: {error.message}</p>}
      {deleteError && <p className="error-text">{deleteError}</p>}
      {copyMessage && <p className="page-hint">{copyMessage}</p>}

      {data && data.count === 0 && (
        <p className="empty-state">
          {debouncedQuery.trim() ? "No crash reports match your search." : "No crash reports received yet."}
        </p>
      )}

      {data && data.count > 0 && (
        <>
          <table className="data-table">
            <thead>
              <tr>
                <th>Device</th>
                <th>Sensor</th>
                <th className="hide-mobile">App version</th>
                <th>Occurred</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {reports.map((report) => {
                const expanded = expandedId === report.id;
                return (
                  <Fragment key={report.id}>
                    <tr>
                      <td>
                        {report.device_model || "—"}
                        {report.os_version && <div className="identifier-subtext">Android {report.os_version}</div>}
                      </td>
                      <td>{report.sensor_name ?? "(sensor deleted)"}</td>
                      <td className="hide-mobile">{report.app_version || "—"}</td>
                      <td>{new Date(report.occurred_at).toLocaleString()}</td>
                      <td>
                        <button onClick={() => setExpandedId(expanded ? null : report.id)}>
                          {expanded ? "Hide" : "View"}
                        </button>
                      </td>
                    </tr>
                    {expanded && (
                      <tr>
                        <td colSpan={5}>
                          <pre className="build-log">{report.stack_trace}</pre>
                          <div style={{ display: "flex", gap: "0.5rem", flexWrap: "wrap", margin: "0.5rem 0" }}>
                            <button onClick={() => copyText("Raw crash log", `${report.occurred_at}\n${report.stack_trace}`)}>
                              Copy raw crash log
                            </button>
                            <button onClick={() => copyText("Claude Code prompt", buildClaudeCodePrompt(report))}>
                              Copy as Claude Code prompt
                            </button>
                            <button onClick={() => handleDelete(report.id)} className="danger-button">
                              Delete
                            </button>
                          </div>
                        </td>
                      </tr>
                    )}
                  </Fragment>
                );
              })}
            </tbody>
          </table>
          <Pager page={page} pageSize={PAGE_SIZE} total={data.count} onPageChange={setPage} noun="crash reports" />
        </>
      )}
    </section>
  );
}
