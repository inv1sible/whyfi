import type { SortDirection } from "../hooks/useSortableData";

interface SortableThProps<T> {
  label: string;
  sortKey: keyof T;
  currentKey: keyof T | undefined;
  direction: SortDirection;
  onSort: (key: keyof T) => void;
  // Collapsed below the mobile breakpoint (see .hide-mobile in index.css) —
  // every entity-browsing table converges on the same 3 always-visible
  // columns on a phone: the detail link, a signal-strength-equivalent, and
  // last seen. Matching <td> cells need the same class applied directly.
  hideMobile?: boolean;
}

export function SortableTh<T>({ label, sortKey, currentKey, direction, onSort, hideMobile }: SortableThProps<T>) {
  const isActive = currentKey === sortKey;
  return (
    <th
      onClick={() => onSort(sortKey)}
      className={hideMobile ? "hide-mobile" : undefined}
      style={{ cursor: "pointer", userSelect: "none" }}
    >
      {label}
      <span style={{ opacity: isActive ? 1 : 0.25, marginLeft: "0.3rem" }}>{direction === "asc" ? "▲" : "▼"}</span>
    </th>
  );
}
