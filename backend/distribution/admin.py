from django.contrib import admin

from .models import AppRelease


@admin.register(AppRelease)
class AppReleaseAdmin(admin.ModelAdmin):
    list_display = ("version_name", "version_code", "build_status", "created_at")
    list_filter = ("build_status",)
    readonly_fields = ("created_at", "build_started_at", "build_finished_at", "build_log_tail")
