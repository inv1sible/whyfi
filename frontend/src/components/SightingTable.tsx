import type { ReactNode } from "react";
import { useFilter } from "../context/FilterContext";
import { useSortableData } from "../hooks/useSortableData";
import type { SortDirection } from "../hooks/useSortableData";
import { formatCoords, osmLink } from "../reportLinks";
import { filterBySearch } from "../searchFilter";
import { SortableTh } from "./SortableTh";
import { TableControls } from "./TableControls";

/**
 * What every sighting row has, whatever radio it came from: where the phone
 * was, and when. The radio-specific columns come from [SightingColumn]s.
 */
export interface SightingBase {
  /** Observation rows carry a numeric primary key; synthesised rows (the
   * SSID group's per-BSSID readings) use a composite string. Both are valid
   * React keys, so both are allowed rather than forcing a cast at each site. */
  id: string | number;
  latitude: number | null;
  longitude: number | null;
  observedAt: string | null;
}

export interface SightingColumn<T> {
  key: keyof T;
  label: string;
  /** Defaults to the raw value; supply this for colour, links or units. */
  render?: (row: T) => ReactNode;
  hideMobile?: boolean;
}

interface SightingTableProps<T extends SightingBase> {
  rows: T[];
  /** Radio-specific columns, shown before the shared Coordinates/Observed pair. */
  columns: SightingColumn<T>[];
  initialSortKey?: keyof T;
  initialDirection?: SortDirection;
}

/**
 * The "Sighting history" table, shared by every page that has one.
 *
 * Exists because six pages had six hand-rolled versions of the same table
 * with different columns in different orders — one of them omitting the
 * coordinates entirely, none of them sortable. Appending Coordinates and
 * Observed here rather than at each call site is what makes that consistency
 * structural instead of a convention someone has to remember.
 *
 * Sorting and the search box are wired here too. The box was already rendered
 * above these tables but filtered nothing, which is worse than not having one.
 */
export function SightingTable<T extends SightingBase>({
  rows,
  columns,
  initialSortKey = "observedAt" as keyof T,
  initialDirection = "desc",
}: SightingTableProps<T>) {
  const { searchQuery } = useFilter();
  const filtered = filterBySearch(rows, searchQuery);
  const { sorted, sortKey, direction, requestSort } = useSortableData<T>(
    filtered,
    initialSortKey,
    initialDirection,
  );

  // Coordinates sort by latitude: a single ordering has to pick an axis, and
  // north-to-south at least groups a walked route sensibly.
  const latitudeKey = "latitude" as keyof T;
  const observedKey = "observedAt" as keyof T;

  return (
    <>
      <TableControls />
      <table className="data-table">
        <thead>
          <tr>
            {columns.map((column) => (
              <SortableTh
                key={String(column.key)}
                label={column.label}
                sortKey={column.key}
                currentKey={sortKey}
                direction={direction}
                onSort={requestSort}
                hideMobile={column.hideMobile}
              />
            ))}
            <SortableTh
              label="Coordinates"
              sortKey={latitudeKey}
              currentKey={sortKey}
              direction={direction}
              onSort={requestSort}
            />
            <SortableTh
              label="Observed"
              sortKey={observedKey}
              currentKey={sortKey}
              direction={direction}
              onSort={requestSort}
            />
          </tr>
        </thead>
        <tbody>
          {sorted.map((row) => (
            <tr key={row.id}>
              {columns.map((column) => (
                <td key={String(column.key)} className={column.hideMobile ? "hide-mobile" : undefined}>
                  {column.render ? column.render(row) : formatCell(row[column.key])}
                </td>
              ))}
              <td className="mono">
                {/* A real anchor: browser print-to-PDF keeps <a href> as a
                    clickable link annotation, which is the one piece of
                    interactivity printing preserves. */}
                {row.latitude != null && row.longitude != null ? (
                  <a href={osmLink(row.latitude, row.longitude)} target="_blank" rel="noreferrer noopener">
                    {formatCoords(row.latitude, row.longitude)}
                  </a>
                ) : (
                  "—"
                )}
              </td>
              <td>{row.observedAt ? new Date(row.observedAt).toLocaleString() : "—"}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </>
  );
}

function formatCell(value: unknown): ReactNode {
  if (value == null || value === "") return "—";
  return String(value);
}
