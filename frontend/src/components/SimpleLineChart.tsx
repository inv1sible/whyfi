interface Point {
  label: string;
  value: number;
}

interface SimpleLineChartProps {
  points: Point[];
  unit?: string;
  // When given, the latest reading's number is tinted with this and each
  // dot is colored by its own value, turning the line into a signal-quality
  // gradient rather than one flat accent color. Omit for series with no
  // meaningful absolute scale (e.g. satellite Cn0).
  valueColor?: (value: number) => string;
  valueLabel?: (value: number) => string;
}

// Dependency-free SVG sparkline — good enough for a single-series time
// chart (signal strength/Cn0 history) without pulling in a charting library.
export function SimpleLineChart({ points, unit = "", valueColor, valueLabel }: SimpleLineChartProps) {
  if (points.length === 0) {
    return <p className="empty-state">No data yet.</p>;
  }

  const width = 640;
  const height = 200;
  const padding = 32;

  const values = points.map((p) => p.value);
  const min = Math.min(...values);
  const max = Math.max(...values);
  const range = max - min || 1;

  const stepX = (width - padding * 2) / Math.max(points.length - 1, 1);
  const coords = points.map((p, i) => {
    const x = padding + i * stepX;
    const y = height - padding - ((p.value - min) / range) * (height - padding * 2);
    return { x, y, ...p };
  });

  const path = coords.map((c, i) => `${i === 0 ? "M" : "L"}${c.x.toFixed(1)},${c.y.toFixed(1)}`).join(" ");

  // Points are chronological, so the last one is the current reading.
  const current = points[points.length - 1].value;
  const currentColor = valueColor ? valueColor(current) : "var(--accent)";

  return (
    <>
      <div style={{ display: "flex", alignItems: "baseline", gap: "0.75rem", flexWrap: "wrap", margin: "0.4rem 0" }}>
        <span style={{ fontSize: "1.6rem", fontWeight: 600, color: currentColor, lineHeight: 1 }}>
          {Math.round(current)}
          {unit}
        </span>
        {valueLabel && <span style={{ color: currentColor, fontSize: "0.9rem" }}>{valueLabel(current)}</span>}
        <span className="page-hint" style={{ margin: 0 }}>
          current · {points.length} reading{points.length === 1 ? "" : "s"} · range {Math.round(min)}
          {unit} to {Math.round(max)}
          {unit}
        </span>
      </div>
      <svg viewBox={`0 0 ${width} ${height}`} className="chart" role="img" aria-label="Signal history chart">
        <path d={path} fill="none" stroke="var(--accent)" strokeWidth={2} />
        {coords.map((c, i) => (
          // Index key, not c.label: several devices legitimately share one
          // observed_at (a single scan pass sees many at once), and the
          // resulting duplicate keys made React reuse the wrong <circle>
          // nodes — dots visibly failed to move when the data changed.
          <circle
            key={i}
            cx={c.x}
            cy={c.y}
            r={i === coords.length - 1 ? 5 : 3}
            fill={valueColor ? valueColor(c.value) : "var(--accent)"}
          />
        ))}
        <text x={padding} y={16} fill="var(--text-dim)" fontSize={11}>
          {Math.round(max)}
          {unit}
        </text>
        <text x={padding} y={height - padding + 16} fill="var(--text-dim)" fontSize={11}>
          {Math.round(min)}
          {unit}
        </text>
      </svg>
    </>
  );
}
