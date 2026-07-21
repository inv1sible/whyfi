from django.contrib import admin

from .models import (
    AccessPoint,
    BLEObservation,
    CellObservation,
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
admin.site.register(CellObservation)
admin.site.register(BLEObservation)
admin.site.register(SatelliteObservation)
admin.site.register(LANObservation)
