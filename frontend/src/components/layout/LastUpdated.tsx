import { useEffect, useState } from "react";
import { LAST_UPDATED_EVENT } from "../../api/client";

export function LastUpdated() {
  const [timestamp, setTimestamp] = useState<number | null>(null);

  useEffect(() => {
    const handler = (event: Event) => setTimestamp((event as CustomEvent<number>).detail);
    window.addEventListener(LAST_UPDATED_EVENT, handler);
    return () => window.removeEventListener(LAST_UPDATED_EVENT, handler);
  }, []);

  if (!timestamp) return null;

  return <footer className="app-footer">Last updated {new Date(timestamp).toLocaleTimeString()}</footer>;
}
