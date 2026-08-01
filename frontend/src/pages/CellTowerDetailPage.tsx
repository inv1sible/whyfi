import { useParams } from "react-router-dom";
import { api } from "../api/client";
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

export function CellTowerDetailPage() {
  const { towerKey = "" } = useParams();
  const { refreshKey, deleteScanSession } = useDeleteScanSession();
  const {
    since,
    until,
    sessionLimit,
    mapDisplayMode,
    setMapDisplayMode,
    scanIndexPercent,
    setScanIndexPercent,
  } = useFilter();

  const { printing, onMapReady, printButtonProps } = useReportPrinting();

  const tower = usePolling(() => api.cellTower(towerKey), 20000, [towerKey], { paused: printing });
  const observations = usePolling(
    () => api.cellObservationsForTower(towerKey, { since, until, sessionLimit }),
    20000,
    [towerKey, refreshKey, since, until, sessionLimit],
    { paused: printing },
  );

  const chronological = observations.data ? [...observations.data].reverse() : [];
  const geotagged = chronological.filter((o) => o.latitude != null && o.longitude != null);

  const latest = observations.data?.[0];

  const currentScan = resolveCurrentScan(
    geotagged.map((o) => ({
      lat: o.latitude as number,
      lng: o.longitude as number,
      weight: o.signal_dbm ?? 0,
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

  // Cellular has no distance cap (a sector legitimately covers km-scale
  // areas — see coverageConfig.ts), so this only ever falls back to plain
  // points when there are <3 geotagged sightings to form a shape from.
  const heatShape =
    visibleGeotagged.length > 0
      ? classifyDeviceCoverage(
          visibleGeotagged.map((o) => ({ lat: o.latitude as number, lng: o.longitude as number, weight: o.signal_dbm ?? 0 })),
          "cellular",
        )
      : null;
  const heatPolygons: CoveragePolygon[] =
    heatShape?.kind === "polygon"
      ? [
          {
            points: heatShape.polygon,
            color: COVERAGE_STROKE_COLOR,
            label: tower.data?.carrier_name || towerKey,
            gradientCenter: heatShape.center,
            centerIconType: "cellular",
          },
        ]
      : [];
  const rawPoints: MapPoint[] = visibleGeotagged.map((o) => ({
    lat: o.latitude as number,
    lng: o.longitude as number,
    weight: o.signal_dbm ?? 0,
    accuracyMeters: o.location_accuracy_meters,
    scanSessionId: o.scan_session,
    observedAt: o.observed_at,
  }));
  const heatPoints = heatShape?.kind === "points" ? rawPoints : [];

  // The tower's estimated position from its *entire* sighting history, not
  // just the slider-visible readings — see NetworkDetailPage.tsx for why.
  const apEstimatedLocation =
    geotagged.length > 0
      ? weightedCentroid(geotagged.map((o) => ({ lat: o.latitude as number, lng: o.longitude as number, weight: o.signal_dbm ?? 0 })))
      : null;

  // Solo mode: a cone from the tower's known position to this one reading,
  // or an RSSI-derived range blob when the position isn't known yet.
  const soloPolygons: CoveragePolygon[] = soloShapes(currentScan.points, apEstimatedLocation, "cellular").map((shape) => ({
    points: shape.polygon,
    color: shape.color,
    label: tower.data?.carrier_name || towerKey,
    fillOpacity: shape.fillOpacity,
    gradientCenter: shape.gradientCenter,
    gradientEdgeColor: shape.gradientEdgeColor,
    centerIconType: shape.gradientCenter ? "cellular" : undefined,
  }));

  const displayPolygons = mapDisplayMode === "solo" ? soloPolygons : heatPolygons;
  const displayPoints: MapPoint[] =
    mapDisplayMode === "solo" ? (soloPolygons.length > 0 ? [] : currentScan.points) : heatPoints;

  const reportViewSettings = useReportViewSettings(currentScan.label);
  const reportSummary = [
    { label: "Carrier", value: tower.data?.carrier_name || "Cell tower" },
    { label: "Tower key", value: towerKey },
    { label: "Radio type", value: tower.data?.radio_type ?? "—" },
    { label: "Readings", value: `${visibleChronological.length} of ${chronological.length} in range` },
    {
      label: "Signal range",
      // signal_dbm is nullable on cell observations, unlike RSSI elsewhere.
      value: describeSignalRange(
        visibleChronological.map((o) => o.signal_dbm).filter((v): v is number => v != null),
      ),
    },
    { label: "Observed", value: describeObservedSpan(visibleChronological.map((o) => o.observed_at)) },
    {
      label: "Estimated position",
      value: apEstimatedLocation ? formatCoords(apEstimatedLocation.lat, apEstimatedLocation.lng) : "—",
    },
  ];

  return (
    <section>
      <ReportHeader
        title={`Coverage report — ${tower.data?.carrier_name || towerKey}`}
        summary={reportSummary}
        viewSettings={reportViewSettings}
      />

      <h1 className="print-hide">{tower.data?.carrier_name || "Cell tower"}</h1>
      <p className="mono page-hint print-hide">{towerKey}</p>

      {tower.error && <p className="error-text">Could not reach the backend: {tower.error.message}</p>}

      {tower.data && (
        <dl className="detail-list">
          <dt>MCC/MNC</dt>
          <dd className="mono">
            {tower.data.mcc || "—"}/{tower.data.mnc || "—"}
          </dd>
          <dt>LAC/TAC</dt>
          <dd className="mono">{tower.data.tac_or_lac || "—"}</dd>
          <dt>Cell ID</dt>
          <dd className="mono">{tower.data.cell_id || "—"}</dd>
          <dt>Radio type</dt>
          <dd>{tower.data.radio_type || "—"}</dd>
          <dt>First seen</dt>
          <dd>{new Date(tower.data.first_seen_at).toLocaleString()}</dd>
          <dt>Last seen</dt>
          <dd>{new Date(tower.data.last_seen_at).toLocaleString()}</dd>
        </dl>
      )}

      {latest && (
        <dl className="detail-list">
          <dt>Physical cell ID</dt>
          <dd className="mono">{latest.physical_cell_id ?? "—"}</dd>
          <dt>ARFCN</dt>
          <dd className="mono">{latest.arfcn ?? "—"}</dd>
          <dt>Bandwidth</dt>
          <dd>{latest.bandwidth_khz != null ? `${(latest.bandwidth_khz / 1000).toFixed(1)} MHz` : "—"}</dd>
          <dt>Timing advance</dt>
          <dd>{latest.timing_advance ?? "—"}</dd>
          <dt>Signal</dt>
          <dd>{latest.signal_dbm != null ? `${latest.signal_dbm} dBm` : "—"}</dd>
          <dt>RSRP / RSRQ / SINR</dt>
          <dd>
            {latest.rsrp ?? "—"} / {latest.rsrq ?? "—"} / {latest.sinr ?? "—"}
          </dd>
          <dt>Serving/Neighbor</dt>
          <dd>{latest.is_serving_cell ? "Serving" : "Neighbor"}</dd>
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
          points={visibleChronological
            .filter((o) => o.signal_dbm != null)
            .map((o) => ({ label: o.observed_at, value: o.signal_dbm as number }))}
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
              signal: o.signal_dbm,
              serving: o.is_serving_cell ? "Serving" : "Neighbor",
              quality: `${o.rsrp ?? "—"} / ${o.rsrq ?? "—"} / ${o.sinr ?? "—"}`,
              latitude: o.latitude ?? null,
              longitude: o.longitude ?? null,
              observedAt: o.observed_at,
            }))}
          columns={[
            {
              key: "signal",
              label: "Signal",
              render: (row) => (row.signal != null ? `${row.signal} dBm` : "—"),
            },
            { key: "serving", label: "Serving?" },
            { key: "quality", label: "RSRP/RSRQ/SINR", hideMobile: true },
          ]}
        />
      )}
    </section>
  );
}
