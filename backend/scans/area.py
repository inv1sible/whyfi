"""The focus area: the part of the map every list, map and report is narrowed to.

A circle (`area_lat`, `area_lng`, `area_radius_m`) or, since 2026-11-11, a
polygon (`area_polygon=lat,lng;lat,lng;…`, 3–200 corners). Devices are
judged by their *estimated* position (see views.within_area); the shape only
answers "is this point inside".

Pure functions on degrees: a polygon the size of a street or a campus is
flat enough for a planar point-in-polygon test, and the circle uses the
same haversine as everything else.
"""
import math

from .localization import haversine_m

MAX_POLYGON_CORNERS = 200


def _float(value):
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) else None


def _valid(lat, lng):
    return lat is not None and lng is not None and -90 <= lat <= 90 and -180 <= lng <= 180


class Circle:
    kind = "circle"

    def __init__(self, lat, lng, radius_m):
        self.lat, self.lng, self.radius_m = lat, lng, radius_m

    def contains(self, lat, lng):
        return haversine_m(self.lat, self.lng, lat, lng) <= self.radius_m

    def describe(self):
        return f"{round(self.radius_m)} m around {self.lat:.5f}, {self.lng:.5f}"


class Polygon:
    kind = "polygon"

    def __init__(self, points):
        self.points = list(points)

    def contains(self, lat, lng):
        """Ray casting on (lat, lng) as planar coordinates; corners count as inside."""
        inside = False
        n = len(self.points)
        for i in range(n):
            y1, x1 = self.points[i]
            y2, x2 = self.points[(i + 1) % n]
            if (y1, x1) == (lat, lng):
                return True
            if (y1 > lat) != (y2 > lat):
                x = x1 + (lat - y1) * (x2 - x1) / (y2 - y1)
                if lng < x:
                    inside = not inside
                elif lng == x:
                    return True
        return inside

    def describe(self):
        lat = sum(p[0] for p in self.points) / len(self.points)
        lng = sum(p[1] for p in self.points) / len(self.points)
        return f"polygon with {len(self.points)} corners around {lat:.5f}, {lng:.5f}"


def parse_polygon(text):
    """`lat,lng;lat,lng;…` → Polygon, or None when malformed, too small or too big."""
    if not text:
        return None
    points = []
    for pair in text.split(";"):
        parts = pair.split(",")
        if len(parts) != 2:
            return None
        lat, lng = _float(parts[0]), _float(parts[1])
        if not _valid(lat, lng):
            return None
        points.append((lat, lng))
    if points and points[0] == points[-1] and len(points) > 1:
        points.pop()  # a closed ring repeats its first corner
    if len(points) < 3 or len(points) > MAX_POLYGON_CORNERS:
        return None
    return Polygon(points)


def parse_area(request):
    """The focus area from the query string, or None.

    A polygon wins over a circle when both are sent. A half-specified circle
    (two of the three numbers, or a radius ≤ 0) means the caller's intent is
    unknown, and silently filtering by a partial one is worse than not
    filtering at all.
    """
    polygon = parse_polygon(request.query_params.get("area_polygon"))
    if polygon:
        return polygon
    lat = _float(request.query_params.get("area_lat"))
    lng = _float(request.query_params.get("area_lng"))
    radius = _float(request.query_params.get("area_radius_m"))
    if radius is None or radius <= 0 or not _valid(lat, lng):
        return None
    return Circle(lat, lng, radius)
