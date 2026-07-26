// Matches "channel=6" or "cell_id = 456732" — a column-specific filter
// rather than free text across every field. The key doesn't need to be the
// exact property name (e.g. "channel" for AccessPoint's "latest_channel"):
// any property whose name *contains* the typed key is checked, so users
// don't need to know the underlying field name precisely.
const COLUMN_FILTER = /^([a-zA-Z0-9_]+)\s*=\s*(.+)$/;

/** Free-text filter across every column of a row — matches if *any* own
 * property's string form contains the query (case-insensitive), unless the
 * query looks like "key=value" (see COLUMN_FILTER above), in which case
 * only properties whose name contains "key" are checked, and the match
 * must be exact rather than a substring (so "channel=6" doesn't also match
 * channel 16 or 26). Skips nested objects/arrays rather than trying to
 * stringify them meaningfully. Pure function (not a hook) since the query
 * itself now lives in the global FilterContext, not per-page local state —
 * see MEMORY.md. */
export function filterBySearch<T>(items: T[], query: string): T[] {
  const trimmed = query.trim();
  if (!trimmed) return items;

  const columnMatch = trimmed.match(COLUMN_FILTER);
  if (columnMatch) {
    const [, key, rawValue] = columnMatch;
    const needleKey = key.toLowerCase();
    const needleValue = rawValue.trim().toLowerCase();
    return items.filter((item) => {
      const record = item as Record<string, unknown>;
      return Object.keys(record).some((prop) => {
        if (!prop.toLowerCase().includes(needleKey)) return false;
        const value = record[prop];
        return value != null && String(value).toLowerCase() === needleValue;
      });
    });
  }

  const needle = trimmed.toLowerCase();
  return items.filter((item) =>
    Object.values(item as Record<string, unknown>).some((value) => {
      if (value == null || typeof value === "object") return false;
      return String(value).toLowerCase().includes(needle);
    }),
  );
}
