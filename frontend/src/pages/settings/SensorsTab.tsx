import QRCode from "qrcode";
import { useEffect, useState, type FormEvent, type ReactNode } from "react";
import { ApiError, api, getBackendUrlOverride } from "../../api/client";
import type { Sensor } from "../../api/types";
import { TableControls } from "../../components/TableControls";

interface RevealedToken {
  name: string;
  token: string;
}

/** Set once a delete attempt comes back 409 (this sensor has scan
 * sessions) — replaces the plain confirm/cancel step with a real choice
 * instead of a dead-end error message. */
interface DeleteConflict {
  sensorId: string;
  scanSessionCount: number;
}

function describeError(err: unknown): string {
  // ApiError's body is the parsed JSON response — surface {"detail": "..."}
  // (the shape every backend error path in this project returns, see
  // scans/views.py's bulk_delete/perform_destroy) instead of the raw
  // "whyfi API error 409 for /sensors/...: {...}" wrapper message.
  if (err instanceof ApiError && err.body && typeof err.body === "object" && "detail" in err.body) {
    return String((err.body as { detail: unknown }).detail);
  }
  return err instanceof Error ? err.message : "Unknown error";
}

// Prefixed so the Android app can tell "this is a whyfi setup code" apart
// from an arbitrary QR code before it tries to parse JSON out of it.
const SETUP_QR_PREFIX = "whyfi-setup:";

function setupQrPayload(sensor: { name: string; token: string }): string {
  const backend = getBackendUrlOverride() ?? window.location.origin;
  return SETUP_QR_PREFIX + JSON.stringify({ backend, token: sensor.token, name: sensor.name });
}

function SetupQrCode({ sensor }: { sensor: { name: string; token: string } }) {
  const [dataUrl, setDataUrl] = useState<string | null>(null);

  useEffect(() => {
    QRCode.toDataURL(setupQrPayload(sensor), { width: 220, margin: 1 }).then(setDataUrl);
  }, [sensor]);

  if (!dataUrl) return null;
  return (
    <div>
      <p className="page-hint">
        Or scan this in the whyfi app (Settings → Scan QR to configure) instead of typing the backend URL and token by
        hand:
      </p>
      {/* Opens the same image full-size in a new tab — useful when this page
          is shown small (e.g. a laptop screen across the room from the phone
          doing the scanning). */}
      <a href={dataUrl} target="_blank" rel="noreferrer noopener">
        <img src={dataUrl} alt="QR code to configure a whyfi sensor" width={220} height={220} />
      </a>
    </div>
  );
}

export function SensorsTab() {
  const [sensors, setSensors] = useState<Sensor[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [newName, setNewName] = useState("");
  const [creating, setCreating] = useState(false);
  const [revealed, setRevealed] = useState<RevealedToken | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [confirmDeleteId, setConfirmDeleteId] = useState<string | null>(null);
  const [deleteConflict, setDeleteConflict] = useState<DeleteConflict | null>(null);

  function loadSensors() {
    setLoading(true);
    api
      .sensors()
      .then((r) => {
        setSensors(r.results);
        setError(null);
      })
      .catch((err) => setError(err.message))
      .finally(() => setLoading(false));
  }

  useEffect(loadSensors, []);

  async function handleCreate(event: FormEvent) {
    event.preventDefault();
    const name = newName.trim();
    if (!name) return;
    setCreating(true);
    setError(null);
    try {
      const sensor = await api.createSensor(name);
      setRevealed({ name: sensor.name, token: sensor.token });
      setNewName("");
      loadSensors();
    } catch (err) {
      setError(`Could not create sensor — ${describeError(err)}`);
    } finally {
      setCreating(false);
    }
  }

  async function handleRegenerate(sensor: Sensor) {
    if (!window.confirm(`Regenerate the token for "${sensor.name}"? The old token stops working immediately.`)) {
      return;
    }
    try {
      const updated = await api.regenerateSensorToken(sensor.id);
      setRevealed({ name: updated.name, token: updated.token });
    } catch (err) {
      setError(`Could not regenerate token — ${describeError(err)}`);
    }
  }

  async function handleToggleActive(sensor: Sensor) {
    const activating = !sensor.is_active;
    if (
      !activating &&
      !window.confirm(
        `Deactivate "${sensor.name}"? Its token stops authenticating immediately — it won't be able to check in ` +
          "or upload scans until reactivated. Its existing scan history is unaffected.",
      )
    ) {
      return;
    }
    setBusyId(sensor.id);
    setError(null);
    try {
      await api.setSensorActive(sensor.id, activating);
      setDeleteConflict(null);
      loadSensors();
    } catch (err) {
      setError(`Could not update "${sensor.name}" — ${describeError(err)}`);
    } finally {
      setBusyId(null);
    }
  }

  async function handleDelete(sensor: Sensor, opts: { onConflict?: "delete_data" | "keep_data" } = {}) {
    setBusyId(sensor.id);
    setError(null);
    try {
      await api.deleteSensor(sensor.id, opts);
      setConfirmDeleteId(null);
      setDeleteConflict(null);
      loadSensors();
    } catch (err) {
      if (err instanceof ApiError && err.status === 409 && err.body && typeof err.body === "object") {
        const count = (err.body as { scan_session_count?: number }).scan_session_count ?? 0;
        setDeleteConflict({ sensorId: sensor.id, scanSessionCount: count });
      } else {
        setError(`Could not delete "${sensor.name}" — ${describeError(err)}`);
      }
    } finally {
      setBusyId(null);
    }
  }

  // Shared between the desktop table row and the mobile card — same
  // buttons, same states, just laid out differently.
  function renderActions(sensor: Sensor): ReactNode {
    const busy = busyId === sensor.id;

    if (deleteConflict?.sensorId === sensor.id) {
      const count = deleteConflict.scanSessionCount;
      return (
        <div className="sensor-actions">
          <p className="page-hint">
            "{sensor.name}" has {count} scan session{count === 1 ? "" : "s"}. Delete the sensor and that data
            together, or delete the sensor but keep the data (its scans stay, just without a device attached)?
          </p>
          <div style={{ display: "flex", gap: "0.5rem", flexWrap: "wrap" }}>
            <button
              onClick={() => handleDelete(sensor, { onConflict: "delete_data" })}
              disabled={busy}
              className="danger-button"
            >
              Delete sensor + all its data
            </button>
            <button onClick={() => handleDelete(sensor, { onConflict: "keep_data" })} disabled={busy} className="danger-button">
              Delete sensor, keep data
            </button>
            <button onClick={() => setDeleteConflict(null)} disabled={busy}>
              Cancel
            </button>
          </div>
        </div>
      );
    }

    return (
      <div className="sensor-actions">
        <button onClick={() => handleRegenerate(sensor)} disabled={busy}>
          Regenerate token
        </button>
        <button onClick={() => handleToggleActive(sensor)} disabled={busy}>
          {sensor.is_active ? "Deactivate" : "Reactivate"}
        </button>
        {confirmDeleteId === sensor.id ? (
          <>
            <button onClick={() => handleDelete(sensor)} disabled={busy} className="danger-button">
              Confirm delete
            </button>
            <button onClick={() => setConfirmDeleteId(null)} disabled={busy}>
              Cancel
            </button>
          </>
        ) : (
          <button onClick={() => setConfirmDeleteId(sensor.id)} disabled={busy}>
            Delete
          </button>
        )}
      </div>
    );
  }

  return (
    <div>
      <p className="page-hint">
        A sensor is one Android device. Create one to get a token, then paste the backend URL and this token into the
        app's Settings screen — or scan the QR code below — to start scanning.
      </p>

      <form onSubmit={handleCreate} className="field">
        <span>New sensor name</span>
        <input value={newName} onChange={(e) => setNewName(e.target.value)} placeholder="e.g. Pixel 8" />
        <button type="submit" disabled={creating || !newName.trim()}>
          {creating ? "Creating…" : "Create sensor"}
        </button>
      </form>

      {revealed && (
        <div className="download-card">
          <p>
            Token for <strong>{revealed.name}</strong> — copy it now, it won't be shown again:
          </p>
          <pre className="build-log">{revealed.token}</pre>
          <SetupQrCode sensor={revealed} />
          <button onClick={() => setRevealed(null)}>Done</button>
        </div>
      )}

      {error && <p className="error-text">{error}</p>}
      {loading && <p>Loading…</p>}

      {!loading && sensors.length === 0 && <p className="empty-state">No sensors yet — create one above.</p>}

      {sensors.length > 0 && (
        <>
          <TableControls />

          {/* Desktop/wide: a table. Below 640px (see .sensors-table /
              .sensor-cards in index.css) a plain data-table with 5 columns
              plus an action row has nowhere to go but a horizontal scroll,
              which is what this replaces on narrow screens. */}
          <table className="data-table sensors-table">
            <thead>
              <tr>
                <th>Name</th>
                <th>Type</th>
                <th>Active</th>
                <th>Last seen</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {sensors.map((sensor) => (
                <tr key={sensor.id}>
                  <td>{sensor.name}</td>
                  <td>{sensor.sensor_type}</td>
                  <td>
                    <span className={`badge ${sensor.is_active ? "badge-ok" : "badge-neutral"}`}>
                      {sensor.is_active ? "Active" : "Deactivated"}
                    </span>
                  </td>
                  <td>{sensor.last_seen_at ? new Date(sensor.last_seen_at).toLocaleString() : "Never"}</td>
                  <td>{renderActions(sensor)}</td>
                </tr>
              ))}
            </tbody>
          </table>

          <div className="sensor-cards">
            {sensors.map((sensor) => (
              <div className="download-card" key={sensor.id}>
                <div className="remote-card-header">
                  <h2>{sensor.name}</h2>
                  <span className={`badge ${sensor.is_active ? "badge-ok" : "badge-neutral"}`}>
                    {sensor.is_active ? "Active" : "Deactivated"}
                  </span>
                </div>
                <p className="page-hint">
                  {sensor.sensor_type} · last seen{" "}
                  {sensor.last_seen_at ? new Date(sensor.last_seen_at).toLocaleString() : "never"}
                </p>
                {renderActions(sensor)}
              </div>
            ))}
          </div>
        </>
      )}
    </div>
  );
}
