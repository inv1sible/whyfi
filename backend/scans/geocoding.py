"""Reverse geocoding via the public Nominatim API — same "fetched by the
server/browser when it has internet access, no self-hosted service" spirit
as the OSM map tiles (see docs/architecture.md), but done server-side here
(rather than client-side like the tiles) so results can be cached in
GeocodedLocation and shared across every viewer, and so the required
request-rate limiting happens in one place rather than per browser tab.

Nominatim's usage policy (https://operations.osmfoundation.org/policies/nominatim/)
requires a real identifying User-Agent, caps requests at ~1/sec, and expects
results to be cached rather than re-requested — GEOCODE_PRECISION plus the
GeocodedLocation cache table are what make repeated lookups near the same
spot free after the first one.
"""

import json
import time
import urllib.parse
import urllib.request

from .models import GeocodedLocation

GEOCODE_PRECISION = 3  # ~111m — fine-grained enough for "which neighborhood"
NOMINATIM_URL = "https://nominatim.openstreetmap.org/reverse"
USER_AGENT = "whyfi-self-hosted-scanner/1.0 (self-hosted personal tool; see project README)"
MIN_REQUEST_INTERVAL_SECONDS = 1.1

_last_request_at = 0.0


def _pick_locality(address: dict) -> str:
    locality = (
        address.get("suburb")
        or address.get("village")
        or address.get("town")
        or address.get("city_district")
        or address.get("municipality")
    )
    city = address.get("city") or address.get("town") or address.get("municipality") or address.get("county")
    if locality and city and locality != city:
        return f"{locality}, {city}"
    return locality or city or address.get("state") or ""


def rounded_key(lat: float, lng: float) -> tuple:
    return (round(lat, GEOCODE_PRECISION), round(lng, GEOCODE_PRECISION))


def reverse_geocode(lat: float, lng: float) -> str:
    """A single live Nominatim lookup — callers are responsible for rate
    limiting and caching (see resolve_missing_addresses below); don't call
    this in a tight loop directly."""
    global _last_request_at
    elapsed = time.monotonic() - _last_request_at
    if elapsed < MIN_REQUEST_INTERVAL_SECONDS:
        time.sleep(MIN_REQUEST_INTERVAL_SECONDS - elapsed)

    params = urllib.parse.urlencode({"format": "json", "lat": lat, "lon": lng, "zoom": 14, "addressdetails": 1})
    request = urllib.request.Request(f"{NOMINATIM_URL}?{params}", headers={"User-Agent": USER_AGENT})
    _last_request_at = time.monotonic()
    with urllib.request.urlopen(request, timeout=5) as response:
        body = json.loads(response.read())
    return _pick_locality(body.get("address", {}))


def resolve_missing_addresses(scan_sessions, limit=20) -> int:
    """Geocodes up to `limit` distinct not-yet-cached locations among the
    given ScanSessions (rounded to GEOCODE_PRECISION), sequentially and
    rate-limited. Returns how many were newly resolved. A failure on one
    location (network error, Nominatim unreachable) doesn't abort the
    rest — it's just skipped and can be retried on the next call."""
    seen = set()
    resolved = 0
    for session in scan_sessions:
        if resolved >= limit:
            break
        if session.latitude is None or session.longitude is None:
            continue
        key = rounded_key(session.latitude, session.longitude)
        if key in seen:
            continue
        seen.add(key)
        if GeocodedLocation.objects.filter(lat_rounded=key[0], lng_rounded=key[1]).exists():
            continue
        try:
            address = reverse_geocode(*key)
        except Exception:
            continue
        GeocodedLocation.objects.update_or_create(
            lat_rounded=key[0], lng_rounded=key[1], defaults={"address": address}
        )
        resolved += 1
    return resolved
