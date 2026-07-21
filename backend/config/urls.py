from django.conf import settings
from django.contrib import admin
from django.urls import include, path, re_path
from django.views.static import serve as static_serve

from .views import spa_index

urlpatterns = [
    path("admin/", admin.site.urls),
    path("api/v1/", include("config.api_urls")),
    re_path(r"^media/(?P<path>.*)$", static_serve, {"document_root": settings.MEDIA_ROOT}),
]

# Catch-all must stay last: serves the SPA's index.html for every other
# path (deep links like /trackers, /settings, etc.). Anything above this
# (admin/, api/, media/) is matched first since Django tries patterns in
# order and stops at the first match.
urlpatterns += [re_path(r"^.*$", spa_index)]
