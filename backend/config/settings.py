import os
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent

SECRET_KEY = os.environ.get("DJANGO_SECRET_KEY", "insecure-dev-key-change-me")
DEBUG = os.environ.get("DJANGO_DEBUG", "false").lower() == "true"
ALLOWED_HOSTS = [h.strip() for h in os.environ.get("DJANGO_ALLOWED_HOSTS", "*").split(",") if h.strip()]

# This stack is designed to sit behind a reverse proxy (Nginx Proxy Manager
# or similar) that terminates TLS and forwards plain HTTP internally — see
# MEMORY.md for why there's no nginx/Caddy container of our own. Without the
# line below, Django has no way to know the original request was HTTPS, so
# it computes the wrong scheme for CSRF's Origin check (and for building
# absolute URLs) and rejects real browser requests. Only safe because the
# backend port isn't meant to be reachable directly from the internet — if
# it is, someone could spoof this header. See docs/deployment.md.
SECURE_PROXY_SSL_HEADER = ("HTTP_X_FORWARDED_PROTO", "https")

# Extra safety net alongside the header above — set to your public HTTPS
# origin(s) if you're reverse-proxying (e.g.
# DJANGO_CSRF_TRUSTED_ORIGINS=https://whyfi.yourdomain.tld). Empty by
# default so plain local/LAN HTTP use (no reverse proxy) isn't affected.
CSRF_TRUSTED_ORIGINS = [
    o.strip() for o in os.environ.get("DJANGO_CSRF_TRUSTED_ORIGINS", "").split(",") if o.strip()
]

INSTALLED_APPS = [
    "django.contrib.admin",
    "django.contrib.auth",
    "django.contrib.contenttypes",
    "django.contrib.sessions",
    "django.contrib.messages",
    "django.contrib.staticfiles",
    "rest_framework",
    "sensors",
    "scans",
    "distribution",
]

MIDDLEWARE = [
    "django.middleware.security.SecurityMiddleware",
    "whitenoise.middleware.WhiteNoiseMiddleware",
    "django.contrib.sessions.middleware.SessionMiddleware",
    "django.middleware.common.CommonMiddleware",
    "django.middleware.csrf.CsrfViewMiddleware",
    "django.contrib.auth.middleware.AuthenticationMiddleware",
    "django.contrib.messages.middleware.MessageMiddleware",
    "django.middleware.clickjacking.XFrameOptionsMiddleware",
]

ROOT_URLCONF = "config.urls"

TEMPLATES = [
    {
        "BACKEND": "django.template.backends.django.DjangoTemplates",
        "DIRS": [],
        "APP_DIRS": True,
        "OPTIONS": {
            "context_processors": [
                "django.template.context_processors.debug",
                "django.template.context_processors.request",
                "django.contrib.auth.context_processors.auth",
                "django.contrib.messages.context_processors.messages",
            ],
        },
    },
]

WSGI_APPLICATION = "config.wsgi.application"
ASGI_APPLICATION = "config.asgi.application"

DATABASES = {
    "default": {
        "ENGINE": "django.db.backends.postgresql",
        "NAME": os.environ.get("POSTGRES_DB", "whyfi"),
        "USER": os.environ.get("POSTGRES_USER", "whyfi"),
        "PASSWORD": os.environ.get("POSTGRES_PASSWORD", "whyfi"),
        "HOST": os.environ.get("POSTGRES_HOST", "postgres"),
        "PORT": os.environ.get("POSTGRES_PORT", "5432"),
    }
}

AUTH_PASSWORD_VALIDATORS = [
    {"NAME": "django.contrib.auth.password_validation.UserAttributeSimilarityValidator"},
    {"NAME": "django.contrib.auth.password_validation.MinimumLengthValidator"},
    {"NAME": "django.contrib.auth.password_validation.CommonPasswordValidator"},
    {"NAME": "django.contrib.auth.password_validation.NumericPasswordValidator"},
]

LANGUAGE_CODE = "en-us"
TIME_ZONE = "UTC"
USE_I18N = True
USE_TZ = True

# --- Static (build-time SPA assets + Django admin/DRF assets) ---
STATIC_URL = "/static/"
STATIC_ROOT = BASE_DIR / "staticfiles"
STORAGES = {
    "default": {"BACKEND": "django.core.files.storage.FileSystemStorage"},
    "staticfiles": {"BACKEND": "whitenoise.storage.CompressedManifestStaticFilesStorage"},
}

# --- Media (deploy-time content: uploaded APK releases) ---
MEDIA_URL = "/media/"
MEDIA_ROOT = BASE_DIR / "media"

# The built React/Vite PWA is copied here by backend/Dockerfile's frontend
# build stage. WhiteNoise serves it directly at the root ('/'), separate from
# STATIC_URL, so the PWA's service worker gets root scope. See MEMORY.md for
# why there's no nginx/Caddy container doing this instead.
FRONTEND_DIST_DIR = BASE_DIR / "frontend_dist"
if FRONTEND_DIST_DIR.exists():
    WHITENOISE_ROOT = str(FRONTEND_DIST_DIR)

DEFAULT_AUTO_FIELD = "django.db.models.BigAutoField"

# Locked down by default: browsing the API/PWA requires a logged-in Django
# session (the same admin account created via DJANGO_SUPERUSER_* env vars —
# there's no separate multi-user system, see AGENT.md/MEMORY.md). The
# Android app's sensor-token auth is layered on top per-view where needed
# (see scans/views.py's ScanSessionViewSet, distribution/views.py). Only
# /api/v1/health/ and the login/session endpoints stay public.
REST_FRAMEWORK = {
    "DEFAULT_AUTHENTICATION_CLASSES": ["rest_framework.authentication.SessionAuthentication"],
    "DEFAULT_PERMISSION_CLASSES": ["rest_framework.permissions.IsAuthenticated"],
    # Lets every list endpoint's page size be raised via `?limit=` — the
    # param every overview page already sends. Plain PageNumberPagination
    # doesn't recognise `limit` at all, so it was silently ignored: any
    # window with more than PAGE_SIZE matches hid everything past the 50
    # most-recently-seen with no indication. See scans/pagination.py.
    "DEFAULT_PAGINATION_CLASS": "scans.pagination.LimitablePageNumberPagination",
    "PAGE_SIZE": 50,
}
