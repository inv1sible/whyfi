import secrets

from django.contrib import admin

from .models import Sensor, SensorScanPolicy


class SensorScanPolicyInline(admin.StackedInline):
    model = SensorScanPolicy
    can_delete = False
    extra = 0
    readonly_fields = ("policy_revision", "updated_at", "last_heartbeat_at")


@admin.register(Sensor)
class SensorAdmin(admin.ModelAdmin):
    list_display = ("name", "sensor_type", "is_active", "last_seen_at", "last_scan_upload_at", "created_at")
    list_filter = ("sensor_type", "is_active")
    readonly_fields = ("token", "created_at", "last_seen_at", "last_scan_upload_at")
    inlines = [SensorScanPolicyInline]
    actions = ["regenerate_token"]

    @admin.action(description="Regenerate token for selected sensors")
    def regenerate_token(self, request, queryset):
        for sensor in queryset:
            sensor.token = secrets.token_hex(32)
            sensor.save(update_fields=["token"])
