import { useParams } from "react-router-dom";
import { api } from "../api/client";
import { SecurityBadge } from "../components/SecurityBadge";
import { SimpleLineChart } from "../components/SimpleLineChart";
import { usePolling } from "../hooks/usePolling";

export function NetworkDetailPage() {
  const { bssid = "" } = useParams();

  const ap = usePolling(() => api.accessPoint(bssid), 20000, [bssid]);
  const observations = usePolling(() => api.wifiObservationsForAp(bssid), 20000, [bssid]);

  return (
    <section>
      <h1>{ap.data?.ssid || "(hidden network)"}</h1>
      <p className="mono page-hint">{bssid}</p>

      {ap.error && <p className="error-text">Could not reach the backend: {ap.error.message}</p>}

      {ap.data && (
        <dl className="detail-list">
          <dt>Security</dt>
          <dd>
            <SecurityBadge securityType={ap.data.latest_security_type} />
          </dd>
          <dt>Band</dt>
          <dd>{ap.data.latest_band ?? "—"}</dd>
          <dt>First seen</dt>
          <dd>{new Date(ap.data.first_seen_at).toLocaleString()}</dd>
          <dt>Last seen</dt>
          <dd>{new Date(ap.data.last_seen_at).toLocaleString()}</dd>
        </dl>
      )}

      {observations.data && observations.data.length > 0 && (
        <dl className="detail-list">
          <dt>Standard</dt>
          <dd>{observations.data[0].wifi_standard || "—"}</dd>
          <dt>Channel width</dt>
          <dd>{observations.data[0].channel_width_mhz != null ? `${observations.data[0].channel_width_mhz} MHz` : "—"}</dd>
          <dt>FTM/RTT capable</dt>
          <dd>{observations.data[0].is_80211mc_responder ? "Yes" : "No"}</dd>
          {observations.data[0].venue_name && (
            <>
              <dt>Venue</dt>
              <dd>{observations.data[0].venue_name}</dd>
            </>
          )}
          {observations.data[0].operator_friendly_name && (
            <>
              <dt>Operator</dt>
              <dd>{observations.data[0].operator_friendly_name}</dd>
            </>
          )}
        </dl>
      )}

      <h2>Signal history</h2>
      {observations.data && (
        <SimpleLineChart
          unit=" dBm"
          points={[...observations.data]
            .reverse()
            .map((o) => ({ label: o.observed_at, value: o.rssi }))}
        />
      )}
    </section>
  );
}
