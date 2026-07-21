from rest_framework import serializers

from .models import Sensor


class SensorSerializer(serializers.ModelSerializer):
    class Meta:
        model = Sensor
        fields = ["id", "name", "sensor_type", "is_active", "created_at", "last_seen_at"]
        # token is deliberately excluded here — it's only ever shown once,
        # in the create/regenerate-token response (SensorTokenRevealSerializer
        # below), never through list/retrieve. If you lose it, regenerate.


class SensorTokenRevealSerializer(serializers.ModelSerializer):
    """Used only for the create and regenerate-token responses — the one
    moment the token is meant to be visible."""

    class Meta:
        model = Sensor
        fields = ["id", "name", "sensor_type", "is_active", "created_at", "last_seen_at", "token"]
        read_only_fields = ["id", "is_active", "created_at", "last_seen_at", "token"]
