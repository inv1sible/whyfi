import { useCallback, useMemo, useRef, useState } from "react";
import type { RadioMapHandle } from "../components/RadioMap";
import { describeDisplayMode, describeFilterWindow } from "../components/ReportHeader";
import type { ReportField } from "../components/ReportHeader";
import { useFilter } from "../context/FilterContext";
import { formatCoords } from "../reportLinks";

/**
 * The wiring every printable page needs: a handle on the map so it can be
 * re-framed for paper, and a printing flag so polling can be paused while the
 * dialog is open.
 *
 * Extracted because it is identical on all six report pages and easy to get
 * subtly wrong — `printButtonProps` in particular has to keep a stable
 * identity, or `usePolling`'s dependency comparison (which is by identity)
 * restarts the timer on every render.
 */
export function useReportPrinting() {
  const [printing, setPrinting] = useState(false);
  const mapHandleRef = useRef<RadioMapHandle | null>(null);

  const onMapReady = useCallback((handle: RadioMapHandle) => {
    mapHandleRef.current = handle;
  }, []);

  const printButtonProps = useMemo(
    () => ({
      // Resolves immediately when there's no map on the page — the report is
      // still worth printing without one.
      onPrepare: () => mapHandleRef.current?.prepareForPrint() ?? Promise.resolve(),
      onPrintingChange: setPrinting,
    }),
    [],
  );

  return { printing, onMapReady, printButtonProps };
}

/**
 * The "View settings" half of a report header, built from the global filter.
 *
 * Every report has to state the view that produced it — a coverage map with
 * no record of which scans, which slider position and which focus area it
 * came from isn't reportable. Centralised so the six pages can't word it
 * differently or, worse, quietly omit the focus area.
 *
 * @param selectedScanLabel the slider's current position, in the same words
 *   the on-screen control uses. Null when nothing on the page is geotagged,
 *   in which case there is no scan timeline to be positioned on.
 */
export function useReportViewSettings(selectedScanLabel: string | null): ReportField[] {
  const filter = useFilter();
  return [
    { label: "Range", value: describeFilterWindow(filter) },
    {
      label: "Focus area",
      value: filter.area
        ? `${Math.round(filter.area.radiusM)} m around ${formatCoords(filter.area.lat, filter.area.lng)}`
        : "Whole survey",
    },
    { label: "Display mode", value: describeDisplayMode(filter.mapDisplayMode) },
    { label: "Selected scan", value: selectedScanLabel ?? "No geotagged scans in range" },
  ];
}

/** Signal span across a set of readings, or a dash when there are none. */
export function describeSignalRange(values: number[], unit = "dBm"): string {
  if (values.length === 0) return "—";
  const min = Math.min(...values);
  const max = Math.max(...values);
  return min === max ? `${min} ${unit}` : `${max} to ${min} ${unit}`;
}

/** Oldest→newest span of an already-chronological list of ISO timestamps. */
export function describeObservedSpan(timestamps: string[]): string {
  if (timestamps.length === 0) return "—";
  const first = new Date(timestamps[0]).toLocaleString();
  const last = new Date(timestamps[timestamps.length - 1]).toLocaleString();
  return first === last ? first : `${first} — ${last}`;
}
