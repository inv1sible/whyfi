import { useEffect, useState, type FormEvent } from "react";
import { api } from "../../api/client";
import type { Sensor } from "../../api/types";

interface RevealedToken {
  name: string;
  token: string;
}

function describeError(err: unknown): string {
  return err instanceof Error ? err.message : "Unknown error";
}

export function SensorsTab() {
  const [sensors, setSensors] = useState<Sensor[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [newName, setNewName] = useState("");
  const [creating, setCreating] = useState(false);
  const [revealed, setRevealed] = useState<RevealedToken | null>(null);

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

  return (
    <div>
      <p className="page-hint">
        A sensor is one Android device. Create one to get a token, then paste the backend URL and this token into the
        app's Settings screen to start scanning.
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
          <button onClick={() => setRevealed(null)}>Done</button>
        </div>
      )}

      {error && <p className="error-text">{error}</p>}
      {loading && <p>Loading…</p>}

      {!loading && sensors.length === 0 && <p className="empty-state">No sensors yet — create one above.</p>}

      {sensors.length > 0 && (
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
                <td>{sensor.is_active ? "Yes" : "No"}</td>
                <td>{sensor.last_seen_at ? new Date(sensor.last_seen_at).toLocaleString() : "Never"}</td>
                <td>
                  <button onClick={() => handleRegenerate(sensor)}>Regenerate token</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
