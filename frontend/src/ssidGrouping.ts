import type { AccessPoint } from "./api/types";

// One row per SSID (mesh networks share one name across several BSSIDs,
// same idea as Cellular/LAN/BLE grouping their own raw readings into one
// row per physical thing) — a hidden/blank SSID can't be grouped
// meaningfully by name, so each of those stays its own row instead.
export interface SsidGroupRow {
  key: string;
  ssid: string;
  bssidCount: number;
  singleBssid: string | null;
  channels: string;
  security: string;
  vendor: string;
  strongestRssi: number | null;
  lastSeen: string;
  hasLocation: boolean;
  linkPath: string;
}

export function groupBySsid(accessPoints: AccessPoint[]): SsidGroupRow[] {
  const groups = new Map<string, AccessPoint[]>();
  accessPoints.forEach((ap) => {
    const key = ap.ssid ? `ssid:${ap.ssid}` : `bssid:${ap.bssid}`;
    const list = groups.get(key) ?? [];
    list.push(ap);
    groups.set(key, list);
  });

  return Array.from(groups.values()).map((members) => {
    const ssid = members[0].ssid;
    const channels = Array.from(
      new Set(members.map((m) => m.latest_channel).filter((c): c is number => c != null)),
    ).sort((a, b) => a - b);
    const securityTypes = Array.from(
      new Set(members.map((m) => m.latest_security_type).filter((s): s is string => s != null)),
    );
    const vendors = Array.from(new Set(members.map((m) => m.vendor_oui).filter(Boolean)));
    const strongestRssi = members.reduce<number | null>(
      (max, m) => (m.latest_rssi != null && (max == null || m.latest_rssi > max) ? m.latest_rssi : max),
      null,
    );
    const lastSeen = members.reduce(
      (latest, m) => (new Date(m.last_seen_at) > new Date(latest) ? m.last_seen_at : latest),
      members[0].last_seen_at,
    );

    return {
      key: ssid ? `ssid:${ssid}` : `bssid:${members[0].bssid}`,
      ssid: ssid || "(hidden)",
      bssidCount: members.length,
      singleBssid: members.length === 1 ? members[0].bssid : null,
      channels: channels.length > 0 ? channels.join(", ") : "—",
      security: securityTypes.length === 1 ? securityTypes[0] : securityTypes.length > 1 ? "Mixed" : "—",
      vendor: vendors.length === 1 ? vendors[0] : vendors.length > 1 ? "Multiple" : "—",
      strongestRssi,
      lastSeen,
      hasLocation: members.some((m) => m.latest_has_location),
      linkPath: ssid ? `/networks/ssid/${encodeURIComponent(ssid)}` : `/networks/${encodeURIComponent(members[0].bssid)}`,
    };
  });
}
