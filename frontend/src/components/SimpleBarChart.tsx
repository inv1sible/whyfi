interface Bar {
  label: string;
  value: number;
  color?: string;
}

export function SimpleBarChart({ bars, unit = "" }: { bars: Bar[]; unit?: string }) {
  if (bars.length === 0) {
    return <p className="empty-state">No data yet.</p>;
  }

  const max = Math.max(...bars.map((b) => b.value), 1);

  return (
    <div className="bar-chart">
      {bars.map((bar) => (
        <div className="bar-row" key={bar.label}>
          <span className="bar-label">{bar.label}</span>
          <div className="bar-track">
            <div
              className="bar-fill"
              style={{ width: `${(bar.value / max) * 100}%`, background: bar.color ?? "var(--accent)" }}
            />
          </div>
          <span className="bar-value">
            {bar.value}
            {unit}
          </span>
        </div>
      ))}
    </div>
  );
}
