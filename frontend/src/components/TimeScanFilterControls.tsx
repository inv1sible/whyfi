import type { FilterMode } from "../hooks/useTimeScanFilter";
import { formatDuration, MAX_SCAN_COUNT } from "../hooks/useTimeScanFilter";

interface TimeScanFilterControlsProps {
  mode: FilterMode;
  onModeChange: (mode: FilterMode) => void;
  timePercent: number;
  onTimePercentChange: (percent: number) => void;
  minutes: number;
  scanCount: number;
  onScanCountChange: (count: number) => void;
  rangeFrom: string;
  onRangeFromChange: (value: string) => void;
  rangeTo: string;
  onRangeToChange: (value: string) => void;
}

export function TimeScanFilterControls({
  mode,
  onModeChange,
  timePercent,
  onTimePercentChange,
  minutes,
  scanCount,
  onScanCountChange,
  rangeFrom,
  onRangeFromChange,
  rangeTo,
  onRangeToChange,
}: TimeScanFilterControlsProps) {
  return (
    <>
      <div className="band-selector">
        <button className={mode === "last-n-scans" ? "active" : ""} onClick={() => onModeChange("last-n-scans")}>
          Scans
        </button>
        <button className={mode === "time" ? "active" : ""} onClick={() => onModeChange("time")}>
          Time
        </button>
        <button className={mode === "range" ? "active" : ""} onClick={() => onModeChange("range")}>
          Date
        </button>
      </div>

      {/* The sliders all mean "up to now", so they slide out from under a
          report. This is the mode that pins both ends. */}
      {mode === "range" && (
        <div className="date-range">
          <label>
            <span>From</span>
            <input type="datetime-local" value={rangeFrom} onChange={(e) => onRangeFromChange(e.target.value)} />
          </label>
          <label>
            <span>To</span>
            <input type="datetime-local" value={rangeTo} onChange={(e) => onRangeToChange(e.target.value)} />
          </label>
        </div>
      )}

      {mode === "time" && (
        <div className="time-slider">
          <input
            type="range"
            min={0}
            max={100}
            value={timePercent}
            onChange={(e) => onTimePercentChange(Number(e.target.value))}
          />
          <span className="time-slider-value">{formatDuration(minutes)}</span>
        </div>
      )}

      {mode === "last-n-scans" && (
        <div className="time-slider">
          <input
            type="range"
            min={1}
            max={MAX_SCAN_COUNT}
            value={scanCount}
            onChange={(e) => onScanCountChange(Number(e.target.value))}
          />
          <span className="time-slider-value">
            {scanCount >= MAX_SCAN_COUNT ? "All scans" : `Last ${scanCount} scan${scanCount === 1 ? "" : "s"}`}
          </span>
        </div>
      )}
    </>
  );
}
