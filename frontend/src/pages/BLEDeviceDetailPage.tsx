import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { api } from "../api/client";
import { DeviceTypeBadge } from "../components/DeviceTypeBadge";
import { RadioMap } from "../components/RadioMap";
import { usePolling } from "../hooks/usePolling";
import { bearingToCompass, formatDistance, haversineDistanceMeters, initialBearingDegrees } from "../geo";

export function BLEDeviceDetailPage() {
  const { identifier = "" } = useParams();
  const sightings = usePolling(() => api.bleObservationsForDevice(identifier), 15000, [identifier]);

  const [browserLocation, setBrowserLocation] = useState<GeolocationCoordinates | null>(null);
  const [locationError, setLocationError] = useState<string | null>(null);

  useEffect(() => {
    if (!navigator.geolocation) {
      setLocationError("This browser doesn't support geolocation.");
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (position) => setBrowserLocation(position.coords),
      (err) => setLocationError(err.message),
      { enableHighAccuracy: true, timeout: 10000 },
    );
  }, []);

  const results = sightings.data?.results ?? [];
  const latest = results[0];
  // Results come back newest-first; the path line should trace the
  // device's movement in the order it actually happened.
  const mapPoints = results
    .filter((s) => s.latitude != null && s.longitude != null)
    .map((s) => ({ lat: s.latitude as number, lng: s.longitude as number, weight: s.rssi }))
    .reverse();

  let distanceBearingText: string | null = null;
  if (browserLocation && latest?.latitude != null && latest?.longitude != null) {
    const distance = haversineDistanceMeters(
      browserLocation.latitude,
      browserLocation.longitude,
      latest.latitude,
      latest.longitude,
    );
    const bearing = initialBearingDegrees(
      browserLocation.latitude,
      browserLocation.longitude,
      latest.latitude,
      latest.longitude,
    );
    distanceBearingText = `${formatDistance(distance)} away, bearing ${bearingToCompass(bearing)} (${Math.round(bearing)}°) from where you are now`;
  }

  return (
    <section>
      <h1>BLE device</h1>
      <p className="mono page-hint">{identifier}</p>

      {sightings.loading && !sightings.data && <p>Loading…</p>}
      {sightings.error && <p className="error-text">Could not reach the backend: {sightings.error.message}</p>}
      {sightings.data && results.length === 0 && <p className="empty-state">No sightings found for this device.</p>}

      {latest && (
        <dl className="detail-list">
          <dt>Type</dt>
          <dd>
            <DeviceTypeBadge deviceType={latest.device_type_guess} />
          </dd>
          {latest.device_name && (
            <>
              <dt>Name</dt>
              <dd>{latest.device_name}</dd>
            </>
          )}
          <dt>Last signal</dt>
          <dd>{latest.rssi} dBm</dd>
          <dt>Connectable</dt>
          <dd>{latest.is_connectable ? "Yes" : "No"}</dd>
          {latest.primary_phy && (
            <>
              <dt>PHY</dt>
              <dd>{latest.primary_phy}</dd>
            </>
          )}
          <dt>Last seen</dt>
          <dd>{new Date(latest.observed_at).toLocaleString()}</dd>
        </dl>
      )}

      <h2>Direction</h2>
      {distanceBearingText && <p>{distanceBearingText}</p>}
      {!distanceBearingText && locationError && (
        <p className="page-hint">
          Can't compute direction — {locationError}. Direction needs your browser's location and at least one
          geotagged sighting.
        </p>
      )}
      {!distanceBearingText && !locationError && !browserLocation && <p className="page-hint">Getting your location…</p>}
      {!distanceBearingText && !locationError && browserLocation && (
        <p className="page-hint">No geotagged sighting available yet to compute direction from.</p>
      )}

      <h2>Sighting locations</h2>
      {mapPoints.length === 0 && <p className="empty-state">No geotagged sightings yet.</p>}
      {mapPoints.length === 1 && <p className="page-hint">Only one geotagged sighting so far — no path to draw yet.</p>}
      {mapPoints.length > 0 && <RadioMap points={mapPoints} mode="path" />}

      <h2>Sighting history</h2>
      {results.length > 0 && (
        <table className="data-table">
          <thead>
            <tr>
              <th>Signal</th>
              <th>Observed</th>
            </tr>
          </thead>
          <tbody>
            {results.map((sighting) => (
              <tr key={sighting.id}>
                <td>{sighting.rssi} dBm</td>
                <td>{new Date(sighting.observed_at).toLocaleString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
