import math
import re
from datetime import timedelta
from urllib.parse import quote

from django.db.models import Count, IntegerField, OuterRef, Prefetch, Q, Subquery
from django.db.models.functions import Coalesce
from django.utils import timezone
from rest_framework import mixins, status, viewsets
from rest_framework.authentication import SessionAuthentication
from rest_framework.decorators import action, api_view, authentication_classes, permission_classes
from rest_framework.permissions import AllowAny, IsAuthenticated
from rest_framework.response import Response

from sensors.authentication import SensorTokenAuthentication
from sensors.models import Sensor

from .ble_signatures import is_ble_capable, load_catalog
from .wifi_signatures import is_wifi_capable
from .signature_lookup import lookup as signature_lookup
from .calibration import fit_path_loss
from .location_quality import clean_session_locations, original_position
from .floorplan import (
    derive_scale_and_bearing,
    image_to_world,
    MIN_OUTLINE_VERTICES,
    interpolate_coverage,
    outline_pixels,
    predict_coverage,
    plan_corners,
    solve_transform,
    world_to_image,
    suggest_ap_placements,
)
from .estimators import (
    DEFAULT_ESTIMATOR,
    ESTIMATORS,
    FTM_MULTILATERATION,
    RANGE_MODEL,
    compare_estimators,
    estimate_position,
    ranged_points_for,
)
from .geocoding import resolve_missing_addresses
from .area import parse_area
from .localization import (
    cluster_ap_hypotheses,
    haversine_m,
    position_covariance,
    position_probability_grid,
    solve_ap_position,
    suggest_next_positions,
)
from .models import (
    AccessPoint,
    BLEDevice,
    BLEObservation,
    CalibratedRangeModel,
    CellObservation,
    CellTower,
    FloorPlan,
    FtmRangingObservation,
    GeocodedLocation,
    GroundTruthPosition,
    LANDevice,
    LANObservation,
    SatelliteObservation,
    ScanSession,
    WiFiObservation,
)
from .serializers import (
    AccessPointDetailSerializer,
    AccessPointSerializer,
    CalibratedRangeModelSerializer,
    BLEDeviceDetailSerializer,
    BLEDeviceSerializer,
    BLEObservationSerializer,
    CellObservationSerializer,
    CellTowerSerializer,
    FloorPlanSerializer,
    GroundTruthPositionSerializer,
    LANDeviceSerializer,
    LANObservationSerializer,
    SatelliteObservationSerializer,
    ScanSessionIngestSerializer,
    ScanSessionSerializer,
    WiFiObservationSerializer,
)


@api_view(["GET"])
@authentication_classes([])
@permission_classes([AllowAny])
def health(request):
    return Response({"status": "ok"})


def recent_session_ids(n, radio_related_name=None, area=None):
    """The N most recent ScanSessions' ids — "last N scans" filtering used
    across several list endpoints (as an alternative to a time-based cutoff,
    which doesn't line up with how often you actually scanned).

    A LAN scan is its own separate action/session (a subnet sweep + port
    scan takes longer than a regular WiFi/cellular/BLE/GNSS pass, see
    LANObservation's docstring), so "last N scans" for LAN data must only
    count sessions that actually contain a LAN observation — otherwise it
    counts the N most recent sessions of *any* type, which are dominated by
    regular passes and often contain zero LAN scans at all, silently
    returning nothing. Pass radio_related_name="lan_observations" for that;
    leave it unset for WiFi/cellular/BLE/satellite, which all share one
    session type and don't have this problem.

    With a focus area (user, 2026-11-11: "combine filters, like scans and
    location") it is the N most recent scans recorded *inside* the area, not
    the N most recent overall with the area applied afterwards — otherwise a
    survey elsewhere this morning would empty every page about last week's
    site. A scan without a position is never inside an area."""
    qs = ScanSession.objects.order_by("-started_at")
    if radio_related_name:
        qs = qs.filter(**{f"{radio_related_name}__isnull": False}).distinct()
    if area is None:
        return list(qs.values_list("id", flat=True)[:n])
    return session_ids_in_area(qs, area, limit=n)


def session_ids_in_area(qs, area, limit=None):
    """Ids of the sessions in `qs` (in its order) whose own corrected position
    lies inside the focus area — at most `limit` of them."""
    positioned = qs.exclude(latitude__isnull=True).exclude(longitude__isnull=True)
    ids = []
    for pk, lat, lng in positioned.values_list("id", "latitude", "longitude").iterator():
        if area.contains(lat, lng):
            ids.append(pk)
            if limit is not None and len(ids) >= limit:
                break
    return ids


# Grouped coverage/heatmap payloads are assembled row-by-row in Python, so
# they're bounded to keep one request from reading an unbounded number of
# observations. Hitting the bound is now reported to the caller rather than
# silently changing the answer — see capped_take()/capped_response().
COVERAGE_OBSERVATION_CAP = 20000
HEATMAP_OBSERVATION_CAP = 5000
# One SSID's worth of near-location-filtered readings for the Android Mission
# view — smaller than COVERAGE_OBSERVATION_CAP since this is one network, not
# the whole dataset. See mission_wifi_observations() for why this cap is
# applied *after* the near-radius filter, not before.
MISSION_OBSERVATION_CAP = 2000
# Sessions per export request. Generous (an export is meant to be a backup,
# not a page) but bounded, since each session carries every observation it
# recorded and the response is assembled in memory.
EXPORT_SESSION_CAP = 2000

# Ceilings for caller-supplied row counts. Generous — these exist to keep a
# hand-edited URL from turning into an unbounded read, not to constrain the UI.
MAX_OBSERVATION_LIMIT = 1000
MAX_GEOCODE_LIMIT = 50


def positive_int(raw, default=None, maximum=None):
    """Parses a caller-supplied positive integer, falling back to `default`
    for anything unusable: missing, blank, non-numeric, zero or negative.

    Every value these parse into ends up as a queryset slice bound, and
    Django raises ValueError("Negative indexing is not supported.") on a
    negative one — so `?session_limit=-1` used to be a plain unhandled 500,
    as did `?limit=abc` on every per-entity observation endpoint. Falling
    back beats 400ing: these are view/window hints from the UI, not
    semantically load-bearing input worth rejecting a whole request over.
    """
    if raw is None or raw == "":
        return default
    try:
        value = int(raw)
    except (TypeError, ValueError):
        return default
    if value <= 0:
        return default
    return min(value, maximum) if maximum is not None else value


def parse_float(raw, default=None):
    """Same tolerant posture as positive_int, for coordinates and radii —
    these are view hints from the map UI, not load-bearing input. Rejects
    NaN/inf, which parse fine as floats and would poison every comparison
    they touch."""
    if raw is None or raw == "":
        return default
    try:
        value = float(raw)
    except (TypeError, ValueError):
        return default
    if value != value or value in (float("inf"), float("-inf")):
        return default
    return value




def weighted_centroid(points):
    """A device's estimated position: the signal-weighted centre of everywhere
    it was heard from.

    MUST stay identical to weightedCentroid() in frontend/src/geo.ts, which
    computes the `gradientCenter` dot drawn at the middle of every coverage
    shape. If these two drift, the map shows a device inside the focus circle
    while this filter excludes it — the filter looks broken, and the cause is
    invisible. There is a parity test in tests.py pinning them together.

    Note the 0.1 floor: the weakest reading still counts for something, so a
    few strong readings can't collapse the centre onto themselves.
    """
    weights = [p["weight"] for p in points]
    min_w, max_w = min(weights), max(weights)
    spread = (max_w - min_w) or 1
    w = [0.1 + 0.9 * ((weight - min_w) / spread) for weight in weights]
    total = sum(w)
    return (
        sum(wi * p["lat"] for wi, p in zip(w, points)) / total,
        sum(wi * p["lng"] for wi, p in zip(w, points)) / total,
    )


def parse_estimator(request):
    """Which position estimator the caller asked for. Falls back to the
    default for anything unrecognised, same tolerant posture as every other
    view hint here — an unknown estimator name is a stale bookmark or a
    hand-edited URL, not something worth 400ing a map over."""
    requested = request.query_params.get("estimator")
    return requested if requested in ESTIMATORS else DEFAULT_ESTIMATOR


def annotate_estimated_positions(results, request, radio_kind):
    """Attach each device's estimated position to a coverage result list.

    Lives on the coverage endpoints rather than a per-device call because the
    Heatmap and SSID-group pages estimate a position for *every* device in one
    render pass — one request per device would be dozens of round trips per
    poll. Residuals are deliberately not included here (they'd multiply the
    payload by the reading count); the per-device `position` action carries
    those for the one device actually being inspected.
    """
    estimator = parse_estimator(request)
    range_model = active_range_model(radio_kind)
    for entry in results:
        estimate = estimate_position(entry["points"], estimator, radio_kind, range_model)
        entry["estimated_position"] = (
            None
            if estimate["lat"] is None
            else {
                "lat": estimate["lat"],
                "lng": estimate["lng"],
                "estimator": estimate["estimator"],
                "available": estimate["available"],
                "fell_back_to": estimate.get("fell_back_to"),
            }
        )
    return results


def export_session_payload(session):
    """One ScanSession in the exact shape `POST /scan-sessions/` accepts.

    Field names here must track ScanSessionIngestSerializer's inputs, not the
    model's columns — the two differ deliberately in places (`capabilities`
    vs `capabilities_raw`, and `security_type`/`channel`/`band` being derived
    server-side rather than sent). Anything that drifts here produces an
    export that silently fails to import, so there's a round-trip test.
    """
    return {
        "client_scan_id": session.client_scan_id,
        "sensor_name": session.sensor.name if session.sensor else None,
        "started_at": session.started_at.isoformat(),
        "completed_at": session.completed_at.isoformat(),
        # The phone's original position (raw_* once corrected), so an
        # import re-runs the same checks instead of freezing a correction.
        **original_position(session),
        "location_fix_age_ms": session.location_fix_age_ms,
        "fused_latitude": session.fused_latitude,
        "fused_longitude": session.fused_longitude,
        "fused_accuracy_meters": session.fused_accuracy_meters,
        "wifi_observations": [
            {
                "bssid": o.access_point_id,
                "ssid": o.access_point.ssid,
                "rssi": o.rssi,
                "frequency_mhz": o.frequency_mhz,
                "capabilities": o.capabilities_raw,
                "channel_width_mhz": o.channel_width_mhz,
                "center_freq0_mhz": o.center_freq0_mhz,
                "center_freq1_mhz": o.center_freq1_mhz,
                "wifi_standard": o.wifi_standard,
                "is_80211mc_responder": o.is_80211mc_responder,
                "operator_friendly_name": o.operator_friendly_name,
                "venue_name": o.venue_name,
                "vendor_ie_ouis": o.vendor_ie_ouis,
                "observed_at": o.observed_at.isoformat(),
            }
            for o in session.wifi_observations.all()
        ],
        "ftm_observations": [
            {
                "bssid": o.access_point_id,
                "success": o.success,
                "distance_mm": o.distance_mm,
                "distance_std_dev_mm": o.distance_std_dev_mm,
                "rssi": o.rssi,
                "num_attempted_measurements": o.num_attempted_measurements,
                "num_successful_measurements": o.num_successful_measurements,
                "status": o.status,
                "observed_at": o.observed_at.isoformat(),
            }
            for o in session.ftm_observations.all()
        ],
        "cell_observations": [
            {
                "mcc": o.mcc, "mnc": o.mnc, "carrier_name": o.carrier_name, "radio_type": o.radio_type,
                "cell_id": o.cell_id, "tac_or_lac": o.tac_or_lac, "band": o.band,
                "is_serving_cell": o.is_serving_cell, "signal_dbm": o.signal_dbm,
                "rsrp": o.rsrp, "rsrq": o.rsrq, "sinr": o.sinr,
                "physical_cell_id": o.physical_cell_id, "arfcn": o.arfcn,
                "bandwidth_khz": o.bandwidth_khz, "timing_advance": o.timing_advance,
                "observed_at": o.observed_at.isoformat(),
            }
            for o in session.cell_observations.all()
        ],
        "ble_observations": [
            {
                "ble_mac": o.ble_mac, "stable_identifier": o.stable_identifier, "rssi": o.rssi,
                "tx_power": o.tx_power, "manufacturer_data": o.manufacturer_data_raw,
                "service_uuids": o.service_uuids, "manufacturer_records": o.manufacturer_records,
                "service_data": o.service_data, "device_type_guess": o.device_type_guess,
                "device_name": o.device_name, "is_connectable": o.is_connectable,
                "primary_phy": o.primary_phy, "observed_at": o.observed_at.isoformat(),
            }
            for o in session.ble_observations.all()
        ],
        "satellite_observations": [
            {
                "constellation": o.constellation, "svid": o.svid, "cn0_db_hz": o.cn0_db_hz,
                "elevation_degrees": o.elevation_degrees, "azimuth_degrees": o.azimuth_degrees,
                "used_in_fix": o.used_in_fix, "carrier_frequency_hz": o.carrier_frequency_hz,
                "has_ephemeris_data": o.has_ephemeris_data, "has_almanac_data": o.has_almanac_data,
                "observed_at": o.observed_at.isoformat(),
            }
            for o in session.satellite_observations.all()
        ],
        "lan_observations": [
            {
                "ip_address": o.ip_address, "mac_address": o.mac_address, "hostname": o.hostname,
                "vendor_oui": o.vendor_oui, "open_ports": o.open_ports,
                "response_time_ms": o.response_time_ms, "banner": o.banner,
                "device_type_guess": o.device_type_guess, "observed_at": o.observed_at.isoformat(),
            }
            for o in session.lan_observations.all()
        ],
    }


def ground_truth_overrides(request):
    """`{scan_session_id: (lat, lng)}` for every pinned observer position.

    A pin says "the phone was actually *here* for this scan", correcting a bad
    GPS fix. That correction is worth more than any estimator improvement: a
    wrong observer position corrupts every algorithm equally and no amount of
    better maths recovers from it.

    Built once per request rather than per observation — the coverage
    endpoints touch tens of thousands of rows. Switched off with
    `?use_ground_truth=0`, so the corrected and uncorrected answers can be
    compared rather than the correction being an invisible act of faith.
    """
    if request.query_params.get("use_ground_truth") == "0":
        return {}
    return {
        pin.target_key: (pin.latitude, pin.longitude)
        for pin in GroundTruthPosition.objects.filter(kind=GroundTruthPosition.Kind.OBSERVER)
    }


def truth_for_access_point(bssid):
    """The pinned real position of one AP, or None."""
    pin = GroundTruthPosition.objects.filter(
        kind=GroundTruthPosition.Kind.ACCESS_POINT, target_key=bssid
    ).first()
    return None if pin is None else {"lat": pin.latitude, "lng": pin.longitude, "label": pin.label}


def active_range_model(radio_kind):
    """The fitted path-loss model for this radio type, when one is active.
    None means the generic RANGE_MODEL constants apply."""
    fitted = CalibratedRangeModel.objects.filter(radio_kind=radio_kind, is_active=True).first()
    if fitted is None:
        return None
    return {"ref_rssi_at_1m": fitted.ref_rssi_at_1m, "path_loss_exponent": fitted.path_loss_exponent}


def observation_points(qs, request, weight_field):
    """Geotagged observations as estimator input, honouring the caller's
    time/scan window.

    That filtering is the point: without it a position estimate silently
    covers all history while the page around it (and a printed report's
    "Range" header) claims a window — which is exactly the bug the first
    Localization page shipped with.
    """
    since, until = parse_window(request)
    qs = qs.exclude(scan_session__latitude__isnull=True).exclude(scan_session__longitude__isnull=True)
    if since:
        qs = qs.filter(observed_at__gte=since)
    if until:
        qs = qs.filter(observed_at__lte=until)
    session_limit = parse_session_limit(request)
    if session_limit:
        qs = qs.filter(scan_session_id__in=recent_session_ids(session_limit, area=parse_area(request)))

    observations, _ = capped_take(qs.select_related("scan_session"), COVERAGE_OBSERVATION_CAP)
    overrides = ground_truth_overrides(request)
    points = []
    for o in observations:
        if getattr(o, weight_field) is None:
            continue
        lat, lng = overrides.get(str(o.scan_session_id), (o.scan_session.latitude, o.scan_session.longitude))
        points.append({
            "lat": lat,
            "lng": lng,
            "weight": getattr(o, weight_field),
            "observed_at": o.observed_at,
            "scan_session_id": str(o.scan_session_id),
            "position_is_pinned": str(o.scan_session_id) in overrides,
        })
    return points


def device_position_response(request, identifier, points, radio_kind, ftm_points=None, extra=None):
    """One device's estimated position — `?estimator=` for a single answer,
    `?compare=1` for every estimator side by side plus their disagreement."""
    body = {"identifier": identifier, "radio_kind": radio_kind, **(extra or {})}
    range_model = active_range_model(radio_kind)
    body["range_model_is_calibrated"] = range_model is not None
    # A pinned real position turns "these disagree" into "this one is wrong by
    # N metres", which is the number worth having.
    truth = truth_for_access_point(identifier) if radio_kind == "wifi" else None
    body["truth"] = truth

    if request.query_params.get("compare") == "1":
        compared = compare_estimators(points, radio_kind, ftm_points=ftm_points, range_model=range_model)
        if truth is not None:
            for estimate in compared["estimates"].values():
                estimate["error_m"] = (
                    None
                    if estimate.get("lat") is None
                    else haversine_m(estimate["lat"], estimate["lng"], truth["lat"], truth["lng"])
                )
        body.update(compared)
        return Response(body)

    estimator = parse_estimator(request)
    source = ftm_points if (estimator == FTM_MULTILATERATION and ftm_points is not None) else points
    result = estimate_position(source, estimator, radio_kind, range_model)
    if truth is not None and result.get("lat") is not None:
        result["error_m"] = haversine_m(result["lat"], result["lng"], truth["lat"], truth["lng"])
    body.update(result)

    # The AP-position probability surface, opt-in because it's by far the
    # largest part of the payload. Only meaningful for the estimators that
    # actually model distance — see ranged_points_for.
    if request.query_params.get("include_grid") == "1" and result.get("lat") is not None:
        ranged = ranged_points_for(source, estimator, radio_kind, range_model)
        body["probability_grid"] = (
            position_probability_grid(
                ranged,
                result["lat"],
                result["lng"],
                span_m=parse_float(request.query_params.get("grid_span_m"), 120.0),
                steps=positive_int(request.query_params.get("grid_steps"), 25, maximum=61),
            )
            if len(ranged) >= 3
            else None
        )
    return Response(body)


def ftm_points_for(access_point, request):
    """FTM ranging readings as estimator input. Same window filtering as
    observation_points, so switching estimators never silently switches the
    time range too."""
    since, until = parse_window(request)
    qs = access_point.ftm_observations.filter(
        success=True,
        distance_mm__isnull=False,
        scan_session__latitude__isnull=False,
        scan_session__longitude__isnull=False,
    )
    if since:
        qs = qs.filter(observed_at__gte=since)
    if until:
        qs = qs.filter(observed_at__lte=until)
    session_limit = parse_session_limit(request)
    if session_limit:
        qs = qs.filter(scan_session_id__in=recent_session_ids(session_limit, area=parse_area(request)))

    overrides = ground_truth_overrides(request)
    return [
        {
            "lat": overrides.get(str(o.scan_session_id), (o.scan_session.latitude, o.scan_session.longitude))[0],
            "lng": overrides.get(str(o.scan_session_id), (o.scan_session.latitude, o.scan_session.longitude))[1],
            # Inverse-variance from the ranging stddev Android reports; a flat
            # fallback when it's missing rather than dividing by zero.
            "weight": 1 / (o.distance_std_dev_mm / 1000) ** 2 if o.distance_std_dev_mm else 1.0,
            "distance_m": o.distance_mm / 1000,
            "distance_std_dev_m": (o.distance_std_dev_mm / 1000) if o.distance_std_dev_mm else None,
            "observed_at": o.observed_at,
            "scan_session_id": str(o.scan_session_id),
        }
        for o in qs.select_related("scan_session")
    ]


def within_area(points, area):
    """Whether a device's estimated position falls inside the focus area (scans/area.py).

    Deliberately judged on the *centroid*, not on whether any single reading
    lands inside: the circle asks "which devices are in this area", and a
    device heard once from across the street belongs to where it actually is,
    not to wherever the phone happened to be standing.

    An empty point list is unplaceable, so it's excluded. In practice this
    can't happen from the coverage endpoints — they drop observations without
    a scan-session position long before grouping — but the guard keeps
    weighted_centroid from dividing by an empty set if a future caller is
    less careful.
    """
    if not points:
        return False
    lat, lng = weighted_centroid(points)
    return area.contains(lat, lng)


def apply_area_filter(results, area):
    """Narrows grouped per-device coverage results to the focus circle.

    Runs after grouping and after the time filter, never before:
    weighted_centroid normalises against the device's own min/max signal, so
    computing it from a spatially pre-filtered subset would shift the centre
    and admit devices that don't belong. That also means the circle narrows
    *devices*, not the underlying observation scan — it doesn't relieve
    COVERAGE_OBSERVATION_CAP. Narrowing the time window is what does that.

    A kept device keeps *all* its points, including ones outside the circle:
    the filter selects which devices to report on, and a device's coverage is
    its coverage.
    """
    if area is None:
        return results
    return [entry for entry in results if within_area(entry["points"], area)]


def area_device_ids(request, obs_model, id_field, weight_field, area, session_limit=None):
    """Ids of the devices whose estimated position falls inside the focus
    circle — the device *list* endpoints' equivalent of apply_area_filter.

    Deliberately reuses within_area/weighted_centroid rather than
    approximating, so a list page and the map agree about which devices are in
    the circle. Scoped to the same time window the list itself uses, because
    the centroid depends on which readings are in the set (see
    weighted_centroid) — computing it over all time while the page shows an
    hour would place devices somewhere the map never draws them.

    weight_field=None means "unweighted": every reading counts the same, which
    collapses weighted_centroid to a plain mean. That's the honest treatment
    for LAN devices, which carry no signal strength at all.
    """
    if area is None:
        return None
    since, until = parse_window(request)
    obs_qs = obs_model.objects.select_related("scan_session")
    if since:
        obs_qs = obs_qs.filter(observed_at__gte=since)
    if until:
        obs_qs = obs_qs.filter(observed_at__lte=until)
    if session_limit:
        obs_qs = obs_qs.filter(scan_session_id__in=recent_session_ids(session_limit, area=area))
    obs_qs = obs_qs.exclude(scan_session__latitude__isnull=True).exclude(scan_session__longitude__isnull=True)

    groups = {}
    for obs in obs_qs.iterator():
        weight = 0 if weight_field is None else getattr(obs, weight_field)
        if weight is None:
            # A reading with no signal strength can't be weighted; dropping it
            # beats guessing a value that would drag the centroid.
            continue
        groups.setdefault(getattr(obs, id_field), []).append(
            {"lat": obs.scan_session.latitude, "lng": obs.scan_session.longitude, "weight": weight}
        )
    return {key for key, points in groups.items() if within_area(points, area)}


def parse_window(request):
    """The observation time window as raw (since, until) strings, either of
    which may be None. Django parses the ISO strings itself at filter time.

    `until` exists so a report can cover an exact interval — "Tuesday 14:00 to
    16:00" — rather than only ever "the last N minutes up to now", which is all
    the sliders can express and which makes a report impossible to reproduce
    tomorrow.
    """
    return request.query_params.get("since"), request.query_params.get("until")


def apply_active_window(qs, request):
    """Narrows a device-list queryset (AccessPoint/CellTower/BLEDevice/
    LANDevice) to those last seen within [active_since, active_until].

    A device list has no `observed_at` of its own to filter on — only the
    aggregate's `last_seen_at` — hence the separate `active_*` param names
    from `parse_window`'s `since`/`until`, which bound individual
    observations. `active_until` exists for the same reason `until` does:
    without it, "Date range" mode only ever closes the *start* of the
    window, and a device last seen after the requested range still shows up
    in a list that's supposed to be capped at `active_until`.
    """
    active_since = request.query_params.get("active_since")
    if active_since:
        qs = qs.filter(last_seen_at__gte=active_since)
    active_until = request.query_params.get("active_until")
    if active_until:
        qs = qs.filter(last_seen_at__lte=active_until)
    return qs


COLUMN_SEARCH = re.compile(r"^([a-zA-Z0-9_]+)\s*=\s*(.+)$")


def apply_search(qs, request, fields, distinct=False):
    """Server-side counterpart to the frontend's searchFilter.ts::filterBySearch,
    so a search box backed by real pagination can find a match anywhere in the
    table, not just on whichever page happens to be loaded — the exact shape of
    the "missing Venus" bug (see LimitablePageNumberPagination), just one layer
    up: raising the page-size cap doesn't help if the match is on page 23 of a
    search that only ever looks at page 1.

    `fields` maps a display-ish key to the ORM lookup path used to search it.
    The key doesn't need to match the path exactly (mirrors the frontend's
    "any property whose name *contains* the typed key" contract) — e.g.
    {"channel": "latest_channel"} lets `channel=6` find `latest_channel`.

    Free text (no "="): OR-icontains across every field. "key=value": only
    fields whose key contains "key" are searched, and matched with `iexact`
    (exact, not substring) — same fuzzy-vs-precise split as the frontend.

    `distinct=True` for field sets that traverse a to-many relation (e.g.
    ScanSession's search reaches into related observations) — a plain
    per-model field set never needs it.
    """
    raw = request.query_params.get("q", "").strip()
    if not raw:
        return qs
    match = COLUMN_SEARCH.match(raw)
    if match:
        key, value = match.groups()
        key = key.strip().lower()
        value = value.strip()
        paths = [path for name, path in fields.items() if key in name.lower()]
        if not paths:
            return qs.none()
        q = Q()
        for path in paths:
            q |= Q(**{f"{path}__iexact": value})
        qs = qs.filter(q)
    else:
        q = Q()
        for path in fields.values():
            q |= Q(**{f"{path}__icontains": raw})
        qs = qs.filter(q)
    return qs.distinct() if distinct else qs


def parse_session_limit(request):
    return positive_int(request.query_params.get("session_limit"))


def parse_observation_limit(request, default=200):
    return positive_int(request.query_params.get("limit"), default=default, maximum=MAX_OBSERVATION_LIMIT)


def capped_take(queryset, cap):
    """Materializes at most `cap` rows, plus whether more of them matched.

    Fetches one row past the cap rather than running a separate COUNT(*) —
    the caller only needs "was anything left out", and counting the full
    unbounded match set is the expensive half of that question.
    """
    rows = list(queryset[: cap + 1])
    return rows[:cap], len(rows) > cap


def capped_response(results, truncated, cap):
    """Envelope for the grouped coverage/heatmap payloads.

    These used to be bare JSON arrays, silently sliced at `cap`, which made
    an incomplete answer indistinguishable from a complete one — the map
    just quietly left APs out, in a UI whose entire job is showing you what
    was there. `truncated` is what lets the PWA say so (see HeatmapPage /
    SSIDGroupPage) instead of the operator finding out by noticing something
    missing. Don't flatten this back to a bare list.

    Deliberately carries no "devices the area filter couldn't place" count:
    every coverage query already excludes observations whose scan session has
    no latitude/longitude, so such a device never reaches the area filter and
    the number would be structurally zero. Reporting a field that can only
    ever say 0 is worse than omitting it — it reads as a guarantee.
    """
    return {"results": results, "truncated": truncated, "observation_limit": cap}


def filter_by_signature(qs, request):
    """`?signature=<fleet id>` / `?signature_class=<KIND>` on a model with a
    `signature_ids` list (BLEDevice, AccessPoint)."""
    signature = request.query_params.get("signature")
    if signature:
        qs = qs.filter(signature_ids__contains=[signature])
    signature_class = request.query_params.get("signature_class")
    if signature_class:
        fleet_ids = [fleet.id for fleet in load_catalog().fleets if fleet.kind == signature_class]
        match = Q()
        for fleet_id in fleet_ids:
            match |= Q(signature_ids__contains=[fleet_id])
        qs = qs.filter(match) if fleet_ids else qs.none()
    return qs


class AccessPointViewSet(mixins.ListModelMixin, mixins.RetrieveModelMixin, viewsets.GenericViewSet):
    serializer_class = AccessPointSerializer
    lookup_field = "bssid"
    lookup_value_regex = "[^/]+"

    def get_serializer_class(self):
        return AccessPointDetailSerializer if self.action == "retrieve" else AccessPointSerializer

    def get_queryset(self):
        qs = AccessPoint.objects.all().order_by("-last_seen_at")
        qs = filter_by_signature(qs, self.request)
        ssid = self.request.query_params.get("ssid")
        if ssid:
            qs = qs.filter(ssid__icontains=ssid)
        ssid_exact = self.request.query_params.get("ssid_exact")
        if ssid_exact:
            # Precise match for grouping BSSIDs that share one SSID (e.g. a
            # mesh network) — plain `ssid` is intentionally fuzzy/icontains
            # for search-like use, which would also pull in similarly-named
            # unrelated networks.
            qs = qs.filter(ssid=ssid_exact)
        qs = apply_active_window(qs, self.request)
        session_limit = parse_session_limit(self.request)
        if session_limit:
            qs = qs.filter(observations__scan_session_id__in=recent_session_ids(session_limit, area=parse_area(self.request))).distinct()
        band = self.request.query_params.get("band")
        if band:
            qs = qs.filter(observations__band=band).distinct()
        security = self.request.query_params.get("security")
        if security:
            qs = qs.filter(observations__security_type=security).distinct()
        area = parse_area(self.request)
        if area:
            ids = area_device_ids(self.request, WiFiObservation, "access_point_id", "rssi", area, session_limit)
            qs = qs.filter(pk__in=ids)
        qs = apply_search(qs, self.request, {"bssid": "bssid", "ssid": "ssid", "vendor_oui": "vendor_oui"})
        return qs

    @action(detail=True, methods=["get"], url_path="wifi-observations")
    def wifi_observations(self, request, bssid=None):
        access_point = self.get_object()
        obs = access_point.observations.select_related("scan_session").order_by("-observed_at")
        since, until = parse_window(request)
        if since:
            obs = obs.filter(observed_at__gte=since)
        if until:
            obs = obs.filter(observed_at__lte=until)
        session_limit = parse_session_limit(request)
        if session_limit:
            obs = obs.filter(scan_session_id__in=recent_session_ids(session_limit, area=parse_area(request)))
        limit = parse_observation_limit(request)
        return Response(WiFiObservationSerializer(obs[:limit], many=True).data)

    @action(detail=True, methods=["get"])
    def position(self, request, bssid=None):
        """This AP's estimated position under the caller's chosen estimator
        (`?estimator=`), or every estimator side by side (`?compare=1`) —
        see scans/estimators.py. Honours the same time/scan window as every
        other read endpoint."""
        access_point = self.get_object()
        return device_position_response(
            request,
            identifier=access_point.bssid,
            points=observation_points(access_point.observations.all(), request, "rssi"),
            radio_kind="wifi",
            ftm_points=ftm_points_for(access_point, request),
            extra={"ssid": access_point.ssid},
        )

    @action(detail=True, methods=["get"], url_path="ftm-position")
    def ftm_position(self, request, bssid=None):
        """AP position estimate from this BSSID's Wi-Fi RTT/FTM ranging
        samples — see scans/localization.py (V4 of ap-localization-design.md).
        Needs at least 3 successful readings from 2+ distinct observer
        positions to be non-degenerate (2 unknowns: lat, lng); below that,
        responds with `available: false` rather than a solve on insufficient
        data pretending to be a real answer."""
        access_point = self.get_object()
        points = ftm_points_for(access_point, request)
        distinct_sessions = {p["scan_session_id"] for p in points}
        if len(points) < 3 or len(distinct_sessions) < 2:
            return Response({
                "bssid": bssid,
                "available": False,
                "sample_count": len(points),
                "reason": "Needs at least 3 successful FTM readings from 2+ observer positions.",
            })

        solved = solve_ap_position(points)
        # V5/V6 ride along with the position rather than living on their own
        # endpoints: all three are derived from the exact same solve, and
        # splitting them would mean re-running it (and risking the UI showing
        # an ellipse computed around a different estimate than the dot).
        ellipse = position_covariance(solved["lat"], solved["lng"], points)
        include_grid = request.query_params.get("include_grid") == "1"
        probability_grid = (
            position_probability_grid(
                points,
                solved["lat"],
                solved["lng"],
                span_m=parse_float(request.query_params.get("grid_span_m"), 120.0),
                steps=positive_int(request.query_params.get("grid_steps"), 25, maximum=61),
            )
            if include_grid
            else None
        )
        return Response({
            "bssid": bssid,
            "ssid": access_point.ssid,
            "available": True,
            "sample_count": len(points),
            "distinct_position_count": len(distinct_sessions),
            **solved,
            "uncertainty": ellipse,
            "probability_grid": probability_grid,
            "next_measurements": suggest_next_positions(
                solved["lat"], solved["lng"], [(p["lat"], p["lng"]) for p in points]
            ),
            # Worst-disagreeing reading first — a multipath outlier should be
            # the first row you see, not something to hunt for.
            "observations": sorted(
                (
                    {
                        "scan_session_id": p["scan_session_id"],
                        "lat": p["lat"],
                        "lng": p["lng"],
                        "distance_m": p["distance_m"],
                        "distance_std_dev_m": p["distance_std_dev_m"],
                        "observed_at": p["observed_at"],
                        "weight": p["weight"],
                        "residual_m": haversine_m(solved["lat"], solved["lng"], p["lat"], p["lng"]) - p["distance_m"],
                    }
                    for p in points
                ),
                key=lambda row: abs(row["residual_m"]),
                reverse=True,
            ),
        })

    @action(detail=False, methods=["get"])
    def coverage(self, request):
        """Per-AP list of distinct observed locations, each with a weight
        (average RSSI seen from that spot) — feeds the heatmap page's
        coverage-ellipse rendering (frontend/src/geo.ts's
        weightedCoverageEllipse/classifyCoverage). Devices with <3 distinct
        points are still returned (the frontend treats those as "too sparse
        for a shape" and falls back to plain points) rather than filtered
        out here — the frontend also needs the sub-3-point case to decide
        that, not just silence."""
        since, until = parse_window(request)
        ssid_exact = request.query_params.get("ssid_exact")
        qs = (
            WiFiObservation.objects.select_related("access_point", "scan_session")
            .exclude(scan_session__latitude__isnull=True)
            .exclude(scan_session__longitude__isnull=True)
        )
        if since:
            qs = qs.filter(observed_at__gte=since)
        if until:
            qs = qs.filter(observed_at__lte=until)
        session_limit = parse_session_limit(request)
        if session_limit:
            qs = qs.filter(scan_session_id__in=recent_session_ids(session_limit, area=parse_area(request)))
        if ssid_exact:
            # Powers the SSID-group page — one coverage polygon per BSSID
            # sharing an SSID (e.g. a mesh network), not the whole dataset.
            qs = qs.filter(access_point__ssid=ssid_exact)

        observations, truncated = capped_take(qs, COVERAGE_OBSERVATION_CAP)
        by_ap = {}
        for obs in observations:
            entry = by_ap.get(obs.access_point_id)
            if entry is None:
                catalog = load_catalog()
                entry = by_ap[obs.access_point_id] = {
                    "bssid": obs.access_point.bssid,
                    "ssid": obs.access_point.ssid,
                    "detail_path": f"/networks/{quote(obs.access_point_id, safe='')}",
                    # The AP's catalog signature names (an id from an older
                    # catalog shows as itself).
                    "signature_names": [
                        (catalog.get(fleet_id).name if catalog.get(fleet_id) else fleet_id)
                        for fleet_id in obs.access_point.signature_ids or ()
                    ],
                    "points": {},
                }
            key = (round(obs.scan_session.latitude, 5), round(obs.scan_session.longitude, 5))
            entry["points"].setdefault(key, []).append(
                {
                    "rssi": obs.rssi,
                    "scan_session_id": obs.scan_session_id,
                    "accuracy": obs.scan_session.location_accuracy_meters,
                    "observed_at": obs.observed_at,
                }
            )

        results = [
            {
                "bssid": v["bssid"],
                "ssid": v["ssid"],
                "detail_path": v["detail_path"],
                "signature_names": v["signature_names"],
                "points": [
                    {
                        "lat": lat,
                        "lng": lng,
                        "weight": sum(p["rssi"] for p in samples) / len(samples),
                        "observed_at": samples[0]["observed_at"],
                        # One representative reading's scan/accuracy per
                        # bucket (buckets are rounded to ~1m, so this is
                        # almost always exactly one scan anyway) — lets the
                        # frontend's "show device location pins" toggle mark
                        # where the phone stood, same as the per-entity
                        # detail pages.
                        "scan_session_id": samples[0]["scan_session_id"],
                        "accuracy_meters": samples[0]["accuracy"],
                    }
                    for (lat, lng), samples in v["points"].items()
                ],
            }
            for v in by_ap.values()
        ]
        results = apply_area_filter(results, parse_area(request))
        results = annotate_estimated_positions(results, request, "wifi")
        return Response(capped_response(results, truncated, COVERAGE_OBSERVATION_CAP))

    @action(detail=False, methods=["get"], url_path="mesh-groups")
    def mesh_groups(self, request):
        """BSSIDs grouped into probable physical access points — V8 of
        ap-localization-design.md (`BSSID != physical AP`).

        Each BSSID's position is the signal-weighted centroid of where it was
        heard (the same weighted_centroid() the coverage map already draws
        its centre dot from), so this works on ordinary RSSI scan data and
        doesn't require FTM ranging. Results are hypotheses with a confidence
        and their supporting evidence, never a hard claim — see
        cluster_ap_hypotheses().
        """
        since, until = parse_window(request)
        qs = (
            WiFiObservation.objects.select_related("access_point", "scan_session")
            .exclude(scan_session__latitude__isnull=True)
            .exclude(scan_session__longitude__isnull=True)
        )
        if since:
            qs = qs.filter(observed_at__gte=since)
        if until:
            qs = qs.filter(observed_at__lte=until)
        session_limit = parse_session_limit(request)
        if session_limit:
            qs = qs.filter(scan_session_id__in=recent_session_ids(session_limit, area=parse_area(request)))
        ssid_exact = request.query_params.get("ssid_exact")
        if ssid_exact:
            qs = qs.filter(access_point__ssid=ssid_exact)

        observations, truncated = capped_take(qs, COVERAGE_OBSERVATION_CAP)
        by_ap = {}
        for obs in observations:
            entry = by_ap.setdefault(
                obs.access_point_id,
                {
                    "bssid": obs.access_point.bssid,
                    "ssid": obs.access_point.ssid,
                    "vendor_oui": obs.access_point.vendor_oui,
                    "band": obs.band,
                    "points": [],
                },
            )
            entry["points"].append(
                {"lat": obs.scan_session.latitude, "lng": obs.scan_session.longitude, "weight": obs.rssi}
            )

        candidates = []
        for entry in by_ap.values():
            lat, lng = weighted_centroid(entry["points"])
            candidates.append({
                "bssid": entry["bssid"],
                "ssid": entry["ssid"],
                "vendor_oui": entry["vendor_oui"],
                "band": entry["band"],
                "lat": lat,
                "lng": lng,
            })

        radius_m = parse_float(request.query_params.get("radius_m"), 15.0)
        results = cluster_ap_hypotheses(candidates, radius_m=radius_m)
        return Response(capped_response(results, truncated, COVERAGE_OBSERVATION_CAP))


class ScanSessionViewSet(
    mixins.ListModelMixin, mixins.RetrieveModelMixin, mixins.CreateModelMixin, viewsets.GenericViewSet
):
    queryset = ScanSession.objects.all()
    serializer_class = ScanSessionSerializer

    def initialize_request(self, request, *args, **kwargs):
        # self.action isn't set yet at the point get_authenticators() runs —
        # APIView.initialize_request() (called via super() below) builds the
        # Request object (and, as part of that, calls get_authenticators())
        # *before* ViewSetMixin.initialize_request() goes on to set
        # self.action from the resolved method. self.action_map, though, is
        # set earlier still (in ViewSetMixin.as_view(), before dispatch()
        # even runs) and already maps this request's HTTP method to the
        # action name DRF resolved from the URL — same information,
        # available sooner. Don't go back to branching on the raw HTTP verb
        # alone (`method == "POST"`): that broke the instant a second
        # POST-based custom action (resolve-addresses) was added, since
        # every POST got treated as "create".
        self._resolved_action = self.action_map.get(request.method.lower())
        return super().initialize_request(request, *args, **kwargs)

    def get_authenticators(self):
        # Ingest (create) is machine-to-machine via a per-device sensor
        # token; every other action (including the bulk-delete/
        # resolve-addresses actions below — human PWA housekeeping actions,
        # not something a sensor should ever call) uses the same admin
        # session used for /admin/. See MEMORY.md.
        #
        # The per-session observation detail actions (wifi-observations,
        # cell-observations, etc.) and the list endpoint also accept a
        # sensor token — the Android app fetches the latest session on a
        # fresh install to backfill the Dashboard (see LatestScanFetcher).
        # Session auth still works for the PWA; both are tried in order.
        action = getattr(self, "_resolved_action", None)
        if action == "create":
            return [SensorTokenAuthentication()]
        if action in ("list", "retrieve", "wifi_observations", "cell_observations",
                       "ble_observations", "satellite_observations", "lan_observations"):
            return [SessionAuthentication(), SensorTokenAuthentication()]
        return [SessionAuthentication()]

    def get_permissions(self):
        return [IsAuthenticated()]

    def get_queryset(self):
        qs = super().get_queryset()
        # A sensor token (from the Android app) is scoped to its own sessions
        # only — the app backfills its Dashboard from its own latest scan, not
        # every device's. Session auth (the PWA) sees everything.
        # SensorTokenAuthentication returns a Sensor (not a Django User), so
        # checking the model type is the cleanest way to tell the two apart.
        from sensors.models import Sensor
        if isinstance(self.request.user, Sensor):
            qs = qs.filter(sensor=self.request.user)
        sensor_id = self.request.query_params.get("sensor")
        if sensor_id:
            qs = qs.filter(sensor_id=sensor_id)
        # The same window and focus area as every other list (user,
        # 2026-11-11: filter the scans page by date/time and location, and
        # combine the two). `since`/`until` bound the scan's start, not its
        # observations, because this list is about scans as units; the area
        # judges the scan's own corrected position. Applied before the
        # annotations below so no to-many join sneaks under a Count.
        since, until = parse_window(self.request)
        if since:
            qs = qs.filter(started_at__gte=since)
        if until:
            qs = qs.filter(started_at__lte=until)
        area = parse_area(self.request)
        session_limit = parse_session_limit(self.request)
        if session_limit:
            qs = qs.filter(pk__in=recent_session_ids(session_limit, area=area))
        elif area:
            qs = qs.filter(pk__in=session_ids_in_area(qs.order_by(), area))
        # Annotated once here (list/retrieve) rather than one .count() query
        # per radio type per row — see ScanSessionSerializer._count().
        #
        # Correlated subqueries, NOT Count(..., distinct=True) annotations.
        # Five aggregates over five *different* to-many relations in one
        # query makes the database join all five together first — a
        # five-way cartesian product per session — and only then dedupe it
        # with DISTINCT. Measured on this deployment (1418 sessions, 70k
        # WiFi rows): one such Count took 0.07s, all five took 6.33s, and
        # 8.0s with the prefetches below. That was the whole reason the
        # floor-plan page's "which scan are you placing?" dropdown sat
        # empty for ~9 seconds on load and again after every single
        # placement — long enough to read as "it's broken, I can't add any
        # more points".
        #
        # Each subquery touches exactly one table and hits the
        # scan_session_id index, so the cost is linear rather than
        # multiplicative. Coalesce because a correlated aggregate subquery
        # returns NULL, not 0, for a session with no rows in that table.
        def observation_count(relation):
            field = ScanSession._meta.get_field(relation)
            return Coalesce(
                Subquery(
                    field.related_model.objects
                    .filter(scan_session=OuterRef("pk"))
                    .order_by()
                    .values("scan_session")
                    .annotate(n=Count("pk"))
                    .values("n")[:1],
                    output_field=IntegerField(),
                ),
                0,
            )

        # Ordering is re-applied explicitly: this used to be an aggregate
        # annotate(), which silently drops Meta.ordering (it doesn't
        # survive a GROUP BY) and made pagination nondeterministic. The
        # subqueries above no longer group, but keeping the explicit
        # order_by costs nothing and doesn't re-depend on that subtlety.
        qs = qs.annotate(
            wifi_count_annotated=observation_count("wifi_observations"),
            cell_count_annotated=observation_count("cell_observations"),
            ble_count_annotated=observation_count("ble_observations"),
            satellite_count_annotated=observation_count("satellite_observations"),
            lan_count_annotated=observation_count("lan_observations"),
        ).order_by("-started_at")
        # Feeds ScanSessionSerializer.get_identifiers_summary() without a
        # query per session per radio type.
        qs = qs.prefetch_related(
            Prefetch("wifi_observations", queryset=WiFiObservation.objects.select_related("access_point")),
            "ble_observations",
            "lan_observations",
        )
        # Reaches into the same related fields ScanSessionSerializer.
        # get_identifiers_summary() reads (plus sensor name and the carrier
        # seen on this session) — so the box that shows "SSID, device name,
        # hostname…" per row can actually find a match by one, not just
        # sensor_name/address.
        #
        # Run against a fresh, unannotated queryset and narrow by id rather
        # than filtering `qs` directly: `qs` already carries Count(...)
        # annotations + an implicit GROUP BY, and every search path here
        # crosses a *different* to-many relation than the one each Count
        # aggregates — filtering it in place would restrict the joined rows
        # each Count sees too, silently undercounting wifi_count/ble_count/
        # etc. for a session that only matched via one radio type.
        if self.request.query_params.get("q", "").strip():
            match_ids = apply_search(
                ScanSession.objects.only("pk"),
                self.request,
                {
                    "sensor_name": "sensor__name",
                    "ssid": "wifi_observations__access_point__ssid",
                    "bssid": "wifi_observations__access_point__bssid",
                    "device_name": "ble_observations__device_name",
                    "ble_mac": "ble_observations__ble_mac",
                    "hostname": "lan_observations__hostname",
                    "ip_address": "lan_observations__ip_address",
                    "carrier_name": "cell_observations__carrier_name",
                },
                distinct=True,
            ).values_list("pk", flat=True)
            qs = qs.filter(pk__in=list(match_ids))
        return qs

    def get_serializer_context(self):
        context = super().get_serializer_context()
        # One query for the whole (typically small) geocode cache table,
        # rather than one per row — see ScanSessionSerializer.get_resolved_address.
        context["geocode_cache"] = {
            (loc.lat_rounded, loc.lng_rounded): loc.address for loc in GeocodedLocation.objects.all()
        }
        return context

    def create(self, request, *args, **kwargs):
        serializer = ScanSessionIngestSerializer(data=request.data, context={"sensor": request.user})
        serializer.is_valid(raise_exception=True)
        session = serializer.save()
        return Response(ScanSessionSerializer(session).data, status=status.HTTP_201_CREATED)

    @action(detail=False, methods=["get"])
    def export(self, request):
        """Scan sessions as a JSON array in the *ingest* schema.

        Deliberately the same shape `POST /scan-sessions/` accepts, so import
        is a replay through the existing serializer rather than a second,
        divergent write path — which also means re-importing a file is
        idempotent (see ScanSessionIngestSerializer.create) instead of
        doubling the data.

        Filters mirror the rest of the API (`since`/`until`/`session_limit`/
        `ssid_exact`/`area_*`). Truncation is reported loudly: a backup that
        silently stops short while looking complete is the worst failure this
        endpoint could have.
        """
        qs = ScanSession.objects.all().prefetch_related(
            "wifi_observations__access_point", "ftm_observations__access_point",
            "cell_observations", "ble_observations", "satellite_observations", "lan_observations",
        ).select_related("sensor")

        since, until = parse_window(request)
        if since:
            qs = qs.filter(started_at__gte=since)
        if until:
            qs = qs.filter(started_at__lte=until)
        ssid_exact = request.query_params.get("ssid_exact")
        if ssid_exact:
            qs = qs.filter(wifi_observations__access_point__ssid=ssid_exact).distinct()
        area = parse_area(request)
        if area:
            qs = qs.exclude(latitude__isnull=True).exclude(longitude__isnull=True)
        # "Last N scans" (inside the area, when one is set) — the Manage scans
        # page exports exactly what it shows (user, 2026-11-11).
        session_limit = parse_session_limit(request)
        if session_limit:
            qs = qs.filter(pk__in=recent_session_ids(session_limit, area=area))

        limit = positive_int(request.query_params.get("limit"), EXPORT_SESSION_CAP, maximum=EXPORT_SESSION_CAP)
        sessions = list(qs.order_by("-started_at")[: limit + 1])
        truncated = len(sessions) > limit
        sessions = sessions[:limit]

        payload = []
        for session in sessions:
            if area and not area.contains(session.latitude, session.longitude):
                continue
            payload.append(export_session_payload(session))

        return Response({
            "sessions": payload,
            "session_count": len(payload),
            "truncated": truncated,
            "session_limit": limit,
            "exported_at": timezone.now().isoformat(),
        })

    @action(detail=False, methods=["post"])
    def import_sessions(self, request, *args, **kwargs):
        """Replays exported sessions through the normal ingest serializer.

        Sessions already present (matched on `client_scan_id`) are skipped,
        not duplicated — that's the same idempotency the phone's retry relies
        on, so importing the same file twice is safe and the second run
        reports every session as skipped rather than erroring.

        Each session's original sensor is matched by name and created if
        absent, so provenance survives a round trip. `Sensor.name` isn't
        unique, so this takes the first match rather than pretending it is.
        """
        sessions = request.data.get("sessions")
        if not isinstance(sessions, list):
            return Response({"detail": "Expected a JSON object with a 'sessions' array."}, status=400)

        created, skipped, failed = 0, 0, []
        # Oldest first: ingest judges a position against the sensor's
        # previous scan (scans/location_quality.py), and export is newest
        # first. Malformed timestamps sort last and fail validation below.
        def started(entry):
            return str(entry.get("started_at") or "~") if isinstance(entry, dict) else "~"

        sessions = sorted(sessions, key=started)
        for entry in sessions:
            client_scan_id = entry.get("client_scan_id")
            if client_scan_id and ScanSession.objects.filter(client_scan_id=client_scan_id).exists():
                skipped += 1
                continue
            sensor_name = entry.pop("sensor_name", None) or "Imported"
            sensor = Sensor.objects.filter(name=sensor_name).first() or Sensor.objects.create(name=sensor_name)
            serializer = ScanSessionIngestSerializer(data=entry, context={"sensor": sensor})
            if not serializer.is_valid():
                failed.append({"client_scan_id": client_scan_id, "errors": serializer.errors})
                continue
            serializer.save()
            created += 1

        # Re-run the position cleanup so imported history reaches the same
        # corrected state it had when exported (idempotent, originals kept).
        if created:
            clean_session_locations()

        return Response({
            "created": created,
            "skipped": skipped,
            "failed": failed,
            "failed_count": len(failed),
        })

    @action(detail=False, methods=["post"], url_path="resolve-addresses")
    def resolve_addresses(self, request):
        """Reverse-geocodes up to `limit` distinct not-yet-cached scan
        locations (see scans/geocoding.py) — an explicit, human-triggered
        action rather than something that runs automatically, since it
        makes live calls to a third-party service."""
        # Bounded and type-safe: a bare int() here 500'd on any non-numeric
        # body value, and each resolution sleeps ~1.1s to respect Nominatim's
        # rate limit, so an unbounded count would tie up one of gunicorn's
        # three sync workers for as long as the caller asked for.
        limit = positive_int(request.data.get("limit"), default=20, maximum=MAX_GEOCODE_LIMIT)
        sessions = self.filter_queryset(self.get_queryset()).exclude(latitude__isnull=True).exclude(
            longitude__isnull=True
        )
        resolved = resolve_missing_addresses(sessions, limit=limit)
        return Response({"resolved": resolved})

    @action(detail=False, methods=["delete"], url_path="bulk-delete")
    def bulk_delete(self, request):
        """Deletes a batch of scan sessions by id, cascading to every radio
        observation FK'd to them (see on_delete=CASCADE on each Observation
        model). Uses DELETE rather than POST so it doesn't trip the
        create-vs-everything-else branch in get_authenticators() above,
        which would otherwise route it to sensor-token auth instead of the
        human PWA session — this is a human-triggered housekeeping action,
        not something a sensor should ever call."""
        ids = request.data.get("ids")
        if not isinstance(ids, list) or not ids:
            return Response({"detail": "ids must be a non-empty list"}, status=status.HTTP_400_BAD_REQUEST)
        deleted_count, _ = ScanSession.objects.filter(id__in=ids).delete()
        return Response({"deleted": deleted_count})

    @action(detail=True, methods=["get"], url_path="wifi-observations")
    def wifi_observations(self, request, pk=None):
        session = self.get_object()
        return Response(WiFiObservationSerializer(session.wifi_observations.order_by("-observed_at"), many=True).data)

    @action(detail=True, methods=["get"], url_path="cell-observations")
    def cell_observations(self, request, pk=None):
        session = self.get_object()
        return Response(CellObservationSerializer(session.cell_observations.order_by("-observed_at"), many=True).data)

    @action(detail=True, methods=["get"], url_path="ble-observations")
    def ble_observations(self, request, pk=None):
        session = self.get_object()
        return Response(BLEObservationSerializer(session.ble_observations.order_by("-observed_at"), many=True).data)

    @action(detail=True, methods=["get"], url_path="satellite-observations")
    def satellite_observations(self, request, pk=None):
        session = self.get_object()
        return Response(
            SatelliteObservationSerializer(session.satellite_observations.order_by("-observed_at"), many=True).data
        )

    @action(detail=True, methods=["get"], url_path="lan-observations")
    def lan_observations(self, request, pk=None):
        session = self.get_object()
        return Response(LANObservationSerializer(session.lan_observations.order_by("-observed_at"), many=True).data)


class CellTowerViewSet(mixins.ListModelMixin, mixins.RetrieveModelMixin, viewsets.GenericViewSet):
    serializer_class = CellTowerSerializer
    lookup_field = "tower_key"
    lookup_value_regex = "[^/]+"

    def get_queryset(self):
        qs = CellTower.objects.all().order_by("-last_seen_at")
        qs = apply_active_window(qs, self.request)
        session_limit = parse_session_limit(self.request)
        if session_limit:
            qs = qs.filter(observations__scan_session_id__in=recent_session_ids(session_limit, area=parse_area(self.request))).distinct()
        area = parse_area(self.request)
        if area:
            ids = area_device_ids(self.request, CellObservation, "cell_tower_id", "signal_dbm", area, session_limit)
            qs = qs.filter(pk__in=ids)
        qs = apply_search(
            qs,
            self.request,
            {
                "tower_key": "tower_key",
                "mcc": "mcc",
                "mnc": "mnc",
                "tac_or_lac": "tac_or_lac",
                "cell_id": "cell_id",
                "carrier_name": "carrier_name",
                "radio_type": "radio_type",
            },
        )
        return qs

    @action(detail=True, methods=["get"])
    def position(self, request, tower_key=None):
        """This tower's estimated position — see AccessPointViewSet.position.
        FTM has no cellular equivalent, so `ftm_multilateration` reports
        itself unavailable here and falls back to the centroid."""
        tower = self.get_object()
        return device_position_response(
            request,
            identifier=tower.tower_key,
            points=observation_points(tower.observations.all(), request, "signal_dbm"),
            radio_kind="cellular",
            extra={"label": tower.carrier_name or tower.tower_key},
        )

    @action(detail=True, methods=["get"], url_path="cell-observations")
    def cell_observations(self, request, tower_key=None):
        tower = self.get_object()
        obs = tower.observations.select_related("scan_session").order_by("-observed_at")
        since, until = parse_window(request)
        if since:
            obs = obs.filter(observed_at__gte=since)
        if until:
            obs = obs.filter(observed_at__lte=until)
        session_limit = parse_session_limit(request)
        if session_limit:
            obs = obs.filter(scan_session_id__in=recent_session_ids(session_limit, area=parse_area(request)))
        limit = parse_observation_limit(request)
        return Response(CellObservationSerializer(obs[:limit], many=True).data)

    @action(detail=False, methods=["get"])
    def coverage(self, request):
        """Per-tower list of distinct observed locations with weight
        (average signal_dbm) — same shape/purpose as AccessPointViewSet's
        coverage action. Cell towers have no distance cap on the frontend
        (a sector legitimately covers km-scale areas), so every tower with
        >=3 points ends up drawn as a shape regardless of spread."""
        since, until = parse_window(request)
        qs = (
            CellObservation.objects.select_related("cell_tower", "scan_session")
            .exclude(scan_session__latitude__isnull=True)
            .exclude(scan_session__longitude__isnull=True)
            .exclude(cell_tower__isnull=True)
        )
        if since:
            qs = qs.filter(observed_at__gte=since)
        if until:
            qs = qs.filter(observed_at__lte=until)
        session_limit = parse_session_limit(request)
        if session_limit:
            qs = qs.filter(scan_session_id__in=recent_session_ids(session_limit, area=parse_area(request)))

        observations, truncated = capped_take(qs, COVERAGE_OBSERVATION_CAP)
        by_tower = {}
        for obs in observations:
            entry = by_tower.setdefault(
                obs.cell_tower_id,
                {
                    "key": obs.cell_tower_id,
                    "label": obs.cell_tower.carrier_name or obs.cell_tower_id,
                    "detail_path": f"/cellular/{quote(obs.cell_tower_id, safe='')}",
                    "points": {},
                },
            )
            key = (round(obs.scan_session.latitude, 5), round(obs.scan_session.longitude, 5))
            entry["points"].setdefault(key, []).append(
                {
                    "signal": obs.signal_dbm or 0,
                    "scan_session_id": obs.scan_session_id,
                    "accuracy": obs.scan_session.location_accuracy_meters,
                    "observed_at": obs.observed_at,
                }
            )

        results = [
            {
                "key": v["key"],
                "label": v["label"],
                "detail_path": v["detail_path"],
                "points": [
                    {
                        "lat": lat,
                        "lng": lng,
                        "weight": sum(p["signal"] for p in samples) / len(samples),
                        "scan_session_id": samples[0]["scan_session_id"],
                        "accuracy_meters": samples[0]["accuracy"],
                        "observed_at": samples[0]["observed_at"],
                    }
                    for (lat, lng), samples in v["points"].items()
                ],
            }
            for v in by_tower.values()
        ]
        results = apply_area_filter(results, parse_area(request))
        results = annotate_estimated_positions(results, request, "cellular")
        return Response(capped_response(results, truncated, COVERAGE_OBSERVATION_CAP))


class CellObservationViewSet(mixins.ListModelMixin, viewsets.GenericViewSet):
    serializer_class = CellObservationSerializer

    def get_queryset(self):
        qs = CellObservation.objects.all().order_by("-observed_at")
        mcc = self.request.query_params.get("mcc")
        mnc = self.request.query_params.get("mnc")
        since, until = parse_window(self.request)
        # Neighbor-cell readings vastly outnumber serving-cell ones and
        # rarely carry useful signal — filtered server-side (not just
        # client-side) so a fixed page size isn't mostly wasted on rows the
        # UI hides by default.
        if self.request.query_params.get("serving_only") == "true":
            qs = qs.filter(is_serving_cell=True)
        if mcc:
            qs = qs.filter(mcc=mcc)
        if mnc:
            qs = qs.filter(mnc=mnc)
        if since:
            qs = qs.filter(observed_at__gte=since)
        if until:
            qs = qs.filter(observed_at__lte=until)
        return qs


class BLEObservationViewSet(mixins.ListModelMixin, viewsets.GenericViewSet):
    serializer_class = BLEObservationSerializer

    def get_queryset(self):
        qs = BLEObservation.objects.all().order_by("-observed_at")
        device_type = self.request.query_params.get("device_type")
        since, until = parse_window(self.request)
        identifier = self.request.query_params.get("identifier")
        if device_type:
            qs = qs.filter(device_type_guess=device_type)
        if since:
            qs = qs.filter(observed_at__gte=since)
        if until:
            qs = qs.filter(observed_at__lte=until)
        if identifier:
            qs = qs.filter(Q(ble_mac=identifier) | Q(stable_identifier=identifier))
        session_limit = parse_session_limit(self.request)
        if session_limit:
            qs = qs.filter(scan_session_id__in=recent_session_ids(session_limit, area=parse_area(self.request)))
        return qs

    @action(detail=False, methods=["get"])
    def coverage(self, request):
        """Per-device list of distinct observed locations with weight
        (average RSSI) — same shape/purpose as AccessPointViewSet's coverage
        action. Groups directly by identifier here (rather than joining
        through BLEDevice) since that's all this needs and avoids a join;
        BLEDevice.device_key uses the identical `ble_mac or stable_identifier`
        precedence, so the grouping is consistent either way.

        Also reports the most-recently-observed device_type_guess per
        device — the frontend treats HEADPHONES/WEARABLE as inherently
        mobile (worn on a person) regardless of measured sighting spread,
        so it needs this to make that call before even looking at the
        points."""
        since, until = parse_window(request)
        qs = BLEObservation.objects.select_related("scan_session").exclude(
            scan_session__latitude__isnull=True
        ).exclude(scan_session__longitude__isnull=True).order_by("-observed_at")
        if since:
            qs = qs.filter(observed_at__gte=since)
        if until:
            qs = qs.filter(observed_at__lte=until)
        session_limit = parse_session_limit(request)
        if session_limit:
            qs = qs.filter(scan_session_id__in=recent_session_ids(session_limit, area=parse_area(request)))

        observations, truncated = capped_take(qs, COVERAGE_OBSERVATION_CAP)
        catalog = load_catalog()
        by_device = {}
        for obs in observations:
            identifier = obs.ble_mac or obs.stable_identifier
            if not identifier:
                continue
            entry = by_device.setdefault(
                identifier,
                {
                    "key": identifier,
                    "label": obs.device_name or identifier,
                    "detail_path": f"/ble-devices/{quote(identifier, safe='')}",
                    # First entry encountered per device is the latest
                    # (queryset is ordered -observed_at).
                    "device_type_guess": obs.device_type_guess,
                    # Catalog signature names over these readings, latest
                    # first (an id from an older catalog shows as itself).
                    "signature_names": {},
                    "points": {},
                },
            )
            for match in obs.signature_matches or ():
                fleet = catalog.get(match["id"])
                entry["signature_names"].setdefault(fleet.name if fleet else match["id"], None)
            key = (round(obs.scan_session.latitude, 5), round(obs.scan_session.longitude, 5))
            entry["points"].setdefault(key, []).append(
                {
                    "rssi": obs.rssi,
                    "scan_session_id": obs.scan_session_id,
                    "accuracy": obs.scan_session.location_accuracy_meters,
                    "observed_at": obs.observed_at,
                }
            )

        results = [
            {
                "key": v["key"],
                "label": v["label"],
                "detail_path": v["detail_path"],
                "device_type_guess": v["device_type_guess"],
                "signature_names": list(v["signature_names"]),
                "points": [
                    {
                        "lat": lat,
                        "lng": lng,
                        "weight": sum(p["rssi"] for p in samples) / len(samples),
                        "scan_session_id": samples[0]["scan_session_id"],
                        "accuracy_meters": samples[0]["accuracy"],
                        "observed_at": samples[0]["observed_at"],
                    }
                    for (lat, lng), samples in v["points"].items()
                ],
            }
            for v in by_device.values()
        ]
        results = apply_area_filter(results, parse_area(request))
        results = annotate_estimated_positions(results, request, "ble")
        return Response(capped_response(results, truncated, COVERAGE_OBSERVATION_CAP))


class WifiSignatureViewSet(viewsets.ViewSet):
    """The Wi-Fi-capable fleets of the vendored catalog (scans/wifi_signatures.py)."""

    def list(self, request):
        catalog = load_catalog()
        return Response({
            "catalog_version": catalog.version,
            "results": [
                {"id": fleet.id, "name": fleet.name, "kind": fleet.kind}
                for fleet in catalog.fleets
                if is_wifi_capable(fleet)
            ],
        })


class BLESignatureViewSet(viewsets.ViewSet):
    """The BLE-capable fleets of the vendored signature catalog, for the
    PWA's class filter and labels. Wi-Fi-only fleets are left out because
    only BLE is labeled (scans/ble_signatures.py)."""

    def list(self, request):
        catalog = load_catalog()
        return Response({
            "catalog_version": catalog.version,
            "results": [
                {"id": fleet.id, "name": fleet.name, "kind": fleet.kind}
                for fleet in catalog.fleets
                if is_ble_capable(fleet)
            ],
        })


class BLEDeviceViewSet(mixins.ListModelMixin, mixins.RetrieveModelMixin, viewsets.GenericViewSet):
    serializer_class = BLEDeviceSerializer
    lookup_field = "device_key"
    lookup_value_regex = "[^/]+"

    def get_serializer_class(self):
        return BLEDeviceDetailSerializer if self.action == "retrieve" else BLEDeviceSerializer

    def get_queryset(self):
        qs = BLEDevice.objects.all().order_by("-last_seen_at")
        device_type = self.request.query_params.get("device_type")
        if device_type:
            qs = qs.filter(device_type_guess=device_type)
        qs = filter_by_signature(qs, self.request)
        qs = apply_active_window(qs, self.request)
        session_limit = parse_session_limit(self.request)
        if session_limit:
            qs = qs.filter(observations__scan_session_id__in=recent_session_ids(session_limit, area=parse_area(self.request))).distinct()
        area = parse_area(self.request)
        if area:
            ids = area_device_ids(self.request, BLEObservation, "ble_device_id", "rssi", area, session_limit)
            qs = qs.filter(pk__in=ids)
        qs = apply_search(
            qs,
            self.request,
            {"device_key": "device_key", "device_name": "device_name", "device_type": "device_type_guess"},
        )
        return qs

    @action(detail=True, methods=["get"])
    def position(self, request, device_key=None):
        """This BLE device's estimated position — see
        AccessPointViewSet.position. Note a BLE device that moves (headphones,
        a wearable) has no meaningful fixed position at all; the estimators
        will still return one, and the disagreement between them is the clue
        that it's moving."""
        device = self.get_object()
        return device_position_response(
            request,
            identifier=device.device_key,
            points=observation_points(device.observations.all(), request, "rssi"),
            radio_kind="ble",
            extra={"label": device.device_name or device.device_key},
        )

    @action(detail=True, methods=["get"], url_path="ble-observations")
    def ble_observations(self, request, device_key=None):
        device = self.get_object()
        obs = device.observations.select_related("scan_session").order_by("-observed_at")
        since, until = parse_window(request)
        if since:
            obs = obs.filter(observed_at__gte=since)
        if until:
            obs = obs.filter(observed_at__lte=until)
        session_limit = parse_session_limit(request)
        if session_limit:
            obs = obs.filter(scan_session_id__in=recent_session_ids(session_limit, area=parse_area(request)))
        limit = parse_observation_limit(request)
        return Response(BLEObservationSerializer(obs[:limit], many=True).data)


class SatelliteObservationViewSet(mixins.ListModelMixin, viewsets.GenericViewSet):
    serializer_class = SatelliteObservationSerializer
    queryset = SatelliteObservation.objects.all().order_by("-observed_at")


class LANObservationViewSet(mixins.ListModelMixin, mixins.RetrieveModelMixin, viewsets.GenericViewSet):
    serializer_class = LANObservationSerializer

    def get_queryset(self):
        qs = LANObservation.objects.all().order_by("-observed_at")
        since, until = parse_window(self.request)
        if since:
            qs = qs.filter(observed_at__gte=since)
        if until:
            qs = qs.filter(observed_at__lte=until)
        session_limit = parse_session_limit(self.request)
        if session_limit:
            qs = qs.filter(scan_session_id__in=recent_session_ids(session_limit, area=parse_area(self.request), radio_related_name="lan_observations"))
        return qs


class LANDeviceViewSet(mixins.ListModelMixin, mixins.RetrieveModelMixin, viewsets.GenericViewSet):
    serializer_class = LANDeviceSerializer
    lookup_field = "ip_address"
    lookup_value_regex = "[^/]+"

    def get_queryset(self):
        qs = LANDevice.objects.all().order_by("-last_seen_at")
        qs = apply_active_window(qs, self.request)
        session_limit = parse_session_limit(self.request)
        if session_limit:
            qs = qs.filter(
                observations__scan_session_id__in=recent_session_ids(session_limit, area=parse_area(self.request), radio_related_name="lan_observations")
            ).distinct()
        area = parse_area(self.request)
        if area:
            # weight_field=None: a LAN observation carries no signal strength
            # (it's a subnet sweep, not a radio reading), so every sighting
            # counts equally and the estimate is a plain mean of where the
            # phone stood when it saw the device.
            ids = area_device_ids(self.request, LANObservation, "lan_device_id", None, area, session_limit)
            qs = qs.filter(pk__in=ids)
        qs = apply_search(
            qs,
            self.request,
            {
                "ip_address": "ip_address",
                "mac_address": "mac_address",
                "hostname": "hostname",
                "vendor_oui": "vendor_oui",
                "device_type": "device_type_guess",
            },
        )
        return qs

    def list(self, request, *args, **kwargs):
        queryset = self.filter_queryset(self.get_queryset())
        page = self.paginate_queryset(queryset)
        devices = page if page is not None else list(queryset)

        # is_online is unconditional — "was this device in the single most
        # recent LAN scan" regardless of whatever time/scan window the
        # request is otherwise filtering by, so it always answers "is it
        # here right now" rather than "was it here within my current view".
        latest_ids = recent_session_ids(1, radio_related_name="lan_observations")
        latest_id = latest_ids[0] if latest_ids else None
        online_device_ids = set()
        if latest_id and devices:
            online_device_ids = set(
                LANObservation.objects.filter(
                    lan_device_id__in=[d.pk for d in devices], scan_session_id=latest_id
                ).values_list("lan_device_id", flat=True)
            )
        for device in devices:
            device._is_online = device.pk in online_device_ids

        # New/left, in contrast, are only meaningful with >=2 LAN scans in
        # the *requested* window — otherwise there's nothing to compare
        # against.
        session_limit = parse_session_limit(request)
        if session_limit and session_limit >= 2 and devices:
            window_session_ids = recent_session_ids(session_limit, area=parse_area(request), radio_related_name="lan_observations")
            window_latest_id = window_session_ids[0] if window_session_ids else None
            membership = {}
            for device_id, session_id in LANObservation.objects.filter(
                lan_device_id__in=[d.pk for d in devices], scan_session_id__in=window_session_ids
            ).values_list("lan_device_id", "scan_session_id"):
                membership.setdefault(device_id, set()).add(session_id)
            for device in devices:
                sessions_seen = membership.get(device.pk, set())
                device._is_new_in_window = bool(sessions_seen) and sessions_seen == {window_latest_id}
                device._is_left_in_window = bool(sessions_seen) and window_latest_id not in sessions_seen
        else:
            for device in devices:
                device._is_new_in_window = False
                device._is_left_in_window = False

        serializer = self.get_serializer(devices, many=True)
        if page is not None:
            return self.get_paginated_response(serializer.data)
        return Response(serializer.data)

    @action(detail=True, methods=["get"], url_path="lan-observations")
    def lan_observations(self, request, ip_address=None):
        device = self.get_object()
        obs = device.observations.select_related("scan_session").order_by("-observed_at")
        since, until = parse_window(request)
        if since:
            obs = obs.filter(observed_at__gte=since)
        if until:
            obs = obs.filter(observed_at__lte=until)
        session_limit = parse_session_limit(request)
        if session_limit:
            # LAN scans are sparser than WiFi/cell/BLE — a plain
            # recent_session_ids(n) window (most recent N sessions overall)
            # regularly contains zero LAN scans. See MEMORY.md.
            obs = obs.filter(
                scan_session_id__in=recent_session_ids(session_limit, area=parse_area(request), radio_related_name="lan_observations")
            )
        limit = parse_observation_limit(request)
        return Response(LANObservationSerializer(obs[:limit], many=True).data)


@api_view(["GET"])
def channel_congestion(request):
    band = request.query_params.get("band", "2.4GHz")
    since, until = parse_window(request)
    session_limit = parse_session_limit(request)

    qs = WiFiObservation.objects.filter(band=band)
    if session_limit:
        qs = qs.filter(scan_session_id__in=recent_session_ids(session_limit, area=parse_area(request)))
    elif since or until:
        if since:
            qs = qs.filter(observed_at__gte=since)
        if until:
            qs = qs.filter(observed_at__lte=until)
    else:
        # A single scan session only sees whatever's nearby at that one
        # moment, which under-represents "what channels are actually in
        # use around here" — default to a rolling recent window instead.
        qs = qs.filter(observed_at__gte=timezone.now() - timedelta(hours=24))

    counts = (
        qs.values("channel")
        .annotate(ap_count=Count("access_point", distinct=True))
        .order_by("channel")
    )
    return Response(list(counts))


@api_view(["GET"])
def heatmap(request):
    source = request.query_params.get("source", "wifi")
    since, until = parse_window(request)
    session_limit = parse_session_limit(request)
    bounds = request.query_params.get("bounds")

    if source == "wifi":
        qs = WiFiObservation.objects.select_related("scan_session", "access_point")
        weight_field = "rssi"
    elif source == "cellular":
        qs = CellObservation.objects.select_related("scan_session", "cell_tower")
        weight_field = "signal_dbm"
    elif source == "ble":
        qs = BLEObservation.objects.select_related("scan_session")
        weight_field = "rssi"
    else:
        return Response({"detail": "invalid source, expected wifi|cellular|ble"}, status=400)

    # session_limit ("last scan" = 1, "last N scans" = N) takes precedence
    # over a time cutoff — it's a more direct answer to "what does the most
    # recent handful of passes look like" than picking a duration and
    # hoping it lines up with how often you actually scanned.
    if session_limit:
        qs = qs.filter(scan_session_id__in=recent_session_ids(session_limit, area=parse_area(request)))
    else:
        if since:
            qs = qs.filter(observed_at__gte=since)
        if until:
            qs = qs.filter(observed_at__lte=until)

    if bounds:
        try:
            sw_lat, sw_lng, ne_lat, ne_lng = (float(v) for v in bounds.split(","))
            qs = qs.filter(
                scan_session__latitude__gte=sw_lat,
                scan_session__latitude__lte=ne_lat,
                scan_session__longitude__gte=sw_lng,
                scan_session__longitude__lte=ne_lng,
            )
        except (ValueError, TypeError):
            pass

    qs = qs.exclude(scan_session__latitude__isnull=True).exclude(scan_session__longitude__isnull=True)

    # Bucket to ~11m grid cells so the response stays small regardless of
    # how many raw observations exist in the requested window. Each bucket
    # also tracks which sources (APs/towers/BLE devices) contributed to it,
    # so the map can show "what's actually here" with a link through to the
    # detail page — not just an anonymous intensity value.
    observations, truncated = capped_take(qs, HEATMAP_OBSERVATION_CAP)
    buckets = {}
    for obs in observations:
        key = (round(obs.scan_session.latitude, 4), round(obs.scan_session.longitude, 4))
        value = getattr(obs, weight_field) or 0
        bucket = buckets.setdefault(key, {"sum": 0, "count": 0, "sources": {}})
        bucket["sum"] += value
        bucket["count"] += 1

        source_key, source_label, source_path = None, None, None
        if source == "wifi":
            source_key = obs.access_point_id
            source_label = obs.access_point.ssid or obs.access_point.bssid
            source_path = f"/networks/{quote(obs.access_point_id, safe='')}"
        elif source == "cellular" and obs.cell_tower_id:
            source_key = obs.cell_tower_id
            source_label = obs.cell_tower.carrier_name or obs.cell_tower_id
            source_path = f"/cellular/{quote(obs.cell_tower_id, safe='')}"
        elif source == "ble":
            source_key = obs.ble_mac or obs.stable_identifier
            if source_key:
                source_label = obs.device_name or source_key
                source_path = f"/ble-devices/{quote(source_key, safe='')}"

        if source_key:
            entry = bucket["sources"].setdefault(source_key, {"label": source_label, "path": source_path, "count": 0})
            entry["count"] += 1

    points = []
    for (lat, lng), agg in buckets.items():
        point = {"lat": lat, "lng": lng, "weight": agg["sum"] / agg["count"]}
        if agg["sources"]:
            top_key = max(agg["sources"], key=lambda k: agg["sources"][k]["count"])
            top = agg["sources"][top_key]
            point["source"] = {
                "label": top["label"],
                "detail_path": top["path"],
                "extra_count": len(agg["sources"]) - 1,
            }
        points.append(point)

    return Response(capped_response(points, truncated, HEATMAP_OBSERVATION_CAP))


def parse_required_near(request):
    """near_lat/near_lng/near_radius_m — required, not the tolerant
    all-or-nothing-silent-drop posture of parse_area(). Shared by the three
    mission_*_observations views below: for each of them, this triple is the
    entire reason the endpoint exists (excluding a favorited device/network's
    sightings recorded somewhere else entirely — a travel router, a phone
    that changed owners), so a missing/malformed one is a 400, not a
    silently-unfiltered response.

    Returns `((lat, lng, radius), None)` on success or `(None, error_response)`
    on failure — callers `return error` immediately when it's not None.
    """
    near_lat = parse_float(request.query_params.get("near_lat"))
    near_lng = parse_float(request.query_params.get("near_lng"))
    near_radius_m = parse_float(request.query_params.get("near_radius_m"))
    if near_lat is None or not (-90 <= near_lat <= 90):
        return None, Response(
            {"detail": "near_lat is required and must be a valid latitude"}, status=status.HTTP_400_BAD_REQUEST
        )
    if near_lng is None or not (-180 <= near_lng <= 180):
        return None, Response(
            {"detail": "near_lng is required and must be a valid longitude"}, status=status.HTTP_400_BAD_REQUEST
        )
    if near_radius_m is None or near_radius_m <= 0:
        return None, Response(
            {"detail": "near_radius_m is required and must be a positive number"}, status=status.HTTP_400_BAD_REQUEST
        )
    return (near_lat, near_lng, near_radius_m), None


def mission_estimates(near_points, radio_kind):
    """Every estimator's answer for a Mission-view target, so the phone can
    draw its cone from the one the user picked *and* show how far the others
    land from it — disagreement is the useful signal when you're standing
    there deciding which way to walk.

    FTM naturally reports itself unavailable here: these are RSSI sightings
    with no measured distance attached, and a WiFi Mission target is an SSID
    (possibly several BSSIDs), which no single ranging solve corresponds to.
    """
    if not near_points:
        return None
    return compare_estimators(near_points, radio_kind)


@api_view(["GET"])
@authentication_classes([SensorTokenAuthentication])
@permission_classes([IsAuthenticated])
def mission_wifi_observations(request):
    """All recent observations of one SSID, restricted to those recorded near
    the caller's current position — feeds the Android app's Mission view,
    where a favorited SSID's estimated access-point position is drawn as a
    gradient cone (see mission/Geo.kt on the Android side, itself a port of
    frontend/src/geo.ts).

    Sensor-token-only, not session auth — the mirror image of every other
    read endpoint in this file (AccessPointViewSet's list/coverage/
    wifi_observations are all session-only). This is a phone-triggered
    machine read, not a PWA/human one, so it gets its own dedicated endpoint
    rather than an auth change to AccessPointViewSet.

    near_lat/near_lng/near_radius_m exist specifically so a "travel router" —
    an SSID seen from many unrelated physical locations over time (a mobile
    hotspot, a router that moved) — doesn't corrupt the estimate: only
    observations near where the phone is standing right now are returned.
    Unlike parse_area()'s tolerant silent-drop posture (fine for a map UI
    hint), these three params are the entire reason this endpoint exists, so
    a missing/malformed one is a 400, not a silently-unfiltered response.
    """
    ssid_exact = request.query_params.get("ssid_exact")
    if not ssid_exact:
        return Response({"detail": "ssid_exact is required"}, status=status.HTTP_400_BAD_REQUEST)
    near, error = parse_required_near(request)
    if error:
        return error
    near_lat, near_lng, near_radius_m = near

    since, until = parse_window(request)
    qs = (
        WiFiObservation.objects.select_related("access_point", "scan_session")
        .filter(access_point__ssid=ssid_exact)
        .exclude(scan_session__latitude__isnull=True)
        .exclude(scan_session__longitude__isnull=True)
    )
    if since:
        qs = qs.filter(observed_at__gte=since)
    if until:
        qs = qs.filter(observed_at__lte=until)
    session_limit = parse_session_limit(request)
    if session_limit:
        qs = qs.filter(scan_session_id__in=recent_session_ids(session_limit, area=parse_area(request)))

    # The near-radius filter MUST see every window-filtered observation
    # before any cap is applied — iterate the full queryset first, the same
    # iterate-before-deciding posture area_device_ids() already uses. Capping
    # first (as coverage()'s capped_take() does, which doesn't need this
    # distinction) could let a travel router's many far-away sightings
    # exhaust the cap before a single legitimate nearby reading is ever
    # considered, silently defeating the whole point of this endpoint.
    near_points = []
    for obs in qs.iterator():
        lat, lng = obs.scan_session.latitude, obs.scan_session.longitude
        if haversine_m(near_lat, near_lng, lat, lng) > near_radius_m:
            continue
        near_points.append(
            {
                "bssid": obs.access_point.bssid,
                "lat": lat,
                "lng": lng,
                "weight": obs.rssi,
                "observed_at": obs.observed_at,
                "scan_session_id": obs.scan_session_id,
                "accuracy_meters": obs.scan_session.location_accuracy_meters,
            }
        )

    truncated = len(near_points) > MISSION_OBSERVATION_CAP
    return Response(
        {
            "ssid": ssid_exact,
            "points": near_points[:MISSION_OBSERVATION_CAP],
            "estimates": mission_estimates(near_points, "wifi"),
            "truncated": truncated,
            "observation_limit": MISSION_OBSERVATION_CAP,
        }
    )


@api_view(["GET"])
@authentication_classes([SensorTokenAuthentication])
@permission_classes([IsAuthenticated])
def mission_ble_observations(request):
    """BLE equivalent of mission_wifi_observations — see that function's
    docstring for the shared reasoning (sensor-token-only, near-radius
    required and validated before any cap).

    device_key_exact matches BLEDevice.device_key exactly (ble_mac, falling
    back to stable_identifier when no MAC was ever captured — see that
    model's docstring), the same identifier the Android app already has for
    a device from its own scan results, so no key recomputation is needed
    on either side of the wire.
    """
    device_key = request.query_params.get("device_key_exact")
    if not device_key:
        return Response({"detail": "device_key_exact is required"}, status=status.HTTP_400_BAD_REQUEST)
    near, error = parse_required_near(request)
    if error:
        return error
    near_lat, near_lng, near_radius_m = near

    since, until = parse_window(request)
    qs = (
        BLEObservation.objects.select_related("scan_session")
        .filter(ble_device_id=device_key)
        .exclude(scan_session__latitude__isnull=True)
        .exclude(scan_session__longitude__isnull=True)
    )
    if since:
        qs = qs.filter(observed_at__gte=since)
    if until:
        qs = qs.filter(observed_at__lte=until)
    session_limit = parse_session_limit(request)
    if session_limit:
        qs = qs.filter(scan_session_id__in=recent_session_ids(session_limit, area=parse_area(request)))

    near_points = []
    for obs in qs.iterator():
        lat, lng = obs.scan_session.latitude, obs.scan_session.longitude
        if haversine_m(near_lat, near_lng, lat, lng) > near_radius_m:
            continue
        near_points.append(
            {
                "identifier": obs.ble_mac or obs.stable_identifier,
                "lat": lat,
                "lng": lng,
                "weight": obs.rssi,
                "observed_at": obs.observed_at,
                "scan_session_id": obs.scan_session_id,
                "accuracy_meters": obs.scan_session.location_accuracy_meters,
            }
        )

    truncated = len(near_points) > MISSION_OBSERVATION_CAP
    return Response(
        {
            "identifier": device_key,
            "points": near_points[:MISSION_OBSERVATION_CAP],
            "estimates": mission_estimates(near_points, "ble"),
            "truncated": truncated,
            "observation_limit": MISSION_OBSERVATION_CAP,
        }
    )


@api_view(["GET"])
@authentication_classes([SensorTokenAuthentication])
@permission_classes([IsAuthenticated])
def mission_cell_observations(request):
    """Cellular equivalent of mission_wifi_observations — see that
    function's docstring for the shared reasoning.

    tower_key_exact matches CellTower.tower_key exactly (the same
    "{mcc}-{mnc}-{tac_or_lac}-{cell_id}" composite the Android app already
    builds for its own rows — see ScanDiff.cellKey on that side), so no key
    recomputation is needed here either. Readings with no signal_dbm are
    excluded outright (not just skipped in aggregation) — a reading with no
    signal strength can't be weighted, and dropping it beats guessing a
    value that would distort the estimate, same posture as
    area_device_ids()'s own weight_field handling.
    """
    tower_key = request.query_params.get("tower_key_exact")
    if not tower_key:
        return Response({"detail": "tower_key_exact is required"}, status=status.HTTP_400_BAD_REQUEST)
    near, error = parse_required_near(request)
    if error:
        return error
    near_lat, near_lng, near_radius_m = near

    since, until = parse_window(request)
    qs = (
        CellObservation.objects.select_related("scan_session")
        .filter(cell_tower_id=tower_key)
        .exclude(scan_session__latitude__isnull=True)
        .exclude(scan_session__longitude__isnull=True)
        .exclude(signal_dbm__isnull=True)
    )
    if since:
        qs = qs.filter(observed_at__gte=since)
    if until:
        qs = qs.filter(observed_at__lte=until)
    session_limit = parse_session_limit(request)
    if session_limit:
        qs = qs.filter(scan_session_id__in=recent_session_ids(session_limit, area=parse_area(request)))

    near_points = []
    for obs in qs.iterator():
        lat, lng = obs.scan_session.latitude, obs.scan_session.longitude
        if haversine_m(near_lat, near_lng, lat, lng) > near_radius_m:
            continue
        near_points.append(
            {
                "lat": lat,
                "lng": lng,
                "weight": obs.signal_dbm,
                "observed_at": obs.observed_at,
                "scan_session_id": obs.scan_session_id,
                "accuracy_meters": obs.scan_session.location_accuracy_meters,
            }
        )

    truncated = len(near_points) > MISSION_OBSERVATION_CAP
    return Response(
        {
            "tower_key": tower_key,
            "points": near_points[:MISSION_OBSERVATION_CAP],
            "estimates": mission_estimates(near_points, "cellular"),
            "truncated": truncated,
            "observation_limit": MISSION_OBSERVATION_CAP,
        }
    )


class GroundTruthViewSet(viewsets.ModelViewSet):
    """Operator-asserted true positions — see GroundTruthPosition.

    Full CRUD (unlike the read-only observation viewsets) because these are
    the operator's own annotations rather than recorded measurements: they get
    corrected, moved and deleted as a survey proceeds.
    """

    serializer_class = GroundTruthPositionSerializer

    def get_queryset(self):
        qs = GroundTruthPosition.objects.all()
        kind = self.request.query_params.get("kind")
        if kind:
            qs = qs.filter(kind=kind)
        target = self.request.query_params.get("target_key")
        if target:
            qs = qs.filter(target_key=target)
        return qs

    def create(self, request, *args, **kwargs):
        """Upsert rather than 400 on a duplicate. Re-pinning the same AP is
        "I was slightly off", not an error — and the unique constraint would
        otherwise surface as an opaque 500 from the map's click handler.

        A pin placed on a floor plan sends pixel coordinates instead of
        lat/lng; the real position is derived here so there's one
        implementation of the transform and the frontend never needs it.
        """
        data = request.data.copy()
        if data.get("floor_plan") and data.get("image_x") is not None:
            plan = FloorPlan.objects.filter(pk=data["floor_plan"]).first()
            if plan is None:
                return Response({"detail": "No such floor plan."}, status=400)
            world = image_to_world(plan, float(data["image_x"]), float(data["image_y"]))
            if world is None:
                return Response(
                    {"detail": "That floor plan isn't calibrated yet — set its two anchor points first."},
                    status=400,
                )
            data["latitude"], data["longitude"] = world
            request._full_data = data

        kind = request.data.get("kind")
        target_key = request.data.get("target_key")
        existing = GroundTruthPosition.objects.filter(kind=kind, target_key=target_key).first()
        if existing is not None:
            serializer = self.get_serializer(existing, data=request.data, partial=True)
            serializer.is_valid(raise_exception=True)
            serializer.save()
            return Response(serializer.data)
        return super().create(request, *args, **kwargs)


@api_view(["GET"])
def localization_benchmark(request):
    """Every pinned access point scored under every estimator.

    Per-AP errors say which algorithm won *here*; the aggregate says which to
    trust generally. Median alongside mean because one badly-surveyed AP
    otherwise dominates the mean and inverts the ranking.
    """
    pins = GroundTruthPosition.objects.filter(kind=GroundTruthPosition.Kind.ACCESS_POINT)
    range_model = active_range_model("wifi")
    rows = []
    for pin in pins:
        access_point = AccessPoint.objects.filter(bssid=pin.target_key).first()
        if access_point is None:
            continue
        points = observation_points(access_point.observations.all(), request, "rssi")
        if not points:
            continue
        compared = compare_estimators(
            points, "wifi", ftm_points=ftm_points_for(access_point, request), range_model=range_model
        )
        errors = {}
        for name, estimate in compared["estimates"].items():
            errors[name] = (
                None
                if estimate.get("lat") is None
                else haversine_m(estimate["lat"], estimate["lng"], pin.latitude, pin.longitude)
            )
        rows.append({
            "bssid": pin.target_key,
            "ssid": access_point.ssid,
            "label": pin.label,
            "sample_count": len(points),
            "errors": errors,
        })

    summary = {}
    for name in ESTIMATORS:
        values = sorted(r["errors"][name] for r in rows if r["errors"].get(name) is not None)
        if not values:
            summary[name] = None
            continue
        mid = len(values) // 2
        median = values[mid] if len(values) % 2 else (values[mid - 1] + values[mid]) / 2
        summary[name] = {
            "mean_error_m": sum(values) / len(values),
            "median_error_m": median,
            "best_error_m": values[0],
            "worst_error_m": values[-1],
            "scored_aps": len(values),
        }

    return Response({
        "pinned_ap_count": pins.count(),
        "scored_ap_count": len(rows),
        "results": rows,
        "summary": summary,
        "range_model_is_calibrated": range_model is not None,
    })


@api_view(["GET", "POST"])
def calibration(request):
    """GET the stored path-loss fits; POST to re-fit from current ground truth.

    Fitting never activates by itself — a fitted model changes what every
    historical estimate means, so switching it on is an explicit act
    (`{"activate": true}`) taken after looking at the fit quality.
    """
    if request.method == "GET":
        return Response({
            "generic": RANGE_MODEL,
            "fitted": CalibratedRangeModelSerializer(CalibratedRangeModel.objects.all(), many=True).data,
        })

    radio_kind = request.data.get("radio_kind", "wifi")
    if radio_kind != "wifi":
        # Only WiFi has an identity that can be pinned to a physical position
        # today; a cell tower's true location isn't something you can survey
        # by walking to it, and BLE devices move.
        return Response({"detail": "Only wifi calibration is supported."}, status=400)

    samples = []
    for pin in GroundTruthPosition.objects.filter(kind=GroundTruthPosition.Kind.ACCESS_POINT):
        access_point = AccessPoint.objects.filter(bssid=pin.target_key).first()
        if access_point is None:
            continue
        for point in observation_points(access_point.observations.all(), request, "rssi"):
            samples.append({
                "rssi": point["weight"],
                "distance_m": haversine_m(point["lat"], point["lng"], pin.latitude, pin.longitude),
            })

    fit = fit_path_loss(samples)
    if not fit["available"]:
        return Response(fit, status=400)

    stored, _ = CalibratedRangeModel.objects.update_or_create(
        radio_kind=radio_kind,
        defaults={
            "ref_rssi_at_1m": fit["ref_rssi_at_1m"],
            "path_loss_exponent": fit["path_loss_exponent"],
            "r_squared": fit["r_squared"],
            "sample_count": fit["sample_count"],
            "min_distance_m": fit["min_distance_m"],
            "max_distance_m": fit["max_distance_m"],
            "is_active": bool(request.data.get("activate")) and fit["plausible"],
        },
    )
    return Response({
        "fit": fit,
        "stored": CalibratedRangeModelSerializer(stored).data,
        "generic": RANGE_MODEL.get(radio_kind),
    })


@api_view(["POST"])
def calibration_activate(request):
    """Turn a stored fit on or off. Separate from fitting so you can look at
    the numbers first, and switch back to the generic model at any time."""
    radio_kind = request.data.get("radio_kind", "wifi")
    active = bool(request.data.get("is_active"))
    stored = CalibratedRangeModel.objects.filter(radio_kind=radio_kind).first()
    if stored is None:
        return Response({"detail": "No fitted model for that radio kind yet."}, status=404)
    if active and stored.path_loss_exponent <= 0:
        return Response(
            {"detail": "That fit has a non-positive path-loss exponent — it would say signal grows with distance."},
            status=400,
        )
    stored.is_active = active
    stored.save(update_fields=["is_active"])
    return Response(CalibratedRangeModelSerializer(stored).data)


def resync_placements(plan):
    """Re-derives every placement's real-world position from its pixel
    coordinates after the plan's transform changes.

    Pins store *both* the pixel spot they were clicked at and the lat/lng
    derived from it. The pixel spot is the operator's actual assertion ("I
    stood in this corner"); the lat/lng is a derived value — so the moment the
    transform changes, every derived value is stale and must be recomputed.

    Without this, nudging the bearing silently corrupted the whole survey: the
    plan rotated on screen and the dots stayed on their pixels, so it *looked*
    right, while every estimator, the scoreboard and the path-loss calibration
    went on using positions that were now metres out. Measured at 16m of error
    for a 90-degree turn.
    """
    if solve_transform(plan) is None:
        return 0
    updated = 0
    for pin in plan.placements.exclude(image_x__isnull=True).exclude(image_y__isnull=True):
        world = image_to_world(plan, pin.image_x, pin.image_y)
        if world is None:
            continue
        pin.latitude, pin.longitude = world
        pin.save(update_fields=["latitude", "longitude"])
        updated += 1
    return updated


class FloorPlanViewSet(viewsets.ModelViewSet):
    """Uploaded floor plans and the measurement points placed on them."""

    serializer_class = FloorPlanSerializer
    queryset = FloorPlan.objects.all()

    @action(detail=True, methods=["post"])
    def calibrate(self, request, pk=None):
        """Anchors the plan to the world with two matched point pairs.

        Rejects coincident anchors: two clicks in the same place fix no scale
        and no rotation, and silently accepting them would produce a plan that
        maps every pixel to the same spot.
        """
        plan = self.get_object()
        for field in (
            "anchor1_image_x", "anchor1_image_y", "anchor1_lat", "anchor1_lng",
            "anchor2_image_x", "anchor2_image_y", "anchor2_lat", "anchor2_lng",
        ):
            if request.data.get(field) is None:
                return Response({"detail": f"Missing {field}."}, status=400)
            setattr(plan, field, float(request.data[field]))

        derived = derive_scale_and_bearing(plan)
        if derived is None:
            return Response(
                {"detail": "Those two anchor points are too close together to fix a scale — pick points far apart."},
                status=400,
            )
        plan.meters_per_pixel = derived["meters_per_pixel"]
        plan.bearing_deg = derived["bearing_deg"]
        plan.save()
        resync_placements(plan)
        return Response(FloorPlanSerializer(plan).data)

    @action(detail=True, methods=["post"])
    def adjust(self, request, pk=None):
        """Nudges the stored scale and/or bearing directly.

        Exists because two-point anchoring is only as accurate as two clicks
        on a map at house scale, which is not very. Rotating the plan a couple
        of degrees is a far more natural correction than hunting for anchor
        points that happen to produce the right rotation.
        """
        plan = self.get_object()
        if not plan.is_calibrated:
            return Response({"detail": "Calibrate the plan before adjusting it."}, status=400)

        # Rotation and scaling happen about the anchor point, which is
        # wherever the operator happened to click during calibration — often
        # a corner. Rotating about a corner *translates* the whole plan as
        # well as turning it, so it swings away instead of spinning in place
        # and can never be lined up. Pin the plan's centre instead: note where
        # it is, apply the change, then shift the anchor so the centre lands
        # back where it started.
        centre_x, centre_y = plan.image_width_px / 2, plan.image_height_px / 2
        before = image_to_world(plan, centre_x, centre_y)

        if request.data.get("bearing_deg") is not None:
            plan.bearing_deg = float(request.data["bearing_deg"]) % 360
        if request.data.get("meters_per_pixel") is not None:
            scale = float(request.data["meters_per_pixel"])
            if scale <= 0:
                return Response({"detail": "Scale must be greater than zero."}, status=400)
            plan.meters_per_pixel = scale

        after = image_to_world(plan, centre_x, centre_y)
        if before is not None and after is not None:
            plan.anchor1_lat += before[0] - after[0]
            plan.anchor1_lng += before[1] - after[1]

        plan.save()
        resynced = resync_placements(plan)
        return Response({**FloorPlanSerializer(plan).data, "resynced_placements": resynced})

    @action(detail=True, methods=["post"])
    def outline(self, request, pk=None):
        """Stores the building footprint traced on the plan.

        Body: {"points": [{"x": 12.5, "y": 400.0}, ...]}, image pixels, in
        order. An empty list clears the trace and everything reverts to the
        image's own rectangle.

        Alternatively {"points": [{"lat": .., "lng": ..}, ...]} for an outline
        traced on the map rather than on the plan drawing. Converted here via
        world_to_image so the transform has exactly one implementation and the
        stored form stays pixels — which is what makes an outline survive the
        plan later being rotated or rescaled, exactly as placed pins do.
        Mixing the two forms in one request is rejected rather than guessed
        at: a half-pixel, half-world polygon is far more likely to be a
        frontend bug than an intention.

        Validated here rather than trusted: these coordinates drive both the
        map footprint and the heatmap clip, and a malformed or degenerate
        polygon would either crash the ray cast or silently blank the entire
        heatmap — which looks exactly like "the survey lost my data".
        """
        plan = self.get_object()
        raw = request.data.get("points")
        if raw is None or not isinstance(raw, list):
            return Response({"detail": "points must be a list."}, status=400)

        world_form = any(isinstance(p, dict) and "lat" in p for p in raw)
        if world_form and any(isinstance(p, dict) and "x" in p for p in raw):
            return Response(
                {"detail": "Give the outline either as x/y pixels or as lat/lng, not a mixture."},
                status=400,
            )
        if world_form and world_to_image(plan, 0.0, 0.0) is None:
            return Response(
                {"detail": "Calibrate this plan before tracing its outline on the map."},
                status=400,
            )

        points = []
        for point in raw:
            if not isinstance(point, dict):
                return Response({"detail": "Each point must be an object with x and y."}, status=400)
            try:
                if world_form:
                    x, y = world_to_image(plan, float(point["lat"]), float(point["lng"]))
                else:
                    x, y = float(point["x"]), float(point["y"])
            except (KeyError, TypeError, ValueError):
                return Response(
                    {"detail": "Each point needs numeric x and y (or lat and lng)."}, status=400
                )
            if not (math.isfinite(x) and math.isfinite(y)):
                return Response({"detail": "Point coordinates must be finite."}, status=400)
            # Clamped, not rejected: a vertex dragged a little past the edge of
            # the image is an ordinary thing to do while tracing, and snapping
            # it to the border is what the operator meant.
            points.append({
                "x": min(max(x, 0.0), float(plan.image_width_px)),
                "y": min(max(y, 0.0), float(plan.image_height_px)),
            })

        if points and len(points) < MIN_OUTLINE_VERTICES:
            return Response(
                {"detail": f"An outline needs at least {MIN_OUTLINE_VERTICES} points, or none at all."},
                status=400,
            )

        plan.outline_points = points
        plan.save(update_fields=["outline_points", "updated_at"])
        return Response(FloorPlanSerializer(plan).data)

    @action(detail=True, methods=["post"], url_path="reset-calibration")
    def reset_calibration(self, request, pk=None):
        """Clears the anchors and the transform, back to a freshly uploaded
        plan. Re-anchoring on top of an existing calibration was confusing:
        the old footprint stayed on the map while new anchors were being
        picked, so it was never clear which one you were looking at."""
        plan = self.get_object()
        for field in (
            "anchor1_image_x", "anchor1_image_y", "anchor1_lat", "anchor1_lng",
            "anchor2_image_x", "anchor2_image_y", "anchor2_lat", "anchor2_lng",
            "meters_per_pixel", "bearing_deg",
        ):
            setattr(plan, field, None)
        plan.save()
        return Response(FloorPlanSerializer(plan).data)

    @action(detail=True, methods=["get"], url_path="nearby-ssids")
    def nearby_ssids(self, request, pk=None):
        """Networks actually heard near this plan's location.

        The plain SSID list is every network ever recorded anywhere, which for
        a home survey is mostly noise from other places entirely. Anchoring
        gives the plan a real position, so the useful list is "what's audible
        here" — ordered by how often it was heard, since your own network is
        the one you'll have the most readings of.
        """
        plan = self.get_object()
        if not plan.is_calibrated:
            return Response({"detail": "Calibrate the plan first — its location is what makes 'nearby' mean anything."}, status=400)

        radius_m = parse_float(request.query_params.get("radius_m"), 150.0)
        sessions = {
            str(session.id)
            for session in ScanSession.objects.exclude(latitude__isnull=True).exclude(longitude__isnull=True)
            if haversine_m(plan.anchor1_lat, plan.anchor1_lng, session.latitude, session.longitude) <= radius_m
        }
        # Scans already placed on this plan count as nearby by definition —
        # the operator has said "I took this one in that room". Relying on the
        # radius alone left the list empty whenever the plan's anchor didn't
        # happen to sit near the recorded GPS fixes, which is exactly the case
        # indoors, where those fixes are unreliable and the placements are the
        # trustworthy signal.
        sessions |= {
            pin.target_key
            for pin in plan.placements.filter(kind=GroundTruthPosition.Kind.OBSERVER)
        }
        rows = {}
        for obs in (
            WiFiObservation.objects
            .filter(scan_session_id__in=sessions)
            .exclude(access_point__ssid="")
            .values("access_point__ssid", "access_point_id", "rssi", "band")
        ):
            entry = rows.setdefault(
                obs["access_point__ssid"],
                {"ssid": obs["access_point__ssid"], "reading_count": 0, "best_rssi": -999, "bssids": set(), "bands": set()},
            )
            entry["reading_count"] += 1
            entry["best_rssi"] = max(entry["best_rssi"], obs["rssi"])
            entry["bssids"].add(obs["access_point_id"])
            entry["bands"].add(obs["band"])

        results = [
            {
                "ssid": e["ssid"],
                "reading_count": e["reading_count"],
                "best_rssi": e["best_rssi"],
                "bssid_count": len(e["bssids"]),
                "bssids": sorted(e["bssids"]),
                "bands": sorted(e["bands"]),
            }
            for e in rows.values()
        ]
        results.sort(key=lambda r: (-r["reading_count"], r["ssid"]))
        return Response({"radius_m": radius_m, "results": results})

    @action(detail=True, methods=["get"])
    def coverage(self, request, pk=None):
        """Signal strength at every measurement point placed on this plan —
        the weak-spot view.

        Reports the *best* RSSI across all BSSIDs of the chosen SSID at each
        point, not per-BSSID: a mesh hands you between radios as you walk, and
        what you actually experience in a room is whichever one is strongest.
        A room is a weak spot when even the best radio is poor there.

        Returned in pixel coordinates so the frontend draws directly onto the
        plan image without inverting the transform.
        """
        plan = self.get_object()
        # Several SSIDs, because a router commonly names its 2.4 and 5GHz
        # radios differently (MagentaWLAN-3EA4 / -3EA5). Those are one network
        # as far as "do I have signal in this room" is concerned, so treating
        # them separately would report a weak spot wherever the phone happened
        # to prefer the other band.
        ssids = [v for v in request.query_params.getlist("ssid_exact") if v]
        if len(ssids) == 1 and "," in ssids[0]:
            ssids = [part.strip() for part in ssids[0].split(",") if part.strip()]
        if not ssids:
            return Response({"detail": "At least one ssid_exact is required."}, status=400)
        weak_threshold = parse_float(request.query_params.get("weak_threshold_dbm"), -70.0)

        placed_aps = [
            {"bssid": pin.target_key, "image_x": pin.image_x, "image_y": pin.image_y, "label": pin.label}
            for pin in plan.placements.filter(kind=GroundTruthPosition.Kind.ACCESS_POINT).exclude(image_x__isnull=True)
        ]

        def build_prediction(points):
            """Predicted coverage radiating out from each placed access point.

            Deliberately independent of whether any measurements exist: "where
            does this router actually reach?" is answerable from the
            transmitter's position alone, and it is the first thing worth
            knowing after pinning an AP — before any walking has been done.
            Measurements, when present, only improve it by fitting the falloff
            to this building instead of using generic constants.
            """
            if request.query_params.get("include_prediction") != "1" or not placed_aps:
                return None
            return predict_coverage(
                plan,
                placed_aps,
                points,
                fallback_model=active_range_model("wifi") or RANGE_MODEL["wifi"],
                steps=positive_int(request.query_params.get("heatmap_steps"), 40, maximum=80),
                outline=outline_pixels(plan),
            )

        placements = plan.placements.filter(kind=GroundTruthPosition.Kind.OBSERVER).exclude(image_x__isnull=True)
        by_session = {p.target_key: p for p in placements}
        if not by_session:
            # Same envelope shape as the populated branch below — a plan
            # with no measurement points yet is a real, expected state (the
            # very first thing after calibrating), not an error, and the
            # frontend must not have to guess which fields exist depending
            # on how much data happens to be behind the response.
            return Response({
                "ssids": ssids,
                "weak_threshold_dbm": weak_threshold,
                "points": [],
                "heatmap": None,
                "prediction": build_prediction([]),
                "placed_aps": placed_aps,
                "suggestions": [],
                "weak_count": 0,
                "measured_count": 0,
            })

        observations = (
            WiFiObservation.objects
            .filter(scan_session_id__in=list(by_session), access_point__ssid__in=ssids)
            .values("scan_session_id", "rssi", "access_point_id", "access_point__ssid", "observed_at")
        )
        best = {}
        for obs in observations:
            key = str(obs["scan_session_id"])
            if key not in best or obs["rssi"] > best[key]["rssi"]:
                best[key] = obs

        points = []
        for session_id, placement in by_session.items():
            reading = best.get(session_id)
            points.append({
                "scan_session_id": session_id,
                "image_x": placement.image_x,
                "image_y": placement.image_y,
                "label": placement.label,
                "rssi": None if reading is None else reading["rssi"],
                "bssid": None if reading is None else reading["access_point_id"],
                "ssid": None if reading is None else reading["access_point__ssid"],
                "observed_at": None if reading is None else reading["observed_at"],
                # No reading at all is the worst kind of weak spot: the
                # network wasn't merely faint there, it was absent.
                "is_weak": reading is None or reading["rssi"] < weak_threshold,
                "no_coverage": reading is None,
            })

        points.sort(key=lambda p: (p["rssi"] is not None, p["rssi"] if p["rssi"] is not None else 0))

        # The interpolated surface is opt-in: it's the largest part of the
        # payload and only wanted when the heatmap is actually on screen.
        heatmap = None
        if request.query_params.get("include_heatmap") == "1":
            heatmap = interpolate_coverage(
                points,
                plan.image_width_px,
                plan.image_height_px,
                steps=positive_int(request.query_params.get("heatmap_steps"), 40, maximum=80),
                # Clipped to the traced building footprint when there is one,
                # so coverage stops being painted over the parts of the image
                # rectangle that aren't the house.
                outline=outline_pixels(plan),
            )

        prediction = build_prediction(points)

        return Response({
            "ssids": ssids,
            "heatmap": heatmap,
            "prediction": prediction,
            "placed_aps": placed_aps,
            "suggestions": suggest_ap_placements(points, placed_aps, plan),
            "weak_threshold_dbm": weak_threshold,
            "points": points,
            "weak_count": sum(1 for p in points if p["is_weak"]),
            "measured_count": len(points),
        })


@api_view(["POST"])
@authentication_classes([SensorTokenAuthentication])
@permission_classes([IsAuthenticated])
def signature_match(request):
    """Catalog labels for the adverts of one phone scan pass
    (scans/signature_lookup.py). Nothing is stored: the same adverts are
    labeled again, and kept, when the pass is uploaded."""
    return Response(signature_lookup(request.data))
