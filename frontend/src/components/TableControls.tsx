import { useFilter } from "../context/FilterContext";
import { TableSearchBox } from "./TableSearchBox";

interface TableControlsProps {
  searchPlaceholder?: string;
  searchValue?: string;
  onSearchChange?: (value: string) => void;
  /** False for tables with no device-identifier column to search — sighting/
   * observation history rows (see SightingTable) are timestamp+coordinates,
   * not identifiers, and inherited a stale identifier-shaped query from
   * whichever list page the shared search box was last typed into. */
  showSearch?: boolean;
}

// The controls that act on the table right below them — rendered inline
// above it rather than in the global filter bar, so it's obvious what they
// affect (the time/scan range stays up in the filter bar, since that one
// governs which data is fetched for the whole page, maps included).
//
// Both are still backed by shared FilterContext state on purpose: "show all
// columns" is a standing preference about how you like tables rendered, not
// a per-table setting, so toggling it here applies everywhere and survives
// navigation. Only one table view is mounted at a time, so there's no
// ambiguity about which one the search box belongs to.
export function TableControls({ searchPlaceholder, searchValue, onSearchChange, showSearch = true }: TableControlsProps) {
  const { searchQuery, setSearchQuery, compactTables, setCompactTables } = useFilter();
  const effectiveSearchValue = searchValue ?? searchQuery;
  const effectiveSearchChange = onSearchChange ?? setSearchQuery;

  return (
    <div className="table-controls">
      {showSearch && (
        <TableSearchBox value={effectiveSearchValue} onChange={effectiveSearchChange} placeholder={searchPlaceholder} />
      )}
      <label className="table-controls-toggle">
        <input type="checkbox" checked={compactTables} onChange={(e) => setCompactTables(e.target.checked)} />
        Show all columns
      </label>
    </div>
  );
}
