import QRCode from "qrcode";
import { useEffect, useState, type FormEvent } from "react";
import { ApiError, api, getBackendUrlOverride } from "../../api/client";
import type { Sensor } from "../../api/types";
import { TableControls } from "../../components/TableControls";

interface RevealedToken {
  name: string;
  token: string;
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
      loadSensors();
    } catch (err) {
      setError(`Could not update "${sensor.name}" — ${describeError(err)}`);
    } finally {
      setBusyId(null);
    }
  }

  async function handleDelete(sensor: Sensor) {
    setBusyId(sensor.id);
    setError(null);
    try {
      await api.deleteSensor(sensor.id);
      setConfirmDeleteId(null);
      loadSensors();
    } catch (err) {
      // Most likely the 409 guard (this sensor has scan sessions) — surfaced
      // inline rather than losing the confirm-delete row, so the message
      // ("delete its scans from Manage Scans first, or deactivate instead")
      // stays visible right next to the button that triggered it.
      setError(`Could not delete "${sensor.name}" — ${describeError(err)}`);
    } finally {
      setBusyId(null);
    }
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
          <table className="data-table">
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
                <td style={{ display: "flex", gap: "0.5rem", flexWrap: "wrap" }}>
                  <button onClick={() => handleRegenerate(sensor)} disabled={busyId === sensor.id}>
                    Regenerate token
                  </button>
                  <button onClick={() => handleToggleActive(sensor)} disabled={busyId === sensor.id}>
                    {sensor.is_active ? "Deactivate" : "Reactivate"}
                  </button>
                  {confirmDeleteId === sensor.id ? (
                    <>
                      <button
                        onClick={() => handleDelete(sensor)}
                        disabled={busyId === sensor.id}
                        className="danger-button"
                      >
                        Confirm delete
                      </button>
                      <button onClick={() => setConfirmDeleteId(null)} disabled={busyId === sensor.id}>
                        Cancel
                      </button>
                    </>
                  ) : (
                    <button onClick={() => setConfirmDeleteId(sensor.id)} disabled={busyId === sensor.id}>
                      Delete
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        </>
      )}
    </div>
  );
}
