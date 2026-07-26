from django.contrib import admin

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


@admin.register(AccessPoint)
class AccessPointAdmin(admin.ModelAdmin):
    list_display = ("bssid", "ssid", "first_seen_at", "last_seen_at")
    search_fields = ("bssid", "ssid")


@admin.register(ScanSession)
class ScanSessionAdmin(admin.ModelAdmin):
    list_display = ("id", "sensor", "started_at", "completed_at", "latitude", "longitude")
    list_filter = ("sensor",)


admin.site.register(WiFiObservation)
admin.site.register(CellTower)
admin.site.register(CellObservation)
admin.site.register(BLEObservation)
admin.site.register(SatelliteObservation)
admin.site.register(LANObservation)
admin.site.register(LANDevice)
admin.site.register(GeocodedLocation)
admin.site.register(BLEDevice)
