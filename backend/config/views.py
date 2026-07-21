from django.conf import settings
from django.http import FileResponse, HttpResponseNotFound


def spa_index(request):
    """Serve the built PWA's index.html for any non-API/non-admin route,
    so client-side routing (deep links to e.g. /trackers) works."""
    index_path = settings.FRONTEND_DIST_DIR / "index.html"
    if not index_path.exists():
        return HttpResponseNotFound(
            "Frontend build not found. The backend Docker image builds the "
            "frontend as part of its multi-stage build — see backend/Dockerfile."
        )
    return FileResponse(open(index_path, "rb"), content_type="text/html")
