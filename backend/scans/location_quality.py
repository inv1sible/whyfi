"""Scan position quality checks and the non-destructive history cleanup.

Before 2026-11 the Android app took each scan's position from
getLastKnownLocation() with no age, accuracy or jump checks, so stored
history has stale and coarse positions (some kilometres off). The app now
selects fixes live (scan/FixSelector.kt). This module handles the backend
side. See docs/superpowers/plans/2026-11-07-live-scan-location.md.

Two rules, both ported from or modelled on Fieldwatch (MIT):
- `disagrees_with_fused`: a session's position is far from the platform
  fused fix the phone captured at the same moment. Applied at ingest and in
  the cleanup.
- `is_spike`: a position unreachable from both neighbouring sessions of
  the same sensor *and* forming an out-and-back (Fieldwatch's
  `Geo.despikeOnce` geometry plus `hopPlausible`), so fast but consistent
  travel is never mistaken for a spike.

Nothing is deleted. The original position moves to `raw_*`, the session
gets the fused fix (when that is sound) or no position, and
`location_correction` says which. `--revert` puts the originals back. All
the map and estimator code keeps reading `latitude`/`longitude` unchanged.
"""

from __future__ import annotations

import math

FUSED_DISAGREE_MIN_M = 100.0
FUSED_DISAGREE_FACTOR = 3.0
SPIKE_MAX_SPEED_MPS = 42.0
SPIKE_MIN_HOP_M = 40.0
SPIKE_MIN_DT_S = 0.8
SPIKE_RETURN_FACTOR = 0.4

CORRECTED_FUSED = "fused"
CORRECTED_CLEARED = "cleared"


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


def _hop_implausible(a, b) -> bool:
    d = distance_m(a[0], a[1], b[0], b[1])
    if d <= SPIKE_MIN_HOP_M:
        return False
    return d / max(abs(b[2] - a[2]), SPIKE_MIN_DT_S) > SPIKE_MAX_SPEED_MPS


def is_spike(prev, cur, nxt) -> bool:
    """`prev`/`cur`/`nxt` are (lat, lng, seconds). A spike shoots out and
    comes back: both legs implausibly fast, and the neighbours close
    together relative to either leg."""
    if not (_hop_implausible(prev, cur) and _hop_implausible(cur, nxt)):
        return False
    d_ab = distance_m(prev[0], prev[1], cur[0], cur[1])
    d_bc = distance_m(cur[0], cur[1], nxt[0], nxt[1])
    d_ac = distance_m(prev[0], prev[1], nxt[0], nxt[1])
    return d_ac < SPIKE_RETURN_FACTOR * d_ab and d_ac < SPIKE_RETURN_FACTOR * d_bc


def ingest_correction(fields: dict) -> dict:
    """For a new session's location fields: the corrected values (plus raw
    and correction fields) when the position disagrees with its own fused
    fix, else {}."""
    if not disagrees_with_fused(
        fields.get("latitude"), fields.get("longitude"), fields.get("location_accuracy_meters"),
        fields.get("fused_latitude"), fields.get("fused_longitude"), fields.get("fused_accuracy_meters"),
    ):
        return {}
    return {
        **_raw_fields(fields),
        "latitude": fields["fused_latitude"],
        "longitude": fields["fused_longitude"],
        "location_accuracy_meters": fields.get("fused_accuracy_meters"),
        "location_provider": "fused",
        "location_correction": CORRECTED_FUSED,
        "location_correction_reason": _fused_reason(fields),
    }


def _raw_fields(fields: dict) -> dict:
    return {
        "raw_latitude": fields.get("latitude"),
        "raw_longitude": fields.get("longitude"),
        "raw_location_accuracy_meters": fields.get("location_accuracy_meters"),
        "raw_location_provider": fields.get("location_provider", "") or "",
    }


def _fused_reason(fields: dict) -> str:
    d = distance_m(fields["latitude"], fields["longitude"], fields["fused_latitude"], fields["fused_longitude"])
    return f"{d:.0f} m from the fused fix taken at the same moment"


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


def _plan(sessions) -> dict:
    """session id -> target fields, for one sensor's sessions in time order.
    Always computed from the original positions, so re-running is stable."""
    targets = {}
    track = []  # (session, effective (lat, lng, t), was replaced by fused)
    for s in sessions:
        orig = original_position(s)
        fields = {**orig, "fused_latitude": s.fused_latitude, "fused_longitude": s.fused_longitude,
                  "fused_accuracy_meters": s.fused_accuracy_meters}
        correction = ingest_correction(fields) if orig["latitude"] is not None else {}
        targets[s.pk] = correction or {**orig, "raw_latitude": None, "raw_longitude": None,
                                       "raw_location_accuracy_meters": None, "raw_location_provider": "",
                                       "location_correction": "", "location_correction_reason": ""}
        lat, lng = targets[s.pk]["latitude"], targets[s.pk]["longitude"]
        if lat is not None and lng is not None:
            midpoint = s.started_at + (s.completed_at - s.started_at) / 2
            track.append((s, (lat, lng, midpoint.timestamp()), bool(correction)))

    for i in range(1, len(track) - 1):
        session, point, replaced = track[i]
        if not is_spike(track[i - 1][1], point, track[i + 1][1]):
            continue
        orig = original_position(session)
        target = targets[session.pk]
        fused = (session.fused_latitude, session.fused_longitude, point[2])
        if not replaced and session.fused_latitude is not None and session.fused_longitude is not None \
                and not is_spike(track[i - 1][1], fused, track[i + 1][1]):
            target.update(_raw_fields(orig), latitude=fused[0], longitude=fused[1],
                          location_accuracy_meters=session.fused_accuracy_meters, location_provider="fused",
                          location_correction=CORRECTED_FUSED,
                          location_correction_reason="spike away from both neighbouring scans; fused fix used")
        else:
            target.update(_raw_fields(orig), latitude=None, longitude=None, location_accuracy_meters=None,
                          location_provider="", location_correction=CORRECTED_CLEARED,
                          location_correction_reason="spike away from both neighbouring scans"
                          + (" (fused fix too)" if replaced else ""))
    return targets


_FIELDS = [
    "latitude", "longitude", "location_accuracy_meters", "location_provider",
    "raw_latitude", "raw_longitude", "raw_location_accuracy_meters", "raw_location_provider",
    "location_correction", "location_correction_reason",
]


def clean_session_locations(dry_run: bool = False, revert: bool = False, session_model=None) -> dict:
    """Re-evaluates every session's position from its original. Returns
    counts: `changed` rows, and how many end up `fused` / `cleared`.
    `revert=True` restores every original instead."""
    from django.db import transaction

    if session_model is None:
        from .models import ScanSession as session_model

    qs = session_model.objects.order_by("sensor_id", "started_at", "pk")
    by_sensor: dict = {}
    for s in qs.iterator(chunk_size=2000):
        by_sensor.setdefault(s.sensor_id, []).append(s)

    changed = []
    counts = {CORRECTED_FUSED: 0, CORRECTED_CLEARED: 0}
    for sessions in by_sensor.values():
        if revert:
            targets = {s.pk: {**original_position(s), "raw_latitude": None, "raw_longitude": None,
                              "raw_location_accuracy_meters": None, "raw_location_provider": "",
                              "location_correction": "", "location_correction_reason": ""} for s in sessions}
        else:
            targets = _plan(sessions)
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
