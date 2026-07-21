from django.utils import timezone
from rest_framework import exceptions
from rest_framework.authentication import TokenAuthentication

from .models import Sensor


class SensorTokenAuthentication(TokenAuthentication):
    """`Authorization: Token <sensor-token>` auth backed by Sensor.token,
    not Django's built-in auth user/Token models. Returning a Sensor in
    place of a user keeps this a single-operator, no-account-system tool
    while still letting DRF's IsAuthenticated gate ingest endpoints."""

    keyword = "Token"

    def authenticate_credentials(self, key):
        try:
            sensor = Sensor.objects.get(token=key, is_active=True)
        except Sensor.DoesNotExist as exc:
            raise exceptions.AuthenticationFailed("Invalid or inactive sensor token.") from exc

        sensor.last_seen_at = timezone.now()
        sensor.save(update_fields=["last_seen_at"])
        return (sensor, None)
