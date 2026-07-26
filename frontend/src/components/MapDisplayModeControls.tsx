import type { MapDisplayMode } from "../context/FilterContext";
import { TimeSlider } from "./TimeSlider";

interface MapDisplayModeControlsProps {
  mode: MapDisplayMode;
  onModeChange: (mode: MapDisplayMode) => void;
  percent: number;
  onPercentChange: (percent: number) => void;
  label: string | null;
}

// The one shared control block rendered directly under every map view
// (Heatmap page, SSID group page, all 4 entity detail pages) — deliberately
// not in the global filter bar, so it's obviously tied to the map/table it
// affects rather than looking like another list-wide filter. The backing
// state lives in FilterContext so the chosen mode and slider position carry
// over as you navigate between map views.
//
// "Accumulate" shows everything up to the slider position (drag right and
// the coverage picture builds up scan by scan); "Solo" shows just the one
// scan at the slider position. Either way the slider also narrows the
// signal chart and sighting-history table on the same page.
export function MapDisplayModeControls({ mode, onModeChange, percent, onPercentChange, label }: MapDisplayModeControlsProps) {
  return (
    <div style={{ margin: "0.75rem 0" }}>
      <div className="band-selector">
        <button className={mode === "accumulate" ? "active" : ""} onClick={() => onModeChange("accumulate")}>
          Accumulate
        </button>
        <button className={mode === "solo" ? "active" : ""} onClick={() => onModeChange("solo")}>
          Solo
        </button>
      </div>
      <TimeSlider percent={percent} onPercentChange={onPercentChange} label={label} />
      {mode === "solo" && (
        <p className="page-hint">
          Each blob is a range estimate from that one reading's signal strength — the device is somewhere inside it.
          One reading gives a distance, not a direction, so there's no exact spot to mark.
        </p>
      )}
    </div>
  );
}
