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
        active_since = self.request.query_params.get("active_since")
        if active_since:
            qs = qs.filter(last_seen_at__gte=active_since)
        session_limit = parse_session_limit(self.request)
        if session_limit:
            qs = qs.filter(observations__scan_session_id__in=recent_session_ids(session_limit)).distinct()
        band = self.request.query_params.get("band")
        if band:
            qs = qs.filter(observations__band=band).distinct()
        security = self.request.query_params.get("security")
        if security:
            qs = qs.filter(observations__security_type=security).distinct()
        return qs

    @action(detail=True, methods=["get"], url_path="wifi-observations")
    def wifi_observations(self, request, bssid=None):
        access_point = self.get_object()
        obs = access_point.observations.select_related("scan_session").order_by("-observed_at")
        since = request.query_params.get("since")
        if since:
            obs = obs.filter(observed_at__gte=since)
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
        since = request.query_params.get("since")
        ssid_exact = request.query_params.get("ssid_exact")
        qs = (
            WiFiObservation.objects.select_related("access_point", "scan_session")
            .exclude(scan_session__latitude__isnull=True)
            .exclude(scan_session__longitude__isnull=True)
        )
        if since:
            qs = qs.filter(observed_at__gte=since)
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
        active_since = self.request.query_params.get("active_since")
        if active_since:
            qs = qs.filter(last_seen_at__gte=active_since)
        session_limit = parse_session_limit(self.request)
        if session_limit:
            qs = qs.filter(observations__scan_session_id__in=recent_session_ids(session_limit)).distinct()
        return qs

    @action(detail=True, methods=["get"], url_path="cell-observations")
    def cell_observations(self, request, tower_key=None):
        tower = self.get_object()
        obs = tower.observations.select_related("scan_session").order_by("-observed_at")
        since = request.query_params.get("since")
        if since:
            obs = obs.filter(observed_at__gte=since)
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
        since = request.query_params.get("since")
        qs = (
            CellObservation.objects.select_related("cell_tower", "scan_session")
            .exclude(scan_session__latitude__isnull=True)
            .exclude(scan_session__longitude__isnull=True)
            .exclude(cell_tower__isnull=True)
        )
        if since:
            qs = qs.filter(observed_at__gte=since)
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
        return Response(capped_response(results, truncated, COVERAGE_OBSERVATION_CAP))


class CellObservationViewSet(mixins.ListModelMixin, viewsets.GenericViewSet):
    serializer_class = CellObservationSerializer

    def get_queryset(self):
        qs = CellObservation.objects.all().order_by("-observed_at")
        mcc = self.request.query_params.get("mcc")
        mnc = self.request.query_params.get("mnc")
        since = self.request.query_params.get("since")
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
        return qs


class BLEObservationViewSet(mixins.ListModelMixin, viewsets.GenericViewSet):
    serializer_class = BLEObservationSerializer

    def get_queryset(self):
        qs = BLEObservation.objects.all().order_by("-observed_at")
        device_type = self.request.query_params.get("device_type")
        since = self.request.query_params.get("since")
        identifier = self.request.query_params.get("identifier")
        if device_type:
            qs = qs.filter(device_type_guess=device_type)
        if since:
            qs = qs.filter(observed_at__gte=since)
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
        since = request.query_params.get("since")
        qs = BLEObservation.objects.select_related("scan_session").exclude(
            scan_session__latitude__isnull=True
        ).exclude(scan_session__longitude__isnull=True).order_by("-observed_at")
        if since:
            qs = qs.filter(observed_at__gte=since)
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
        active_since = self.request.query_params.get("active_since")
        if active_since:
            qs = qs.filter(last_seen_at__gte=active_since)
        session_limit = parse_session_limit(self.request)
        if session_limit:
            qs = qs.filter(observations__scan_session_id__in=recent_session_ids(session_limit)).distinct()
        return qs

    @action(detail=True, methods=["get"], url_path="ble-observations")
    def ble_observations(self, request, device_key=None):
        device = self.get_object()
        obs = device.observations.select_related("scan_session").order_by("-observed_at")
        since = request.query_params.get("since")
        if since:
            obs = obs.filter(observed_at__gte=since)
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
        since = self.request.query_params.get("since")
        if since:
            qs = qs.filter(observed_at__gte=since)
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
        active_since = self.request.query_params.get("active_since")
        if active_since:
            qs = qs.filter(last_seen_at__gte=active_since)
        session_limit = parse_session_limit(self.request)
        if session_limit:
            qs = qs.filter(
                observations__scan_session_id__in=recent_session_ids(session_limit, radio_related_name="lan_observations")
            ).distinct()
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
        since = request.query_params.get("since")
        if since:
            obs = obs.filter(observed_at__gte=since)
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
    since = request.query_params.get("since")
    session_limit = parse_session_limit(request)

    qs = WiFiObservation.objects.filter(band=band)
    if session_limit:
        qs = qs.filter(scan_session_id__in=recent_session_ids(session_limit))
    elif since:
        qs = qs.filter(observed_at__gte=since)
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
    since = request.query_params.get("since")
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
    elif since:
        qs = qs.filter(observed_at__gte=since)

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
