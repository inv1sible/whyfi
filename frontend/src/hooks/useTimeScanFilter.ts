import { useEffect, useMemo, useState } from "react";

export type FilterMode = "time" | "last-n-scans";

// Log-scaled so the slider gives fine control over the last hour (where
// most of the interesting variation actually is) while still reaching all
// the way out to a month — flat "Last hour/24h/7 days" buttons couldn't
// express anything in between.
const MIN_MINUTES = 5;
const MAX_MINUTES = 60 * 24 * 30;
const DEFAULT_PERCENT = 27; // lands close to 1 hour

// The last-N-scans slider's top end means "no limit" (all scans) rather
// than capping at a fixed count — mirrors "All time" at the top of the
// time-window slider.
export const MAX_SCAN_COUNT = 100;

function sliderToMinutes(percent: number): number {
  const logMin = Math.log(MIN_MINUTES);
  const logMax = Math.log(MAX_MINUTES);
  return Math.exp(logMin + (percent / 100) * (logMax - logMin));
}

export function formatDuration(minutes: number): string {
  if (minutes >= MAX_MINUTES) return "All time";
  if (minutes < 60) return `${Math.round(minutes)} min ago`;
  if (minutes < 60 * 24) return `${(minutes / 60).toFixed(1)} h ago`;
  return `${(minutes / (60 * 24)).toFixed(1)} d ago`;
}

/**
 * Shared time-window / scan-count filter state, used by the Heatmap, WiFi
 * (channel congestion), BLE devices, and Cellular towers pages.
 *
 * The slider's *visual* position (timePercent) updates instantly for
 * responsive feedback while dragging; the *committed* value only follows
 * after a short pause. `since`/`sessionLimit` are derived from the
 * committed value only and memoized — deriving them straight from
 * timePercent (which changes on every drag event, and calls Date.now())
 * caused an infinite render->refetch->render loop the first time this was
 * built for the heatmap page, made worse by the slider hammering the API
 * on every pixel of movement. Don't reintroduce that.
 */
export function useTimeScanFilter(defaultScanCount = 5) {
  // Last-N-scans rather than a time window by default: "the last 5 scans"
  // is what you actually want when you've just walked a survey, and it
  // stays meaningful when the data is hours or days old (a time window
  // silently shows nothing at all in that case).
  const [mode, setMode] = useState<FilterMode>("last-n-scans");
  const [timePercent, setTimePercent] = useState(DEFAULT_PERCENT);
  const [committedPercent, setCommittedPercent] = useState(DEFAULT_PERCENT);
  const [scanCount, setScanCount] = useState(defaultScanCount);

  useEffect(() => {
    const timer = setTimeout(() => setCommittedPercent(timePercent), 400);
    return () => clearTimeout(timer);
  }, [timePercent]);

  const minutes = sliderToMinutes(timePercent);

  const since = useMemo(() => {
    const fetchMinutes = sliderToMinutes(committedPercent);
    return mode === "time" && fetchMinutes < MAX_MINUTES
      ? new Date(Date.now() - fetchMinutes * 60_000).toISOString()
      : undefined;
  }, [mode, committedPercent]);

  const isAllScans = mode === "last-n-scans" && scanCount >= MAX_SCAN_COUNT;
  const sessionLimit = mode === "last-n-scans" && !isAllScans ? scanCount : undefined;

  return {
    mode,
    setMode,
    timePercent,
    setTimePercent,
    scanCount,
    setScanCount,
    since,
    sessionLimit,
    minutes,
    isAllTime: mode === "time" && minutes >= MAX_MINUTES,
    isAllScans,
  };
}
