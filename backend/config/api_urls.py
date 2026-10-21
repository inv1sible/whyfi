from django.urls import include, path
from rest_framework.routers import DefaultRouter

from config.auth_views import login_view, logout_view, session_view
from distribution.views import build_status_view, latest_release, trigger_build_view
from scans.monitor_missions import monitor_missions, upload_monitor_mission
from scans.views import (
    AccessPointViewSet,
    BLEDeviceViewSet,
    BLEObservationViewSet,
    CellObservationViewSet,
    CellTowerViewSet,
    FloorPlanViewSet,
    GroundTruthViewSet,
    LANDeviceViewSet,
    LANObservationViewSet,
    ScanSessionViewSet,
    SatelliteObservationViewSet,
    calibration,
    calibration_activate,
    channel_congestion,
    health,
    localization_benchmark,
    heatmap,
    mission_ble_observations,
    mission_cell_observations,
    mission_wifi_observations,
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
router.register("ground-truth", GroundTruthViewSet, basename="ground-truth")
router.register("floor-plans", FloorPlanViewSet, basename="floor-plan")

urlpatterns = [
    path("health/", health),
    path("auth/login/", login_view),
    path("auth/logout/", logout_view),
    path("auth/session/", session_view),
    path("channel-congestion/", channel_congestion),
    path("heatmap/", heatmap),
    path("localization/benchmark/", localization_benchmark),
    path("calibration/", calibration),
    path("calibration/activate/", calibration_activate),
    path("mission/wifi-observations/", mission_wifi_observations),
    path("mission/ble-observations/", mission_ble_observations),
    path("mission/cell-observations/", mission_cell_observations),
    path("monitor-missions/", monitor_missions),
    path("monitor-missions/upload/", upload_monitor_mission),
    path("app/latest/", latest_release),
    path("android-build/trigger/", trigger_build_view),
    path("android-build/status/", build_status_view),
    # Must stay ahead of the router include: "me" isn't a sensor pk, it means
    # "whichever sensor this token belongs to".
    path("sensors/me/heartbeat/", sensor_heartbeat),
    path("", include(router.urls)),
]
