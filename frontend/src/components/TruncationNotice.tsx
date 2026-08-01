interface TruncationNoticeProps {
  shown: number;
  total: number;
  /** What's being counted, for the message — "devices", "towers", "scans". */
  noun?: string;
}

/**
 * "Showing 200 of 1178" — the honest counterpart to a paginated list that
 * silently hid everything past the first page.
 *
 * Every overview list page requests a generous but finite `limit` (see
 * scans/pagination.py's LimitablePageNumberPagination), and a wide enough
 * time window can still exceed it — 1178 BLE devices in one week is a real
 * case this was built for. Before this notice existed there was nothing
 * telling the reader that "the device I'm looking for isn't in this list"
 * could mean "it's on page 2", which is indistinguishable from "it isn't
 * here" without this. Mirrors the `truncated` flag the coverage/heatmap
 * endpoints already surface, for the same reason: silently truncating a
 * list is indistinguishable from a complete one.
 */
export function TruncationNotice({ shown, total, noun = "results" }: TruncationNoticeProps) {
  if (shown >= total) return null;
  return (
    <p className="warning-text">
      Showing {shown} of {total} matching {noun} — narrow the time range, scan count, or search above to see the
      rest.
    </p>
  );
}
