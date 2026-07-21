import os

from django.core.asgi import get_asgi_application

os.environ.setdefault("DJANGO_SETTINGS_MODULE", "config.settings")

# Reserved for v-next (WebSocket/Channels live updates). Unused in v1, which
# is request/poll-based only — see docs/roadmap.md.
application = get_asgi_application()
