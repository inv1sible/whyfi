import { useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { api } from "../api/client";
import { BLE_LABELS } from "../components/DeviceTypeBadge";
import { MapDisplayModeControls } from "../components/MapDisplayModeControls";
import { PrintReportButton } from "../components/PrintReportButton";
import { RadioMap } from "../components/RadioMap";
import type { CoveragePolygon, MapPoint } from "../components/RadioMap";
import { ReportHeader } from "../components/ReportHeader";
import { SightingTable } from "../components/SightingTable";
import type { ReportField } from "../components/ReportHeader";
import { SimpleLineChart } from "../components/SimpleLineChart";
import { ALWAYS_MOBILE_BLE_TYPES, COVERAGE_STROKE_COLOR, classifyDeviceCoverage, soloShapes } from "../coverageConfig";
import { useFilter } from "../context/FilterContext";
import { resolveCurrentScanMultiDevice } from "../currentScan";
import { weightedCentroid } from "../geo";
import { useReportPrinting, useReportViewSettings } from "../hooks/useDeviceReport";
import { usePolling } from "../hooks/usePolling";
import { formatCoords, osmLink } from "../reportLinks";
import { signalStrengthColor, signalStrengthLabel } from "../signalColor";
import type { AccessPointCoverage, CappedList, HeatmapSource, RadioCoverage } from "../api/types";

const SOURCES: { value: HeatmapSource; label: string }[] = [
  { value: "wifi", label: "WiFi signal" },
  { value: "cellular", label: "Cellular signal" },
  { value: "ble", label: "BLE devices" },
];

// Short forms for the report's Type column, where the full "WiFi signal"
// phrasing of the toggle buttons would just pad the table.
const SOURCE_SHORT: Record<HeatmapSource, string> = {
  wifi: "WiFi",
  cellular: "Cellular",
  ble: "BLE",
};

function describeType(source: HeatmapSource, deviceTypeGuess?: string): string {
  if (source === "ble" && deviceTypeGuess) {
    return `BLE — ${BLE_LABELS[deviceTypeGuess] ?? deviceTypeGuess}`;
  }
  return SOURCE_SHORT[source];
}

/**
 * Number every device that drew a coverage shape, so a badge on the map can be
 * looked up in the table below. Printed reports have no hover, so this is the
 * only way to tell which shape is which.
 *
 * Keyed on detail_path rather than label because two access points can share
 * an SSID — keying on the label would merge them under one number. Ordered by
 * label so scanning the table for a number is quick, and only the first shape
 * per device gets a badge (Solo mode emits one shape per reading, and stamping
 * the same number across all of them would just clutter the map).
 */
function assignCallouts(polygons: CoveragePolygon[]): Map<string, number> {
  const labelByPath = new Map<string, string>();
  polygons.forEach((p) => {
    if (p.detailPath && !labelByPath.has(p.detailPath)) labelByPath.set(p.detailPath, p.label ?? p.detailPath);
  });

  const numbers = new Map<string, number>();
  [...labelByPath.entries()]
    .sort((a, b) => a[1].localeCompare(b[1]))
    .forEach(([path], index) => numbers.set(path, index + 1));

  const badged = new Set<string>();
  polygons.forEach((p) => {
    if (!p.detailPath || badged.has(p.detailPath)) return;
    badged.add(p.detailPath);
    p.calloutNumber = numbers.get(p.detailPath);
  });
  return numbers;
}

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

interface CoverageData {
  bySource: Record<HeatmapSource, RadioCoverage[]>;
  // Which active sources came back capped (see CappedList) — the map is
  // missing devices for these, and saying so is the whole point of tracking
  // it: a silently short answer here looks exactly like a quiet area.
  truncatedSources: HeatmapSource[];
  observationLimit: number | null;
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
  // Held across the whole print interaction so a 20s poll can't swap the data
  // out between clicking Print and the dialog opening — the report has to be
  // the view that was on screen.
  const { printing, onMapReady, printButtonProps } = useReportPrinting();

  const activeSources = SOURCES.map((s) => s.value).filter((s) => enabled[s]);

  function toggleSource(source: HeatmapSource) {
    setEnabled((prev) => ({ ...prev, [source]: !prev[source] }));
  }

  // Named rather than inlined three times, and deliberately not `window` —
  // that shadows the global one every print/DOM call in this file relies on.
  const coverageWindow = {
    since: filter.since,
    until: filter.until,
    sessionLimit: filter.sessionLimit,
    area: filter.area,
  };

  const { data, error, loading } = usePolling<CoverageData>(
    async () => {
      const fetchers: Record<
        HeatmapSource,
        () => Promise<CappedList<AccessPointCoverage> | CappedList<RadioCoverage>>
      > = {
        wifi: () => api.accessPointsCoverage(coverageWindow),
        cellular: () => api.cellTowersCoverage(coverageWindow),
        ble: () => api.bleObservationsCoverage(coverageWindow),
      };
      const responses = await Promise.all(activeSources.map((source) => fetchers[source]()));
      const bySource = { wifi: [], cellular: [], ble: [] } as Record<HeatmapSource, RadioCoverage[]>;
      const truncatedSources: HeatmapSource[] = [];
      activeSources.forEach((source, index) => {
        bySource[source] = toGenericCoverage(responses[index].results);
        if (responses[index].truncated) truncatedSources.push(source);
      });
      // Same cap for every source, so any response reports it.
      return { bySource, truncatedSources, observationLimit: responses[0]?.observation_limit ?? null };
    },
    20000,
    // The area is spread into primitives rather than passed as an object:
    // usePolling compares deps by identity, and a fresh {lat,lng,radiusM}
    // object every render would refetch on every render.
    [
      activeSources.join(","),
      filter.since,
      filter.until,
      filter.sessionLimit,
      filter.area?.lat,
      filter.area?.lng,
      filter.area?.radiusM,
    ],
    { paused: printing },
  );

  const {
    coveragePolygons,
    mobilePoints,
    devicePoints,
    deviceCount,
    currentScanLabel,
    visibleReadings,
    calloutByPath,
    countsBySource,
  } = useMemo(() => {
    // Source is tracked alongside each device so the report's Type column can
    // say which radio a reading came from — the flattened device list alone
    // has no way back to it.
    const deviceEntries: { device: RadioCoverage; source: HeatmapSource }[] = [];
    activeSources.forEach((source) =>
      (data?.bySource[source] ?? []).forEach((device) => deviceEntries.push({ device, source })),
    );
    const allDevices: RadioCoverage[] = deviceEntries.map((entry) => entry.device);
    const countsBySource = activeSources.map((source) => ({
      source,
      count: (data?.bySource[source] ?? []).length,
    }));

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
    const readings = deviceEntries.flatMap(({ device, source }) =>
      device.points.filter(isPointVisible).map((p) => ({
        label: device.label,
        detailPath: device.detail_path,
        source,
        deviceTypeGuess: device.device_type_guess,
        lat: p.lat,
        lng: p.lng,
        weight: p.weight,
        observedAt: p.observed_at,
      })),
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
        (data?.bySource[source] ?? []).forEach((device) => {
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
        calloutByPath: assignCallouts(soloPolygons),
        countsBySource,
      };
    }

    // Accumulate — everything at or before the selected scan's timestamp.
    // Devices not seen yet by that point in time simply don't appear,
    // so dragging the slider right replays the survey building up.
    const cutoff = timeline.cutoffObservedAt;
    const polygons: CoveragePolygon[] = [];
    const points: MapPoint[] = [];

    activeSources.forEach((source) => {
      const devices = data?.bySource[source] ?? [];
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
      calloutByPath: assignCallouts(polygons),
      countsBySource,
    };
  }, [data, activeSources, filter.mapDisplayMode, filter.scanIndexPercent]);

  const chronologicalReadings = [...visibleReadings]
    .filter((r) => r.observedAt)
    .sort((a, b) => (a.observedAt as string).localeCompare(b.observedAt as string));
  // Newest-first like the detail pages' tables, capped so a big combined
  // survey doesn't render thousands of rows.
  const tableReadings = [...chronologicalReadings].reverse().slice(0, 200);

  const reportSummary: ReportField[] = useMemo(() => {
    const breakdown = countsBySource
      .map(({ source, count }) => `${count} ${SOURCE_SHORT[source]}`)
      .join(", ");
    const mapped = calloutByPath.size;
    const weights = visibleReadings.map((r) => r.weight);
    const first = chronologicalReadings[0]?.observedAt;
    const last = chronologicalReadings[chronologicalReadings.length - 1]?.observedAt;

    const fields: ReportField[] = [
      { label: "Devices", value: `${deviceCount}${breakdown ? ` (${breakdown})` : ""}` },
      {
        label: "Coverage",
        value: `${mapped} mapped as coverage, ${Math.max(0, deviceCount - mapped)} shown as points`,
      },
      { label: "Readings", value: `${visibleReadings.length}${tableReadings.length < visibleReadings.length ? ` (newest ${tableReadings.length} listed)` : ""}` },
    ];
    if (first && last) {
      fields.push({
        label: "Observed",
        value: `${new Date(first).toLocaleString()} — ${new Date(last).toLocaleString()}`,
      });
    }
    if (weights.length > 0) {
      fields.push({
        label: "Signal range",
        value: `${Math.round(Math.max(...weights))} to ${Math.round(Math.min(...weights))} dBm`,
      });
    }
    return fields;
  }, [countsBySource, calloutByPath, deviceCount, visibleReadings, chronologicalReadings, tableReadings.length]);

  // The provenance half — everything needed to reproduce this exact map.
  // The area line is not optional: a report filtered to a circle without
  // saying so is a report that lies by omission about what it left out.
  const sharedViewSettings = useReportViewSettings(currentScanLabel);
  // Same four rows as every other report, plus the source toggles that only
  // exist here.
  const reportViewSettings: ReportField[] = [
    { label: "Sources", value: activeSources.map((s) => SOURCE_SHORT[s]).join(", ") || "none" },
    ...sharedViewSettings,
  ];

  return (
    <section>
      <ReportHeader title="Coverage report — Heatmap" summary={reportSummary} viewSettings={reportViewSettings} />

      <h1 className="print-hide">Heatmap</h1>
      <p className="page-hint print-hide">
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

      {filter.area && (
        <p className="page-hint">
          Focus area active: {Math.round(filter.area.radiusM)} m around {formatCoords(filter.area.lat, filter.area.lng)}.
          Every page is narrowed to devices whose <em>estimated</em> position falls inside it — that estimate is the
          signal-weighted centre of everywhere the device was heard, so a device only ever picked up from one side
          sits off to that side of where it really is. Devices heard just once are placed at the phone's own position.
          Narrowing the date range, not the circle, is what reduces how much data has to be read.
        </p>
      )}

      {filter.isAllTime && (
        <p className="page-hint">
          "All time" can span very different locations if you've traveled with the phone — the map zooms out to fit
          everything, which can make individual shapes hard to see. Narrow the range above for a clearer local view.
        </p>
      )}

      {data && data.truncatedSources.length > 0 && (
        <p className="warning-text">
          Incomplete map:{" "}
          {data.truncatedSources.map((source) => SOURCES.find((s) => s.value === source)?.label).join(" and ")} matched
          more than {data.observationLimit?.toLocaleString()} observations, so some devices are missing from the shapes
          below. Narrow the time range or scan window above for the full picture.
        </p>
      )}

      {loading && !data && <p>Loading…</p>}
      {error && <p className="error-text">Could not reach the backend: {error.message}</p>}
      {data && activeSources.length > 0 && deviceCount === 0 && (
        <p className="empty-state">
          {filter.area
            ? "No devices are estimated to be inside the focus area for this time range. Widen the radius, drag the circle, or clear it using the controls on the map."
            : "No geotagged observations yet for the active source(s) in this time range."}
        </p>
      )}
      {/* The focus area keeps the map mounted even when it matches nothing.
          Otherwise drawing a circle over an empty patch unmounts the map and
          takes the radius/clear controls with it, stranding you with a filter
          you can no longer see or undo. */}
      {data && (coveragePolygons.length > 0 || mobilePoints.length > 0 || filter.area) && (
        <>
          <RadioMap
            points={[...mobilePoints, ...devicePoints]}
            mode="heat"
            polygons={coveragePolygons}
            onReady={onMapReady}
            area={filter.area}
            onAreaChange={filter.setArea}
          />

          {/* Paper has no hover and no legend control, so both have to be
              printed alongside the map. */}
          {/* A stationary survey can't produce coverage shapes at all: a
              hull needs a device to have been heard from several different
              places. Without this note the printed map is just a dot, and the
              reader has no way to tell a limitation of the data from a
              failure of the tool. */}
          {coveragePolygons.length === 0 && (
            <p className="page-hint print-only">
              No coverage areas in this view — every device here was heard from a single position, so there is
              nothing to draw an area from. Coverage shapes appear once the phone has moved between scans; walk or
              drive the route you want mapped and the same report will show estimated coverage per device.
            </p>
          )}
          <p className="page-hint print-only">
            Shapes are estimated coverage per device: solid green at the estimated device location, fading to orange at
            the edge of where it was still detected. Numbered badges match the <strong>#</strong> column in the sighting
            history below. Devices that moved too much for coverage to mean anything are drawn as plain points and carry
            no number.
          </p>

          <MapDisplayModeControls
            mode={filter.mapDisplayMode}
            onModeChange={filter.setMapDisplayMode}
            percent={filter.scanIndexPercent}
            onPercentChange={filter.setScanIndexPercent}
            label={currentScanLabel}
          />

          <div className="print-hide" style={{ margin: "0.75rem 0" }}>
            <PrintReportButton {...printButtonProps} />
          </div>

          {/* Both sections describe the readings behind the map, so neither
              means anything when the current filter matches none — which the
              focus area can now legitimately produce. */}
          {chronologicalReadings.length > 0 && (
            <>
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
            </>
          )}

          <h2>Sighting history</h2>
          <p className="page-hint print-hide">
            Follows the slider above — chart and table show the same readings the map does.
          </p>
          {tableReadings.length > 0 && (
            <SightingTable
              rows={tableReadings.map((r, index) => ({
                id: `${r.detailPath}-${r.observedAt}-${index}`,
                callout: calloutByPath.get(r.detailPath) ?? null,
                device: r.label,
                detailPath: r.detailPath,
                type: describeType(r.source, r.deviceTypeGuess),
                signal: Math.round(r.weight),
                latitude: r.lat,
                longitude: r.lng,
                observedAt: r.observedAt ?? null,
              }))}
              columns={[
                {
                  key: "callout",
                  label: "#",
                  render: (row) => <span className="callout-ref">{row.callout ?? "—"}</span>,
                },
                { key: "device", label: "Device", render: (row) => <Link to={row.detailPath}>{row.device}</Link> },
                { key: "type", label: "Type" },
                {
                  key: "signal",
                  label: "Signal",
                  render: (row) => <span style={{ color: signalStrengthColor(row.signal) }}>{row.signal} dBm</span>,
                },
              ]}
            />
          )}
        </>
      )}
    </section>
  );
}
