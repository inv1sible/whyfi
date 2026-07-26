// Shared signal-strength color/label scale, so a dBm number means the same
// thing (and looks the same) in the chart readout, tables, and anywhere else
// it surfaces. Thresholds follow the usual WiFi survey convention:
// -55 and up is excellent, below -85 is barely usable.
const DBM_STEPS: { min: number; color: string; label: string }[] = [
  { min: -55, color: "#22c55e", label: "Excellent" },
  { min: -67, color: "#84cc16", label: "Good" },
  { min: -75, color: "#eab308", label: "Fair" },
  { min: -85, color: "#f97316", label: "Weak" },
  { min: -Infinity, color: "#ef4444", label: "Very weak" },
];

export function signalStrengthColor(dbm: number): string {
  return (DBM_STEPS.find((s) => dbm >= s.min) ?? DBM_STEPS[DBM_STEPS.length - 1]).color;
}

export function signalStrengthLabel(dbm: number): string {
  return (DBM_STEPS.find((s) => dbm >= s.min) ?? DBM_STEPS[DBM_STEPS.length - 1]).label;
}

// LAN devices have no RSSI — their "signal" is round-trip response time,
// where lower is better, so it needs its own scale rather than reusing the
// dBm one. See LANDeviceDetailPage.
const MS_STEPS: { max: number; color: string; label: string }[] = [
  { max: 10, color: "#22c55e", label: "Excellent" },
  { max: 30, color: "#84cc16", label: "Good" },
  { max: 80, color: "#eab308", label: "Fair" },
  { max: 200, color: "#f97316", label: "Slow" },
  { max: Infinity, color: "#ef4444", label: "Very slow" },
];

export function responseTimeColor(ms: number): string {
  return (MS_STEPS.find((s) => ms <= s.max) ?? MS_STEPS[MS_STEPS.length - 1]).color;
}

export function responseTimeLabel(ms: number): string {
  return (MS_STEPS.find((s) => ms <= s.max) ?? MS_STEPS[MS_STEPS.length - 1]).label;
}
