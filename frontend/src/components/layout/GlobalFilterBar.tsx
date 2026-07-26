import { useLocation } from "react-router-dom";
import { TimeScanFilterControls } from "../TimeScanFilterControls";
import { useFilter } from "../../context/FilterContext";

// Only the time/scan range lives up here now — it decides which data gets
// fetched for the whole page (maps included), so it isn't table-specific.
// The controls that act on a table (search, show-all-columns) sit directly
// above that table instead; see TableControls.
const LIST_PATHS = new Set(["/", "/channel-congestion", "/cellular", "/ble-devices", "/heatmap", "/lan-devices"]);

// A single entity's own detail page (one AP/tower/BLE/LAN device) also
// fetches its sighting history scoped to this same filter (see
// NetworkDetailPage.tsx etc. passing since/sessionLimit from useFilter()
// into their observation queries) — so the controls need to be visible
// there too, or there'd be no way to see or change what window is being
// applied. Excludes the SSID-group page, which doesn't (yet) wire the
// filter into its own coverage fetch.
const DETAIL_PAGE_PREFIXES = ["/networks/", "/cellular/", "/ble-devices/", "/lan-devices/"];

function isFilterableDetailPage(pathname: string): boolean {
  if (pathname.startsWith("/networks/ssid/")) return false;
  return DETAIL_PAGE_PREFIXES.some((prefix) => pathname.startsWith(prefix));
}

export function GlobalFilterBar() {
  const location = useLocation();
  const filter = useFilter();

  if (!LIST_PATHS.has(location.pathname) && !isFilterableDetailPage(location.pathname)) return null;

  return (
    <div className="global-filter-bar">
      <TimeScanFilterControls
        mode={filter.mode}
        onModeChange={filter.setMode}
        timePercent={filter.timePercent}
        onTimePercentChange={filter.setTimePercent}
        minutes={filter.minutes}
        scanCount={filter.scanCount}
        onScanCountChange={filter.setScanCount}
      />
    </div>
  );
}
