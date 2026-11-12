"""USB monitor missions → ordinary WiFi observations (user, 2026-11-11).

An uploaded mission log (mission.jsonl) holds periodic `devices_snapshot`
rows (the native adapter's device TSV with `at_ms` wall time and `clock_ms`
elapsedRealtime) and `receiver_fix` rows (the phone's own position on the
same boot clock). This module rebuilds every AP signal reading the app's
MonitorSampleReconciler.kt rebuilds, pairs it with a receiver fix exactly
like MonitorLocationPolicy.select, and stores the result as ScanSessions +
WiFiObservations, so the existing estimators, Localization, Heatmap and
reports use the USB data without a parallel concept (AGENT.md).

Source: a Sensor of type USB_ADAPTER named "<phone>_USB" with `parent` set
to the uploading phone. Its token is never handed out and the sensor is
created deactivated, so nothing can authenticate as it.

Grouping: one ScanSession per receiver fix used (its position is the
session's geotag), one observation per AP per session (strongest reading).
Idempotent: client_scan_id = "usb:<mission>:<provider>:<fix boot ms>", and a
re-import first deletes that mission's sessions.

Caveat: the adapter's RSSI comes from the Realtek PHY status, not Android's
ScanResult, so it is on a somewhat different scale; filter by sensor to
compare the sources.
"""
import json
import logging
from bisect import bisect_left, bisect_right
from datetime import datetime, timezone

from django.db import transaction
from django.utils import timezone as dj_timezone

from sensors.models import Sensor

from .ble_signatures import load_catalog, merge_signature_ids
from .models import AccessPoint, MonitorMission, ScanSession, SecurityType, WiFiObservation
from .serializers import band_for_frequency, security_type_from_capabilities
from .wifi_signatures import match_wifi, parse_wifi

log = logging.getLogger(__name__)

ROLE_AP = 1
MAX_FIX_AGE_MS = 180_000  # MonitorLocationPolicy.MAX_FIX_AGE_MS
MIN_MOTION_M_PER_S = 2.0
# Readings whose position is worse than this are not imported: a coarse
# network fix would drag the AP estimate far more than it helps.
MAX_EFFECTIVE_ACCURACY_M = 100.0
DEDUPE_MS = 10_000


def client_scan_prefix(mission_id) -> str:
    return f"usb:{mission_id}:"


def frequency_for_channel(channel: int) -> int | None:
    if 1 <= channel <= 13:
        return 2407 + 5 * channel
    if channel == 14:
        return 2484
    if 32 <= channel <= 177:
        return 5000 + 5 * channel
    return None


def security_for(security: str, akms: str, pairwise: str) -> tuple[str, str]:
    """(SecurityType, capabilities_raw) from the adapter's RSN/WPA fields."""
    raw = " ".join(p for p in (security, akms, pairwise) if p).strip()
    if not security:
        return SecurityType.UNKNOWN, raw
    if security.lower() == "open":
        return SecurityType.OPEN, raw
    if "WEP" in (security + pairwise).upper():
        return SecurityType.WEP, raw
    # Android-style "[<proto>-<akm>-<cipher>]" so the phone's classifier applies.
    caps = f"[{security.replace('+', '/')}-{akms.replace(',', '+')}-{pairwise.replace(',', '+')}]"
    kind = security_type_from_capabilities(caps)
    if kind == SecurityType.UNKNOWN:
        upper = security.upper()
        kind = SecurityType.WPA2 if "WPA2" in upper or "RSN" in upper else SecurityType.WPA if "WPA" in upper else kind
    return kind, raw


def _int(value, default=None):
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def parse_log(lines):
    """(snapshots, fixes) from mission.jsonl lines; malformed rows are skipped."""
    snapshots, fixes = [], []
    for line in lines:
        try:
            row = json.loads(line)
        except (ValueError, TypeError):
            continue
        event = row.get("event")
        if event == "devices_snapshot":
            at, clock = _int(row.get("at_ms"), 0), _int(row.get("clock_ms"), 0)
            if at > 0:
                snapshots.append((at, clock, row.get("tsv") or ""))
        elif event == "receiver_fix":
            try:
                fix = {
                    "at_ms": int(row["at_ms"]), "boot_ms": int(row["boot_ms"]),
                    "provider": str(row.get("provider") or "unknown")[:16],
                    "lat": float(row["lat"]), "lng": float(row["lng"]),
                    "accuracy_m": float(row.get("accuracy_m", 1000.0)),
                    "speed_mps": float(row.get("speed_mps", 0.0)),
                }
            except (KeyError, TypeError, ValueError):
                continue
            if (fix["boot_ms"] > 0 and -90 <= fix["lat"] <= 90 and -180 <= fix["lng"] <= 180
                    and fix["accuracy_m"] >= 0 and 0 <= fix["speed_mps"] <= 100):
                fixes.append(fix)
    snapshots.sort(key=lambda s: s[0])
    return snapshots, fixes


def ap_readings(snapshots, fixes, stats=None):
    """Every AP reading with its receiver fix, like MonitorSampleReconciler(apOnly).

    `stats`, when given, receives why readings were left out, so the import
    summary can say what the server did not keep (user, 2026-11-12: a day of
    indoor captures imported as "0 observations" with no hint why):
    "no_fix" (no receiver fix within MAX_FIX_AGE_MS), "poor_fix" (the best fix
    was worse than MAX_EFFECTIVE_ACCURACY_M), "unpaired" (older logs without
    the boot clock). Counted after deduplication, like the kept readings.
    """
    fixes = sorted(fixes, key=lambda f: f["boot_ms"])
    boots = [f["boot_ms"] for f in fixes]
    seen = {}
    out = []
    counts = {"no_fix": 0, "poor_fix": 0, "unpaired": 0}
    for at_ms, clock_ms, tsv in snapshots:
        for line in tsv.splitlines():
            f = line.split("\t")
            if len(f) < 20:
                continue
            roles = _int(f[1], 0)
            rssi, boot, channel = _int(f[17]), _int(f[18], 0), _int(f[19], 0)
            if not roles & ROLE_AP or rssi is None or boot <= 0 or channel <= 0:
                continue
            if clock_ms <= 0:
                counts["unpaired"] += 1  # older logs without the boot clock cannot be paired
                continue
            if boot > clock_ms + 2_000:
                continue
            mac = f[0].strip().lower()
            reading_at = at_ms - clock_ms + boot
            previous = seen.get((mac, boot))
            if previous is not None and abs(previous - reading_at) < DEDUPE_MS:
                continue
            seen[(mac, boot)] = reading_at
            best = None
            for fix in fixes[bisect_left(boots, boot - MAX_FIX_AGE_MS):bisect_right(boots, boot + MAX_FIX_AGE_MS)]:
                age = abs(fix["boot_ms"] - boot)
                effective = fix["accuracy_m"] + age / 1000.0 * max(MIN_MOTION_M_PER_S, fix["speed_mps"] * 1.5)
                if best is None or (effective, age) < (best[0], best[1]):
                    best = (effective, age, fix)
            if best is None:
                counts["no_fix"] += 1
                continue
            if best[0] > MAX_EFFECTIVE_ACCURACY_M:
                counts["poor_fix"] += 1
                continue
            out.append({
                "mac": mac, "at_ms": reading_at, "rssi": rssi, "channel": channel,
                "ssid": f[8], "security": f[26] if len(f) > 26 else "",
                "akms": f[27] if len(f) > 27 else "", "pairwise": f[28] if len(f) > 28 else "",
                "fix": best[2], "effective_accuracy_m": best[0],
            })
    if stats is not None:
        stats.update(counts)
    return out


def usb_sensor_for(phone: Sensor) -> Sensor:
    existing = Sensor.objects.filter(parent=phone, sensor_type=Sensor.SensorType.USB_ADAPTER).first()
    if existing:
        return existing
    return Sensor.objects.create(
        name=f"{phone.name}_USB"[:100], sensor_type=Sensor.SensorType.USB_ADAPTER, parent=phone, is_active=False,
    )


def _dt(ms: int) -> datetime:
    return datetime.fromtimestamp(ms / 1000.0, tz=timezone.utc)


def import_mission(mission: MonitorMission, force: bool = False) -> dict:
    """Imports one mission; returns (and stores) a summary. Safe to re-run.

    Serialized per mission: two uploads of the same mission at the same time
    (seen 2026-11-11: the app's log-only and log+PCAP jobs ran together on
    Wi-Fi) must import one after the other, or the second collides on
    client_scan_id. The lock on the mission row also makes the second import
    see the first upload's files.
    """
    with transaction.atomic():
        mission = MonitorMission.objects.select_for_update().get(pk=mission.pk)
        return _import_locked(mission, force)


def _import_locked(mission: MonitorMission, force: bool) -> dict:
    if mission.imported_at and not force:
        return mission.import_summary
    if mission.sensor is None:
        summary = {"status": "skipped", "reason": "the uploading sensor was deleted"}
    else:
        with mission.log.open("rb") as handle:
            snapshots, fixes = parse_log(line.decode("utf-8", "replace") for line in handle)
        dropped = {}
        readings = ap_readings(snapshots, fixes, dropped)
        groups = {}
        for r in readings:
            freq = frequency_for_channel(r["channel"])
            if freq is None:
                continue
            key = (r["fix"]["provider"], r["fix"]["boot_ms"])
            per_ap = groups.setdefault(key, {})
            if r["mac"] not in per_ap or r["rssi"] > per_ap[r["mac"]]["rssi"]:
                per_ap[r["mac"]] = {**r, "frequency": freq}
        summary = _store(mission, groups, len(snapshots), len(fixes))
        # What the position rule did, next to what it kept (see ap_readings).
        summary.update({
            "readings": len(readings),
            "dropped_no_fix": dropped["no_fix"], "dropped_poor_fix": dropped["poor_fix"],
            "dropped_unpaired": dropped["unpaired"],
            "position_limit_m": MAX_EFFECTIVE_ACCURACY_M,
            "best_fix_accuracy_m": min((f["accuracy_m"] for f in fixes), default=None),
        })
    mission.imported_at = dj_timezone.now()
    mission.import_summary = summary
    mission.save(update_fields=["imported_at", "import_summary"])
    return summary


@transaction.atomic
def _store(mission, groups, snapshot_count, fix_count) -> dict:
    usb = usb_sensor_for(mission.sensor)
    prefix = client_scan_prefix(mission.id)
    ScanSession.objects.filter(client_scan_id__startswith=prefix).delete()
    catalog = load_catalog()
    sessions = observations = 0
    bssids = set()
    latest = None
    for (provider, boot_ms), per_ap in groups.items():
        fix = next(iter(per_ap.values()))["fix"]
        times = [r["at_ms"] for r in per_ap.values()]
        session = ScanSession.objects.create(
            sensor=usb, client_scan_id=f"{prefix}{provider}:{boot_ms}"[:64],
            started_at=_dt(min(times)), completed_at=_dt(max(times)),
            latitude=fix["lat"], longitude=fix["lng"],
            location_accuracy_meters=round(max(r["effective_accuracy_m"] for r in per_ap.values()), 1),
            location_provider=provider,
            location_fix_age_ms=int(max(abs(fix["at_ms"] - t) for t in times)),
        )
        sessions += 1
        latest = max(latest or session.completed_at, session.completed_at)
        for mac, r in sorted(per_ap.items()):
            ap, created = AccessPoint.objects.get_or_create(bssid=mac, defaults={"ssid": r["ssid"]})
            if r["ssid"] and not ap.ssid:
                ap.ssid = r["ssid"]
            matches = match_wifi(parse_wifi(mac, ap.ssid, []), catalog)
            ap.signature_ids = merge_signature_ids(ap.signature_ids, matches)
            ap.save(update_fields=["ssid", "signature_ids", "last_seen_at"])
            security, raw = security_for(r["security"], r["akms"], r["pairwise"])
            WiFiObservation.objects.create(
                scan_session=session, access_point=ap, rssi=r["rssi"],
                frequency_mhz=r["frequency"], channel=r["channel"], band=band_for_frequency(r["frequency"]),
                security_type=security, capabilities_raw=f"USB: {raw}"[:255],
                signature_matches=matches, signature_catalog_version=catalog.version,
                observed_at=_dt(r["at_ms"]),
            )
            observations += 1
            bssids.add(mac)
    if latest:
        usb.last_scan_upload_at = latest
        usb.save(update_fields=["last_scan_upload_at"])
    return {
        "status": "imported", "source": usb.name, "sessions": sessions, "observations": observations,
        "access_points": len(bssids), "snapshots": snapshot_count, "receiver_fixes": fix_count,
    }


def import_safely(mission: MonitorMission, force: bool = False) -> None:
    """Upload hook: an import failure must never fail the upload itself."""
    try:
        import_mission(mission, force=force)
    except Exception:  # noqa: BLE001 — logged; the files are stored either way
        log.exception("USB mission import failed for %s", mission.id)
        MonitorMission.objects.filter(pk=mission.pk).update(
            import_summary={"status": "failed", "reason": "see server log"})
