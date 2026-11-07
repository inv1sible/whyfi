# Live Scan Location Implementation Plan

> **For agentic workers:** Execute task-by-task with TDD (failing test first), one commit per task.

**Goal:** Stop scan positions from jumping. Each scan pass gets the best *current* fix from a live location subscription with Fieldwatch-style gates, instead of whatever `getLastKnownLocation` happens to hold; stored history gets its implausible positions corrected or cleared without losing the originals.

**Why (measured 2026-11-07 on the live DB):** whyfi never subscribes to location while scanning. `ScanCoordinator.resolveLocation()` takes the newest of the GPS/network *last-known* fixes at pass start, with no age, accuracy or jump checks. Of 1,459 geotagged sessions, 944 used the network provider; 45 were more than 1 km and 65 more 100 m–1 km from the platform fused fix captured at the same moment. GPS last-known fixes had a median accuracy of 51 m (cold/stale). The fused fix is stored but never used, and `LocationSourcePreference` is dead code.

**Source:** Fieldwatch commit `2f79baf9` (MIT): `FieldwatchApp.startLocationUpdates` / `acceptFix` / `seedLastKnown`, `Geo.hopPlausible` / `despikePath` (`SPIKE_MAX_SPEED_MPS = 42`, `SPIKE_MIN_HOP_M = 40`, `SPIKE_MIN_DT_MS = 800`, accuracy cap 75 m, age cap 30 s). Reuses whyfi's own `MonitorLocationPolicy.effectiveAccuracy`.

## Decisions (locked)

- **Live subscription while scanning** (`scan/LiveLocationTracker.kt`): GPS every 1 s with no distance floor, network every 4 s, platform `FUSED_PROVIDER` every 2 s on API 31+ (no Play Services, per MEMORY.md). Started when a pass or continuous scanning starts, stopped when the continuous loop ends or a single pass finishes. Runs in `ScanForegroundService` (already `foregroundServiceType=location`). Battery cost accepted: GPS stays on while scanning, as in Fieldwatch.
- **Pure selector** (`scan/FixSelector.kt`, no Android types, unit-tested):
  - Accept a fix only if coordinates are valid and finite, accuracy ≤ 75 m and age ≤ 30 s.
  - Jump gate (`hopPlausible`): reject a fix that moves more than 40 m from the last accepted fix at more than 42 m/s (over ≥ 0.8 s; the limit rises to 1.5 × measured speed + 5 m/s when either fix reports a speed, capped at 100 m/s, so an Autobahn or a train doesn't freeze the track) **unless it is markedly more precise** (accuracy ≤ 0.75 × the anchor's), so a coarse anchor can't lock out good GPS. Three consecutive rejected fixes that agree among themselves (pairwise plausible) re-anchor, so one bad anchor can't freeze the track.
  - Pass position: among accepted fixes, the one with the lowest `MonitorLocationPolicy.effectiveAccuracy(accuracy, |fix time − reference|, speed)`, reference = pass midpoint, within 30 s of it.
  - Times use `elapsedRealtimeNanos` (immune to wall-clock changes).
- **Pass timing:** location is resolved at the **end** of a pass, against its midpoint (GPS has had the pass to warm up; the radios were heard across the pass).
- **Fallback:** with no live fix accepted, the last-known GPS/network/fused fixes go through the same gates (age ≤ 30 s, accuracy ≤ 75 m). If none pass, the session gets **no position** (not mapped) rather than a stale one. `fused_*` keeps reporting the freshest fused fix for comparison.
- **Upload:** new optional `location_fix_age_ms` (fix age at the pass midpoint).
- **Backend history cleanup** (`scans/location_quality.py`, command `clean_session_locations [--dry-run] [--revert]`), non-destructive. A session is suspect when:
  - (a) its position is more than `max(100 m, 3 × (accuracy + fused accuracy))` from its own fused fix; or
  - (b) it is a spike: unreachable from **both** neighbours of the same sensor (more than 40 m and more than 42 m/s on each leg, over ≥ 0.8 s) **and** an out-and-back (the neighbours are within 0.4 × either leg of each other, Fieldwatch's `despikeOnce` geometry), so a fast train isn't mistaken for spikes.

  Originals move to `raw_latitude` / `raw_longitude` / `raw_location_accuracy_meters` / `raw_location_provider`. The position becomes the fused fix when (a) applies and the fused fix itself isn't suspect, otherwise it is cleared. `location_correction` = `"fused"` / `"cleared"` plus `location_correction_reason`. `--revert` restores originals. Ingest applies (a) to every new payload, including from old APKs. All 34 existing coordinate reads stay unchanged.
- **Export** writes the original (raw) coordinates, so an import re-runs the same checks.
- Remove the dead `LocationSourcePreference` and correct the Settings text.
- Out of scope: GNSS interference detection, raw GNSS measurements, Kalman smoothing, and Monitor's location (it has its own policy).

## Review Focus

1. A stale far-away first fix must not lock out correct later fixes. Tests: `preciseFixOverridesCoarseAnchor`, `consistentRejectsReanchor`.
2. A coarse network fix jumping away from a GPS track is rejected. Test: `networkJumpIsRejected`.
3. Nothing accepted: no position rather than a stale one. Test: `staleOnlyGivesNull`.
4. Tracker lifecycle: listeners removed when scanning stops (no GPS left on when idle).
5. Cleanup is idempotent, reversible and never deletes. Tests: `test_clean_is_idempotent`, `test_revert_restores_originals`.

## Tasks

1. `FixSelector` + tests. Commit `Select scan positions with Fieldwatch-style fix gates`.
2. `LiveLocationTracker`, service lifecycle, `ScanCoordinator` resolves at pass end, `location_fix_age_ms` DTO, remove dead preference. Commit `Keep live location running while scanning`.
3. Backend: fields + migration, `location_quality.py`, ingest check (a), command, export of raw coordinates, tests. Commit `Correct implausible scan positions without losing originals`.
4. PWA: correction shown on Manage Scans and the scan session serializer. Commit `Show corrected scan positions`.
5. Deploy: dry-run the cleanup on a DB copy, deploy, apply, before/after stats, test APK + dex grep, docs/MEMORY, review, push.
