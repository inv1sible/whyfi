import { useMemo } from "react";
import { Link, useParams } from "react-router-dom";
import { api } from "../api/client";
import { MapDisplayModeControls } from "../components/MapDisplayModeControls";
import { PrintReportButton } from "../components/PrintReportButton";
import { RadioMap } from "../components/RadioMap";
import type { CoveragePolygon, MapPoint } from "../components/RadioMap";
import { ReportHeader } from "../components/ReportHeader";
import { SightingTable } from "../components/SightingTable";
import { SortableTh } from "../components/SortableTh";
import { SimpleLineChart } from "../components/SimpleLineChart";
import { COVERAGE_STROKE_COLOR, classifyDeviceCoverage, soloShapes } from "../coverageConfig";
import { useFilter } from "../context/FilterContext";
import { filterBySearch } from "../searchFilter";
import { resolveCurrentScanMultiDevice } from "../currentScan";
import { weightedCentroid } from "../geo";
import {
  describeObservedSpan,
  describeSignalRange,
  useReportPrinting,
  useReportViewSettings,
} from "../hooks/useDeviceReport";
import { usePolling } from "../hooks/usePolling";
import { useSortableData } from "../hooks/useSortableData";
import { signalStrengthColor, signalStrengthLabel } from "../signalColor";
import { TableControls } from "../components/TableControls";

const PALETTE = ["#2dd4bf", "#f87171", "#60a5fa", "#fbbf24", "#a78bfa", "#34d399", "#f472b6", "#38bdf8"];

export function SSIDGroupPage() {
  const { ssid = "" } = useParams();
  const filter = useFilter();
  const { printing, onMapReady, printButtonProps } = useReportPrinting();
  const accessPoints = usePolling(
    () => api.accessPoints(`?ssid_exact=${encodeURIComponent(ssid)}`),
    15000,
    [ssid],
    { paused: printing },
  );
  const coverage = usePolling(() => api.accessPointsCoverage({ ssidExact: ssid }), 30000, [ssid], {
    paused: printing,
  });

  // Each BSSID's stroke color still marks "which one is this" (matching
  // the color dot in the table below) — the gradient fill itself is
  // always green→orange by signal strength, same as everywhere else.
  const { polygons, mobilePoints, currentScanLabel, visibleReadings } = useMemo(() => {
    if (!coverage.data)
      return {
        polygons: [] as CoveragePolygon[],
        mobilePoints: [] as MapPoint[],
        currentScanLabel: null,
        visibleReadings: [] as {
          bssid: string;
          weight: number;
          observedAt?: string;
          lat?: number;
          lng?: number;
        }[],
      };
    // .results, not the response itself — the coverage endpoints return a
    // CappedList envelope so a capped (incomplete) answer can be told apart
    // from a complete one. See the warning below the map.
    const devices = coverage.data.results;
    const polys: CoveragePolygon[] = [];
    const points: MapPoint[] = [];

    // Same shared multi-device timeline as the Heatmap page — one scan
    // pass sees several of this SSID's BSSIDs at once, so the slider
    // means the same physical scan for all of them.
    const timeline = resolveCurrentScanMultiDevice(devices, filter.scanIndexPercent);

    // Same slider-following readings the map shows, flattened for the
    // signal chart and sighting table below — mirrors the detail pages.
    const isPointVisible = (p: { scan_session_id?: string; observed_at?: string }) =>
      filter.mapDisplayMode === "solo"
        ? timeline.scanSessionId === null || p.scan_session_id === timeline.scanSessionId
        : timeline.cutoffObservedAt === null || !p.observed_at || p.observed_at <= timeline.cutoffObservedAt;
    const readings = devices.flatMap((ap) =>
      ap.points.filter(isPointVisible).map((p) => ({
        bssid: ap.bssid,
        weight: p.weight,
        observedAt: p.observed_at,
        lat: p.lat,
        lng: p.lng,
      })),
    );

    if (filter.mapDisplayMode === "solo") {
      // One shape per BSSID seen in the selected scan — a cone from that
      // BSSID's own known position (weighted centroid of its *entire*
      // history) when available, else an RSSI-derived range blob. Either
      // way the outline keeps that BSSID's palette color (matching the
      // table's color dots) while the fill carries the signal-strength
      // info, same split as everywhere else on this page.
      devices.forEach((ap, index) => {
        const apEstimatedLocation =
          ap.points.length > 0 ? weightedCentroid(ap.points.map((p) => ({ lat: p.lat, lng: p.lng, weight: p.weight }))) : null;
        soloShapes(ap.points.filter(isPointVisible), apEstimatedLocation, "wifi").forEach((shape) => {
          polys.push({
            points: shape.polygon,
            color: PALETTE[index % PALETTE.length],
            label: ap.bssid,
            detailPath: ap.detail_path,
            fillOpacity: shape.fillOpacity,
            gradientCenter: shape.gradientCenter,
            gradientEdgeColor: shape.gradientEdgeColor,
            centerIconType: shape.gradientCenter ? "wifi" : undefined,
          });
        });
      });
      return {
        polygons: polys,
        mobilePoints: polys.length > 0 ? [] : timeline.points,
        currentScanLabel: timeline.label,
        visibleReadings: readings,
      };
    }

    const cutoff = timeline.cutoffObservedAt;
    devices.forEach((ap, index) => {
      const color = PALETTE[index % PALETTE.length];
      const visiblePoints = cutoff === null ? ap.points : ap.points.filter((p) => !p.observed_at || p.observed_at <= cutoff);
      if (visiblePoints.length === 0) return;
      const shape = classifyDeviceCoverage(visiblePoints, "wifi");
      if (shape.kind === "polygon") {
        polys.push({
          points: shape.polygon,
          color,
          label: ap.bssid,
          detailPath: ap.detail_path,
          gradientCenter: shape.center,
          centerIconType: "wifi",
        });
      } else {
        visiblePoints.forEach((p) => {
          points.push({
            lat: p.lat,
            lng: p.lng,
            weight: p.weight,
            source: { label: ap.ssid || ap.bssid, detailPath: ap.detail_path },
          });
        });
      }
    });
    return { polygons: polys, mobilePoints: points, currentScanLabel: timeline.label, visibleReadings: readings };
  }, [coverage.data, filter.mapDisplayMode, filter.scanIndexPercent]);

  const chronologicalReadings = [...visibleReadings]
    .filter((r) => r.observedAt)
    .sort((a, b) => (a.observedAt as string).localeCompare(b.observedAt as string));
  // Newest-first like the detail pages' tables, capped so a long survey
  // doesn't render thousands of rows.
  const tableReadings = [...chronologicalReadings].reverse().slice(0, 200);

  const results = accessPoints.data?.results ?? [];
  const {
    sorted: sortedAps,
    sortKey: apSortKey,
    direction: apDirection,
    requestSort: requestApSort,
  } = useSortableData(filterBySearch(results, filter.searchQuery), "last_seen_at", "desc");

  const reportViewSettings = useReportViewSettings(currentScanLabel);
  const reportSummary = [
    { label: "SSID", value: ssid || "(hidden network)" },
    { label: "Access points", value: `${results.length} broadcasting this SSID` },
    { label: "Coverage areas", value: `${polygons.length} mapped, ${mobilePoints.length} shown as points` },
    { label: "Readings", value: `${chronologicalReadings.length} in range` },
    { label: "Signal range", value: describeSignalRange(chronologicalReadings.map((r) => r.weight)) },
    {
      label: "Observed",
      value: describeObservedSpan(chronologicalReadings.map((r) => r.observedAt as string)),
    },
  ];

  return (
    <section>
      <ReportHeader
        title={`Coverage report — ${ssid || "(hidden network)"}`}
        summary={reportSummary}
        viewSettings={reportViewSettings}
      />

      <h1 className="print-hide">{ssid || "(hidden network)"}</h1>
      <p className="page-hint print-hide">
        All access points broadcasting this SSID (e.g. a mesh network's individual radios), grouped together but
        kept separate below — each BSSID's own coverage area is outlined in a different color on the map (fill is
        always green→orange by signal strength). A BSSID that moved around too much for "coverage" to mean anything
        shows as plain points instead.
      </p>

      {accessPoints.loading && !accessPoints.data && <p>Loading…</p>}
      {accessPoints.error && <p className="error-text">Could not reach the backend: {accessPoints.error.message}</p>}
      {accessPoints.data && results.length === 0 && (
        <p className="empty-state">No access points found for this SSID.</p>
      )}

      {results.length > 0 && (
        <>
        <TableControls />
          <table className="data-table">
          <thead>
            <tr>
              <SortableTh label="BSSID" sortKey="bssid" currentKey={apSortKey} direction={apDirection} onSort={requestApSort} />
              <SortableTh label="Band" sortKey="latest_band" currentKey={apSortKey} direction={apDirection} onSort={requestApSort} hideMobile />
              <SortableTh label="Channel" sortKey="latest_channel" currentKey={apSortKey} direction={apDirection} onSort={requestApSort} hideMobile />
              <SortableTh label="Signal" sortKey="latest_rssi" currentKey={apSortKey} direction={apDirection} onSort={requestApSort} />
              <SortableTh label="Last seen" sortKey="last_seen_at" currentKey={apSortKey} direction={apDirection} onSort={requestApSort} />
            </tr>
          </thead>
          <tbody>
            {sortedAps.length === 0 && (
              <tr><td colSpan={5} className="empty-state">No access points match your search.</td></tr>
            )}
            {sortedAps.map((ap) => {
              const color = polygons.find((p) => p.label === ap.bssid)?.color;
              return (
                <tr key={ap.bssid}>
                  <td className="mono">
                    {color && (
                      <span
                        style={{
                          display: "inline-block",
                          width: "0.6rem",
                          height: "0.6rem",
                          borderRadius: "50%",
                          background: color,
                          marginRight: "0.4rem",
                        }}
                      />
                    )}
                    <Link to={`/networks/${encodeURIComponent(ap.bssid)}`}>{ap.bssid}</Link>
                  </td>
                  <td className="hide-mobile">{ap.latest_band ?? "—"}</td>
                  <td className="hide-mobile">{ap.latest_channel ?? "—"}</td>
                  <td>{ap.latest_rssi != null ? `${ap.latest_rssi} dBm` : "—"}</td>
                  <td>{new Date(ap.last_seen_at).toLocaleString()}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
        </>
      )}

      <h2>Coverage per access point</h2>
      {coverage.data?.truncated && (
        <p className="warning-text">
          Incomplete: this SSID matched more than {coverage.data.observation_limit.toLocaleString()} observations, so
          some sightings are missing from the shapes below.
        </p>
      )}
      {polygons.length === 0 && mobilePoints.length === 0 && coverage.data && (
        <p className="page-hint">No geotagged sightings yet for any BSSID in this group.</p>
      )}
      {(polygons.length > 0 || mobilePoints.length > 0) && (
        <>
          <RadioMap points={mobilePoints} mode="heat" polygons={polygons} onReady={onMapReady} />
          <MapDisplayModeControls
            mode={filter.mapDisplayMode}
            onModeChange={filter.setMapDisplayMode}
            percent={filter.scanIndexPercent}
            onPercentChange={filter.setScanIndexPercent}
            label={currentScanLabel}
          />
          <div style={{ margin: "0.75rem 0" }}>
            <PrintReportButton {...printButtonProps} />
          </div>

          <h2>Signal history</h2>
          <SimpleLineChart
            unit=" dBm"
            points={chronologicalReadings.map((r) => ({ label: r.observedAt as string, value: r.weight }))}
            valueColor={signalStrengthColor}
            valueLabel={signalStrengthLabel}
          />

          <h2>Sighting history</h2>
          <p className="page-hint">Follows the slider above — chart and table show the same readings the map does.</p>
          {tableReadings.length > 0 && (
            <>
            <SightingTable
              rows={tableReadings.map((r, index) => ({
                id: `${r.bssid}-${r.observedAt}-${index}`,
                bssid: r.bssid,
                signal: Math.round(r.weight),
                latitude: r.lat ?? null,
                longitude: r.lng ?? null,
                observedAt: r.observedAt ?? null,
              }))}
              columns={[
                {
                  key: "bssid",
                  label: "BSSID",
                  render: (row) => (
                    <Link className="mono" to={`/networks/${encodeURIComponent(row.bssid)}`}>
                      {row.bssid}
                    </Link>
                  ),
                },
                {
                  key: "signal",
                  label: "Signal",
                  render: (row) => (
                    <span style={{ color: signalStrengthColor(row.signal) }}>{row.signal} dBm</span>
                  ),
                },
              ]}
            />
            </>
          )}
        </>
      )}
    </section>
  );
}
