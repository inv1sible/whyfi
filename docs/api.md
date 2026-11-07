# API reference (`/api/v1/`)

Two independent auth mechanisms, layered on top of each other:

- **Session login** (browser/PWA) — every read endpoint requires a logged-in
  Django session, the same admin account used for `/admin/` (see
  `DJANGO_SUPERUSER_*` in `.env`). There is no separate multi-user system.
- **Sensor token** (Android app) — `Authorization: Token <sensor-token>`,
  issued per device via the PWA's Settings > Sensors tab (or Django admin),
  authorizes ingest only (and `/app/latest/`, which accepts either). There
  is no *public* self-registration endpoint — creating a sensor is itself a
  session-authenticated action (a human managing their own devices), not
  something a device can do for itself.

Only `/health/` and the three `/auth/` endpoints below are public. That
includes `/media/` (the built APKs): it's served through a login-gated view
rather than a bare `static_serve`, and accepts either a session or a
short-lived signed `?t=` token scoped to that one file. `download_url` in
`/app/latest/` always carries such a token, which is what keeps the Download
page's QR code scannable from the phone being sideloaded — a browser with no
whyfi session.

## Auth endpoints

- `POST /auth/login/` — `{"username": "...", "password": "..."}` → sets a
  session cookie.
- `POST /auth/logout/` — clears the session cookie.
- `GET /auth/session/` — `{"authenticated": true, "username": "..."}` or
  `{"authenticated": false}`. Also sets the `csrftoken` cookie
  (`@ensure_csrf_cookie`) — the frontend calls this on every app load before
  anything else, specifically so that cookie exists in time for any
  session-authenticated POST (see below).

### CSRF for session-authenticated writes

Login/logout don't need a CSRF token (no session exists yet at login;
forcing a logout via CSRF is harmless/idempotent). Every *other*
session-authenticated POST (currently just `/android-build/trigger/`) does:
send the `csrftoken` cookie's value as an `X-CSRFToken` header. `api/client.ts`'s
`post()` helper already does this automatically. This was missing in an
earlier version and failed exactly as you'd expect the first time it was
exercised against a real browser session — see `MEMORY.md`.

## Ingest

`POST /scan-sessions/` (sensor token required)

```json
{
  "client_scan_id": "a1b2c3d4-...",
  "started_at": "2026-07-16T10:00:00Z",
  "completed_at": "2026-07-16T10:00:03Z",
  "latitude": 48.1351,
  "longitude": 11.5820,
  "location_accuracy_meters": 12.5,
  "location_provider": "gps",
  "wifi_observations": [
    {"bssid": "aa:bb:cc:dd:ee:ff", "ssid": "MyNetwork", "rssi": -55,
     "frequency_mhz": 2437, "capabilities": "[WPA2-PSK-CCMP][ESS]"}
  ],
  "ftm_observations": [
    {"bssid": "aa:bb:cc:dd:ee:ff", "success": true, "distance_mm": 4231,
     "distance_std_dev_mm": 120, "rssi": -52, "status": "success"}
  ],
  "cell_observations": [
    {"mcc": "262", "mnc": "01", "radio_type": "LTE", "is_serving_cell": true,
     "signal_dbm": -85, "rsrp": -95, "rsrq": -10, "sinr": 12}
  ],
  "ble_observations": [
    {"ble_mac": "11:22:33:44:55:66", "rssi": -70, "tx_power": -12,
     "manufacturer_data": "004c:1219...", "service_uuids": [],
     "manufacturer_records": ["004c:1219...", "0006:0109..."],
     "service_data": ["0000fd5a-0000-1000-8000-00805f9b34fb:01..."]}
  ],
  "satellite_observations": [
    {"constellation": "GPS", "svid": 14, "cn0_db_hz": 34.5,
     "elevation_degrees": 61.2, "azimuth_degrees": 210.0, "used_in_fix": true}
  ],
  "lan_observations": [
    {"ip_address": "192.168.1.42", "mac_address": "", "hostname": "printer.local",
     "open_ports": [80, 443]}
  ]
}
```

Every `*_observations` array is optional/independently empty. Idempotent on
`client_scan_id` — replaying the same payload returns the existing session
(200) instead of duplicating rows.

In `ble_observations`, `manufacturer_records` (`"cccc:hex"`, company id in hex,
data without the two company-id bytes) and `service_data` (`"uuid:hex"`) are
the complete advertisement, both optional (max 32 entries of 4096 chars).
APKs from before 2026-11 send only `manufacturer_data`, the first record in
the same format; it's used for signature matching only when
`manufacturer_records` is empty. Every BLE observation is labeled at ingest
with `signature_matches` (`[{id, rule}]`) and `signature_catalog_version`.

`ftm_observations` are Wi-Fi RTT/FTM **distance** measurements, not RSSI
readings — a separate table from `wifi_observations` because they're a
different kind of raw measurement (a metric distance plus its quality), and
observations stay immutable per measurement type so estimators can be re-run
against the originals. They arrive in their own session from the Android
app's manual "Range" action rather than as part of a regular scan pass, and
are what `/access-points/{bssid}/ftm-position/` solves against. A failed
ranging attempt is still recorded (`"success": false`) — knowing an AP
refused to range is a real result, not an absence of one.

Also **atomic** (`@transaction.atomic` on the ingest serializer's `create`),
and that isn't optional given the idempotency above: a session committed with
only part of its observations would make the device's retry a no-op — it hits
the "already exists" branch, gets a 201 and drops the payload from its outbox
— so the missing rows would never be written by anyone.

`security_type` is derived server-side from the raw `capabilities` string,
keyed on the *key management* token rather than the protocol prefix: Android
reports WPA3 as `[RSN-SAE-CCMP]`, containing the substring "WPA3" nowhere at
all. Values are `OPEN`, `WEP`, `WPA`, `WPA2`, `WPA3`, `WPA2_WPA3`, `OWE`
(Enhanced Open — encrypted, but joinable with no credential) and `UNKNOWN`.

## Read (session login required)

- `GET /access-points/` (includes `latest_channel`), `/access-points/{bssid}/`, `/access-points/{bssid}/wifi-observations/`
- `GET /scan-sessions/` (includes `location_accuracy_meters`/`location_provider`), `/scan-sessions/{id}/wifi-observations/`, `/.../cell-observations/`, `/.../ble-observations/`, `/.../satellite-observations/`, `/.../lan-observations/`
- `GET /channel-congestion/?band=2.4GHz|5GHz|6GHz&since=` — defaults to a rolling last-24h window if `since` is omitted (a single scan session under-represents "what channels are in use around here")
- `GET /cell-observations/?mcc=&mnc=&since=`
- `GET /ble-observations/?device_type=&since=&identifier=` — `identifier` matches either `ble_mac` or `stable_identifier`, for one device's sighting history; also includes each sighting's `latitude`/`longitude` (from its scan session)
- `GET /ble-devices/?signature=<fleet id>&signature_class=<KIND>` — each device carries `signature_ids` and `signatures` (`[{id, name, kind, rules}]`, where `rules` are the rule kinds that hit, e.g. `OUI` = address prefix only); the detail view adds each signature's `notes`/`attention_note`. Labels are pattern matches from the vendored Fieldwatch catalog (`backend/scans/ble_signatures.py`, update via `backend/scans/signatures/README.md`), informational only. An id no longer in the catalog is served as `{id, name: id, kind: "OTHER"}` until `manage.py relabel_ble_signatures` runs
- `GET /ble-signatures/` — `{catalog_version, results: [{id, name, kind}]}`, the BLE-capable catalog fleets
- `GET /satellite-observations/`
- `GET /lan-observations/?since=`
- `GET /heatmap/?source=wifi|cellular|ble&bounds=<sw_lat>,<sw_lng>,<ne_lat>,<ne_lng>&since=` — grid-bucketed points; **capped envelope**, see below
- `GET /access-points/coverage/?ssid_exact=&since=`, `GET /cell-towers/coverage/?since=`, `GET /ble-observations/coverage/?since=` — per-AP/tower/device list of distinct observed locations with a weight (mean RSSI/dBm from that spot), which is what the map's coverage shapes are built from. **Capped envelope**, see below
- `GET /access-points/{bssid}/position/?estimator=&compare=1`, and the same action on `/cell-towers/{tower_key}/` and `/ble-devices/{device_key}/` — one device's estimated position under a chosen estimator (`centroid`, `strongest`, `rssi_multilateration`, `ftm_multilateration`; unknown values fall back to the default rather than erroring). `compare=1` returns every estimator side by side plus their pairwise disagreement in metres, which is the point: close agreement means the position is well determined, a wide spread means at least one algorithm is being misled. Each estimate carries `residuals` — per reading, worst first — distinguishing a true fit residual (multilateration, which predicts a distance) from plain distance-to-estimate (centroid/strongest, which have no distance model). An estimator that can't run for a device returns `available: false` with a `reason` and `fell_back_to`, never a different algorithm's answer under the requested name. **Honours the standard `since`/`until`/`session_limit` window** — see `scans/estimators.py`
- The three `coverage` endpoints also accept `?estimator=` and return `estimated_position` per device, so the Heatmap/SSID-group pages get one estimate per device in a single request rather than one round trip each
- `GET /access-points/{bssid}/ftm-position/?include_grid=1&grid_steps=25&grid_span_m=120` — estimated **physical position** of one AP, solved from its Wi-Fi RTT/FTM ranging samples by weighted least squares (see `scans/localization.py`). Returns `{"available": false, "reason": ..., "sample_count": N}` when there aren't at least 3 successful readings from 2+ distinct observer positions — 2 unknowns can't be solved from less, and a confident-looking answer from insufficient data would be worse than none. When available, carries `lat`/`lng`, a 95% confidence ellipse (`uncertainty`, null when there's no degree of freedom left to estimate spread from), suggested `next_measurements`, and — only with `include_grid=1` — a `probability_grid` of peak-normalized relative likelihood. **This grid is the AP *position* heatmap and is not the RSSI coverage heatmap**; they answer different questions and the design deliberately keeps them apart
- `GET /ground-truth/`, `POST /ground-truth/`, `DELETE /ground-truth/{id}/` — operator-asserted true positions. `kind=AP` pins where an access point really is (scores estimators, feeds calibration); `kind=OBSERVER` pins where the phone really was for one scan session, correcting a bad GPS fix. POST upserts on `(kind, target_key)` — re-pinning is a correction, not an error. These **overlay** recorded data and never rewrite it; pass `?use_ground_truth=0` to any position/coverage endpoint to see the uncorrected answer
- `GET /floor-plans/`, `POST /floor-plans/` (multipart: `name`, `image`, `image_width_px`, `image_height_px`), `POST /floor-plans/{id}/calibrate/` — uploaded floor plans for indoor surveying. `image` is a `FileField` (Pillow isn't a dependency, and nothing server-side decodes the file — the browser sends the pixel dimensions). Calibration takes two matched anchor pairs, plan pixels ↔ real lat/lng, which fixes scale and rotation together; coincident anchors are rejected. Plans are served through the same login-gated `/media/` view as APKs
- `POST /ground-truth/` accepts `floor_plan` + `image_x`/`image_y` in place of `latitude`/`longitude` — the real position is derived server-side (`scans/floorplan.image_to_world`), so there's one implementation of the transform and the pin still feeds every estimator normally. Pixel coordinates are stored alongside so the plan can redraw the pin without inverting the transform
- `GET /floor-plans/{id}/coverage/?ssid_exact=&weak_threshold_dbm=` — the weak-spot view. Per placed measurement point, the **best** RSSI across all of that SSID's BSSIDs (a mesh hands you between radios; what you feel in a room is whichever is strongest), returned in pixel coordinates. `no_coverage` distinguishes "not heard here at all" from "heard, but faint" — the former is the worse finding
- `GET /localization/benchmark/` — every pinned access point scored under every estimator, with mean/median/best/worst error each. The only endpoint that says which estimator is *right* rather than merely different
- `GET /calibration/`, `POST /calibration/` — the generic path-loss constants alongside any fitted to your own measurements. POST re-fits from current ground truth (ordinary least squares on `rssi` vs `log10(distance)` — see `scans/calibration.py`) and reports `r_squared`, sample count and the distance range fitted over. Fitting never activates by itself: `POST /calibration/activate/` is separate, because a fitted model changes what every RSSI-derived distance means
- `GET /scan-sessions/export/?since=&until=&ssid_exact=&limit=` — every matching session and its observations **in the ingest schema**, so the file is a restore point rather than a report. `truncated` means the cap was hit and the file is *not* a complete backup
- `POST /scan-sessions/import_sessions/` — replays an exported bundle through the normal ingest serializer. Idempotent: sessions already present are skipped, so importing the same file twice changes nothing. Sensors are matched by exported name (created if absent) so provenance survives the round trip
- `GET /access-points/mesh-groups/?ssid_exact=&since=&radius_m=15` — BSSIDs grouped into probable physical access points (a BSSID is a radio, not a box). Each group carries a `confidence` and the `evidence` behind it (position agreement, vendor OUI, consecutive MACs, distinct bands) rather than asserting a verdict. Position alone never merges two BSSIDs — readings taken from one spot give every AP in earshot the same centroid, so corroborating identity evidence is required. **Capped envelope**, see below
- `GET /app/latest/` — latest **successful** Android release metadata + download URL (session **or** sensor token); 404 while a build is in progress or none has ever succeeded. `download_url` is absolute and carries a signed, path-scoped, 30-minute `?t=` token so it's fetchable from a phone with no session
- `GET /sensors/` — list, **never includes the token** (see write endpoints below for the one time it's shown).
  Each sensor carries a nested `scan_policy` object: the desired scanning state, whatever the device last
  reported about itself, plus derived `agent_online` and `policy_pending` flags. A sensor that has never been
  controlled or heard from returns defaults without a row being created — reads never write.
- `GET /android-build/status/` — most recent build attempt (any status), including a live log tail while `QUEUED`/`BUILDING`

### Shared read parameters

- `session_limit=N` — "last N scans" instead of a time cutoff (`since`), because a duration doesn't line up with how
  often you actually scanned. Takes precedence over `since` where both are accepted. For LAN endpoints it counts only
  sessions that contain LAN observations (a LAN sweep is its own session type and is much sparser).
- `limit=N` — row cap on the per-entity observation endpoints; defaults to 200, ceiling 1000.
- Unusable values for either (non-numeric, zero, negative) fall back to the default rather than erroring — they're view
  hints from the UI, not load-bearing input. They used to be passed straight to `int()` and into a queryset slice, where
  a negative number is an unhandled `ValueError` and therefore a 500.

### Capped envelopes (coverage + heatmap)

These four group raw observations in Python rather than paginating, so they're bounded by an observation cap
(20 000 for coverage, 5 000 for the heatmap) and return an envelope rather than a bare array:

```json
{"results": [...], "truncated": false, "observation_limit": 20000}
```

`truncated` means the cap was reached and the answer is knowingly incomplete. It exists because these used to be bare
arrays, silently sliced — a partial map was indistinguishable from a complete one, on a page whose whole job is showing
what's out there. The PWA surfaces it as a warning above the map (HeatmapPage/SSIDGroupPage); any new consumer should
too, rather than reading `results` and ignoring the flag.

## Write (session login + CSRF required)

- `POST /android-build/trigger/` — `{"version_name": "...", "notes": "..."}` (both optional; version_name
  defaults to a timestamp, version_code auto-increments). 202 with the new release (status `QUEUED`), or 409 if a
  build is already in progress. See `docs/android-setup.md` for how this actually gets built (no Docker socket
  access anywhere — a shared-volume file-signal protocol between the backend and the always-on `android-builder`
  watcher).
- `POST /sensors/` — `{"name": "...", "sensor_type": "android"}` (`sensor_type` optional). 201 with the new
  sensor **including its token** — the only response that ever includes it; copy it now, paste it into the
  Android app's Settings screen.
- `POST /sensors/{id}/regenerate-token/` — invalidates the old token immediately, returns the new one (again,
  only shown this once). Use this if a token is lost — there's no way to retrieve an existing one afterwards.
- `POST /sensors/{id}/scan-policy/` — set the *desired* scanning state for one device. Partial: send only what
  you're changing. Accepts `remote_scan_enabled`, `scan_interval_seconds`, `heartbeat_interval_seconds`,
  `include_wifi|cellular|ble|gnss`. Every write bumps `policy_revision`. 400 if `scan_interval_seconds` is under
  30 while `include_wifi` is set (Android throttles WiFi scans to 4 per 2 minutes, so a shorter interval doesn't
  produce more scans) — the check considers the resulting state, so re-enabling WiFi against an already-stored
  short interval is rejected too. Hard floor is 15s with WiFi off.
- `POST /sensors/{id}/scan-now/` — increments `scan_now_nonce`, asking for exactly one pass without changing the
  running mode. The device echoes the nonce back once it's run it, giving at-most-once semantics with no queue.
- `POST /sensors/{id}/reset-counters/` — increments `reset_counters_nonce`, zeroing the device's session
  tallies (completed passes). Same nonce/echo shape as scan-now, and deliberately does *not* bump
  `policy_revision` — it's a one-off action, not a change to desired state. Needed because those counters
  otherwise only reset when the scan service stops, which under remote control is never.

## Remote scanning control (sensor token)

- `POST /sensors/me/heartbeat/` — the device half of remote control, and the only endpoint besides ingest that a
  sensor token can reach. The body is what the device is currently doing (all `reported_*` fields, all optional
  — an older APK omitting one must not 400); the response is the desired policy. One round trip, called every
  `heartbeat_interval_seconds`. `me` resolves from the token, so a device can only ever address itself.

  The device cannot write desired state: the request serializer accepts `reported_*` fields only. `last_heartbeat_at`
  is stamped from the server's clock, never the device's, which keeps clock skew out of the online/offline
  determination.

  `agent_online` is judged against the cadence the device *actually* polls at, not `heartbeat_interval_seconds`:
  while armed but not scanning it backs off to `min(interval * 4, 60s)` to save battery, and the staleness window
  (`expected_heartbeat_interval_seconds * 2 + 15s`) mirrors that. Computing it from the configured interval instead
  left 5s of margin at the default and made a healthy idle device at `heartbeat_interval_seconds=15` flap between
  online and offline forever. If you change the backoff in `RemoteControlAgent`, change the constants in
  `sensors/models.py` with it.

  Note this endpoint runs frequently, and `SensorTokenAuthentication` bumps `Sensor.last_seen_at` on every
  authenticated request — so `last_seen_at` means "last contact". Use `last_scan_upload_at` for "last actually
  contributed data".

## Public

- `GET /health/`
