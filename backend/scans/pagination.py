from rest_framework.pagination import PageNumberPagination

# Mirrors scans.views.MAX_OBSERVATION_LIMIT — not imported from there to keep
# this module import-safe from config/settings.py (DEFAULT_PAGINATION_CLASS
# is resolved lazily by dotted path, but there's no reason to risk it).
# Both exist for the same reason: a UI convenience ceiling on caller-supplied
# row counts, not semantically load-bearing input worth rejecting a request
# over.
MAX_PAGE_SIZE = 1000


class LimitablePageNumberPagination(PageNumberPagination):
    """The project-wide default pagination, except the page size can be
    raised with `?limit=` — the query param every list page already sends.

    Before this class existed, `?limit=200` on every overview page (WiFi,
    Cellular, BLE, LAN, Manage Scans) was silently ignored: plain
    PageNumberPagination has no notion of `limit`, only `page`, so every one
    of those pages was hard-capped at PAGE_SIZE (50) with no indication —
    a device ranked 51st-or-later by last_seen_at was invisible regardless
    of how wide a time window was selected. Whether the frontend then warns
    about remaining truncation (`count` vs `len(results)` is already in the
    standard paginated envelope) is a UI concern; this class just makes the
    parameter it was already sending actually do something.

    Callers that never pass `limit` see no behaviour change: page size
    stays PAGE_SIZE.
    """

    page_size_query_param = "limit"
    max_page_size = MAX_PAGE_SIZE
