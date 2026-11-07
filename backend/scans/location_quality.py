"""Scan position quality: the ingest check and the non-destructive history
cleanup. See docs/superpowers/plans/2026-11-07-live-scan-location.md.

Before 2026-11 the Android app took each scan's position from
getLastKnownLocation() with no age, accuracy or jump checks. The app now
selects fixes live (scan/FixSelector.kt); this module repairs what is
already stored and guards ingest from old APKs.

Neither reading is trusted on its own. The primary fix and the platform
fused fix both come partly from Wi-Fi/cell databases, and relocated access
points fool both: the live DB has Las Vegas scans whose network *and* fused
fixes sat in Tokyo, Iowa, New York or Germany for minutes. A first version
of this module preferred the fused fix whenever the two disagreed, and on
those scans it swapped correct positions for wrong ones. So each scan's
position is chosen by consistency with the same sensor's track: keep the
original, take the fused fix, or drop the position, whichever makes the
track most plausible (`_choose`, a small dynamic-programming pass).

Nothing is deleted. The original moves to `raw_*`, `location_correction`
says what happened, and `clean_session_locations(revert=True)` undoes it.
The map and estimator code keeps reading `latitude`/`longitude` unchanged.
"""

from __future__ import annotations

import math

FUSED_DISAGREE_MIN_M = 100.0
FUSED_DISAGREE_FACTOR = 3.0
HOP_MIN_M = 40.0
# The phone (FixSelector) uses Fieldwatch's 42 m/s plus the fix's measured
# speed. Stored sessions have no speed, so this sits above trains (~83 m/s)
# and still catches the real teleports (thousands of km within minutes).
HOP_MAX_SPEED_MPS = 100.0
HOP_MIN_DT_S = 0.8
# Beyond this gap the phone may have flown: allow airliner speed instead.
HOP_MAX_DT_S = 30 * 60.0
HOP_MAX_FLIGHT_SPEED_MPS = 270.0  # ~970 km/h, airliner ground speed
# An implausible hop costs as much as dropping this many positions, so in
# practice the kept track has none, but a sensor's history can't be wiped
# by one unexplained hop between two very long stays either.
HOP_PENALTY = 200.0
# GPS measures the position itself; network/fused positions come from
# Wi-Fi/cell databases that relocated access points fool, sometimes for
# hours (real data: 71 network scans in Iowa while GPS said Las Vegas).
GPS_WEIGHT = 10.0
FUSED_PENALTY = 0.01
LOOKBACK = 200

CORRECTED_FUSED = "fused"
CORRECTED_CLEARED = "cleared"

_FIELDS = [
    "latitude", "longitude", "location_accuracy_meters", "location_provider",
    "raw_latitude", "raw_longitude", "raw_location_accuracy_meters", "raw_location_provider",
    "location_correction", "location_correction_reason",
]
_UNCORRECTED = {
    "raw_latitude": None, "raw_longitude": None, "raw_location_accuracy_meters": None,
    "raw_location_provider": "", "location_correction": "", "location_correction_reason": "",
}


def distance_m(lat1, lng1, lat2, lng2) -> float:
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lng2 - lng1)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 12_742_000.0 * math.asin(math.sqrt(min(1.0, max(0.0, h))))


def disagrees_with_fused(lat, lng, accuracy, fused_lat, fused_lng, fused_accuracy) -> bool:
    """True when both fixes exist and sit further apart than both of their
    uncertainties can explain (at least 100 m)."""
    if None in (lat, lng, fused_lat, fused_lng):
        return False
    limit = max(FUSED_DISAGREE_MIN_M, FUSED_DISAGREE_FACTOR * ((accuracy or 0.0) + (fused_accuracy or 0.0)))
    return distance_m(lat, lng, fused_lat, fused_lng) > limit


def hop_plausible(a, b, flight_s: float | None = None) -> bool:
    """`a`/`b` are (lat, lng, seconds). Up to 100 m/s, plus airliner speed
    during `flight_s`: the longest stretch in between when the phone
    recorded no scans at all (only stretches over 30 min count). Defaults to
    the whole gap, i.e. two scans with nothing recorded between them.

    Measuring flight time from the *recording*, not from the two scans
    being compared, matters: otherwise dropping scans in between opens a
    window a flight "explains" (real data: a fake Iowa run, then Las Vegas
    scans every few minutes for two hours)."""
    dt = abs(b[2] - a[2])
    d = distance_m(a[0], a[1], b[0], b[1])
    if d <= HOP_MIN_M:
        return True
    flight = dt if flight_s is None else min(flight_s, dt)
    if flight <= HOP_MAX_DT_S:
        flight = 0.0
    allowed = HOP_MAX_SPEED_MPS * max(dt - flight, HOP_MIN_DT_S) + HOP_MAX_FLIGHT_SPEED_MPS * flight
    return d <= allowed


def _seconds(session_or_fields) -> float:
    started, completed = session_or_fields["started_at"], session_or_fields["completed_at"]
    return (started + (completed - started) / 2).timestamp()


def _raw_fields(orig: dict) -> dict:
    return {
        "raw_latitude": orig.get("latitude"),
        "raw_longitude": orig.get("longitude"),
        "raw_location_accuracy_meters": orig.get("location_accuracy_meters"),
        "raw_location_provider": orig.get("location_provider", "") or "",
    }


def _fused_target(orig: dict, fused: tuple, reason: str) -> dict:
    return {
        **_raw_fields(orig),
        "latitude": fused[0], "longitude": fused[1], "location_accuracy_meters": fused[2],
        "location_provider": "fused", "location_correction": CORRECTED_FUSED,
        "location_correction_reason": reason[:128],
    }


def _cleared_target(orig: dict, reason: str) -> dict:
    return {
        **_raw_fields(orig),
        "latitude": None, "longitude": None, "location_accuracy_meters": None, "location_provider": "",
        "location_correction": CORRECTED_CLEARED, "location_correction_reason": reason[:128],
    }


def ingest_correction(fields: dict, previous) -> dict:
    """For a new session (location fields plus started_at/completed_at):
    when its position disagrees with its own fused fix, judge both against
    the sensor's previous scan, `previous` = (lat, lng, seconds) or None.
    Returns the fields to apply, or {} to keep the position as sent (also
    when there's nothing to judge by: the cleanup can decide later)."""
    lat, lng = fields.get("latitude"), fields.get("longitude")
    fused = (fields.get("fused_latitude"), fields.get("fused_longitude"), fields.get("fused_accuracy_meters"))
    if previous is None or not disagrees_with_fused(lat, lng, fields.get("location_accuracy_meters"), *fused):
        return {}
    t = _seconds(fields)
    if hop_plausible(previous, (lat, lng, t)) or not hop_plausible(previous, (fused[0], fused[1], t)):
        return {}
    d = distance_m(lat, lng, fused[0], fused[1])
    return _fused_target(fields, fused, f"{d:.0f} m from the fused fix, which fits the previous scan")


def original_position(session) -> dict:
    """The position as the phone sent it (raw_* once corrected)."""
    if session.location_correction:
        return {
            "latitude": session.raw_latitude, "longitude": session.raw_longitude,
            "location_accuracy_meters": session.raw_location_accuracy_meters,
            "location_provider": session.raw_location_provider,
        }
    return {
        "latitude": session.latitude, "longitude": session.longitude,
        "location_accuracy_meters": session.location_accuracy_meters,
        "location_provider": session.location_provider,
    }


def _choose(sessions) -> dict:
    """session pk -> target fields for one sensor's sessions in time order,
    always computed from the originals, so re-running is stable.

    Each geotagged scan offers its original and, when it disagrees, its
    fused fix. The kept track maximises the weight of the positions
    kept (GPS GPS_WEIGHT, network 1, a fused stand-in slightly less) −
    HOP_PENALTY × (implausible hops between consecutive kept positions).
    Scans left out lose their position.

    Hops between kept scans get flight allowance only for the stretches the
    phone recorded nothing (see hop_plausible), so dropping scans can't
    manufacture a flight."""
    nodes = []  # (index into points, option, (lat, lng, t), accuracy)
    points = []
    for s in sessions:
        orig = original_position(s)
        if orig["latitude"] is None or orig["longitude"] is None:
            continue
        t = _seconds({"started_at": s.started_at, "completed_at": s.completed_at})
        idx = len(points)
        points.append((s, orig))
        nodes.append((idx, "orig", (orig["latitude"], orig["longitude"], t), orig["location_accuracy_meters"],
                      GPS_WEIGHT if orig["location_provider"] == "gps" else 1.0))
        if disagrees_with_fused(orig["latitude"], orig["longitude"], orig["location_accuracy_meters"],
                                s.fused_latitude, s.fused_longitude, s.fused_accuracy_meters):
            nodes.append((idx, "fused", (s.fused_latitude, s.fused_longitude, t), s.fused_accuracy_meters,
                          1.0 - FUSED_PENALTY))

    # gaps[k]: time between geotagged scans k and k+1 as recorded.
    times = [_seconds({"started_at": s.started_at, "completed_at": s.completed_at}) for s, _ in points]
    gaps = [b - a for a, b in zip(times, times[1:])]
    score = [0.0] * len(nodes)
    back = [-1] * len(nodes)
    first_node_of = {}
    for n, node in enumerate(nodes):
        first_node_of.setdefault(node[0], n)
    for n, (idx, option, point, _, gain) in enumerate(nodes):
        best, best_from = gain, -1
        start = first_node_of.get(max(0, idx - LOOKBACK), 0)
        low, longest_gap = idx, 0.0
        for m in range(n - 1, start - 1, -1):
            if nodes[m][0] >= idx:
                continue
            while low > nodes[m][0]:
                low -= 1
                longest_gap = max(longest_gap, gaps[low])
            plausible = hop_plausible(nodes[m][2], point, flight_s=longest_gap)
            candidate = score[m] + gain - (0.0 if plausible else HOP_PENALTY)
            if candidate > best or (candidate == best and best_from != -1 and m < best_from):
                best, best_from = candidate, m
        score[n], back[n] = best, best_from

    chosen = {}
    if nodes:
        n = max(range(len(nodes)), key=lambda k: (score[k], -k))
        while n != -1:
            chosen[nodes[n][0]] = nodes[n]
            n = back[n]

    targets = {}
    for s in sessions:
        targets[s.pk] = {**original_position(s), **_UNCORRECTED}
    for idx, (s, orig) in enumerate(points):
        node = chosen.get(idx)
        if node is None:
            targets[s.pk] = _cleared_target(orig, "implausible jump against this sensor's neighbouring scans")
        elif node[1] == "fused":
            targets[s.pk] = _fused_target(
                orig, (node[2][0], node[2][1], node[3]),
                "original off this sensor's track; the fused fix taken at the same moment fits it",
            )
    return targets


def clean_session_locations(dry_run: bool = False, revert: bool = False, session_model=None) -> dict:
    """Re-evaluates every session's position from its original. Returns
    counts: `changed` rows, and how many end up `fused` / `cleared`.
    `revert=True` restores every original instead."""
    from django.db import transaction

    if session_model is None:
        from .models import ScanSession as session_model

    by_sensor: dict = {}
    for s in session_model.objects.order_by("sensor_id", "started_at", "pk").iterator(chunk_size=2000):
        by_sensor.setdefault(s.sensor_id, []).append(s)

    changed = []
    counts = {CORRECTED_FUSED: 0, CORRECTED_CLEARED: 0}
    for sessions in by_sensor.values():
        if revert:
            targets = {s.pk: {**original_position(s), **_UNCORRECTED} for s in sessions}
        else:
            targets = _choose(sessions)
        for s in sessions:
            target = targets[s.pk]
            target["location_provider"] = target.get("location_provider") or ""
            if target["location_correction"]:
                counts[target["location_correction"]] += 1
            if any(getattr(s, f) != target[f] for f in _FIELDS):
                for f in _FIELDS:
                    setattr(s, f, target[f])
                changed.append(s)

    if not dry_run and changed:
        with transaction.atomic():
            session_model.objects.bulk_update(changed, _FIELDS, batch_size=1000)
    return {"sessions": sum(map(len, by_sensor.values())), "changed": len(changed), **counts,
            "dry_run": dry_run, "revert": revert}
