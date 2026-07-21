from datetime import timedelta

from django.db.models import Count, Q
from django.utils import timezone
from rest_framework import mixins, status, viewsets
from rest_framework.authentication import SessionAuthentication
from rest_framework.decorators import action, api_view, authentication_classes, permission_classes
from rest_framework.permissions import AllowAny, IsAuthenticated
from rest_framework.response import Response

from sensors.authentication import SensorTokenAuthentication

from .models import AccessPoint, BLEObservation, CellObservation, LANObservation, SatelliteObservation, ScanSession, WiFiObservation
from .serializers import (
    AccessPointSerializer,
    BLEObservationSerializer,
    CellObservationSerializer,
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


class AccessPointViewSet(mixins.ListModelMixin, mixins.RetrieveModelMixin, viewsets.GenericViewSet):
    serializer_class = AccessPointSerializer
    lookup_field = "bssid"
    lookup_value_regex = "[^/]+"

    def get_queryset(self):
        qs = AccessPoint.objects.all().order_by("-last_seen_at")
        ssid = self.request.query_params.get("ssid")
        if ssid:
            qs = qs.filter(ssid__icontains=ssid)
        active_since = self.request.query_params.get("active_since")
        if active_since:
            qs = qs.filter(last_seen_at__gte=active_since)
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
        obs = access_point.observations.order_by("-observed_at")
        since = request.query_params.get("since")
        if since:
            obs = obs.filter(observed_at__gte=since)
        limit = int(request.query_params.get("limit", 200))
        return Response(WiFiObservationSerializer(obs[:limit], many=True).data)


class ScanSessionViewSet(
    mixins.ListModelMixin, mixins.RetrieveModelMixin, mixins.CreateModelMixin, viewsets.GenericViewSet
):
    queryset = ScanSession.objects.all()
    serializer_class = ScanSessionSerializer

    def initialize_request(self, request, *args, **kwargs):
        # self.action isn't set yet at the point get_authenticators() runs
        # (DRF sets it *after* calling initialize_request(), which is what
        # triggers get_authenticators() in the first place) — so branch on
        # the raw HTTP method here instead, stashed for get_authenticators()
        # to read. POST is create (the only POST this viewset supports);
        # revisit if update/delete mixins are ever added.
        self._is_create_request = request.method == "POST"
        return super().initialize_request(request, *args, **kwargs)

    def get_authenticators(self):
        # Ingest (create) is machine-to-machine via a per-device sensor
        # token; every other action is a human browsing the PWA, via the
        # same admin session used for /admin/. See MEMORY.md.
        if getattr(self, "_is_create_request", False):
            return [SensorTokenAuthentication()]
        return [SessionAuthentication()]

    def get_permissions(self):
        return [IsAuthenticated()]

    def get_queryset(self):
        qs = super().get_queryset()
        sensor_id = self.request.query_params.get("sensor")
        if sensor_id:
            qs = qs.filter(sensor_id=sensor_id)
        return qs

    def create(self, request, *args, **kwargs):
        serializer = ScanSessionIngestSerializer(data=request.data, context={"sensor": request.user})
        serializer.is_valid(raise_exception=True)
        session = serializer.save()
        return Response(ScanSessionSerializer(session).data, status=status.HTTP_201_CREATED)

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


class CellObservationViewSet(mixins.ListModelMixin, viewsets.GenericViewSet):
    serializer_class = CellObservationSerializer

    def get_queryset(self):
        qs = CellObservation.objects.all().order_by("-observed_at")
        mcc = self.request.query_params.get("mcc")
        mnc = self.request.query_params.get("mnc")
        since = self.request.query_params.get("since")
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
        return qs


class SatelliteObservationViewSet(mixins.ListModelMixin, viewsets.GenericViewSet):
    serializer_class = SatelliteObservationSerializer
    queryset = SatelliteObservation.objects.all().order_by("-observed_at")


class LANObservationViewSet(mixins.ListModelMixin, viewsets.GenericViewSet):
    serializer_class = LANObservationSerializer

    def get_queryset(self):
        qs = LANObservation.objects.all().order_by("-observed_at")
        since = self.request.query_params.get("since")
        if since:
            qs = qs.filter(observed_at__gte=since)
        return qs


@api_view(["GET"])
def channel_congestion(request):
    band = request.query_params.get("band", "2.4GHz")
    since = request.query_params.get("since")

    qs = WiFiObservation.objects.filter(band=band)
    if since:
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
    bounds = request.query_params.get("bounds")

    if source == "wifi":
        qs = WiFiObservation.objects.select_related("scan_session")
        weight_field = "rssi"
    elif source == "cellular":
        qs = CellObservation.objects.select_related("scan_session")
        weight_field = "signal_dbm"
    elif source == "ble":
        qs = BLEObservation.objects.select_related("scan_session")
        weight_field = "rssi"
    else:
        return Response({"detail": "invalid source, expected wifi|cellular|ble"}, status=400)

    if since:
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
    # how many raw observations exist in the requested window.
    buckets = {}
    for obs in qs[:5000]:
        key = (round(obs.scan_session.latitude, 4), round(obs.scan_session.longitude, 4))
        value = getattr(obs, weight_field) or 0
        bucket = buckets.setdefault(key, {"sum": 0, "count": 0})
        bucket["sum"] += value
        bucket["count"] += 1

    points = [
        {"lat": lat, "lng": lng, "weight": agg["sum"] / agg["count"]} for (lat, lng), agg in buckets.items()
    ]
    return Response(points)
