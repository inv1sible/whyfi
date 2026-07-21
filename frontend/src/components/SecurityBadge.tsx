const LABELS: Record<string, string> = {
  OPEN: "Open (unencrypted)",
  WEP: "WEP",
  WPA: "WPA",
  WPA2: "WPA2",
  WPA3: "WPA3",
  WPA2_WPA3: "WPA2/WPA3",
  UNKNOWN: "Unknown",
};

function severity(securityType: string | null): "danger" | "warning" | "ok" | "neutral" {
  if (securityType === "OPEN") return "danger";
  if (securityType === "WEP") return "warning";
  if (securityType === "WPA2" || securityType === "WPA3" || securityType === "WPA2_WPA3") return "ok";
  return "neutral";
}

export function SecurityBadge({ securityType }: { securityType: string | null }) {
  const level = severity(securityType);
  const label = securityType ? LABELS[securityType] ?? securityType : "Unknown";
  return <span className={`badge badge-${level}`}>{label}</span>;
}
