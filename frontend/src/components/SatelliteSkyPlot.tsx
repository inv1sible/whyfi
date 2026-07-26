import type { SatelliteObservation } from "../api/types";

const CONSTELLATION_COLORS: Record<string, string> = {
  GPS: "#0f766e",
  GLONASS: "#b45309",
  GALILEO: "#1d4ed8",
  BEIDOU: "#be123c",
  QZSS: "#7c3aed",
  SBAS: "#4b5563",
  IRNSS: "#059669",
};

const SIZE = 320;
const CENTER = SIZE / 2;
const MAX_RADIUS = SIZE / 2 - 28;

// Elevation 90° (zenith) plots at the center, 0° (horizon) at the edge —
// the standard "sky plot" convention used by GNSS tools. Azimuth is
// compass-relative (0°=N, 90°=E) with N pointing up.
function project(azimuthDeg: number, elevationDeg: number): { x: number; y: number } {
  const radius = ((90 - elevationDeg) / 90) * MAX_RADIUS;
  const angleRad = (azimuthDeg * Math.PI) / 180;
  return { x: CENTER + radius * Math.sin(angleRad), y: CENTER - radius * Math.cos(angleRad) };
}

// Both size and opacity encode signal strength (Cn0), not just size —
// makes weak-signal satellites visually recede rather than just shrink a
// little, which was easy to miss.
function dotRadius(cn0DbHz: number): number {
  const clamped = Math.max(10, Math.min(45, cn0DbHz));
  return 4 + ((clamped - 10) / 35) * 10;
}

function dotOpacity(cn0DbHz: number): number {
  const clamped = Math.max(10, Math.min(45, cn0DbHz));
  return 0.25 + ((clamped - 10) / 35) * 0.75;
}

export function SatelliteSkyPlot({ satellites }: { satellites: SatelliteObservation[] }) {
  const plottable = satellites.filter((sat) => sat.azimuth_degrees != null && sat.elevation_degrees != null);

  return (
    <svg viewBox={`0 0 ${SIZE} ${SIZE}`} width="100%" style={{ maxWidth: 360, display: "block", margin: "0 auto" }}>
      {[0, 30, 60].map((elevation) => (
        <circle
          key={elevation}
          cx={CENTER}
          cy={CENTER}
          r={((90 - elevation) / 90) * MAX_RADIUS}
          fill="none"
          style={{ stroke: "var(--border)" }}
          strokeWidth={1}
        />
      ))}
      <line x1={CENTER} y1={CENTER - MAX_RADIUS} x2={CENTER} y2={CENTER + MAX_RADIUS} style={{ stroke: "var(--border)" }} />
      <line x1={CENTER - MAX_RADIUS} y1={CENTER} x2={CENTER + MAX_RADIUS} y2={CENTER} style={{ stroke: "var(--border)" }} />
      <text x={CENTER} y={CENTER - MAX_RADIUS - 8} textAnchor="middle" fontSize={11} style={{ fill: "var(--text-dim)" }}>N</text>
      <text x={CENTER + MAX_RADIUS + 10} y={CENTER + 4} textAnchor="start" fontSize={11} style={{ fill: "var(--text-dim)" }}>E</text>
      <text x={CENTER} y={CENTER + MAX_RADIUS + 18} textAnchor="middle" fontSize={11} style={{ fill: "var(--text-dim)" }}>S</text>
      <text x={CENTER - MAX_RADIUS - 10} y={CENTER + 4} textAnchor="end" fontSize={11} style={{ fill: "var(--text-dim)" }}>W</text>

      {plottable.map((sat) => {
        const { x, y } = project(sat.azimuth_degrees as number, sat.elevation_degrees as number);
        const color = CONSTELLATION_COLORS[sat.constellation] ?? "#94a3b8";
        const radius = dotRadius(sat.cn0_db_hz);
        const opacity = dotOpacity(sat.cn0_db_hz);
        return (
          <g key={`${sat.constellation}-${sat.svid}`}>
            <circle
              cx={x}
              cy={y}
              r={radius}
              fill={color}
              fillOpacity={sat.used_in_fix ? opacity : opacity * 0.5}
              stroke={color}
              strokeWidth={sat.used_in_fix ? 2.5 : 1.25}
            />
            <text x={x} y={y - radius - 4} textAnchor="middle" fontSize={9} style={{ fill: "var(--text-dim)" }}>
              {sat.svid}
            </text>
          </g>
        );
      })}

      {plottable.length === 0 && (
        <text x={CENTER} y={CENTER} textAnchor="middle" fontSize={12} style={{ fill: "var(--text-dim)" }}>
          No azimuth/elevation data
        </text>
      )}
    </svg>
  );
}
