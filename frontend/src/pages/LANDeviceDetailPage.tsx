import { useParams } from "react-router-dom";
import { api } from "../api/client";
import { DeviceTypeBadge, LAN_DEVICE_LABELS } from "../components/DeviceTypeBadge";
import { MapDisplayModeControls } from "../components/MapDisplayModeControls";
import { PrintReportButton } from "../components/PrintReportButton";
import { RadioMap } from "../components/RadioMap";
import type { CoveragePolygon, MapPoint } from "../components/RadioMap";
import { ReportHeader } from "../components/ReportHeader";
import { SightingTable } from "../components/SightingTable";
import { SimpleLineChart } from "../components/SimpleLineChart";
import { COVERAGE_STROKE_COLOR, classifyDeviceCoverage, soloShapes } from "../coverageConfig";
import { useFilter } from "../context/FilterContext";
import { resolveCurrentScan } from "../currentScan";
import { useDeleteScanSession } from "../hooks/useDeleteScanSession";
import { describeObservedSpan, useReportPrinting, useReportViewSettings } from "../hooks/useDeviceReport";
import { usePolling } from "../hooks/usePolling";
import { responseTimeColor, responseTimeLabel } from "../signalColor";

const PORT_NAMES: Record<number, string> = {
  21: "FTP",
  22: "SSH",
  23: "Telnet",
  25: "SMTP",
  53: "DNS",
  80: "HTTP",
  139: "NetBIOS",
  443: "HTTPS",
  445: "SMB",
  554: "RTSP",
  631: "IPP",
  3389: "RDP",
  5000: "UPnP",
  5353: "mDNS",
  7000: "AirPlay",
  8000: "HTTP-alt",
  8008: "HTTP-alt",
  8009: "Chromecast",
  8080: "HTTP-alt",
  8443: "HTTPS-alt",
  9100: "JetDirect",
  32400: "Plex",
  62078: "iOS sync",
};

// Ports worth offering as a direct "open in browser" link.
const WEB_PORTS: Record<number, "http" | "https"> = {
  80: "http",
  8000: "http",
  8008: "http",
  8080: "http",
  32400: "http",
  443: "https",
  8443: "https",
};

function portLabel(port: number): string {
  const name = PORT_NAMES[port];
  return name ? `${port} (${name})` : `${port}`;
}

export function LANDeviceDetailPage() {
  const { ip = "" } = useParams();
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

  const device = usePolling(() => api.lanDevice(ip), 20000, [ip], { paused: printing });
  const observations = usePolling(
    () => api.lanObservationsForDevice(ip, { since, sessionLimit }),
    20000,
    [ip, refreshKey, since, sessionLimit],
    { paused: printing },
  );

  const chronological = observations.data ? [...observations.data].reverse() : [];
  const geotagged = chronological.filter((o) => o.latitude != null && o.longitude != null);

  const latest = observations.data?.[0];

  const currentScan = resolveCurrentScan(
    geotagged.map((o) => ({
      lat: o.latitude as number,
      lng: o.longitude as number,
      weight: o.response_time_ms != null ? -o.response_time_ms : 0,
      scanSessionId: o.scan_session,
      observedAt: o.observed_at,
    })),
    scanIndexPercent,
  );
  const isRowVisible = (scanSession: string, observedAt: string) =>
    mapDisplayMode === "solo"
      ? currentScan.scanSessionId === null || scanSession === currentScan.scanSessionId
      : currentScan.cutoffObservedAt === null || observedAt <= currentScan.cutoffObservedAt;

  const visibleGeotagged = geotagged.filter((o) => isRowVisible(o.scan_session, o.observed_at));
  const visibleChronological = chronological.filter((o) => isRowVisible(o.scan_session, o.observed_at));

  const heatShape =
    visibleGeotagged.length > 0
      ? classifyDeviceCoverage(
          visibleGeotagged.map((o) => ({
            lat: o.latitude as number,
            lng: o.longitude as number,
            weight: o.response_time_ms != null ? -o.response_time_ms : 0,
          })),
          "lan",
        )
      : null;
  const heatPolygons: CoveragePolygon[] =
    heatShape?.kind === "polygon"
      ? [
          {
            points: heatShape.polygon,
            color: COVERAGE_STROKE_COLOR,
            label: device.data?.hostname || ip,
            gradientCenter: heatShape.center,
            centerIconType: "lan",
          },
        ]
      : [];
  const rawPoints: MapPoint[] = visibleGeotagged.map((o) => ({
    lat: o.latitude as number,
    lng: o.longitude as number,
    weight: o.response_time_ms != null ? -o.response_time_ms : 0,
    accuracyMeters: o.location_accuracy_meters,
    scanSessionId: o.scan_session,
    observedAt: o.observed_at,
  }));
  const heatPoints = heatShape?.kind === "points" ? rawPoints : [];

  // LAN has no signal model (response time isn't a dBm reading — see
  // coverageConfig.ts), so soloShapes always returns [] here and Solo mode
  // falls back to showing the raw reading as a point. No AP-location
  // anchor needed for a call that never produces a shape either way.
  const soloPolygons: CoveragePolygon[] = soloShapes(currentScan.points, null, "lan").map((shape) => ({
    points: shape.polygon,
    color: shape.color,
    label: device.data?.hostname || ip,
    fillOpacity: shape.fillOpacity,
    gradientCenter: shape.gradientCenter,
    gradientEdgeColor: shape.gradientEdgeColor,
    centerIconType: shape.gradientCenter ? "lan" : undefined,
  }));

  const displayPolygons = mapDisplayMode === "solo" ? soloPolygons : heatPolygons;
  const displayPoints: MapPoint[] =
    mapDisplayMode === "solo" ? (soloPolygons.length > 0 ? [] : currentScan.points) : heatPoints;

  const webLinks = latest
    ? latest.open_ports
        .filter((port) => port in WEB_PORTS)
        .map((port) => ({ port, url: `${WEB_PORTS[port]}://${device.data?.ip_address ?? ip}${port === 80 || port === 443 ? "" : `:${port}`}` }))
    : [];

  const reportViewSettings = useReportViewSettings(currentScan.label);
  // No RSSI on a LAN sweep — response time is the closest thing to a signal,
  // and faster is "stronger" (see responseTimeColor).
  const visibleResponseTimes = visibleChronological
    .map((o) => o.response_time_ms)
    .filter((v): v is number => v != null);
  const reportSummary = [
    { label: "Device", value: device.data?.hostname || device.data?.ip_address || "LAN device" },
    { label: "IP address", value: ip },
    { label: "MAC address", value: device.data?.mac_address || "—" },
    { label: "Sightings", value: `${visibleChronological.length} of ${chronological.length} in range` },
    {
      label: "Response time",
      value:
        visibleResponseTimes.length > 0
          ? `${Math.min(...visibleResponseTimes).toFixed(0)}–${Math.max(...visibleResponseTimes).toFixed(0)} ms`
          : "—",
    },
    { label: "Observed", value: describeObservedSpan(visibleChronological.map((o) => o.observed_at)) },
  ];

  return (
    <section>
      <ReportHeader
        title={`Device report — ${device.data?.hostname || ip}`}
        summary={reportSummary}
        viewSettings={reportViewSettings}
      />

      <h1 className="print-hide">{device.data?.hostname || device.data?.ip_address || "LAN device"}</h1>
      <p className="mono page-hint print-hide">{ip}</p>

      {device.error && <p className="error-text">Could not reach the backend: {device.error.message}</p>}

      {device.data && (
        <dl className="detail-list">
          <dt>Type</dt>
          <dd>
            <DeviceTypeBadge deviceType={device.data.device_type_guess} labels={LAN_DEVICE_LABELS} />
          </dd>
          <dt>MAC address</dt>
          <dd className="mono">{device.data.mac_address || "—"}</dd>
          <dt>Vendor</dt>
          <dd>{device.data.vendor_oui || "—"}</dd>
          <dt>First seen</dt>
          <dd>{new Date(device.data.first_seen_at).toLocaleString()}</dd>
          <dt>Last seen</dt>
          <dd>{new Date(device.data.last_seen_at).toLocaleString()}</dd>
        </dl>
      )}

      {latest && (
        <dl className="detail-list">
          <dt>Open ports</dt>
          <dd>{latest.open_ports.length > 0 ? latest.open_ports.map(portLabel).join(", ") : "—"}</dd>
          <dt>Banner</dt>
          <dd className="mono">{latest.banner || "—"}</dd>
          <dt>Response time</dt>
          <dd>{latest.response_time_ms != null ? `${latest.response_time_ms.toFixed(0)} ms` : "—"}</dd>
        </dl>
      )}

      {webLinks.length > 0 && (
        <>
          <h2>Open in browser</h2>
          <p className="page-hint">
            Only reachable if your browser is on the same network as this device — the backend just tells you these
            ports were open, it doesn't proxy the connection.
          </p>
          <ul>
            {webLinks.map(({ port, url }) => (
              <li key={port}>
                <a href={url} target="_blank" rel="noopener noreferrer">
                  {url}
                </a>
              </li>
            ))}
          </ul>
        </>
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
          <p className="page-hint">Coverage/signal strength here reflects response time (faster = stronger), not RSSI.</p>
          <div style={{ margin: "0.75rem 0" }}>
            <PrintReportButton {...printButtonProps} />
          </div>
        </>
      )}

      <h2>Response time history</h2>
      {observations.data && (
        <SimpleLineChart
          unit=" ms"
          points={visibleChronological
            .filter((o) => o.response_time_ms != null)
            .map((o) => ({ label: o.observed_at, value: o.response_time_ms as number }))}
          valueColor={responseTimeColor}
          valueLabel={responseTimeLabel}
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
              hostname: o.hostname || "—",
              openPorts: o.open_ports.length > 0 ? o.open_ports.join(", ") : "—",
              responseTimeMs: o.response_time_ms,
              latitude: o.latitude ?? null,
              longitude: o.longitude ?? null,
              observedAt: o.observed_at,
            }))}
          columns={[
            { key: "hostname", label: "Hostname" },
            { key: "openPorts", label: "Open ports", hideMobile: true },
            {
              key: "responseTimeMs",
              label: "Response time",
              render: (row) => (row.responseTimeMs != null ? `${row.responseTimeMs.toFixed(0)} ms` : "—"),
            },
          ]}
        />
      )}
    </section>
  );
}
