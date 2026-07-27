from django.contrib import admin
from django.urls import include, path, re_path

from .views import protected_media, spa_index

urlpatterns = [
    path("admin/", admin.site.urls),
    path("api/v1/", include("config.api_urls")),
    # Login-gated, not a bare static_serve — see protected_media's docstring.
    re_path(r"^media/(?P<path>.*)$", protected_media),
]

# Catch-all must stay last: serves the SPA's index.html for every other
# path (deep links like /trackers, /settings, etc.). Anything above this
# (admin/, api/, media/) is matched first since Django tries patterns in
# order and stops at the first match.
urlpatterns += [re_path(r"^.*$", spa_index)]
