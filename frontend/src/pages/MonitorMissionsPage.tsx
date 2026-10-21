import { api } from "../api/client";
import { usePolling } from "../hooks/usePolling";

function size(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KiB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MiB`;
}

export function MonitorMissionsPage() {
  const { data, error, loading } = usePolling(() => api.monitorMissions(), 15000, []);
  return (
    <section>
      <h1>Monitor missions</h1>
      <p className="page-hint">
        Completed captures appear here after you choose Upload to backend on the phone. The phone keeps its own copy.
      </p>
      {loading && !data && <p>Loading…</p>}
      {error && <p className="error-text">Could not load missions: {error.message}</p>}
      {data?.length === 0 && <p className="empty-state">No monitor missions uploaded yet.</p>}
      {data && data.length > 0 && (
        <table className="data-table">
          <thead><tr><th>Uploaded</th><th>Sensor</th><th>Mission</th><th>Files</th></tr></thead>
          <tbody>
            {data.map((mission) => (
              <tr key={mission.id}>
                <td>{new Date(mission.uploaded_at).toLocaleString()}</td>
                <td>{mission.sensor}</td>
                <td><code>{mission.id}</code></td>
                <td>
                  <a href={mission.report_url} target="_blank" rel="noreferrer">PDF ({size(mission.report_bytes)})</a>
                  {" · "}
                  <a href={mission.log_url} download>Log ({size(mission.log_bytes)})</a>
                  {mission.pcap_url && <>
                    {" · "}
                    <a href={mission.pcap_url} download>PCAP ({size(mission.pcap_bytes)})</a>
                  </>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
