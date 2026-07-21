interface Point {
  label: string;
  value: number;
}

// Dependency-free SVG sparkline — good enough for a single-series time
// chart (signal strength/Cn0 history) without pulling in a charting library.
export function SimpleLineChart({ points, unit = "" }: { points: Point[]; unit?: string }) {
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

  return (
    <svg viewBox={`0 0 ${width} ${height}`} className="chart" role="img" aria-label="Signal history chart">
      <path d={path} fill="none" stroke="var(--accent)" strokeWidth={2} />
      {coords.map((c) => (
        <circle key={c.label} cx={c.x} cy={c.y} r={3} fill="var(--accent)" />
      ))}
      <text x={padding} y={16} fill="var(--text-dim)" fontSize={11}>
        {max}
        {unit}
      </text>
      <text x={padding} y={height - padding + 16} fill="var(--text-dim)" fontSize={11}>
        {min}
        {unit}
      </text>
    </svg>
  );
}
