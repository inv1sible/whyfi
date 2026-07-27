import { useRef, useState } from "react";
import { api } from "../api/client";
import type { Paginated, Sensor, SensorScanPolicy, SensorScanPolicyUpdate } from "../api/types";
import { usePolling } from "../hooks/usePolling";

// Fast enough that clicking Start feels responsive, given the device itself
// polls every ~15s. Paused while the tab is hidden — see the fetcher below.
const POLL_INTERVAL_MS = 5000;

// Mirrors the backend's own rule (sensors/serializers.py). The server stays
// the authority — this only avoids a pointless round trip for an obviously
// invalid value.
const MIN_INTERVAL_WITH_WIFI = 30;
const MIN_INTERVAL_WITHOUT_WIFI = 15;
const MIN_HEARTBEAT_SECONDS = 5;

const RADIOS = [
  { key: "include_wifi", label: "WiFi" },
  { key: "include_cellular", label: "Cellular" },
  { key: "include_ble", label: "BLE" },
  { key: "include_gnss", label: "GNSS" },
] as const;

function describeError(err: unknown): string {
  return err instanceof Error ? err.message : "Unknown error";
}

function formatWhen(iso: string | null): string {
  if (!iso) return "never";
  const seconds = Math.round((Date.now() - new Date(iso).getTime()) / 1000);
  if (seconds < 5) return "just now";
  if (seconds < 60) return `${seconds}s ago`;
  if (seconds < 3600) return `${Math.round(seconds / 60)}m ago`;
  if (seconds < 86400) return `${Math.round(seconds / 3600)}h ago`;
  return new Date(iso).toLocaleString();
}

/** The one-line answer to "I pressed Start, why is nothing happening?".
 *
 * Ordered most-blocking first: a device that isn't reachable at all makes
 * every other reason moot, and missing permissions make the radio-level
 * reasons moot in turn. */
function statusExplanation(policy: SensorScanPolicy): string | null {
  if (!policy.agent_online) {
    return "The remote agent isn't running on this phone. Open the whyfi app and turn on Remote control.";
  }
  if (policy.reported_permissions_granted === false) {
    return "Permissions have been revoked on the phone — open the app and grant them again.";
  }
  if (policy.reported_location_services_enabled === false) {
    return "Location services are turned off on the phone. Android returns no scan results without them.";
  }
  const blocked = [
    policy.reported_wifi_unavailable_reason && `WiFi: ${policy.reported_wifi_unavailable_reason}`,
    policy.reported_cellular_unavailable_reason && `Cellular: ${policy.reported_cellular_unavailable_reason}`,
    policy.reported_ble_unavailable_reason && `BLE: ${policy.reported_ble_unavailable_reason}`,
  ].filter(Boolean);
  return blocked.length > 0 ? blocked.join(" · ") : null;
}

function StatusBadge({ policy }: { policy: SensorScanPolicy }) {
  if (!policy.agent_online) return <span className="badge badge-neutral">Offline</span>;
  if (policy.reported_is_scanning) {
    return <span className="badge badge-ok">Scanning{policy.reported_phase ? ` · ${policy.reported_phase}` : ""}</span>;
  }
  if (policy.reported_is_continuous) return <span className="badge badge-ok">Armed</span>;
  return <span className="badge badge-warning">Idle</span>;
}

function SensorCard({ sensor, onChanged }: { sensor: Sensor; onChanged: () => void }) {
  const policy = sensor.scan_policy;
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Only held while a field is being edited, so the polled server value stays
  // the source of truth the rest of the time (and a change made in another
  // browser tab shows up here).
  const [transmitDraft, setTransmitDraft] = useState<string | null>(null);
  const [checkInDraft, setCheckInDraft] = useState<string | null>(null);

  async function apply(patch: SensorScanPolicyUpdate) {
    setBusy(true);
    setError(null);
    try {
      await api.setSensorScanPolicy(sensor.id, patch);
      onChanged();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  async function handleScanNow() {
    setBusy(true);
    setError(null);
    try {
      await api.sensorScanNow(sensor.id);
      onChanged();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  async function handleResetCounters() {
    setBusy(true);
    setError(null);
    try {
      await api.resetSensorCounters(sensor.id);
      onChanged();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  function commitTransmitInterval() {
    if (transmitDraft === null) return;
    const value = Number(transmitDraft);
    setTransmitDraft(null);
    const floor = policy.include_wifi ? MIN_INTERVAL_WITH_WIFI : MIN_INTERVAL_WITHOUT_WIFI;
    if (!Number.isFinite(value) || value === policy.scan_interval_seconds) return;
    if (value < floor) {
      setError(`Transmit interval must be at least ${floor}s${policy.include_wifi ? " while WiFi is included" : ""}.`);
      return;
    }
    apply({ scan_interval_seconds: value });
  }

  function commitCheckInInterval() {
    if (checkInDraft === null) return;
    const value = Number(checkInDraft);
    setCheckInDraft(null);
    if (!Number.isFinite(value) || value === policy.heartbeat_interval_seconds) return;
    if (value < MIN_HEARTBEAT_SECONDS) {
      setError(`Check-in interval must be at least ${MIN_HEARTBEAT_SECONDS}s.`);
      return;
    }
    apply({ heartbeat_interval_seconds: value });
  }

  const explanation = statusExplanation(policy);

  return (
    <div className="download-card">
      <div className="remote-card-header">
        <h2>{sensor.name}</h2>
        <StatusBadge policy={policy} />
        {policy.policy_pending && policy.agent_online && <span className="badge badge-warning">Pending</span>}
        {!sensor.is_active && <span className="badge badge-danger">Deactivated</span>}
      </div>

      <div className="remote-card-actions">
        <button
          onClick={() => apply({ remote_scan_enabled: !policy.remote_scan_enabled })}
          disabled={busy || !policy.agent_online}
          title={policy.agent_online ? undefined : "This phone's remote agent isn't running"}
        >
          {policy.remote_scan_enabled ? "Stop scanning" : "Start scanning"}
        </button>
        <button onClick={handleScanNow} disabled={busy || !policy.agent_online}>
          Scan once now
        </button>
      </div>

      <div className="band-selector">
        {RADIOS.map((radio) => (
          <button
            key={radio.key}
            className={policy[radio.key] ? "active" : ""}
            onClick={() => apply({ [radio.key]: !policy[radio.key] } as SensorScanPolicyUpdate)}
            disabled={busy}
          >
            {radio.label}
          </button>
        ))}
      </div>

      <div className="remote-intervals">
        <label className="field remote-interval">
          <span title="How often the phone runs a scan pass and sends it up">Transmit (s)</span>
          <input
            type="number"
            min={MIN_INTERVAL_WITHOUT_WIFI}
            step={5}
            value={transmitDraft ?? policy.scan_interval_seconds}
            onChange={(e) => setTransmitDraft(e.target.value)}
            onBlur={commitTransmitInterval}
            onKeyDown={(e) => e.key === "Enter" && e.currentTarget.blur()}
            disabled={busy}
          />
        </label>
        <label className="field remote-interval">
          <span
            title={
              "How often the phone checks in for instructions — this is the delay before Start/Stop takes effect. " +
              "While armed but not scanning the phone backs off to 4× this (capped at 60s) to save battery, so " +
              "starting a scan can take that much longer to take effect than stopping one."
            }
          >
            Check-in (s)
          </span>
          <input
            type="number"
            min={MIN_HEARTBEAT_SECONDS}
            step={5}
            value={checkInDraft ?? policy.heartbeat_interval_seconds}
            onChange={(e) => setCheckInDraft(e.target.value)}
            onBlur={commitCheckInInterval}
            onKeyDown={(e) => e.key === "Enter" && e.currentTarget.blur()}
            disabled={busy}
          />
        </label>
      </div>

      {error && <p className="error-text">{error}</p>}
      {explanation && <p className="page-hint">{explanation}</p>}

      <p className="page-hint remote-chips">
        <span>Heartbeat {formatWhen(policy.last_heartbeat_at)}</span>
        <span>Last scan {formatWhen(sensor.last_scan_upload_at)}</span>
        {policy.reported_completed_scans !== null && (
          <span>
            {policy.reported_completed_scans} passes this session{" "}
            <button className="link-button" onClick={handleResetCounters} disabled={busy || !policy.agent_online}>
              reset
            </button>
          </span>
        )}
        {policy.reported_battery_percent !== null && <span>Battery {policy.reported_battery_percent}%</span>}
        {policy.reported_app_version && <span className="mono">v{policy.reported_app_version}</span>}
      </p>
    </div>
  );
}

export function RemoteScanPage() {
  const [refreshKey, setRefreshKey] = useState(0);
  // Replaying the last response while the tab is hidden (rather than
  // resolving null) keeps usePolling's data populated — otherwise the list
  // would empty out and flash the "no sensors" state on the way back.
  const lastResult = useRef<Paginated<Sensor> | null>(null);

  const { data, error, loading } = usePolling(
    async () => {
      // A hidden tab doesn't need a 5s poll; this is a PWA that people leave
      // open on a phone.
      if (document.visibilityState === "hidden" && lastResult.current) {
        return lastResult.current;
      }
      const result = await api.sensors();
      lastResult.current = result;
      return result;
    },
    POLL_INTERVAL_MS,
    [refreshKey],
  );

  const sensors = data?.results ?? [];

  return (
    <div>
      <h1>Remote scanning</h1>
      <p className="page-hint">
        Start and stop scanning on your devices from here. Controls only reach a phone whose remote agent is already
        running — Android does not let a server wake a phone's scanner, so turn on <strong>Remote control</strong> in
        the whyfi app once per device. After a reboot or force-stop, open the app on the phone again to re-arm it.
      </p>

      {error && <p className="error-text">Could not reach the backend: {error.message}</p>}
      {loading && !data && <p>Loading…</p>}

      {data && sensors.length === 0 && (
        <p className="empty-state">No sensors yet — create one under Settings &gt; Sensors first.</p>
      )}

      {sensors.map((sensor) => (
        <SensorCard key={sensor.id} sensor={sensor} onChanged={() => setRefreshKey((k) => k + 1)} />
      ))}
    </div>
  );
}
