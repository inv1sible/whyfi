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
  "cell_observations": [
    {"mcc": "262", "mnc": "01", "radio_type": "LTE", "is_serving_cell": true,
     "signal_dbm": -85, "rsrp": -95, "rsrq": -10, "sinr": 12}
  ],
  "ble_observations": [
    {"ble_mac": "11:22:33:44:55:66", "rssi": -70, "tx_power": -12,
     "manufacturer_data": "4c00...", "service_uuids": []}
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
- `GET /satellite-observations/`
- `GET /lan-observations/?since=`
- `GET /heatmap/?source=wifi|cellular|ble&bounds=<sw_lat>,<sw_lng>,<ne_lat>,<ne_lng>&since=` — grid-bucketed points; **capped envelope**, see below
- `GET /access-points/coverage/?ssid_exact=&since=`, `GET /cell-towers/coverage/?since=`, `GET /ble-observations/coverage/?since=` — per-AP/tower/device list of distinct observed locations with a weight (mean RSSI/dBm from that spot), which is what the map's coverage shapes are built from. **Capped envelope**, see below
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
