import { api } from "../api/client";
import { NetworkTable } from "../components/NetworkTable";
import { usePolling } from "../hooks/usePolling";

export function DashboardPage() {
  const { data, error, loading } = usePolling(() => api.accessPoints("?active_since=" + since24h()), 15000);

  return (
    <section>
      <h1>WiFi networks</h1>
      <p className="page-hint">Networks seen in the last 24 hours, most recently seen first.</p>
      {loading && !data && <p>Loading…</p>}
      {error && <p className="error-text">Could not reach the backend: {error.message}</p>}
      {data && <NetworkTable accessPoints={data.results} />}
    </section>
  );
}

function since24h(): string {
  return new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString();
}
