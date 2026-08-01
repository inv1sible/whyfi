/**
 * The four device-list endpoints (AccessPoint/CellTower/BLEDevice/LANDevice)
 * filter by `last_seen_at`, not by an individual observation's timestamp —
 * hence the separate `active_since`/`active_until` param names, distinct
 * from the `since`/`until` the per-entity observation-history endpoints use
 * (see backend's `apply_active_window` vs `parse_window`).
 *
 * `active_until` is the point of this helper. Without it, "Date range" mode
 * only ever closed the *start* of a device list's window — a device last
 * seen after the requested range still showed up in a list that was
 * supposed to stop at the end of it, which is what made the date picker
 * look broken on every overview page (BLE/Cellular/LAN/WiFi/Dashboard).
 *
 * `&`-prefixed (or empty) so it can be appended directly after an existing
 * `?...` query string, same convention as `areaQuery`.
 */
export function activeWindowQuery(opts: { since?: string; until?: string; sessionLimit?: number }): string {
  if (opts.sessionLimit) return `&session_limit=${opts.sessionLimit}`;
  const parts: string[] = [];
  if (opts.since) parts.push(`active_since=${encodeURIComponent(opts.since)}`);
  if (opts.until) parts.push(`active_until=${encodeURIComponent(opts.until)}`);
  return parts.length > 0 ? `&${parts.join("&")}` : "";
}
