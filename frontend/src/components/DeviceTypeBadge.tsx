export const BLE_LABELS: Record<string, string> = {
  AIRTAG: "Possible AirTag",
  TILE: "Possible Tile",
  SMARTTAG: "Possible Samsung SmartTag",
  CHIPOLO: "Possible Chipolo",
  HEADPHONES: "Headphones",
  WEARABLE: "Wearable",
  UNKNOWN: "Unknown device",
  OTHER: "Other",
};

export const LAN_DEVICE_LABELS: Record<string, string> = {
  ROUTER: "Router/Gateway",
  PRINTER: "Printer",
  NAS: "NAS/File server",
  MEDIA: "Media/streaming",
  CAMERA: "Camera",
  WINDOWS_HOST: "Windows host",
  LINUX_HOST: "Linux/Unix host",
  IOT: "IoT device",
  UNKNOWN: "Unknown",
};

// Informational only — see MEMORY.md: no alerting/correlation is attached
// to this, it's just a best-effort label on a passive observation.
export function DeviceTypeBadge({ deviceType, labels = BLE_LABELS }: { deviceType: string; labels?: Record<string, string> }) {
  return <span className="badge badge-neutral">{labels[deviceType] ?? deviceType}</span>;
}
