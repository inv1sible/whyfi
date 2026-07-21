"""Triggers and tracks Android builds without ever giving this container
Docker socket access (that would be host-root-equivalent — see MEMORY.md).
Instead, the backend and the always-running `android-builder` watcher
service talk exclusively through plain files on their shared `build_output`
volume:

  backend writes  build_output/request.txt        -> "<release-id>"
  builder writes  build_output/<release-id>.state  -> "BUILDING" | "SUCCESS" | "FAILED"
  builder writes  build_output/<release-id>.log    -> full gradle output
  builder writes  build_output/<release-id>.apk    -> the built APK, on success

No JSON/RPC library needed on the shell side, no daemon needed on the
Django side — `sync_build_status` just re-reads these files on each status
poll from the frontend.
"""

from pathlib import Path

from django.core.files import File
from django.db.models import Max
from django.utils import timezone

from .models import AppRelease

BUILD_SIGNAL_DIR = Path("/build-output")
LOG_TAIL_CHARS = 4000


def trigger_build(version_name: str = "", notes: str = "") -> AppRelease:
    in_flight = AppRelease.objects.filter(
        build_status__in=[AppRelease.BuildStatus.QUEUED, AppRelease.BuildStatus.BUILDING]
    ).exists()
    if in_flight:
        raise ValueError("A build is already in progress.")

    next_version_code = (AppRelease.objects.aggregate(Max("version_code"))["version_code__max"] or 0) + 1
    version_name = version_name.strip() or timezone.now().strftime("%Y.%m.%d-%H%M")

    release = AppRelease.objects.create(
        version_code=next_version_code,
        version_name=version_name,
        release_notes=notes,
        build_status=AppRelease.BuildStatus.QUEUED,
        build_started_at=timezone.now(),
    )

    BUILD_SIGNAL_DIR.mkdir(parents=True, exist_ok=True)
    (BUILD_SIGNAL_DIR / "request.txt").write_text(str(release.id))
    return release


def sync_build_status(release: AppRelease) -> AppRelease:
    """Re-reads the shared signal files for an in-flight build and updates
    the DB row accordingly. No-op for already-finished releases."""
    if release.build_status not in (AppRelease.BuildStatus.QUEUED, AppRelease.BuildStatus.BUILDING):
        return release

    log_path = BUILD_SIGNAL_DIR / f"{release.id}.log"
    if log_path.exists():
        release.build_log_tail = log_path.read_text(errors="replace")[-LOG_TAIL_CHARS:]

    state_path = BUILD_SIGNAL_DIR / f"{release.id}.state"
    if not state_path.exists():
        release.save(update_fields=["build_log_tail"])
        return release  # watcher hasn't picked up the request yet

    state = state_path.read_text().strip()

    if state == "BUILDING":
        release.build_status = AppRelease.BuildStatus.BUILDING
        release.save(update_fields=["build_status", "build_log_tail"])

    elif state == "SUCCESS":
        apk_path = BUILD_SIGNAL_DIR / f"{release.id}.apk"
        if apk_path.exists():
            with open(apk_path, "rb") as apk_fileobj:
                release.apk_file.save(f"whyfi-{release.version_name}.apk", File(apk_fileobj), save=False)
        release.build_status = AppRelease.BuildStatus.SUCCESS
        release.build_finished_at = timezone.now()
        release.save()

    elif state == "FAILED":
        release.build_status = AppRelease.BuildStatus.FAILED
        release.build_finished_at = timezone.now()
        release.save(update_fields=["build_status", "build_finished_at", "build_log_tail"])

    return release
