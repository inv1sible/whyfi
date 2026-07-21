from rest_framework import mixins, viewsets
from rest_framework.decorators import action
from rest_framework.response import Response

from .models import Sensor, generate_sensor_token
from .serializers import SensorSerializer, SensorTokenRevealSerializer


class SensorViewSet(mixins.ListModelMixin, mixins.RetrieveModelMixin, mixins.CreateModelMixin, viewsets.GenericViewSet):
    """Create/list/regenerate — session-authenticated (a human managing
    devices via Settings > Sensors), not a public self-registration
    endpoint. Uses the project-wide SessionAuthentication + IsAuthenticated
    default (see config/settings.py) — no override needed here."""

    queryset = Sensor.objects.all().order_by("name")

    def get_serializer_class(self):
        if self.action == "create":
            return SensorTokenRevealSerializer
        return SensorSerializer

    @action(detail=True, methods=["post"], url_path="regenerate-token")
    def regenerate_token(self, request, pk=None):
        sensor = self.get_object()
        sensor.token = generate_sensor_token()
        sensor.save(update_fields=["token"])
        return Response(SensorTokenRevealSerializer(sensor).data)
