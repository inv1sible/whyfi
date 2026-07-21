from django.contrib.auth import authenticate, login, logout
from django.views.decorators.csrf import ensure_csrf_cookie
from rest_framework.authentication import SessionAuthentication
from rest_framework.decorators import api_view, authentication_classes, permission_classes
from rest_framework.permissions import AllowAny
from rest_framework.response import Response

# Session-based login for the PWA/admin — the same Django superuser account
# used for /admin/, not a separate account system. login/logout deliberately
# skip SessionAuthentication (no session exists yet to CSRF-protect on
# login; forcing a logout via CSRF is low-severity and harmless being
# idempotent) — see MEMORY.md. session_view forces the csrftoken cookie to
# be set (@ensure_csrf_cookie) since the frontend calls it on every app
# load, before the user does anything else — that's what makes the
# X-CSRFToken header available in time for genuinely state-changing
# session-authenticated endpoints like /android-build/trigger/.


@api_view(["POST"])
@authentication_classes([])
@permission_classes([AllowAny])
def login_view(request):
    username = request.data.get("username", "")
    password = request.data.get("password", "")
    user = authenticate(request, username=username, password=password)
    if user is None:
        return Response({"detail": "Invalid credentials."}, status=401)
    login(request, user)
    return Response({"authenticated": True, "username": user.username})


@api_view(["POST"])
@authentication_classes([])
@permission_classes([AllowAny])
def logout_view(request):
    logout(request)
    return Response({"detail": "Logged out."})


@ensure_csrf_cookie
@api_view(["GET"])
@authentication_classes([SessionAuthentication])
@permission_classes([AllowAny])
def session_view(request):
    if request.user and request.user.is_authenticated:
        return Response({"authenticated": True, "username": request.user.username})
    return Response({"authenticated": False})
