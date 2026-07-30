import { createContext, useContext, useEffect, useState } from "react";
import type { ReactNode } from "react";
import type { FocusArea } from "../api/client";
import { useTimeScanFilter } from "../hooks/useTimeScanFilter";

export type MapDisplayMode = "accumulate" | "solo";

interface FilterContextValue extends ReturnType<typeof useTimeScanFilter> {
  searchQuery: string;
  setSearchQuery: (query: string) => void;
  compactTables: boolean;
  setCompactTables: (value: boolean) => void;
  // "accumulate" (default) blends every geotagged reading up to the slider
  // position into one coverage shape. "solo" instead scrubs through
  // individual scans one at a time (see scanIndexPercent below), showing
  // just that scan's reading as an RSSI-derived estimated-range blob.
  mapDisplayMode: MapDisplayMode;
  setMapDisplayMode: (value: MapDisplayMode) => void;
  // Slider position as a 0-100 percent rather than a raw index — each page
  // has its own number of distinct scans within the current filter window,
  // so a shared percent (mapped to `Math.round(percent/100 * (count-1))`
  // locally by whichever page is mounted) is what actually generalizes
  // across pages, the same way timePercent works in useTimeScanFilter.
  scanIndexPercent: number;
  setScanIndexPercent: (value: number) => void;
  // The map's focus circle, or null for "the whole survey". Lives here rather
  // than on the Heatmap page so the same circle narrows the WiFi/Cellular/BLE/
  // LAN lists too, and so a per-device report can inherit it later.
  //
  // Selects devices by *estimated position*, not by which readings fall
  // inside — see FocusArea and the backend's within_area().
  area: FocusArea | null;
  setArea: (area: FocusArea | null) => void;
}

const FilterContext = createContext<FilterContextValue | null>(null);

// One shared time/scan-range + search-query state for the whole app,
// rendered once (see GlobalFilterBar) instead of once per page — see
// MEMORY.md for why this replaced N separate useTimeScanFilter/
// useTableSearch instances.
export function FilterProvider({ children }: { children: ReactNode }) {
  const timeScan = useTimeScanFilter(5);
  const [searchQuery, setSearchQuery] = useState("");
  const [compactTables, setCompactTables] = useState(false);
  const [mapDisplayMode, setMapDisplayMode] = useState<MapDisplayMode>("accumulate");
  const [scanIndexPercent, setScanIndexPercent] = useState(100);
  const [area, setArea] = useState<FocusArea | null>(null);

  // A body-level class (rather than something scoped to GlobalFilterBar's
  // own subtree) so it reaches every <table> on the page regardless of
  // where in the component tree it's rendered — see .compact-tables in
  // index.css.
  useEffect(() => {
    document.body.classList.toggle("compact-tables", compactTables);
  }, [compactTables]);

  return (
    <FilterContext.Provider
      value={{
        ...timeScan,
        searchQuery,
        setSearchQuery,
        compactTables,
        setCompactTables,
        mapDisplayMode,
        setMapDisplayMode,
        scanIndexPercent,
        setScanIndexPercent,
        area,
        setArea,
      }}
    >
      {children}
    </FilterContext.Provider>
  );
}

export function useFilter(): FilterContextValue {
  const ctx = useContext(FilterContext);
  if (!ctx) throw new Error("useFilter must be used within a FilterProvider");
  return ctx;
}
