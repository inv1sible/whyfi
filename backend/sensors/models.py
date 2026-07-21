import secrets
import uuid

from django.db import models


def generate_sensor_token() -> str:
    return secrets.token_hex(32)


class Sensor(models.Model):
    """A reporting device (in v1: an Android phone). Auth is a per-device
    token, managed via the PWA's Settings > Sensors tab (or Django admin) —
    there is no multi-user account system in v1 (see AGENT.md / MEMORY.md
    for why)."""

    class SensorType(models.TextChoices):
        ANDROID = "android", "Android"
        KALI_LINUX = "kali_linux", "Kali Linux (reserved for v-next)"
        OTHER = "other", "Other"

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    name = models.CharField(max_length=100)
    sensor_type = models.CharField(max_length=20, choices=SensorType.choices, default=SensorType.ANDROID)
    token = models.CharField(max_length=64, unique=True, editable=False, default=generate_sensor_token)
    is_active = models.BooleanField(default=True)
    created_at = models.DateTimeField(auto_now_add=True)
    last_seen_at = models.DateTimeField(null=True, blank=True)

    def __str__(self):
        return f"{self.name} ({self.sensor_type})"

    @property
    def is_authenticated(self):
        # Lets DRF's IsAuthenticated permission treat a Sensor instance
        # (returned by SensorTokenAuthentication in place of a Django user)
        # as authenticated without pulling in a full user-account system.
        return True
