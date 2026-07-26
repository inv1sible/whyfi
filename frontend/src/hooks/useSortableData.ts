import { useMemo, useState } from "react";

export type SortDirection = "asc" | "desc";

export function useSortableData<T>(
  items: T[],
  initialKey?: keyof T,
  initialDirection: SortDirection = "asc",
) {
  const [sortKey, setSortKey] = useState<keyof T | undefined>(initialKey);
  const [direction, setDirection] = useState<SortDirection>(initialDirection);

  const sorted = useMemo(() => {
    if (!sortKey) return items;
    return [...items].sort((a, b) => {
      const av = a[sortKey];
      const bv = b[sortKey];
      if (av == null && bv == null) return 0;
      if (av == null) return 1;
      if (bv == null) return -1;
      if (typeof av === "string" && typeof bv === "string") {
        return direction === "asc" ? av.localeCompare(bv) : bv.localeCompare(av);
      }
      if (av < bv) return direction === "asc" ? -1 : 1;
      if (av > bv) return direction === "asc" ? 1 : -1;
      return 0;
    });
  }, [items, sortKey, direction]);

  function requestSort(key: keyof T) {
    if (sortKey === key) {
      setDirection((d) => (d === "asc" ? "desc" : "asc"));
    } else {
      setSortKey(key);
      setDirection("asc");
    }
  }

  return { sorted, sortKey, direction, requestSort };
}
