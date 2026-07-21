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

Only `/health/` and the three `/auth/` endpoints below are public.

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

## Read (session login required)

- `GET /access-points/` (includes `latest_channel`), `/access-points/{bssid}/`, `/access-points/{bssid}/wifi-observations/`
- `GET /scan-sessions/` (includes `location_accuracy_meters`/`location_provider`), `/scan-sessions/{id}/wifi-observations/`, `/.../cell-observations/`, `/.../ble-observations/`, `/.../satellite-observations/`, `/.../lan-observations/`
- `GET /channel-congestion/?band=2.4GHz|5GHz|6GHz&since=` — defaults to a rolling last-24h window if `since` is omitted (a single scan session under-represents "what channels are in use around here")
- `GET /cell-observations/?mcc=&mnc=&since=`
- `GET /ble-observations/?device_type=&since=&identifier=` — `identifier` matches either `ble_mac` or `stable_identifier`, for one device's sighting history; also includes each sighting's `latitude`/`longitude` (from its scan session)
- `GET /satellite-observations/`
- `GET /lan-observations/?since=`
- `GET /heatmap/?source=wifi|cellular|ble&bounds=<sw_lat>,<sw_lng>,<ne_lat>,<ne_lng>&since=`
- `GET /app/latest/` — latest **successful** Android release metadata + download URL (session **or** sensor token); 404 while a build is in progress or none has ever succeeded
- `GET /sensors/` — list, **never includes the token** (see write endpoints below for the one time it's shown)
- `GET /android-build/status/` — most recent build attempt (any status), including a live log tail while `QUEUED`/`BUILDING`

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

## Public

- `GET /health/`
