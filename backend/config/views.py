from django.conf import settings
from django.http import FileResponse, HttpResponseNotFound, JsonResponse
from django.views.static import serve as static_serve

from distribution.download_tokens import media_token_grants


def protected_media(request, path):
    """Serves MEDIA (the built APKs) to a logged-in session, or to a holder of
    a short-lived signed `?t=` token for this exact path.

    MEDIA was previously wired straight to django.views.static.serve with no
    auth at all, which quietly contradicted the project-wide rule that only
    /health/ and /auth/* are public (see MEMORY.md): the APK itself was
    fetchable by anyone who knew or guessed the URL, and the filenames are
    predictable (whyfi-<version_name>.apk, where version_name defaults to a
    timestamp).

    The PWA's own download goes through the session: downloadWithProgress() is
    a same-origin fetch() that carries the cookie. The signed token exists for
    the Download page's QR code, which is meant to be scanned by the phone
    being sideloaded — a browser with no whyfi session at all. See
    distribution/download_tokens.py.

    Returns JSON 403 rather than redirecting to a login page: there is no
    Django login *view* in this project (the PWA owns login), so a redirect
    would land on the SPA catch-all and hand the caller an HTML page where it
    asked for a binary.
    """
    if not (request.user.is_authenticated or media_token_grants(path, request.GET.get("t", ""))):
        return JsonResponse({"detail": "Authentication required."}, status=403)
    return static_serve(request, path, document_root=settings.MEDIA_ROOT)


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
