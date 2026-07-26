interface TimeSliderProps {
  percent: number;
  onPercentChange: (percent: number) => void;
  // Resolved against whichever map page is currently mounted (see
  // scanTimelineLabel in FilterContext.tsx) — this component only owns the
  // 0-100 position, not what it means for any particular page's data.
  label: string | null;
}

// Scrubs through individual scans one at a time (see MapDisplayMode in
// FilterContext.tsx) — the position is a plain 0-100 percent so it
// generalizes across pages with different scan counts, the same way the
// time-range slider's timePercent does.
export function TimeSlider({ percent, onPercentChange, label }: TimeSliderProps) {
  return (
    <div className="time-slider">
      <input type="range" min={0} max={100} value={percent} onChange={(e) => onPercentChange(Number(e.target.value))} />
      <span className="time-slider-value">{label ?? "No scans in the current filter window"}</span>
    </div>
  );
}
