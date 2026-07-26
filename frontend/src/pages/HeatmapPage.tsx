import { useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { api } from "../api/client";
import { MapDisplayModeControls } from "../components/MapDisplayModeControls";
import { RadioMap } from "../components/RadioMap";
import type { CoveragePolygon, MapPoint } from "../components/RadioMap";
import { SimpleLineChart } from "../components/SimpleLineChart";
import { ALWAYS_MOBILE_BLE_TYPES, COVERAGE_STROKE_COLOR, classifyDeviceCoverage, soloShapes } from "../coverageConfig";
import { useFilter } from "../context/FilterContext";
import { resolveCurrentScanMultiDevice } from "../currentScan";
import { weightedCentroid } from "../geo";
import { usePolling } from "../hooks/usePolling";
import { signalStrengthColor, signalStrengthLabel } from "../signalColor";
import type { AccessPointCoverage, HeatmapSource, RadioCoverage } from "../api/types";

const SOURCES: { value: HeatmapSource; label: string }[] = [
  { value: "wifi", label: "WiFi signal" },
  { value: "cellular", label: "Cellular signal" },
  { value: "ble", label: "BLE devices" },
];

// Fixed hue per radio type, used for the fallback "mobile device" points
// whenever more than one source is active at once — the coverage shapes
// themselves don't need this (they're always green→orange by signal
// strength; the radio type shows via the center icon instead).
const TYPE_HUES: Record<HeatmapSource, number> = {
  wifi: 175,
  cellular: 30,
  ble: 280,
};

function toGenericCoverage(items: AccessPointCoverage[] | RadioCoverage[]): RadioCoverage[] {
  return items.map((item) =>
    "bssid" in item
      ? { key: item.bssid, label: item.ssid || item.bssid, detail_path: item.detail_path, points: item.points }
      : item,
  );
}

export function HeatmapPage() {
  // Independently toggleable — any combination of the three can be active
  // at once, not just "one at a time" or "all three".
  const [enabled, setEnabled] = useState<Record<HeatmapSource, boolean>>({
    wifi: true,
    cellular: false,
    ble: false,
  });
  const filter = useFilter();

  const activeSources = SOURCES.map((s) => s.value).filter((s) => enabled[s]);

  function toggleSource(source: HeatmapSource) {
    setEnabled((prev) => ({ ...prev, [source]: !prev[source] }));
  }

  const { data, error, loading } = usePolling<Record<HeatmapSource, RadioCoverage[]>>(
    async () => {
      const fetchers: Record<HeatmapSource, () => Promise<AccessPointCoverage[] | RadioCoverage[]>> = {
        wifi: () => api.accessPointsCoverage({ since: filter.since, sessionLimit: filter.sessionLimit }),
        cellular: () => api.cellTowersCoverage({ since: filter.since, sessionLimit: filter.sessionLimit }),
        ble: () => api.bleObservationsCoverage({ since: filter.since, sessionLimit: filter.sessionLimit }),
      };
      const results = await Promise.all(activeSources.map((source) => fetchers[source]()));
      const bySource = { wifi: [], cellular: [], ble: [] } as Record<HeatmapSource, RadioCoverage[]>;
      activeSources.forEach((source, index) => {
        bySource[source] = toGenericCoverage(results[index]);
      });
      return bySource;
    },
    20000,
    [activeSources.join(","), filter.since, filter.sessionLimit],
  );

  const { coveragePolygons, mobilePoints, devicePoints, deviceCount, currentScanLabel, visibleReadings } = useMemo(() => {
    const allDevices: RadioCoverage[] = [];
    activeSources.forEach((source) => (data?.[source] ?? []).forEach((device) => allDevices.push(device)));

    // ONE shared chronological timeline across every active device (a
    // single scan pass observes several APs/towers/BLE devices at once) —
    // the slider means the same physical scan for all of them.
    const timeline = resolveCurrentScanMultiDevice(allDevices, filter.scanIndexPercent);

    // Same slider-following readings the map shows, flattened for the
    // signal chart and sighting table below — mirrors the detail pages.
    const isPointVisible = (p: RadioCoverage["points"][number]) =>
      filter.mapDisplayMode === "solo"
        ? timeline.scanSessionId === null || p.scan_session_id === timeline.scanSessionId
        : timeline.cutoffObservedAt === null || !p.observed_at || p.observed_at <= timeline.cutoffObservedAt;
    const readings = allDevices.flatMap((device) =>
      device.points
        .filter(isPointVisible)
        .map((p) => ({ label: device.label, detailPath: device.detail_path, weight: p.weight, observedAt: p.observed_at })),
    );

    if (filter.mapDisplayMode === "solo") {
      // Each device gets a cone from its own known position (the weighted
      // centroid of its *entire* sighting history, not just the readings
      // visible in the selected scan) to the reading it contributed to
      // this scan, or an RSSI-derived range blob when that position isn't
      // known yet (or isn't meaningful — forced-mobile BLE devices skip
      // it, same as the detail pages).
      const soloPolygons: CoveragePolygon[] = [];
      activeSources.forEach((source) => {
        (data?.[source] ?? []).forEach((device) => {
          const readingsHere = device.points.filter(isPointVisible);
          const isForcedMobile =
            source === "ble" && device.device_type_guess != null && ALWAYS_MOBILE_BLE_TYPES.has(device.device_type_guess);
          const apEstimatedLocation =
            !isForcedMobile && device.points.length > 0
              ? weightedCentroid(device.points.map((p) => ({ lat: p.lat, lng: p.lng, weight: p.weight })))
              : null;
          soloShapes(readingsHere, apEstimatedLocation, source).forEach((shape) => {
            soloPolygons.push({
              points: shape.polygon,
              color: shape.color,
              label: device.label,
              detailPath: device.detail_path,
              fillOpacity: shape.fillOpacity,
              gradientCenter: shape.gradientCenter,
              gradientEdgeColor: shape.gradientEdgeColor,
              centerIconType: shape.gradientCenter ? source : undefined,
            });
          });
        });
      });

      return {
        coveragePolygons: soloPolygons,
        mobilePoints: soloPolygons.length > 0 ? [] : timeline.points,
        devicePoints: [],
        deviceCount: allDevices.length,
        currentScanLabel: timeline.label,
        visibleReadings: readings,
      };
    }

    // Accumulate — everything at or before the selected scan's timestamp.
    // Devices not seen yet by that point in time simply don't appear,
    // so dragging the slider right replays the survey building up.
    const cutoff = timeline.cutoffObservedAt;
    const polygons: CoveragePolygon[] = [];
    const points: MapPoint[] = [];

    activeSources.forEach((source) => {
      const devices = data?.[source] ?? [];
      devices.forEach((device) => {
        const visiblePoints =
          cutoff === null ? device.points : device.points.filter((p) => !p.observed_at || p.observed_at <= cutoff);
        if (visiblePoints.length === 0) return;

        const isForcedMobile =
          source === "ble" && device.device_type_guess != null && ALWAYS_MOBILE_BLE_TYPES.has(device.device_type_guess);
        const shape = classifyDeviceCoverage(visiblePoints, source, isForcedMobile);

        if (shape.kind === "polygon") {
          polygons.push({
            points: shape.polygon,
            color: COVERAGE_STROKE_COLOR,
            label: device.label,
            detailPath: device.detail_path,
            gradientCenter: shape.center,
            centerIconType: source,
          });
        } else {
          visiblePoints.forEach((p) => {
            points.push({
              lat: p.lat,
              lng: p.lng,
              weight: p.weight,
              typeHue: activeSources.length > 1 ? TYPE_HUES[source] : undefined,
              source: { label: device.label, detailPath: device.detail_path },
            });
          });
        }
      });
    });

    return {
      coveragePolygons: polygons,
      mobilePoints: points,
      devicePoints: [],
      deviceCount: allDevices.length,
      currentScanLabel: timeline.label,
      visibleReadings: readings,
    };
  }, [data, activeSources, filter.mapDisplayMode, filter.scanIndexPercent]);

  const chronologicalReadings = [...visibleReadings]
    .filter((r) => r.observedAt)
    .sort((a, b) => (a.observedAt as string).localeCompare(b.observedAt as string));
  // Newest-first like the detail pages' tables, capped so a big combined
  // survey doesn't render thousands of rows.
  const tableReadings = [...chronologicalReadings].reverse().slice(0, 200);

  return (
    <section>
      <h1>Heatmap</h1>
      <p className="page-hint">
        Map tiles are fetched from public OpenStreetMap servers when this device has internet access — see
        docs/architecture.md for why v1 doesn't bundle a self-hosted tile server. Each shape is one AP/tower/device's
        estimated coverage — solid green marks its estimated location, fading to orange at the edge of where it was
        still detected. Devices that moved around too much for "coverage" to mean anything (a WiFi hotspot in a car,
        BLE headphones) show as plain points instead — click a shape or point for a link to its detail page.
      </p>

      <div className="band-selector">
        {SOURCES.map((s) => (
          <button key={s.value} className={enabled[s.value] ? "active" : ""} onClick={() => toggleSource(s.value)}>
            {s.label}
          </button>
        ))}
      </div>

      {activeSources.length > 1 && (
        <p className="page-hint">
          Mobile-device points are colored by source:{" "}
          {activeSources.map((source) => (
            <span key={source} style={{ marginRight: "0.75rem" }}>
              <span style={{ color: `hsl(${TYPE_HUES[source]}, 85%, 50%)` }}>■</span>{" "}
              {SOURCES.find((s) => s.value === source)?.label}
            </span>
          ))}
          — coverage shapes are always green→orange regardless of source; look at the center icon to tell them apart.
        </p>
      )}

      {activeSources.length === 0 && <p className="empty-state">Toggle at least one source above to see the map.</p>}

      {filter.isAllTime && (
        <p className="page-hint">
          "All time" can span very different locations if you've traveled with the phone — the map zooms out to fit
          everything, which can make individual shapes hard to see. Narrow the range above for a clearer local view.
        </p>
      )}

      {loading && !data && <p>Loading…</p>}
      {error && <p className="error-text">Could not reach the backend: {error.message}</p>}
      {data && activeSources.length > 0 && deviceCount === 0 && (
        <p className="empty-state">No geotagged observations yet for the active source(s) in this time range.</p>
      )}
      {data && (coveragePolygons.length > 0 || mobilePoints.length > 0) && (
        <>
          <RadioMap points={[...mobilePoints, ...devicePoints]} mode="heat" polygons={coveragePolygons} />
          <MapDisplayModeControls
            mode={filter.mapDisplayMode}
            onModeChange={filter.setMapDisplayMode}
            percent={filter.scanIndexPercent}
            onPercentChange={filter.setScanIndexPercent}
            label={currentScanLabel}
          />

          <h2>Signal history</h2>
          {activeSources.length > 1 && (
            <p className="page-hint">
              Mixes readings from all active sources on one dBm scale — toggle down to a single source above for a
              cleaner picture.
            </p>
          )}
          <SimpleLineChart
            unit=" dBm"
            points={chronologicalReadings.map((r) => ({ label: r.observedAt as string, value: r.weight }))}
            valueColor={signalStrengthColor}
            valueLabel={signalStrengthLabel}
          />

          <h2>Sighting history</h2>
          <p className="page-hint">Follows the slider above — chart and table show the same readings the map does.</p>
          {tableReadings.length > 0 && (
            <table className="data-table">
              <thead>
                <tr>
                  <th>Device</th>
                  <th>Signal</th>
                  <th>Observed</th>
                </tr>
              </thead>
              <tbody>
                {tableReadings.map((r, index) => (
                  <tr key={`${r.detailPath}-${r.observedAt}-${index}`}>
                    <td>
                      <Link to={r.detailPath}>{r.label}</Link>
                    </td>
                    <td style={{ color: signalStrengthColor(r.weight) }}>{Math.round(r.weight)} dBm</td>
                    <td>{r.observedAt ? new Date(r.observedAt).toLocaleString() : "—"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </>
      )}
    </section>
  );
}
