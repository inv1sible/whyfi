import { useEffect, useMemo, useState } from "react";

export type FilterMode = "time" | "last-n-scans" | "range";

/** `datetime-local` gives and takes "YYYY-MM-DDTHH:mm" in the *browser's* zone
 * with no offset, so it can't be handed to the API as-is. `new Date(local)`
 * interprets exactly that way, which is what's wanted: the operator types wall
 * time and gets wall time. Returns undefined for a half-typed value rather
 * than an Invalid Date, which would serialise to null and silently widen the
 * window to everything. */
function localInputToIso(value: string): string | undefined {
  if (!value) return undefined;
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? undefined : parsed.toISOString();
}

/** Now, as a `datetime-local` value in the browser's zone. */
function isoToLocalInput(date: Date): string {
  const offsetMs = date.getTimezoneOffset() * 60_000;
  return new Date(date.getTime() - offsetMs).toISOString().slice(0, 16);
}

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
  // Explicit from/to, for reporting on a fixed interval rather than a window
  // that slides every time you look at it. Defaults to the last hour so the
  // fields are never empty when the mode is first opened.
  const [rangeFrom, setRangeFrom] = useState(() => isoToLocalInput(new Date(Date.now() - 60 * 60_000)));
  const [rangeTo, setRangeTo] = useState(() => isoToLocalInput(new Date()));

  useEffect(() => {
    const timer = setTimeout(() => setCommittedPercent(timePercent), 400);
    return () => clearTimeout(timer);
  }, [timePercent]);

  // Same debounce discipline as the slider: a datetime-local fires on every
  // keystroke while a year is being typed, and each one would refetch.
  const [committedFrom, setCommittedFrom] = useState(rangeFrom);
  const [committedTo, setCommittedTo] = useState(rangeTo);
  useEffect(() => {
    const timer = setTimeout(() => {
      setCommittedFrom(rangeFrom);
      setCommittedTo(rangeTo);
    }, 500);
    return () => clearTimeout(timer);
  }, [rangeFrom, rangeTo]);

  const minutes = sliderToMinutes(timePercent);

  const since = useMemo(() => {
    if (mode === "range") return localInputToIso(committedFrom);
    const fetchMinutes = sliderToMinutes(committedPercent);
    return mode === "time" && fetchMinutes < MAX_MINUTES
      ? new Date(Date.now() - fetchMinutes * 60_000).toISOString()
      : undefined;
  }, [mode, committedPercent, committedFrom]);

  // Only the explicit range closes the far end; the sliders all mean "up to
  // now", and sending an `until` for them would freeze the view at whatever
  // moment the page happened to render.
  const until = useMemo(
    () => (mode === "range" ? localInputToIso(committedTo) : undefined),
    [mode, committedTo],
  );

  const isAllScans = mode === "last-n-scans" && scanCount >= MAX_SCAN_COUNT;
  const sessionLimit = mode === "last-n-scans" && !isAllScans ? scanCount : undefined;

  return {
    mode,
    setMode,
    timePercent,
    setTimePercent,
    scanCount,
    setScanCount,
    rangeFrom,
    setRangeFrom,
    rangeTo,
    setRangeTo,
    since,
    until,
    sessionLimit,
    minutes,
    isAllTime: mode === "time" && minutes >= MAX_MINUTES,
    isAllScans,
  };
}
