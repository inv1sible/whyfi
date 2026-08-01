interface PagerProps {
  page: number;
  pageSize: number;
  total: number;
  onPageChange: (page: number) => void;
  noun?: string;
}

/**
 * Real Prev/Next paging over a server-paginated list, replacing the old
 * "fetch up to 1000 rows + a truncation banner" pattern — that widened the
 * cap but was still just a bigger cap; this instead paginates the actual
 * dataset, so "showing X of Y" is honest at any size, not just up to 1000.
 */
export function Pager({ page, pageSize, total, onPageChange, noun = "results" }: PagerProps) {
  if (total === 0) return null;
  const lastPage = Math.max(1, Math.ceil(total / pageSize));
  const from = (page - 1) * pageSize + 1;
  const to = Math.min(page * pageSize, total);

  return (
    <div className="pager">
      <span>
        Showing {from}–{to} of {total} {noun}
      </span>
      <div className="pager-controls">
        <button onClick={() => onPageChange(page - 1)} disabled={page <= 1}>
          ← Prev
        </button>
        <span>
          Page {page} of {lastPage}
        </span>
        <button onClick={() => onPageChange(page + 1)} disabled={page >= lastPage}>
          Next →
        </button>
      </div>
    </div>
  );
}
