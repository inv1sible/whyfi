import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { api } from "../api/client";
import { CompassArrow } from "../components/CompassArrow";
import { DeviceTypeBadge } from "../components/DeviceTypeBadge";
import { MapDisplayModeControls } from "../components/MapDisplayModeControls";
import { PrintReportButton } from "../components/PrintReportButton";
import { RadioMap } from "../components/RadioMap";
import type { CoveragePolygon, MapPoint } from "../components/RadioMap";
import { ReportHeader } from "../components/ReportHeader";
import { SightingTable } from "../components/SightingTable";
import { SimpleLineChart } from "../components/SimpleLineChart";
import { ALWAYS_MOBILE_BLE_TYPES, COVERAGE_STROKE_COLOR, classifyDeviceCoverage, soloShapes } from "../coverageConfig";
import { useFilter } from "../context/FilterContext";
import { resolveCurrentScan } from "../currentScan";
import { useDeleteScanSession } from "../hooks/useDeleteScanSession";
import {
  describeObservedSpan,
  describeSignalRange,
  useReportPrinting,
  useReportViewSettings,
} from "../hooks/useDeviceReport";
import { usePolling } from "../hooks/usePolling";
import { formatCoords } from "../reportLinks";
import { bearingToCompass, formatDistance, haversineDistanceMeters, initialBearingDegrees, weightedCentroid } from "../geo";
import { signalStrengthColor, signalStrengthLabel } from "../signalColor";

export function BLEDeviceDetailPage() {
  const { identifier = "" } = useParams();
  const { refreshKey, deleteScanSession } = useDeleteScanSession();
  const {
    since,
    sessionLimit,
    mapDisplayMode,
    setMapDisplayMode,
    scanIndexPercent,
    setScanIndexPercent,
  } = useFilter();

  const { printing, onMapReady, printButtonProps } = useReportPrinting();

  const device = usePolling(() => api.bleDevice(identifier), 20000, [identifier], { paused: printing });
  const observations = usePolling(
    () => api.bleObservationsForDevice(identifier, { since, sessionLimit }),
    20000,
    [identifier, refreshKey, since, sessionLimit],
    { paused: printing },
  );

  const [browserLocation, setBrowserLocation] = useState<GeolocationCoordinates | null>(null);
  const [locationError, setLocationError] = useState<string | null>(null);

  useEffect(() => {
    if (!navigator.geolocation) {
      setLocationError("This browser doesn't support geolocation.");
      return;
    }
    // watchPosition, not a one-shot getCurrentPosition — while the Android
    // app is continuously scanning, keeping the browser's own position
    // live too is what makes the compass below actually useful for
    // walking toward the device instead of a single stale reading.
    const watchId = navigator.geolocation.watchPosition(
      (position) => setBrowserLocation(position.coords),
      (err) => setLocationError(err.message),
      { enableHighAccuracy: true, timeout: 10000 },
    );
    return () => navigator.geolocation.clearWatch(watchId);
  }, []);

  // API returns newest-first; the chart wants chronological (oldest→newest)
  // order, same as the other detail pages.
  const chronological = observations.data ? [...observations.data].reverse() : [];
  const geotagged = chronological.filter((s) => s.latitude != null && s.longitude != null);

  const latest = observations.data?.[0];

  const currentScan = resolveCurrentScan(
    geotagged.map((s) => ({
      lat: s.latitude as number,
      lng: s.longitude as number,
      weight: s.rssi,
      scanSessionId: s.scan_session,
      observedAt: s.observed_at,
    })),
    scanIndexPercent,
  );
  const isRowVisible = (scanSession: string, observedAt: string) =>
    mapDisplayMode === "solo"
      ? currentScan.scanSessionId === null || scanSession === currentScan.scanSessionId
      : currentScan.cutoffObservedAt === null || observedAt <= currentScan.cutoffObservedAt;

  const visibleGeotagged = geotagged.filter((s) => isRowVisible(s.scan_session, s.observed_at));
  const visibleChronological = chronological.filter((s) => isRowVisible(s.scan_session, s.observed_at));

  // Headphones/wearables are worn on a person — always mobile, no distance
  // check needed (see coverageConfig.ts).
  const isForcedMobile = device.data != null && ALWAYS_MOBILE_BLE_TYPES.has(device.data.device_type_guess);
  const heatShape =
    visibleGeotagged.length > 0
      ? classifyDeviceCoverage(
          visibleGeotagged.map((s) => ({ lat: s.latitude as number, lng: s.longitude as number, weight: s.rssi })),
          "ble",
          isForcedMobile,
        )
      : null;
  const heatPolygons: CoveragePolygon[] =
    heatShape?.kind === "polygon"
      ? [
          {
            points: heatShape.polygon,
            color: COVERAGE_STROKE_COLOR,
            label: device.data?.latest_device_name || identifier,
            gradientCenter: heatShape.center,
            centerIconType: "ble",
          },
        ]
      : [];
  const rawPoints: MapPoint[] = visibleGeotagged.map((s) => ({
    lat: s.latitude as number,
    lng: s.longitude as number,
    weight: s.rssi,
    accuracyMeters: s.location_accuracy_meters,
    scanSessionId: s.scan_session,
    observedAt: s.observed_at,
  }));
  const heatPoints = heatShape?.kind === "points" ? rawPoints : [];

  // The device's estimated position from its *entire* sighting history —
  // see NetworkDetailPage.tsx for why full history rather than just the
  // slider-visible readings. Skipped for forced-mobile devices (worn
  // headphones/wearables): there's no fixed "where it stands" to average
  // toward, so a weighted centroid of scattered sightings would just be a
  // meaningless point along wherever the person happened to walk.
  const apEstimatedLocation =
    !isForcedMobile && geotagged.length > 0
      ? weightedCentroid(geotagged.map((s) => ({ lat: s.latitude as number, lng: s.longitude as number, weight: s.rssi })))
      : null;

  // Solo mode: a cone from the device's known position to this one
  // reading, or an RSSI-derived range blob when the position isn't known
  // (or isn't meaningful, as with forced-mobile devices above).
  const soloPolygons: CoveragePolygon[] = soloShapes(currentScan.points, apEstimatedLocation, "ble").map((shape) => ({
    points: shape.polygon,
    color: shape.color,
    label: device.data?.latest_device_name || identifier,
    fillOpacity: shape.fillOpacity,
    gradientCenter: shape.gradientCenter,
    gradientEdgeColor: shape.gradientEdgeColor,
    centerIconType: shape.gradientCenter ? "ble" : undefined,
  }));

  const displayPolygons = mapDisplayMode === "solo" ? soloPolygons : heatPolygons;
  const displayPoints: MapPoint[] =
    mapDisplayMode === "solo" ? (soloPolygons.length > 0 ? [] : currentScan.points) : heatPoints;

  let distanceBearingText: string | null = null;
  let bearingDegrees: number | null = null;
  if (browserLocation && latest?.latitude != null && latest?.longitude != null) {
    const distance = haversineDistanceMeters(
      browserLocation.latitude,
      browserLocation.longitude,
      latest.latitude,
      latest.longitude,
    );
    bearingDegrees = initialBearingDegrees(
      browserLocation.latitude,
      browserLocation.longitude,
      latest.latitude,
      latest.longitude,
    );
    distanceBearingText = `${formatDistance(distance)} away, bearing ${bearingToCompass(bearingDegrees)} (${Math.round(bearingDegrees)}°) from where you are now`;
  }

  const reportViewSettings = useReportViewSettings(currentScan.label);
  const reportSummary = [
    { label: "Device", value: device.data?.latest_device_name || device.data?.device_key || "BLE device" },
    { label: "Identifier", value: identifier },
    { label: "Type", value: device.data?.device_type_guess ?? "—" },
    { label: "Readings", value: `${visibleChronological.length} of ${chronological.length} in range` },
    { label: "Signal range", value: describeSignalRange(visibleChronological.map((s) => s.rssi)) },
    { label: "Observed", value: describeObservedSpan(visibleChronological.map((s) => s.observed_at)) },
    {
      label: "Estimated position",
      // Forced-mobile devices deliberately have no estimate — see above.
      value: apEstimatedLocation
        ? formatCoords(apEstimatedLocation.lat, apEstimatedLocation.lng)
        : isForcedMobile
          ? "Not estimated (device moves with its owner)"
          : "—",
    },
  ];

  return (
    <section>
      <ReportHeader
        title={`Coverage report — ${device.data?.latest_device_name || identifier}`}
        summary={reportSummary}
        viewSettings={reportViewSettings}
      />

      <h1 className="print-hide">{device.data?.latest_device_name || device.data?.device_key || "BLE device"}</h1>
      <p className="mono page-hint print-hide">{identifier}</p>

      {device.error && <p className="error-text">Could not reach the backend: {device.error.message}</p>}

      {device.data && (
        <dl className="detail-list">
          <dt>Type</dt>
          <dd>
            <DeviceTypeBadge deviceType={device.data.device_type_guess} />
          </dd>
          <dt>First seen</dt>
          <dd>{new Date(device.data.first_seen_at).toLocaleString()}</dd>
          <dt>Last seen</dt>
          <dd>{new Date(device.data.last_seen_at).toLocaleString()}</dd>
        </dl>
      )}

      {latest && (
        <dl className="detail-list">
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
        </dl>
      )}

      {/* Print-hidden: this is a live bearing from whoever is *reading* the
          screen to the device, so on paper it's either stale or, more often,
          a "Getting your location…" placeholder. Nothing about it describes
          the survey being reported. */}
      <h2 className="print-hide">Direction</h2>
      {distanceBearingText && bearingDegrees != null && (
        <div className="print-hide" style={{ display: "flex", alignItems: "center", gap: "1rem", flexWrap: "wrap" }}>
          <CompassArrow bearingDegrees={bearingDegrees} />
          <p>
            {distanceBearingText}
            <br />
            <span className="page-hint">Updates live as your own location changes and the device is re-scanned.</span>
          </p>
        </div>
      )}
      {!distanceBearingText && locationError && (
        <p className="page-hint print-hide">
          Can't compute direction — {locationError}. Direction needs your browser's location and at least one
          geotagged sighting.
        </p>
      )}
      {!distanceBearingText && !locationError && !browserLocation && (
        <p className="page-hint print-hide">Getting your location…</p>
      )}
      {!distanceBearingText && !locationError && browserLocation && (
        <p className="page-hint print-hide">No geotagged sighting available yet to compute direction from.</p>
      )}

      <h2>Sighting locations</h2>
      {geotagged.length === 0 && <p className="empty-state">No geotagged sightings yet.</p>}
      {geotagged.length > 0 && (
        <>
          <RadioMap
            points={displayPoints}
            mode="heat"
            polygons={displayPolygons}
            onDeleteScanSession={deleteScanSession}
            onReady={onMapReady}
          />
          <MapDisplayModeControls
            mode={mapDisplayMode}
            onModeChange={setMapDisplayMode}
            percent={scanIndexPercent}
            onPercentChange={setScanIndexPercent}
            label={currentScan.label}
          />
          <div style={{ margin: "0.75rem 0" }}>
            <PrintReportButton {...printButtonProps} />
          </div>
        </>
      )}

      <h2>Signal history</h2>
      {observations.data && (
        <SimpleLineChart
          unit=" dBm"
          points={visibleChronological.map((s) => ({ label: s.observed_at, value: s.rssi }))}
          valueColor={signalStrengthColor}
          valueLabel={signalStrengthLabel}
        />
      )}

      <h2>Sighting history</h2>
      <p className="page-hint">Follows the slider above — chart and table show the same readings the map does.</p>
      {observations.data && observations.data.length > 0 && (
        <SightingTable
          rows={observations.data
            .filter((o) => isRowVisible(o.scan_session, o.observed_at))
            .map((o) => ({
              id: o.id,
              signal: o.rssi,
              latitude: o.latitude ?? null,
              longitude: o.longitude ?? null,
              observedAt: o.observed_at,
            }))}
          columns={[
            {
              key: "signal",
              label: "Signal",
              render: (row) => <span style={{ color: signalStrengthColor(row.signal) }}>{row.signal} dBm</span>,
            },
          ]}
        />
      )}
    </section>
  );
}
