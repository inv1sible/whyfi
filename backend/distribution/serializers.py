from rest_framework import serializers

from .download_tokens import sign_media_path
from .models import AppRelease


class AppReleaseSerializer(serializers.ModelSerializer):
    download_url = serializers.SerializerMethodField()
    apk_size = serializers.SerializerMethodField()

    class Meta:
        model = AppRelease
        fields = [
            "id",
            "version_code",
            "version_name",
            "release_notes",
            "created_at",
            "download_url",
            "apk_size",
            "build_status",
            "build_started_at",
            "build_finished_at",
            "build_log_tail",
        ]

    def get_download_url(self, obj):
        if not obj.apk_file:
            return None
        request = self.context.get("request")
        # /media/ is login-gated, so the URL carries a short-lived signed
        # token — otherwise the Download page's QR code would be useless,
        # since it's scanned by the phone being sideloaded and that browser
        # has no session. See distribution/download_tokens.py.
        url = f"{obj.apk_file.url}?t={sign_media_path(obj.apk_file.name)}"
        return request.build_absolute_uri(url) if request else url

    def get_apk_size(self, obj):
        # Lets the frontend show a progress bar and verify the download
        # wasn't truncated/corrupted in transit before handing it to
        # Android's installer — see MEMORY.md.
        if not obj.apk_file:
            return None
        try:
            return obj.apk_file.size
        except (ValueError, FileNotFoundError):
            return None
