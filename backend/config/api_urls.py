from django.urls import include, path
from rest_framework.routers import DefaultRouter

from config.auth_views import login_view, logout_view, session_view
from distribution.views import build_status_view, latest_release, trigger_build_view
from scans.views import (
    AccessPointViewSet,
    BLEDeviceViewSet,
    BLEObservationViewSet,
    CellObservationViewSet,
    CellTowerViewSet,
    LANDeviceViewSet,
    LANObservationViewSet,
    ScanSessionViewSet,
    SatelliteObservationViewSet,
    channel_congestion,
    health,
    heatmap,
)
from sensors.views import CrashReportViewSet, SensorViewSet, sensor_heartbeat

router = DefaultRouter()
router.register("sensors", SensorViewSet, basename="sensor")
router.register("crash-reports", CrashReportViewSet, basename="crash-report")
router.register("access-points", AccessPointViewSet, basename="access-point")
router.register("scan-sessions", ScanSessionViewSet, basename="scan-session")
router.register("cell-observations", CellObservationViewSet, basename="cell-observation")
router.register("cell-towers", CellTowerViewSet, basename="cell-tower")
router.register("ble-observations", BLEObservationViewSet, basename="ble-observation")
router.register("ble-devices", BLEDeviceViewSet, basename="ble-device")
router.register("satellite-observations", SatelliteObservationViewSet, basename="satellite-observation")
router.register("lan-observations", LANObservationViewSet, basename="lan-observation")
router.register("lan-devices", LANDeviceViewSet, basename="lan-device")

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
    # Must stay ahead of the router include: "me" isn't a sensor pk, it means
    # "whichever sensor this token belongs to".
    path("sensors/me/heartbeat/", sensor_heartbeat),
    path("", include(router.urls)),
]
