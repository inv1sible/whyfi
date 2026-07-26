interface CompassArrowProps {
  bearingDegrees: number;
}

// A rotating needle, refreshed on every poll — while the Android app is
// continuously scanning, watching this update each cycle is effectively
// "navigate by the arrow" without needing anything extra on the phone side.
export function CompassArrow({ bearingDegrees }: CompassArrowProps) {
  const size = 140;
  const center = size / 2;

  return (
    <svg viewBox={`0 0 ${size} ${size}`} width={size} height={size} style={{ display: "block" }}>
      <circle cx={center} cy={center} r={center - 4} fill="none" style={{ stroke: "var(--border)" }} strokeWidth={1.5} />
      <text x={center} y={14} textAnchor="middle" fontSize={11} style={{ fill: "var(--text-dim)" }}>N</text>
      <text x={size - 8} y={center + 4} textAnchor="end" fontSize={11} style={{ fill: "var(--text-dim)" }}>E</text>
      <text x={center} y={size - 6} textAnchor="middle" fontSize={11} style={{ fill: "var(--text-dim)" }}>S</text>
      <text x={8} y={center + 4} textAnchor="start" fontSize={11} style={{ fill: "var(--text-dim)" }}>W</text>
      <g transform={`rotate(${bearingDegrees} ${center} ${center})`}>
        <line x1={center} y1={center} x2={center} y2={20} style={{ stroke: "#dc2626" }} strokeWidth={3} strokeLinecap="round" />
        <polygon points={`${center},12 ${center - 6},24 ${center + 6},24`} fill="#dc2626" />
      </g>
      <circle cx={center} cy={center} r={4} fill="#dc2626" />
    </svg>
  );
}
