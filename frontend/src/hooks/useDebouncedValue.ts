import { useEffect, useState } from "react";

/**
 * Trails `value` by `delayMs` — the search box itself stays instant/
 * controlled, but the *request-triggering* value (fetch deps/query string)
 * lags behind so a search that's now server-side (see apply_search in
 * scans/views.py) doesn't fire one HTTP request per keystroke.
 */
export function useDebouncedValue<T>(value: T, delayMs = 300): T {
  const [debounced, setDebounced] = useState(value);

  useEffect(() => {
    const id = setTimeout(() => setDebounced(value), delayMs);
    return () => clearTimeout(id);
  }, [value, delayMs]);

  return debounced;
}
