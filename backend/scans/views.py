import math
import re
from datetime import timedelta
from urllib.parse import quote

from django.db.models import Count, Prefetch, Q
from django.utils import timezone
from rest_framework import mixins, status, viewsets
from rest_framework.authentication import SessionAuthentication
from rest_framework.decorators import action, api_view, authentication_classes, permission_classes
from rest_framework.permissions import AllowAny, IsAuthenticated
from rest_framework.response import Response

from sensors.authentication import SensorTokenAuthentication

from .geocoding import resolve_missing_addresses
from .models import (
    AccessPoint,
    BLEDevice,
    BLEObservation,
    CellObservation,
    CellTower,
    GeocodedLocation,
    LANDevice,
    LANObservation,
    SatelliteObservation,
    ScanSession,
    WiFiObservation,
)
from .serializers import (
    AccessPointSerializer,
    BLEDeviceSerializer,
    BLEObservationSerializer,
    CellObservationSerializer,
    CellTowerSerializer,
    LANDeviceSerializer,
    LANObservationSerializer,
    SatelliteObservationSerializer,
    ScanSessionIngestSerializer,
    ScanSessionSerializer,
    WiFiObservationSerializer,
)


@api_view(["GET"])
@authentication_classes([])
@permission_classes([AllowAny])
def health(request):
    return Response({"status": "ok"})


def recent_session_ids(n, radio_related_name=None):
    """The N most recent ScanSessions' ids — "last N scans" filtering used
    across several list endpoints (as an alternative to a time-based cutoff,
    which doesn't line up with how often you actually scanned).

    A LAN scan is its own separate action/session (a subnet sweep + port
    scan takes longer than a regular WiFi/cellular/BLE/GNSS pass, see
    LANObservation's docstring), so "last N scans" for LAN data must only
    count sessions that actually contain a LAN observation — otherwise it
    counts the N most recent sessions of *any* type, which are dominated by
    regular passes and often contain zero LAN scans at all, silently
    returning nothing. Pass radio_related_name="lan_observations" for that;
    leave it unset for WiFi/cellular/BLE/satellite, which all share one
    session type and don't have this problem."""
    qs = ScanSession.objects.order_by("-started_at")
    if radio_related_name:
        qs = qs.filter(**{f"{radio_related_name}__isnull": False}).distinct()
    return list(qs.values_list("id", flat=True)[:n])


# Grouped coverage/heatmap payloads are assembled row-by-row in Python, so
# they're bounded to keep one request from reading an unbounded number of
# observations. Hitting the bound is now reported to the caller rather than
# silently changing the answer — see capped_take()/capped_response().
COVERAGE_OBSERVATION_CAP = 20000
HEATMAP_OBSERVATION_CAP = 5000
# One SSID's worth of near-location-filtered readings for the Android Mission
# view — smaller than COVERAGE_OBSERVATION_CAP since this is one network, not
# the whole dataset. See mission_wifi_observations() for why this cap is
# applied *after* the near-radius filter, not before.
MISSION_OBSERVATION_CAP = 2000

# Ceilings for caller-supplied row counts. Generous — these exist to keep a
# hand-edited URL from turning into an unbounded read, not to constrain the UI.
MAX_OBSERVATION_LIMIT = 1000
MAX_GEOCODE_LIMIT = 50


def positive_int(raw, default=None, maximum=None):
    """Parses a caller-supplied positive integer, falling back to `default`
    for anything unusable: missing, blank, non-numeric, zero or negative.

    Every value these parse into ends up as a queryset slice bound, and
    Django raises ValueError("Negative indexing is not supported.") on a
    negative one — so `?session_limit=-1` used to be a plain unhandled 500,
    as did `?limit=abc` on every per-entity observation endpoint. Falling
    back beats 400ing: these are view/window hints from the UI, not
    semantically load-bearing input worth rejecting a whole request over.
    """
    if raw is None or raw == "":
        return default
    try:
        value = int(raw)
    except (TypeError, ValueError):
        return default
    if value <= 0:
        return default
    return min(value, maximum) if maximum is not None else value


def parse_float(raw, default=None):
    """Same tolerant posture as positive_int, for coordinates and radii —
    these are view hints from the map UI, not load-bearing input. Rejects
    NaN/inf, which parse fine as floats and would poison every comparison
    they touch."""
    if raw is None or raw == "":
        return default
    try:
        value = float(raw)
    except (TypeError, ValueError):
        return default
    if value != value or value in (float("inf"), float("-inf")):
        return default
    return value


def parse_area(request):
    """The map's focus circle, as (lat, lng, radius_m), or None.

    All three parameters must be present and usable, and the radius positive —
    a half-specified circle means the caller's intent is unknown, and silently
    filtering by a partial one is worse than not filtering at all.
    """
    lat = parse_float(request.query_params.get("area_lat"))
    lng = parse_float(request.query_params.get("area_lng"))
    radius = parse_float(request.query_params.get("area_radius_m"))
    if lat is None or lng is None or radius is None or radius <= 0:
        return None
    if not (-90 <= lat <= 90) or not (-180 <= lng <= 180):
        return None
    return (lat, lng, radius)


def weighted_centroid(points):
    """A device's estimated position: the signal-weighted centre of everywhere
    it was heard from.

    MUST stay identical to weightedCentroid() in frontend/src/geo.ts, which
    computes the `gradientCenter` dot drawn at the middle of every coverage
    shape. If these two drift, the map shows a device inside the focus circle
    while this filter excludes it — the filter looks broken, and the cause is
    invisible. There is a parity test in tests.py pinning them together.

    Note the 0.1 floor: the weakest reading still counts for something, so a
    few strong readings can't collapse the centre onto themselves.
    """
    weights = [p["weight"] for p in points]
    min_w, max_w = min(weights), max(weights)
    spread = (max_w - min_w) or 1
    w = [0.1 + 0.9 * ((weight - min_w) / spread) for weight in weights]
    total = sum(w)
    return (
        sum(wi * p["lat"] for wi, p in zip(w, points)) / total,
        sum(wi * p["lng"] for wi, p in zip(w, points)) / total,
    )


def haversine_m(lat1, lng1, lat2, lng2):
    """Great-circle distance in metres. Real spherical maths rather than the
    flat-plane approximation used for coverage shapes: a shape spans tens of
    metres, but a focus circle can legitimately be kilometres across, where
    treating degrees as square starts to matter at these latitudes."""
    radius = 6371008.8  # mean Earth radius (IUGG), metres
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = p2 - p1
    dl = math.radians(lng2 - lng1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * radius * math.asin(math.sqrt(a))


def within_area(points, area):
    """Whether a device's estimated position falls inside the focus circle.

    Deliberately judged on the *centroid*, not on whether any single reading
    lands inside: the circle asks "which devices are in this area", and a
    device heard once from across the street belongs to where it actually is,
    not to wherever the phone happened to be standing.

    An empty point list is unplaceable, so it's excluded. In practice this
    can't happen from the coverage endpoints — they drop observations without
    a scan-session position long before grouping — but the guard keeps
    weighted_centroid from dividing by an empty set if a future caller is
    less careful.
    """
    if not points:
        return False
    lat, lng = weighted_centroid(points)
    center_lat, center_lng, radius_m = area
    return haversine_m(center_lat, center_lng, lat, lng) <= radius_m


def apply_area_filter(results, area):
    """Narrows grouped per-device coverage results to the focus circle.

    Runs after grouping and after the time filter, never before:
    weighted_centroid normalises against the device's own min/max signal, so
    computing it from a spatially pre-filtered subset would shift the centre
    and admit devices that don't belong. That also means the circle narrows
    *devices*, not the underlying observation scan — it doesn't relieve
    COVERAGE_OBSERVATION_CAP. Narrowing the time window is what does that.

    A kept device keeps *all* its points, including ones outside the circle:
    the filter selects which devices to report on, and a device's coverage is
    its coverage.
    """
    if area is None:
        return results
    return [entry for entry in results if within_area(entry["points"], area)]


def area_device_ids(request, obs_model, id_field, weight_field, area, session_limit=None):
    """Ids of the devices whose estimated position falls inside the focus
    circle — the device *list* endpoints' equivalent of apply_area_filter.

    Deliberately reuses within_area/weighted_centroid rather than
    approximating, so a list page and the map agree about which devices are in
    the circle. Scoped to the same time window the list itself uses, because
    the centroid depends on which readings are in the set (see
    weighted_centroid) — computing it over all time while the page shows an
    hour would place devices somewhere the map never draws them.

    weight_field=None means "unweighted": every reading counts the same, which
    collapses weighted_centroid to a plain mean. That's the honest treatment
    for LAN devices, which carry no signal strength at all.
    """
    if area is None:
        return None
    since, until = parse_window(request)
    obs_qs = obs_model.objects.select_related("scan_session")
    if since:
        obs_qs = obs_qs.filter(observed_at__gte=since)
    if until:
        obs_qs = obs_qs.filter(observed_at__lte=until)
    if session_limit:
        obs_qs = obs_qs.filter(scan_session_id__in=recent_session_ids(session_limit))
    obs_qs = obs_qs.exclude(scan_session__latitude__isnull=True).exclude(scan_session__longitude__isnull=True)

    groups = {}
    for obs in obs_qs.iterator():
        weight = 0 if weight_field is None else getattr(obs, weight_field)
        if weight is None:
            # A reading with no signal strength can't be weighted; dropping it
            # beats guessing a value that would drag the centroid.
            continue
        groups.setdefault(getattr(obs, id_field), []).append(
            {"lat": obs.scan_session.latitude, "lng": obs.scan_session.longitude, "weight": weight}
        )
    return {key for key, points in groups.items() if within_area(points, area)}


def parse_window(request):
    """The observation time window as raw (since, until) strings, either of
    which may be None. Django parses the ISO strings itself at filter time.

    `until` exists so a report can cover an exact interval — "Tuesday 14:00 to
    16:00" — rather than only ever "the last N minutes up to now", which is all
    the sliders can express and which makes a report impossible to reproduce
    tomorrow.
    """
    return request.query_params.get("since"), request.query_params.get("until")


def apply_active_window(qs, request):
    """Narrows a device-list queryset (AccessPoint/CellTower/BLEDevice/
    LANDevice) to those last seen within [active_since, active_until].

    A device list has no `observed_at` of its own to filter on — only the
    aggregate's `last_seen_at` — hence the separate `active_*` param names
    from `parse_window`'s `since`/`until`, which bound individual
    observations. `active_until` exists for the same reason `until` does:
    without it, "Date range" mode only ever closes the *start* of the
    window, and a device last seen after the requested range still shows up
    in a list that's supposed to be capped at `active_until`.
    """
    active_since = request.query_params.get("active_since")
    if active_since:
        qs = qs.filter(last_seen_at__gte=active_since)
    active_until = request.query_params.get("active_until")
    if active_until:
        qs = qs.filter(last_seen_at__lte=active_until)
    return qs


COLUMN_SEARCH = re.compile(r"^([a-zA-Z0-9_]+)\s*=\s*(.+)$")


def apply_search(qs, request, fields, distinct=False):
    """Server-side counterpart to the frontend's searchFilter.ts::filterBySearch,
    so a search box backed by real pagination can find a match anywhere in the
    table, not just on whichever page happens to be loaded — the exact shape of
    the "missing Venus" bug (see LimitablePageNumberPagination), just one layer
    up: raising the page-size cap doesn't help if the match is on page 23 of a
    search that only ever looks at page 1.

    `fields` maps a display-ish key to the ORM lookup path used to search it.
    The key doesn't need to match the path exactly (mirrors the frontend's
    "any property whose name *contains* the typed key" contract) — e.g.
    {"channel": "latest_channel"} lets `channel=6` find `latest_channel`.

    Free text (no "="): OR-icontains across every field. "key=value": only
    fields whose key contains "key" are searched, and matched with `iexact`
    (exact, not substring) — same fuzzy-vs-precise split as the frontend.

    `distinct=True` for field sets that traverse a to-many relation (e.g.
    ScanSession's search reaches into related observations) — a plain
    per-model field set never needs it.
    """
    raw = request.query_params.get("q", "").strip()
    if not raw:
        return qs
    match = COLUMN_SEARCH.match(raw)
    if match:
        key, value = match.groups()
        key = key.strip().lower()
        value = value.strip()
        paths = [path for name, path in fields.items() if key in name.lower()]
        if not paths:
            return qs.none()
        q = Q()
        for path in paths:
            q |= Q(**{f"{path}__iexact": value})
        qs = qs.filter(q)
    else:
        q = Q()
        for path in fields.values():
            q |= Q(**{f"{path}__icontains": raw})
        qs = qs.filter(q)
    return qs.distinct() if distinct else qs


def parse_session_limit(request):
    return positive_int(request.query_params.get("session_limit"))


def parse_observation_limit(request, default=200):
    return positive_int(request.query_params.get("limit"), default=default, maximum=MAX_OBSERVATION_LIMIT)


def capped_take(queryset, cap):
    """Materializes at most `cap` rows, plus whether more of them matched.

    Fetches one row past the cap rather than running a separate COUNT(*) —
    the caller only needs "was anything left out", and counting the full
    unbounded match set is the expensive half of that question.
    """
    rows = list(queryset[: cap + 1])
    return rows[:cap], len(rows) > cap


def capped_response(results, truncated, cap):
    """Envelope for the grouped coverage/heatmap payloads.

    These used to be bare JSON arrays, silently sliced at `cap`, which made
    an incomplete answer indistinguishable from a complete one — the map
    just quietly left APs out, in a UI whose entire job is showing you what
    was there. `truncated` is what lets the PWA say so (see HeatmapPage /
    SSIDGroupPage) instead of the operator finding out by noticing something
    missing. Don't flatten this back to a bare list.

    Deliberately carries no "devices the area filter couldn't place" count:
    every coverage query already excludes observations whose scan session has
    no latitude/longitude, so such a device never reaches the area filter and
    the number would be structurally zero. Reporting a field that can only
    ever say 0 is worse than omitting it — it reads as a guarantee.
    """
    return {"results": results, "truncated": truncated, "observation_limit": cap}


class AccessPointViewSet(mixins.ListModelMixin, mixins.RetrieveModelMixin, viewsets.GenericViewSet):
    serializer_class = AccessPointSerializer
    lookup_field = "bssid"
    lookup_value_regex = "[^/]+"

    def get_queryset(self):
        qs = AccessPoint.objects.all().order_by("-last_seen_at")
        ssid = self.request.query_params.get("ssid")
        if ssid:
            qs = qs.filter(ssid__icontains=ssid)
        ssid_exact = self.request.query_params.get("ssid_exact")
        if ssid_exact:
            # Precise match for grouping BSSIDs that share one SSID (e.g. a
            # mesh network) — plain `ssid` is intentionally fuzzy/icontains
            # for search-like use, which would also pull in similarly-named
            # unrelated networks.
            qs = qs.filter(ssid=ssid_exact)
        qs = apply_active_window(qs, self.request)
        session_limit = parse_session_limit(self.request)
        if session_limit:
            qs = qs.filter(observations__scan_session_id__in=recent_session_ids(session_limit)).distinct()
        band = self.request.query_params.get("band")
        if band:
            qs = qs.filter(observations__band=band).distinct()
        security = self.request.query_params.get("security")
        if security:
            qs = qs.filter(observations__security_type=security).distinct()
        area = parse_area(self.request)
        if area:
            ids = area_device_ids(self.request, WiFiObservation, "access_point_id", "rssi", area, session_limit)
            qs = qs.filter(pk__in=ids)
        qs = apply_search(qs, self.request, {"bssid": "bssid", "ssid": "ssid", "vendor_oui": "vendor_oui"})
        return qs

    @action(detail=True, methods=["get"], url_path="wifi-observations")
    def wifi_observations(self, request, bssid=None):
        access_point = self.get_object()
        obs = access_point.observations.select_related("scan_session").order_by("-observed_at")
        since, until = parse_window(request)
        if since:
            obs = obs.filter(observed_at__gte=since)
        if until:
            obs = obs.filter(observed_at__lte=until)
        session_limit = parse_session_limit(request)
        if session_limit:
            obs = obs.filter(scan_session_id__in=recent_session_ids(session_limit))
        limit = parse_observation_limit(request)
        return Response(WiFiObservationSerializer(obs[:limit], many=True).data)

    @action(detail=False, methods=["get"])
    def coverage(self, request):
        """Per-AP list of distinct observed locations, each with a weight
        (average RSSI seen from that spot) — feeds the heatmap page's
        coverage-ellipse rendering (frontend/src/geo.ts's
        weightedCoverageEllipse/classifyCoverage). Devices with <3 distinct
        points are still returned (the frontend treats those as "too sparse
        for a shape" and falls back to plain points) rather than filtered
        out here — the frontend also needs the sub-3-point case to decide
        that, not just silence."""
        since, until = parse_window(request)
        ssid_exact = request.query_params.get("ssid_exact")
        qs = (
            WiFiObservation.objects.select_related("access_point", "scan_session")
            .exclude(scan_session__latitude__isnull=True)
            .exclude(scan_session__longitude__isnull=True)
        )
        if since:
            qs = qs.filter(observed_at__gte=since)
        if until:
            qs = qs.filter(observed_at__lte=until)
        session_limit = parse_session_limit(request)
        if session_limit:
            qs = qs.filter(scan_session_id__in=recent_session_ids(session_limit))
        if ssid_exact:
            # Powers the SSID-group page — one coverage polygon per BSSID
            # sharing an SSID (e.g. a mesh network), not the whole dataset.
            qs = qs.filter(access_point__ssid=ssid_exact)

        observations, truncated = capped_take(qs, COVERAGE_OBSERVATION_CAP)
        by_ap = {}
        for obs in observations:
            entry = by_ap.setdefault(
                obs.access_point_id,
                {
                    "bssid": obs.access_point.bssid,
                    "ssid": obs.access_point.ssid,
                    "detail_path": f"/networks/{quote(obs.access_point_id, safe='')}",
                    "points": {},
                },
            )
            key = (round(obs.scan_session.latitude, 5), round(obs.scan_session.longitude, 5))
            entry["points"].setdefault(key, []).append(
                {
                    "rssi": obs.rssi,
                    "scan_session_id": obs.scan_session_id,
                    "accuracy": obs.scan_session.location_accuracy_meters,
                    "observed_at": obs.observed_at,
                }
            )

        results = [
            {
                "bssid": v["bssid"],
                "ssid": v["ssid"],
                "detail_path": v["detail_path"],
                "points": [
                    {
                        "lat": lat,
                        "lng": lng,
                        "weight": sum(p["rssi"] for p in samples) / len(samples),
                        "observed_at": samples[0]["observed_at"],
                        # One representative reading's scan/accuracy per
                        # bucket (buckets are rounded to ~1m, so this is
                        # almost always exactly one scan anyway) — lets the
                        # frontend's "show device location pins" toggle mark
                        # where the phone stood, same as the per-entity
                        # detail pages.
                        "scan_session_id": samples[0]["scan_session_id"],
                        "accuracy_meters": samples[0]["accuracy"],
                    }
                    for (lat, lng), samples in v["points"].items()
                ],
            }
            for v in by_ap.values()
        ]
        results = apply_area_filter(results, parse_area(request))
        return Response(capped_response(results, truncated, COVERAGE_OBSERVATION_CAP))


class ScanSessionViewSet(
    mixins.ListModelMixin, mixins.RetrieveModelMixin, mixins.CreateModelMixin, viewsets.GenericViewSet
):
    queryset = ScanSession.objects.all()
    serializer_class = ScanSessionSerializer

    def initialize_request(self, request, *args, **kwargs):
        # self.action isn't set yet at the point get_authenticators() runs —
        # APIView.initialize_request() (called via super() below) builds the
        # Request object (and, as part of that, calls get_authenticators())
        # *before* ViewSetMixin.initialize_request() goes on to set
        # self.action from the resolved method. self.action_map, though, is
        # set earlier still (in ViewSetMixin.as_view(), before dispatch()
        # even runs) and already maps this request's HTTP method to the
        # action name DRF resolved from the URL — same information,
        # available sooner. Don't go back to branching on the raw HTTP verb
        # alone (`method == "POST"`): that broke the instant a second
        # POST-based custom action (resolve-addresses) was added, since
        # every POST got treated as "create".
        self._resolved_action = self.action_map.get(request.method.lower())
        return super().initialize_request(request, *args, **kwargs)

    def get_authenticators(self):
        # Ingest (create) is machine-to-machine via a per-device sensor
        # token; every other action (including the bulk-delete/
        # resolve-addresses actions below — human PWA housekeeping actions,
        # not something a sensor should ever call) uses the same admin
        # session used for /admin/. See MEMORY.md.
        if getattr(self, "_resolved_action", None) == "create":
            return [SensorTokenAuthentication()]
        return [SessionAuthentication()]

    def get_permissions(self):
        return [IsAuthenticated()]

    def get_queryset(self):
        qs = super().get_queryset()
        sensor_id = self.request.query_params.get("sensor")
        if sensor_id:
            qs = qs.filter(sensor_id=sensor_id)
        # Annotated once here (list/retrieve) rather than one .count() query
        # per radio type per row — see ScanSessionSerializer._count().
        # Aggregate annotate() silently drops the model's default ordering
        # (Meta.ordering doesn't survive a GROUP BY), so it has to be
        # re-applied explicitly afterwards or pagination becomes
        # nondeterministic (DRF warns "UnorderedObjectListWarning").
        qs = qs.annotate(
            wifi_count_annotated=Count("wifi_observations", distinct=True),
            cell_count_annotated=Count("cell_observations", distinct=True),
            ble_count_annotated=Count("ble_observations", distinct=True),
            satellite_count_annotated=Count("satellite_observations", distinct=True),
            lan_count_annotated=Count("lan_observations", distinct=True),
        ).order_by("-started_at")
        # Feeds ScanSessionSerializer.get_identifiers_summary() without a
        # query per session per radio type.
        qs = qs.prefetch_related(
            Prefetch("wifi_observations", queryset=WiFiObservation.objects.select_related("access_point")),
            "ble_observations",
            "lan_observations",
        )
        # Reaches into the same related fields ScanSessionSerializer.
        # get_identifiers_summary() reads (plus sensor name and the carrier
        # seen on this session) — so the box that shows "SSID, device name,
        # hostname…" per row can actually find a match by one, not just
        # sensor_name/address.
        #
        # Run against a fresh, unannotated queryset and narrow by id rather
        # than filtering `qs` directly: `qs` already carries Count(...)
        # annotations + an implicit GROUP BY, and every search path here
        # crosses a *different* to-many relation than the one each Count
        # aggregates — filtering it in place would restrict the joined rows
        # each Count sees too, silently undercounting wifi_count/ble_count/
        # etc. for a session that only matched via one radio type.
        if self.request.query_params.get("q", "").strip():
            match_ids = apply_search(
                ScanSession.objects.only("pk"),
                self.request,
                {
                    "sensor_name": "sensor__name",
                    "ssid": "wifi_observations__access_point__ssid",
                    "bssid": "wifi_observations__access_point__bssid",
                    "device_name": "ble_observations__device_name",
                    "ble_mac": "ble_observations__ble_mac",
                    "hostname": "lan_observations__hostname",
                    "ip_address": "lan_observations__ip_address",
                    "carrier_name": "cell_observations__carrier_name",
                },
                distinct=True,
            ).values_list("pk", flat=True)
            qs = qs.filter(pk__in=list(match_ids))
        return qs

    def get_serializer_context(self):
        context = super().get_serializer_context()
        # One query for the whole (typically small) geocode cache table,
        # rather than one per row — see ScanSessionSerializer.get_resolved_address.
        context["geocode_cache"] = {
            (loc.lat_rounded, loc.lng_rounded): loc.address for loc in GeocodedLocation.objects.all()
        }
        return context

    def create(self, request, *args, **kwargs):
        serializer = ScanSessionIngestSerializer(data=request.data, context={"sensor": request.user})
        serializer.is_valid(raise_exception=True)
        session = serializer.save()
        return Response(ScanSessionSerializer(session).data, status=status.HTTP_201_CREATED)

    @action(detail=False, methods=["post"], url_path="resolve-addresses")
    def resolve_addresses(self, request):
        """Reverse-geocodes up to `limit` distinct not-yet-cached scan
        locations (see scans/geocoding.py) — an explicit, human-triggered
        action rather than something that runs automatically, since it
        makes live calls to a third-party service."""
        # Bounded and type-safe: a bare int() here 500'd on any non-numeric
        # body value, and each resolution sleeps ~1.1s to respect Nominatim's
        # rate limit, so an unbounded count would tie up one of gunicorn's
        # three sync workers for as long as the caller asked for.
        limit = positive_int(request.data.get("limit"), default=20, maximum=MAX_GEOCODE_LIMIT)
        sessions = self.filter_queryset(self.get_queryset()).exclude(latitude__isnull=True).exclude(
            longitude__isnull=True
        )
        resolved = resolve_missing_addresses(sessions, limit=limit)
        return Response({"resolved": resolved})

    @action(detail=False, methods=["delete"], url_path="bulk-delete")
    def bulk_delete(self, request):
        """Deletes a batch of scan sessions by id, cascading to every radio
        observation FK'd to them (see on_delete=CASCADE on each Observation
        model). Uses DELETE rather than POST so it doesn't trip the
        create-vs-everything-else branch in get_authenticators() above,
        which would otherwise route it to sensor-token auth instead of the
        human PWA session — this is a human-triggered housekeeping action,
        not something a sensor should ever call."""
        ids = request.data.get("ids")
        if not isinstance(ids, list) or not ids:
            return Response({"detail": "ids must be a non-empty list"}, status=status.HTTP_400_BAD_REQUEST)
        deleted_count, _ = ScanSession.objects.filter(id__in=ids).delete()
        return Response({"deleted": deleted_count})

    @action(detail=True, methods=["get"], url_path="wifi-observations")
    def wifi_observations(self, request, pk=None):
        session = self.get_object()
        return Response(WiFiObservationSerializer(session.wifi_observations.order_by("-observed_at"), many=True).data)

    @action(detail=True, methods=["get"], url_path="cell-observations")
    def cell_observations(self, request, pk=None):
        session = self.get_object()
        return Response(CellObservationSerializer(session.cell_observations.order_by("-observed_at"), many=True).data)

    @action(detail=True, methods=["get"], url_path="ble-observations")
    def ble_observations(self, request, pk=None):
        session = self.get_object()
        return Response(BLEObservationSerializer(session.ble_observations.order_by("-observed_at"), many=True).data)

    @action(detail=True, methods=["get"], url_path="satellite-observations")
    def satellite_observations(self, request, pk=None):
        session = self.get_object()
        return Response(
            SatelliteObservationSerializer(session.satellite_observations.order_by("-observed_at"), many=True).data
        )

    @action(detail=True, methods=["get"], url_path="lan-observations")
    def lan_observations(self, request, pk=None):
        session = self.get_object()
        return Response(LANObservationSerializer(session.lan_observations.order_by("-observed_at"), many=True).data)


class CellTowerViewSet(mixins.ListModelMixin, mixins.RetrieveModelMixin, viewsets.GenericViewSet):
    serializer_class = CellTowerSerializer
    lookup_field = "tower_key"
    lookup_value_regex = "[^/]+"

    def get_queryset(self):
        qs = CellTower.objects.all().order_by("-last_seen_at")
        qs = apply_active_window(qs, self.request)
        session_limit = parse_session_limit(self.request)
        if session_limit:
            qs = qs.filter(observations__scan_session_id__in=recent_session_ids(session_limit)).distinct()
        area = parse_area(self.request)
        if area:
            ids = area_device_ids(self.request, CellObservation, "cell_tower_id", "signal_dbm", area, session_limit)
            qs = qs.filter(pk__in=ids)
        qs = apply_search(
            qs,
            self.request,
            {
                "tower_key": "tower_key",
                "mcc": "mcc",
                "mnc": "mnc",
                "tac_or_lac": "tac_or_lac",
                "cell_id": "cell_id",
                "carrier_name": "carrier_name",
                "radio_type": "radio_type",
            },
        )
        return qs

    @action(detail=True, methods=["get"], url_path="cell-observations")
    def cell_observations(self, request, tower_key=None):
        tower = self.get_object()
        obs = tower.observations.select_related("scan_session").order_by("-observed_at")
        since, until = parse_window(request)
        if since:
            obs = obs.filter(observed_at__gte=since)
        if until:
            obs = obs.filter(observed_at__lte=until)
        session_limit = parse_session_limit(request)
        if session_limit:
            obs = obs.filter(scan_session_id__in=recent_session_ids(session_limit))
        limit = parse_observation_limit(request)
        return Response(CellObservationSerializer(obs[:limit], many=True).data)

    @action(detail=False, methods=["get"])
    def coverage(self, request):
        """Per-tower list of distinct observed locations with weight
        (average signal_dbm) — same shape/purpose as AccessPointViewSet's
        coverage action. Cell towers have no distance cap on the frontend
        (a sector legitimately covers km-scale areas), so every tower with
        >=3 points ends up drawn as a shape regardless of spread."""
        since, until = parse_window(request)
        qs = (
            CellObservation.objects.select_related("cell_tower", "scan_session")
            .exclude(scan_session__latitude__isnull=True)
            .exclude(scan_session__longitude__isnull=True)
            .exclude(cell_tower__isnull=True)
        )
        if since:
            qs = qs.filter(observed_at__gte=since)
        if until:
            qs = qs.filter(observed_at__lte=until)
        session_limit = parse_session_limit(request)
        if session_limit:
            qs = qs.filter(scan_session_id__in=recent_session_ids(session_limit))

        observations, truncated = capped_take(qs, COVERAGE_OBSERVATION_CAP)
        by_tower = {}
        for obs in observations:
            entry = by_tower.setdefault(
                obs.cell_tower_id,
                {
                    "key": obs.cell_tower_id,
                    "label": obs.cell_tower.carrier_name or obs.cell_tower_id,
                    "detail_path": f"/cellular/{quote(obs.cell_tower_id, safe='')}",
                    "points": {},
                },
            )
            key = (round(obs.scan_session.latitude, 5), round(obs.scan_session.longitude, 5))
            entry["points"].setdefault(key, []).append(
                {
                    "signal": obs.signal_dbm or 0,
                    "scan_session_id": obs.scan_session_id,
                    "accuracy": obs.scan_session.location_accuracy_meters,
                    "observed_at": obs.observed_at,
                }
            )

        results = [
            {
                "key": v["key"],
                "label": v["label"],
                "detail_path": v["detail_path"],
                "points": [
                    {
                        "lat": lat,
                        "lng": lng,
                        "weight": sum(p["signal"] for p in samples) / len(samples),
                        "scan_session_id": samples[0]["scan_session_id"],
                        "accuracy_meters": samples[0]["accuracy"],
                        "observed_at": samples[0]["observed_at"],
                    }
                    for (lat, lng), samples in v["points"].items()
                ],
            }
            for v in by_tower.values()
        ]
        results = apply_area_filter(results, parse_area(request))
        return Response(capped_response(results, truncated, COVERAGE_OBSERVATION_CAP))


class CellObservationViewSet(mixins.ListModelMixin, viewsets.GenericViewSet):
    serializer_class = CellObservationSerializer

    def get_queryset(self):
        qs = CellObservation.objects.all().order_by("-observed_at")
        mcc = self.request.query_params.get("mcc")
        mnc = self.request.query_params.get("mnc")
        since, until = parse_window(self.request)
        # Neighbor-cell readings vastly outnumber serving-cell ones and
        # rarely carry useful signal — filtered server-side (not just
        # client-side) so a fixed page size isn't mostly wasted on rows the
        # UI hides by default.
        if self.request.query_params.get("serving_only") == "true":
            qs = qs.filter(is_serving_cell=True)
        if mcc:
            qs = qs.filter(mcc=mcc)
        if mnc:
            qs = qs.filter(mnc=mnc)
        if since:
            qs = qs.filter(observed_at__gte=since)
        if until:
            qs = qs.filter(observed_at__lte=until)
        return qs


class BLEObservationViewSet(mixins.ListModelMixin, viewsets.GenericViewSet):
    serializer_class = BLEObservationSerializer

    def get_queryset(self):
        qs = BLEObservation.objects.all().order_by("-observed_at")
        device_type = self.request.query_params.get("device_type")
        since, until = parse_window(self.request)
        identifier = self.request.query_params.get("identifier")
        if device_type:
            qs = qs.filter(device_type_guess=device_type)
        if since:
            qs = qs.filter(observed_at__gte=since)
        if until:
            qs = qs.filter(observed_at__lte=until)
        if identifier:
            qs = qs.filter(Q(ble_mac=identifier) | Q(stable_identifier=identifier))
        session_limit = parse_session_limit(self.request)
        if session_limit:
            qs = qs.filter(scan_session_id__in=recent_session_ids(session_limit))
        return qs

    @action(detail=False, methods=["get"])
    def coverage(self, request):
        """Per-device list of distinct observed locations with weight
        (average RSSI) — same shape/purpose as AccessPointViewSet's coverage
        action. Groups directly by identifier here (rather than joining
        through BLEDevice) since that's all this needs and avoids a join;
        BLEDevice.device_key uses the identical `ble_mac or stable_identifier`
        precedence, so the grouping is consistent either way.

        Also reports the most-recently-observed device_type_guess per
        device — the frontend treats HEADPHONES/WEARABLE as inherently
        mobile (worn on a person) regardless of measured sighting spread,
        so it needs this to make that call before even looking at the
        points."""
        since, until = parse_window(request)
        qs = BLEObservation.objects.select_related("scan_session").exclude(
            scan_session__latitude__isnull=True
        ).exclude(scan_session__longitude__isnull=True).order_by("-observed_at")
        if since:
            qs = qs.filter(observed_at__gte=since)
        if until:
            qs = qs.filter(observed_at__lte=until)
        session_limit = parse_session_limit(request)
        if session_limit:
            qs = qs.filter(scan_session_id__in=recent_session_ids(session_limit))

        observations, truncated = capped_take(qs, COVERAGE_OBSERVATION_CAP)
        by_device = {}
        for obs in observations:
            identifier = obs.ble_mac or obs.stable_identifier
            if not identifier:
                continue
            entry = by_device.setdefault(
                identifier,
                {
                    "key": identifier,
                    "label": obs.device_name or identifier,
                    "detail_path": f"/ble-devices/{quote(identifier, safe='')}",
                    # First entry encountered per device is the latest
                    # (queryset is ordered -observed_at).
                    "device_type_guess": obs.device_type_guess,
                    "points": {},
                },
            )
            key = (round(obs.scan_session.latitude, 5), round(obs.scan_session.longitude, 5))
            entry["points"].setdefault(key, []).append(
                {
                    "rssi": obs.rssi,
                    "scan_session_id": obs.scan_session_id,
                    "accuracy": obs.scan_session.location_accuracy_meters,
                    "observed_at": obs.observed_at,
                }
            )

        results = [
            {
                "key": v["key"],
                "label": v["label"],
                "detail_path": v["detail_path"],
                "device_type_guess": v["device_type_guess"],
                "points": [
                    {
                        "lat": lat,
                        "lng": lng,
                        "weight": sum(p["rssi"] for p in samples) / len(samples),
                        "scan_session_id": samples[0]["scan_session_id"],
                        "accuracy_meters": samples[0]["accuracy"],
                        "observed_at": samples[0]["observed_at"],
                    }
                    for (lat, lng), samples in v["points"].items()
                ],
            }
            for v in by_device.values()
        ]
        results = apply_area_filter(results, parse_area(request))
        return Response(capped_response(results, truncated, COVERAGE_OBSERVATION_CAP))


class BLEDeviceViewSet(mixins.ListModelMixin, mixins.RetrieveModelMixin, viewsets.GenericViewSet):
    serializer_class = BLEDeviceSerializer
    lookup_field = "device_key"
    lookup_value_regex = "[^/]+"

    def get_queryset(self):
        qs = BLEDevice.objects.all().order_by("-last_seen_at")
        device_type = self.request.query_params.get("device_type")
        if device_type:
            qs = qs.filter(device_type_guess=device_type)
        qs = apply_active_window(qs, self.request)
        session_limit = parse_session_limit(self.request)
        if session_limit:
            qs = qs.filter(observations__scan_session_id__in=recent_session_ids(session_limit)).distinct()
        area = parse_area(self.request)
        if area:
            ids = area_device_ids(self.request, BLEObservation, "ble_device_id", "rssi", area, session_limit)
            qs = qs.filter(pk__in=ids)
        qs = apply_search(
            qs,
            self.request,
            {"device_key": "device_key", "device_name": "device_name", "device_type": "device_type_guess"},
        )
        return qs

    @action(detail=True, methods=["get"], url_path="ble-observations")
    def ble_observations(self, request, device_key=None):
        device = self.get_object()
        obs = device.observations.select_related("scan_session").order_by("-observed_at")
        since, until = parse_window(request)
        if since:
            obs = obs.filter(observed_at__gte=since)
        if until:
            obs = obs.filter(observed_at__lte=until)
        session_limit = parse_session_limit(request)
        if session_limit:
            obs = obs.filter(scan_session_id__in=recent_session_ids(session_limit))
        limit = parse_observation_limit(request)
        return Response(BLEObservationSerializer(obs[:limit], many=True).data)


class SatelliteObservationViewSet(mixins.ListModelMixin, viewsets.GenericViewSet):
    serializer_class = SatelliteObservationSerializer
    queryset = SatelliteObservation.objects.all().order_by("-observed_at")


class LANObservationViewSet(mixins.ListModelMixin, mixins.RetrieveModelMixin, viewsets.GenericViewSet):
    serializer_class = LANObservationSerializer

    def get_queryset(self):
        qs = LANObservation.objects.all().order_by("-observed_at")
        since, until = parse_window(self.request)
        if since:
            qs = qs.filter(observed_at__gte=since)
        if until:
            qs = qs.filter(observed_at__lte=until)
        session_limit = parse_session_limit(self.request)
        if session_limit:
            qs = qs.filter(scan_session_id__in=recent_session_ids(session_limit, radio_related_name="lan_observations"))
        return qs


class LANDeviceViewSet(mixins.ListModelMixin, mixins.RetrieveModelMixin, viewsets.GenericViewSet):
    serializer_class = LANDeviceSerializer
    lookup_field = "ip_address"
    lookup_value_regex = "[^/]+"

    def get_queryset(self):
        qs = LANDevice.objects.all().order_by("-last_seen_at")
        qs = apply_active_window(qs, self.request)
        session_limit = parse_session_limit(self.request)
        if session_limit:
            qs = qs.filter(
                observations__scan_session_id__in=recent_session_ids(session_limit, radio_related_name="lan_observations")
            ).distinct()
        area = parse_area(self.request)
        if area:
            # weight_field=None: a LAN observation carries no signal strength
            # (it's a subnet sweep, not a radio reading), so every sighting
            # counts equally and the estimate is a plain mean of where the
            # phone stood when it saw the device.
            ids = area_device_ids(self.request, LANObservation, "lan_device_id", None, area, session_limit)
            qs = qs.filter(pk__in=ids)
        qs = apply_search(
            qs,
            self.request,
            {
                "ip_address": "ip_address",
                "mac_address": "mac_address",
                "hostname": "hostname",
                "vendor_oui": "vendor_oui",
                "device_type": "device_type_guess",
            },
        )
        return qs

    def list(self, request, *args, **kwargs):
        queryset = self.filter_queryset(self.get_queryset())
        page = self.paginate_queryset(queryset)
        devices = page if page is not None else list(queryset)

        # is_online is unconditional — "was this device in the single most
        # recent LAN scan" regardless of whatever time/scan window the
        # request is otherwise filtering by, so it always answers "is it
        # here right now" rather than "was it here within my current view".
        latest_ids = recent_session_ids(1, radio_related_name="lan_observations")
        latest_id = latest_ids[0] if latest_ids else None
        online_device_ids = set()
        if latest_id and devices:
            online_device_ids = set(
                LANObservation.objects.filter(
                    lan_device_id__in=[d.pk for d in devices], scan_session_id=latest_id
                ).values_list("lan_device_id", flat=True)
            )
        for device in devices:
            device._is_online = device.pk in online_device_ids

        # New/left, in contrast, are only meaningful with >=2 LAN scans in
        # the *requested* window — otherwise there's nothing to compare
        # against.
        session_limit = parse_session_limit(request)
        if session_limit and session_limit >= 2 and devices:
            window_session_ids = recent_session_ids(session_limit, radio_related_name="lan_observations")
            window_latest_id = window_session_ids[0] if window_session_ids else None
            membership = {}
            for device_id, session_id in LANObservation.objects.filter(
                lan_device_id__in=[d.pk for d in devices], scan_session_id__in=window_session_ids
            ).values_list("lan_device_id", "scan_session_id"):
                membership.setdefault(device_id, set()).add(session_id)
            for device in devices:
                sessions_seen = membership.get(device.pk, set())
                device._is_new_in_window = bool(sessions_seen) and sessions_seen == {window_latest_id}
                device._is_left_in_window = bool(sessions_seen) and window_latest_id not in sessions_seen
        else:
            for device in devices:
                device._is_new_in_window = False
                device._is_left_in_window = False

        serializer = self.get_serializer(devices, many=True)
        if page is not None:
            return self.get_paginated_response(serializer.data)
        return Response(serializer.data)

    @action(detail=True, methods=["get"], url_path="lan-observations")
    def lan_observations(self, request, ip_address=None):
        device = self.get_object()
        obs = device.observations.select_related("scan_session").order_by("-observed_at")
        since, until = parse_window(request)
        if since:
            obs = obs.filter(observed_at__gte=since)
        if until:
            obs = obs.filter(observed_at__lte=until)
        session_limit = parse_session_limit(request)
        if session_limit:
            # LAN scans are sparser than WiFi/cell/BLE — a plain
            # recent_session_ids(n) window (most recent N sessions overall)
            # regularly contains zero LAN scans. See MEMORY.md.
            obs = obs.filter(
                scan_session_id__in=recent_session_ids(session_limit, radio_related_name="lan_observations")
            )
        limit = parse_observation_limit(request)
        return Response(LANObservationSerializer(obs[:limit], many=True).data)


@api_view(["GET"])
def channel_congestion(request):
    band = request.query_params.get("band", "2.4GHz")
    since, until = parse_window(request)
    session_limit = parse_session_limit(request)

    qs = WiFiObservation.objects.filter(band=band)
    if session_limit:
        qs = qs.filter(scan_session_id__in=recent_session_ids(session_limit))
    elif since or until:
        if since:
            qs = qs.filter(observed_at__gte=since)
        if until:
            qs = qs.filter(observed_at__lte=until)
    else:
        # A single scan session only sees whatever's nearby at that one
        # moment, which under-represents "what channels are actually in
        # use around here" — default to a rolling recent window instead.
        qs = qs.filter(observed_at__gte=timezone.now() - timedelta(hours=24))

    counts = (
        qs.values("channel")
        .annotate(ap_count=Count("access_point", distinct=True))
        .order_by("channel")
    )
    return Response(list(counts))


@api_view(["GET"])
def heatmap(request):
    source = request.query_params.get("source", "wifi")
    since, until = parse_window(request)
    session_limit = parse_session_limit(request)
    bounds = request.query_params.get("bounds")

    if source == "wifi":
        qs = WiFiObservation.objects.select_related("scan_session", "access_point")
        weight_field = "rssi"
    elif source == "cellular":
        qs = CellObservation.objects.select_related("scan_session", "cell_tower")
        weight_field = "signal_dbm"
    elif source == "ble":
        qs = BLEObservation.objects.select_related("scan_session")
        weight_field = "rssi"
    else:
        return Response({"detail": "invalid source, expected wifi|cellular|ble"}, status=400)

    # session_limit ("last scan" = 1, "last N scans" = N) takes precedence
    # over a time cutoff — it's a more direct answer to "what does the most
    # recent handful of passes look like" than picking a duration and
    # hoping it lines up with how often you actually scanned.
    if session_limit:
        qs = qs.filter(scan_session_id__in=recent_session_ids(session_limit))
    else:
        if since:
            qs = qs.filter(observed_at__gte=since)
        if until:
            qs = qs.filter(observed_at__lte=until)

    if bounds:
        try:
            sw_lat, sw_lng, ne_lat, ne_lng = (float(v) for v in bounds.split(","))
            qs = qs.filter(
                scan_session__latitude__gte=sw_lat,
                scan_session__latitude__lte=ne_lat,
                scan_session__longitude__gte=sw_lng,
                scan_session__longitude__lte=ne_lng,
            )
        except (ValueError, TypeError):
            pass

    qs = qs.exclude(scan_session__latitude__isnull=True).exclude(scan_session__longitude__isnull=True)

    # Bucket to ~11m grid cells so the response stays small regardless of
    # how many raw observations exist in the requested window. Each bucket
    # also tracks which sources (APs/towers/BLE devices) contributed to it,
    # so the map can show "what's actually here" with a link through to the
    # detail page — not just an anonymous intensity value.
    observations, truncated = capped_take(qs, HEATMAP_OBSERVATION_CAP)
    buckets = {}
    for obs in observations:
        key = (round(obs.scan_session.latitude, 4), round(obs.scan_session.longitude, 4))
        value = getattr(obs, weight_field) or 0
        bucket = buckets.setdefault(key, {"sum": 0, "count": 0, "sources": {}})
        bucket["sum"] += value
        bucket["count"] += 1

        source_key, source_label, source_path = None, None, None
        if source == "wifi":
            source_key = obs.access_point_id
            source_label = obs.access_point.ssid or obs.access_point.bssid
            source_path = f"/networks/{quote(obs.access_point_id, safe='')}"
        elif source == "cellular" and obs.cell_tower_id:
            source_key = obs.cell_tower_id
            source_label = obs.cell_tower.carrier_name or obs.cell_tower_id
            source_path = f"/cellular/{quote(obs.cell_tower_id, safe='')}"
        elif source == "ble":
            source_key = obs.ble_mac or obs.stable_identifier
            if source_key:
                source_label = obs.device_name or source_key
                source_path = f"/ble-devices/{quote(source_key, safe='')}"

        if source_key:
            entry = bucket["sources"].setdefault(source_key, {"label": source_label, "path": source_path, "count": 0})
            entry["count"] += 1

    points = []
    for (lat, lng), agg in buckets.items():
        point = {"lat": lat, "lng": lng, "weight": agg["sum"] / agg["count"]}
        if agg["sources"]:
            top_key = max(agg["sources"], key=lambda k: agg["sources"][k]["count"])
            top = agg["sources"][top_key]
            point["source"] = {
                "label": top["label"],
                "detail_path": top["path"],
                "extra_count": len(agg["sources"]) - 1,
            }
        points.append(point)

    return Response(capped_response(points, truncated, HEATMAP_OBSERVATION_CAP))


def parse_required_near(request):
    """near_lat/near_lng/near_radius_m — required, not the tolerant
    all-or-nothing-silent-drop posture of parse_area(). Shared by the three
    mission_*_observations views below: for each of them, this triple is the
    entire reason the endpoint exists (excluding a favorited device/network's
    sightings recorded somewhere else entirely — a travel router, a phone
    that changed owners), so a missing/malformed one is a 400, not a
    silently-unfiltered response.

    Returns `((lat, lng, radius), None)` on success or `(None, error_response)`
    on failure — callers `return error` immediately when it's not None.
    """
    near_lat = parse_float(request.query_params.get("near_lat"))
    near_lng = parse_float(request.query_params.get("near_lng"))
    near_radius_m = parse_float(request.query_params.get("near_radius_m"))
    if near_lat is None or not (-90 <= near_lat <= 90):
        return None, Response(
            {"detail": "near_lat is required and must be a valid latitude"}, status=status.HTTP_400_BAD_REQUEST
        )
    if near_lng is None or not (-180 <= near_lng <= 180):
        return None, Response(
            {"detail": "near_lng is required and must be a valid longitude"}, status=status.HTTP_400_BAD_REQUEST
        )
    if near_radius_m is None or near_radius_m <= 0:
        return None, Response(
            {"detail": "near_radius_m is required and must be a positive number"}, status=status.HTTP_400_BAD_REQUEST
        )
    return (near_lat, near_lng, near_radius_m), None


@api_view(["GET"])
@authentication_classes([SensorTokenAuthentication])
@permission_classes([IsAuthenticated])
def mission_wifi_observations(request):
    """All recent observations of one SSID, restricted to those recorded near
    the caller's current position — feeds the Android app's Mission view,
    where a favorited SSID's estimated access-point position is drawn as a
    gradient cone (see mission/Geo.kt on the Android side, itself a port of
    frontend/src/geo.ts).

    Sensor-token-only, not session auth — the mirror image of every other
    read endpoint in this file (AccessPointViewSet's list/coverage/
    wifi_observations are all session-only). This is a phone-triggered
    machine read, not a PWA/human one, so it gets its own dedicated endpoint
    rather than an auth change to AccessPointViewSet.

    near_lat/near_lng/near_radius_m exist specifically so a "travel router" —
    an SSID seen from many unrelated physical locations over time (a mobile
    hotspot, a router that moved) — doesn't corrupt the estimate: only
    observations near where the phone is standing right now are returned.
    Unlike parse_area()'s tolerant silent-drop posture (fine for a map UI
    hint), these three params are the entire reason this endpoint exists, so
    a missing/malformed one is a 400, not a silently-unfiltered response.
    """
    ssid_exact = request.query_params.get("ssid_exact")
    if not ssid_exact:
        return Response({"detail": "ssid_exact is required"}, status=status.HTTP_400_BAD_REQUEST)
    near, error = parse_required_near(request)
    if error:
        return error
    near_lat, near_lng, near_radius_m = near

    since, until = parse_window(request)
    qs = (
        WiFiObservation.objects.select_related("access_point", "scan_session")
        .filter(access_point__ssid=ssid_exact)
        .exclude(scan_session__latitude__isnull=True)
        .exclude(scan_session__longitude__isnull=True)
    )
    if since:
        qs = qs.filter(observed_at__gte=since)
    if until:
        qs = qs.filter(observed_at__lte=until)
    session_limit = parse_session_limit(request)
    if session_limit:
        qs = qs.filter(scan_session_id__in=recent_session_ids(session_limit))

    # The near-radius filter MUST see every window-filtered observation
    # before any cap is applied — iterate the full queryset first, the same
    # iterate-before-deciding posture area_device_ids() already uses. Capping
    # first (as coverage()'s capped_take() does, which doesn't need this
    # distinction) could let a travel router's many far-away sightings
    # exhaust the cap before a single legitimate nearby reading is ever
    # considered, silently defeating the whole point of this endpoint.
    near_points = []
    for obs in qs.iterator():
        lat, lng = obs.scan_session.latitude, obs.scan_session.longitude
        if haversine_m(near_lat, near_lng, lat, lng) > near_radius_m:
            continue
        near_points.append(
            {
                "bssid": obs.access_point.bssid,
                "lat": lat,
                "lng": lng,
                "weight": obs.rssi,
                "observed_at": obs.observed_at,
                "scan_session_id": obs.scan_session_id,
                "accuracy_meters": obs.scan_session.location_accuracy_meters,
            }
        )

    truncated = len(near_points) > MISSION_OBSERVATION_CAP
    return Response(
        {
            "ssid": ssid_exact,
            "points": near_points[:MISSION_OBSERVATION_CAP],
            "truncated": truncated,
            "observation_limit": MISSION_OBSERVATION_CAP,
        }
    )


@api_view(["GET"])
@authentication_classes([SensorTokenAuthentication])
@permission_classes([IsAuthenticated])
def mission_ble_observations(request):
    """BLE equivalent of mission_wifi_observations — see that function's
    docstring for the shared reasoning (sensor-token-only, near-radius
    required and validated before any cap).

    device_key_exact matches BLEDevice.device_key exactly (ble_mac, falling
    back to stable_identifier when no MAC was ever captured — see that
    model's docstring), the same identifier the Android app already has for
    a device from its own scan results, so no key recomputation is needed
    on either side of the wire.
    """
    device_key = request.query_params.get("device_key_exact")
    if not device_key:
        return Response({"detail": "device_key_exact is required"}, status=status.HTTP_400_BAD_REQUEST)
    near, error = parse_required_near(request)
    if error:
        return error
    near_lat, near_lng, near_radius_m = near

    since, until = parse_window(request)
    qs = (
        BLEObservation.objects.select_related("scan_session")
        .filter(ble_device_id=device_key)
        .exclude(scan_session__latitude__isnull=True)
        .exclude(scan_session__longitude__isnull=True)
    )
    if since:
        qs = qs.filter(observed_at__gte=since)
    if until:
        qs = qs.filter(observed_at__lte=until)
    session_limit = parse_session_limit(request)
    if session_limit:
        qs = qs.filter(scan_session_id__in=recent_session_ids(session_limit))

    near_points = []
    for obs in qs.iterator():
        lat, lng = obs.scan_session.latitude, obs.scan_session.longitude
        if haversine_m(near_lat, near_lng, lat, lng) > near_radius_m:
            continue
        near_points.append(
            {
                "identifier": obs.ble_mac or obs.stable_identifier,
                "lat": lat,
                "lng": lng,
                "weight": obs.rssi,
                "observed_at": obs.observed_at,
                "scan_session_id": obs.scan_session_id,
                "accuracy_meters": obs.scan_session.location_accuracy_meters,
            }
        )

    truncated = len(near_points) > MISSION_OBSERVATION_CAP
    return Response(
        {
            "identifier": device_key,
            "points": near_points[:MISSION_OBSERVATION_CAP],
            "truncated": truncated,
            "observation_limit": MISSION_OBSERVATION_CAP,
        }
    )


@api_view(["GET"])
@authentication_classes([SensorTokenAuthentication])
@permission_classes([IsAuthenticated])
def mission_cell_observations(request):
    """Cellular equivalent of mission_wifi_observations — see that
    function's docstring for the shared reasoning.

    tower_key_exact matches CellTower.tower_key exactly (the same
    "{mcc}-{mnc}-{tac_or_lac}-{cell_id}" composite the Android app already
    builds for its own rows — see ScanDiff.cellKey on that side), so no key
    recomputation is needed here either. Readings with no signal_dbm are
    excluded outright (not just skipped in aggregation) — a reading with no
    signal strength can't be weighted, and dropping it beats guessing a
    value that would distort the estimate, same posture as
    area_device_ids()'s own weight_field handling.
    """
    tower_key = request.query_params.get("tower_key_exact")
    if not tower_key:
        return Response({"detail": "tower_key_exact is required"}, status=status.HTTP_400_BAD_REQUEST)
    near, error = parse_required_near(request)
    if error:
        return error
    near_lat, near_lng, near_radius_m = near

    since, until = parse_window(request)
    qs = (
        CellObservation.objects.select_related("scan_session")
        .filter(cell_tower_id=tower_key)
        .exclude(scan_session__latitude__isnull=True)
        .exclude(scan_session__longitude__isnull=True)
        .exclude(signal_dbm__isnull=True)
    )
    if since:
        qs = qs.filter(observed_at__gte=since)
    if until:
        qs = qs.filter(observed_at__lte=until)
    session_limit = parse_session_limit(request)
    if session_limit:
        qs = qs.filter(scan_session_id__in=recent_session_ids(session_limit))

    near_points = []
    for obs in qs.iterator():
        lat, lng = obs.scan_session.latitude, obs.scan_session.longitude
        if haversine_m(near_lat, near_lng, lat, lng) > near_radius_m:
            continue
        near_points.append(
            {
                "lat": lat,
                "lng": lng,
                "weight": obs.signal_dbm,
                "observed_at": obs.observed_at,
                "scan_session_id": obs.scan_session_id,
                "accuracy_meters": obs.scan_session.location_accuracy_meters,
            }
        )

    truncated = len(near_points) > MISSION_OBSERVATION_CAP
    return Response(
        {
            "tower_key": tower_key,
            "points": near_points[:MISSION_OBSERVATION_CAP],
            "truncated": truncated,
            "observation_limit": MISSION_OBSERVATION_CAP,
        }
    )
