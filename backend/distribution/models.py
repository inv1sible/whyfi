import uuid

from django.db import models


def apk_upload_path(instance, filename):
    return f"releases/{filename}"


class AppRelease(models.Model):
    """A built Android APK. Either registered directly via the `import_apk`
    management command (e.g. a build made with Android Studio), or created
    in QUEUED state by the PWA's "Build Android App" button and filled in
    asynchronously once android-builder finishes — see distribution/services.py.
    Self-hosted distribution, not Play Store."""

    class BuildStatus(models.TextChoices):
        QUEUED = "QUEUED", "Queued"
        BUILDING = "BUILDING", "Building"
        SUCCESS = "SUCCESS", "Success"
        FAILED = "FAILED", "Failed"

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    version_code = models.PositiveIntegerField(unique=True)
    version_name = models.CharField(max_length=32)
    apk_file = models.FileField(upload_to=apk_upload_path, blank=True)
    release_notes = models.TextField(blank=True)
    created_at = models.DateTimeField(auto_now_add=True)

    # Only relevant while a PWA-triggered build is in flight; a manually
    # `import_apk`-registered release is just born SUCCESS with both
    # timestamps unset.
    build_status = models.CharField(max_length=10, choices=BuildStatus.choices, default=BuildStatus.SUCCESS)
    build_started_at = models.DateTimeField(null=True, blank=True)
    build_finished_at = models.DateTimeField(null=True, blank=True)
    build_log_tail = models.TextField(blank=True)

    class Meta:
        ordering = ["-version_code"]

    def __str__(self):
        return f"whyfi {self.version_name} ({self.version_code})"
