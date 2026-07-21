from django.urls import include, path
from rest_framework.routers import DefaultRouter

from config.auth_views import login_view, logout_view, session_view
from distribution.views import build_status_view, latest_release, trigger_build_view
from scans.views import (
    AccessPointViewSet,
    BLEObservationViewSet,
    CellObservationViewSet,
    LANObservationViewSet,
    ScanSessionViewSet,
    SatelliteObservationViewSet,
    channel_congestion,
    health,
    heatmap,
)
from sensors.views import SensorViewSet

router = DefaultRouter()
router.register("sensors", SensorViewSet, basename="sensor")
router.register("access-points", AccessPointViewSet, basename="access-point")
router.register("scan-sessions", ScanSessionViewSet, basename="scan-session")
router.register("cell-observations", CellObservationViewSet, basename="cell-observation")
router.register("ble-observations", BLEObservationViewSet, basename="ble-observation")
router.register("satellite-observations", SatelliteObservationViewSet, basename="satellite-observation")
router.register("lan-observations", LANObservationViewSet, basename="lan-observation")

urlpatterns = [
    path("health/", health),
    path("auth/login/", login_view),
    path("auth/logout/", logout_view),
    path("auth/session/", session_view),
    path("channel-congestion/", channel_congestion),
    path("heatmap/", heatmap),
    path("app/latest/", latest_release),
    path("android-build/trigger/", trigger_build_view),
    path("android-build/status/", build_status_view),
    path("", include(router.urls)),
]
