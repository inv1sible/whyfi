import { useState } from "react";
import { api } from "../api/client";

/** Shared by every detail page's map — deletes the scan a single sighting
 * came from (see RadioMap's onDeleteScanSession) and bumps a refreshKey so
 * the page's own usePolling calls (which should list it in their deps)
 * refetch immediately rather than waiting out the rest of their interval. */
export function useDeleteScanSession() {
  const [refreshKey, setRefreshKey] = useState(0);

  async function deleteScanSession(scanSessionId: string) {
    await api.bulkDeleteScanSessions([scanSessionId]);
    setRefreshKey((k) => k + 1);
  }

  return { refreshKey, deleteScanSession };
}
