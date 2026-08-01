import { Link, useParams } from "react-router-dom";
import { api } from "../api/client";
import { MapDisplayModeControls } from "../components/MapDisplayModeControls";
import { PrintReportButton } from "../components/PrintReportButton";
import { RadioMap } from "../components/RadioMap";
import type { CoveragePolygon, MapPoint } from "../components/RadioMap";
import { ReportHeader } from "../components/ReportHeader";
import { SightingTable } from "../components/SightingTable";
import { SecurityBadge } from "../components/SecurityBadge";
import { SimpleLineChart } from "../components/SimpleLineChart";
import { COVERAGE_STROKE_COLOR, classifyDeviceCoverage, soloShapes } from "../coverageConfig";
import { useFilter } from "../context/FilterContext";
import { resolveCurrentScan } from "../currentScan";
import { weightedCentroid } from "../geo";
import { useDeleteScanSession } from "../hooks/useDeleteScanSession";
import {
  describeObservedSpan,
  describeSignalRange,
  useReportPrinting,
  useReportViewSettings,
} from "../hooks/useDeviceReport";
import { usePolling } from "../hooks/usePolling";
import { formatCoords } from "../reportLinks";
import { signalStrengthColor, signalStrengthLabel } from "../signalColor";

export function NetworkDetailPage() {
  const { bssid = "" } = useParams();
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

  const ap = usePolling(() => api.accessPoint(bssid), 20000, [bssid], { paused: printing });
  const observations = usePolling(
    () => api.wifiObservationsForAp(bssid, { since, sessionLimit }),
    20000,
    [bssid, refreshKey, since, sessionLimit],
    { paused: printing },
  );
  const siblingAps = usePolling(
    () => (ap.data?.ssid ? api.accessPoints(`?ssid_exact=${encodeURIComponent(ap.data.ssid)}`) : Promise.resolve(null)),
    20000,
    [ap.data?.ssid],
  );

  // The API returns newest-first; chronological (oldest→newest) is what
  // the signal-history chart wants, same as the other detail pages.
  const chronological = observations.data ? [...observations.data].reverse() : [];
  const geotagged = chronological.filter((o) => o.latitude != null && o.longitude != null);

  const latest = observations.data?.[0];

  // The slider position resolved against this page's own chronological
  // list of distinct scans — drives the map, the signal chart, and the
  // sighting-history table together in both display modes. Not memoized:
  // every input is a freshly-derived array each render anyway, and the
  // work is trivial at the 200-observation fetch cap.
  const currentScan = resolveCurrentScan(
    geotagged.map((o) => ({
      lat: o.latitude as number,
      lng: o.longitude as number,
      weight: o.rssi,
      scanSessionId: o.scan_session,
      observedAt: o.observed_at,
    })),
    scanIndexPercent,
  );
  // "Accumulate" includes everything at or before the selected scan's
  // timestamp; "Current scan only" includes exactly that one session's
  // rows. Null timeline (nothing geotagged) degrades to showing all.
  const isRowVisible = (scanSession: string, observedAt: string) =>
    mapDisplayMode === "solo"
      ? currentScan.scanSessionId === null || scanSession === currentScan.scanSessionId
      : currentScan.cutoffObservedAt === null || observedAt <= currentScan.cutoffObservedAt;

  const visibleGeotagged = geotagged.filter((o) => isRowVisible(o.scan_session, o.observed_at));
  const visibleChronological = chronological.filter((o) => isRowVisible(o.scan_session, o.observed_at));

  // One gradient coverage shape (or a fallback to plain points if this AP
  // moved around too much for "coverage" to mean anything — see
  // classifyDeviceCoverage) instead of independent grid cells. Rebuilt
  // from just the slider-visible readings, so in Accumulate mode dragging
  // the slider right shows the coverage picture building up scan by scan.
  const heatShape =
    visibleGeotagged.length > 0
      ? classifyDeviceCoverage(
          visibleGeotagged.map((o) => ({ lat: o.latitude as number, lng: o.longitude as number, weight: o.rssi })),
          "wifi",
        )
      : null;
  const heatPolygons: CoveragePolygon[] =
    heatShape?.kind === "polygon"
      ? [
          {
            points: heatShape.polygon,
            color: COVERAGE_STROKE_COLOR,
            label: ap.data?.ssid || bssid,
            gradientCenter: heatShape.center,
            centerIconType: "wifi",
          },
        ]
      : [];
  // The raw per-scan points behind the shape above — each one is exactly
  // where the phone stood for that reading, not an estimate of the AP's
  // own location (that's what the shape's center icon is for). Always
  // shown when there's no shape to fall back on; otherwise an optional
  // overlay via the pin toggle below the map.
  const rawPoints: MapPoint[] = visibleGeotagged.map((o) => ({
    lat: o.latitude as number,
    lng: o.longitude as number,
    weight: o.rssi,
    accuracyMeters: o.location_accuracy_meters,
    scanSessionId: o.scan_session,
    observedAt: o.observed_at,
  }));
  const heatPoints = heatShape?.kind === "points" ? rawPoints : [];

  // The AP's estimated position from its *entire* sighting history (not
  // just the slider-visible readings) — Solo mode uses this as a stable
  // cone apex regardless of which scan you're scrubbed to, so "where the
  // AP is" doesn't jump around as you move the slider.
  const apEstimatedLocation =
    geotagged.length > 0
      ? weightedCentroid(geotagged.map((o) => ({ lat: o.latitude as number, lng: o.longitude as number, weight: o.rssi })))
      : null;

  // Solo mode: a cone from the AP's known position to this one reading
  // (green fading to that reading's own signal color), or an RSSI-derived
  // range blob when the AP's position isn't known yet. Radio types with no
  // signal model (none here — LAN is the only one, on a different page)
  // get neither, falling back to showing the raw reading as a point.
  const soloPolygons: CoveragePolygon[] = soloShapes(currentScan.points, apEstimatedLocation, "wifi").map((shape) => ({
    points: shape.polygon,
    color: shape.color,
    label: ap.data?.ssid || bssid,
    fillOpacity: shape.fillOpacity,
    gradientCenter: shape.gradientCenter,
    gradientEdgeColor: shape.gradientEdgeColor,
    centerIconType: shape.gradientCenter ? "wifi" : undefined,
  }));

  const displayPolygons = mapDisplayMode === "solo" ? soloPolygons : heatPolygons;
  const displayPoints: MapPoint[] =
    mapDisplayMode === "solo" ? (soloPolygons.length > 0 ? [] : currentScan.points) : heatPoints;

  const reportViewSettings = useReportViewSettings(currentScan.label);
  const reportSummary = [
    { label: "Network", value: ap.data?.ssid || "(hidden network)" },
    { label: "BSSID", value: bssid },
    { label: "Security", value: ap.data?.latest_security_type ?? "—" },
    { label: "Readings", value: `${visibleChronological.length} of ${chronological.length} in range` },
    { label: "Signal range", value: describeSignalRange(visibleChronological.map((o) => o.rssi)) },
    { label: "Observed", value: describeObservedSpan(visibleChronological.map((o) => o.observed_at)) },
    {
      label: "Estimated position",
      value: apEstimatedLocation ? formatCoords(apEstimatedLocation.lat, apEstimatedLocation.lng) : "—",
    },
  ];

  return (
    <section>
      <ReportHeader
        title={`Coverage report — ${ap.data?.ssid || bssid}`}
        summary={reportSummary}
        viewSettings={reportViewSettings}
      />

      <h1 className="print-hide">{ap.data?.ssid || "(hidden network)"}</h1>
      <p className="mono page-hint print-hide">{bssid}</p>
      {ap.data?.ssid && siblingAps.data && siblingAps.data.count > 1 && (
        <p className="page-hint">
          {siblingAps.data.count} access points share this SSID —{" "}
          <Link to={`/networks/ssid/${encodeURIComponent(ap.data.ssid)}`}>view all with separate coverage areas</Link>.
        </p>
      )}

      {ap.error && <p className="error-text">Could not reach the backend: {ap.error.message}</p>}

      {ap.data && (
        <dl className="detail-list">
          <dt>Security</dt>
          <dd>
            <SecurityBadge securityType={ap.data.latest_security_type} />
          </dd>
          <dt>Band</dt>
          <dd>{ap.data.latest_band ?? "—"}</dd>
          <dt>Vendor</dt>
          <dd>{ap.data.vendor_oui || "—"}</dd>
          <dt>First seen</dt>
          <dd>{new Date(ap.data.first_seen_at).toLocaleString()}</dd>
          <dt>Last seen</dt>
          <dd>{new Date(ap.data.last_seen_at).toLocaleString()}</dd>
        </dl>
      )}

      {latest && (
        <dl className="detail-list">
          <dt>Standard</dt>
          <dd>{latest.wifi_standard || "—"}</dd>
          <dt>Channel width</dt>
          <dd>{latest.channel_width_mhz != null ? `${latest.channel_width_mhz} MHz` : "—"}</dd>
          <dt>FTM/RTT capable</dt>
          <dd>{latest.is_80211mc_responder ? "Yes" : "No"}</dd>
          {latest.venue_name && (
            <>
              <dt>Venue</dt>
              <dd>{latest.venue_name}</dd>
            </>
          )}
          {latest.operator_friendly_name && (
            <>
              <dt>Operator</dt>
              <dd>{latest.operator_friendly_name}</dd>
            </>
          )}
        </dl>
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
          points={visibleChronological.map((o) => ({ label: o.observed_at, value: o.rssi }))}
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
              band: o.band,
              channel: o.channel,
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
            { key: "band", label: "Band" },
            { key: "channel", label: "Channel" },
          ]}
        />
      )}
    </section>
  );
}
