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
}

export function TimeScanFilterControls({
  mode,
  onModeChange,
  timePercent,
  onTimePercentChange,
  minutes,
  scanCount,
  onScanCountChange,
}: TimeScanFilterControlsProps) {
  return (
    <>
      <div className="band-selector">
        <button className={mode === "last-n-scans" ? "active" : ""} onClick={() => onModeChange("last-n-scans")}>
          Last N scans
        </button>
        <button className={mode === "time" ? "active" : ""} onClick={() => onModeChange("time")}>
          Time window
        </button>
      </div>

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
