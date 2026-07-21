import { api } from "../api/client";
import { usePolling } from "../hooks/usePolling";

export function CellularPage() {
  const { data, error, loading } = usePolling(() => api.cellObservations("?limit=100"), 15000);

  return (
    <section>
      <h1>Cellular</h1>
      <p className="page-hint">
        Your phone's own serving and neighboring cell readings (signal strength, band, carrier). No spectrum scanning
        or external hardware — just what the modem already exposes.
      </p>

      {loading && !data && <p>Loading…</p>}
      {error && <p className="error-text">Could not reach the backend: {error.message}</p>}

      {data && data.results.length === 0 && (
        <p className="empty-state">No cellular observations yet. Run a scan from the Android app.</p>
      )}

      {data && data.results.length > 0 && (
        <table className="data-table">
          <thead>
            <tr>
              <th>Carrier</th>
              <th>MCC/MNC</th>
              <th>Type</th>
              <th>Serving?</th>
              <th>Cell ID</th>
              <th>TAC/LAC</th>
              <th>PCI</th>
              <th>ARFCN</th>
              <th>Bandwidth</th>
              <th>Timing adv.</th>
              <th>Signal</th>
              <th>RSRP / RSRQ / SINR</th>
              <th>Observed</th>
            </tr>
          </thead>
          <tbody>
            {data.results.map((cell) => (
              <tr key={cell.id}>
                <td>{cell.carrier_name || "—"}</td>
                <td className="mono">
                  {cell.mcc || "—"}/{cell.mnc || "—"}
                </td>
                <td>{cell.radio_type}</td>
                <td>{cell.is_serving_cell ? "Serving" : "Neighbor"}</td>
                <td className="mono">{cell.cell_id || "—"}</td>
                <td className="mono">{cell.tac_or_lac || "—"}</td>
                <td className="mono">{cell.physical_cell_id ?? "—"}</td>
                <td className="mono">{cell.arfcn ?? "—"}</td>
                <td>{cell.bandwidth_khz != null ? `${(cell.bandwidth_khz / 1000).toFixed(1)} MHz` : "—"}</td>
                <td>{cell.timing_advance ?? "—"}</td>
                <td>{cell.signal_dbm !== null ? `${cell.signal_dbm} dBm` : "—"}</td>
                <td>
                  {cell.rsrp ?? "—"} / {cell.rsrq ?? "—"} / {cell.sinr ?? "—"}
                </td>
                <td>{new Date(cell.observed_at).toLocaleString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
