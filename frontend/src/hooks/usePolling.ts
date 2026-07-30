import { useEffect, useRef, useState } from "react";

interface PollingState<T> {
  data: T | null;
  error: Error | null;
  loading: boolean;
}

interface PollingOptions {
  /** Freeze on the data currently held instead of refreshing. Used while a
   * report is being printed: a refresh landing between the click and the
   * print dialog would silently swap in data the user never saw, and the
   * whole point of a report is that it shows what was on screen. */
  paused?: boolean;
}

export function usePolling<T>(
  fetcher: () => Promise<T>,
  intervalMs = 15000,
  deps: unknown[] = [],
  { paused = false }: PollingOptions = {},
): PollingState<T> {
  const [state, setState] = useState<PollingState<T>>({ data: null, error: null, loading: true });
  const fetcherRef = useRef(fetcher);
  fetcherRef.current = fetcher;

  useEffect(() => {
    if (paused) return;
    let cancelled = false;

    const tick = async () => {
      try {
        const result = await fetcherRef.current();
        if (!cancelled) setState({ data: result, error: null, loading: false });
      } catch (err) {
        if (!cancelled) setState((prev) => ({ ...prev, error: err as Error, loading: false }));
      }
    };

    setState((prev) => ({ ...prev, loading: true }));
    tick();
    const id = setInterval(tick, intervalMs);
    return () => {
      cancelled = true;
      clearInterval(id);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [intervalMs, paused, ...deps]);

  return state;
}
