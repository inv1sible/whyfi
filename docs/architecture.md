# Architecture

## Components

```
 Android app (Kotlin)                     Browser / installed PWA
 - WifiScanManager, CellularManager,      - React + Vite, installed on
   BleDeviceScanner, GnssStatusManager,     Android or iOS home screen
   NfcReaderManager                       - Read-only viewer everywhere
 - Room outbox + WorkManager sync         - On iOS: can only view data an
        |  POST /api/v1/scan-sessions/       Android device collected
        v                                        |
 +----------------------------------------------+---+
 |            Django + DRF backend (gunicorn)        |
 |  sensors/  scans/  distribution/                   |
 |  WhiteNoise (SPA static)  +  MEDIA (APK downloads)  |
 +------------------------+----------------------------+
                          |
                     Postgres
```

One plain HTTP port is exposed by the `backend` container; there is no
internal nginx/Caddy layer (see `MEMORY.md` for why) — front it with your own
reverse proxy (e.g. Nginx Proxy Manager) for TLS/vhosts.

## Why scanning can only happen on Android

Neither iOS Safari nor Android Chrome (nor any browser engine) exposes an API
to enumerate nearby WiFi networks, cellular cells, or Bluetooth devices to web
content — there is no such web platform API on either OS. iOS additionally
never exposes this to third-party *native* apps either (no public API; the
one legacy mechanism, `NEHotspotHelper`, requires a special Apple entitlement
essentially unavailable to indie developers).

Android **native** apps can:
- Scan WiFi via `WifiManager` (throttled to ~4 scans/2 minutes in the
  foreground since Android 9).
- Read the phone's own serving/neighboring cellular info via
  `TelephonyManager` (no external hardware, no spectrum scanning).
- Scan BLE advertisements via `BluetoothLeScanner`.
- Read GNSS per-satellite signal quality via `GnssStatus.Callback`.
- Discover devices on its own WiFi subnet via a TCP-connect sweep (no raw
  ICMP/root needed — see `docs/android-setup.md`'s LAN scanner notes).

All of the above require `ACCESS_FINE_LOCATION` (Android ties WiFi/BLE/GNSS
data to location permission) and, for BLE on Android 12+, `BLUETOOTH_SCAN`.

Because of this, the Android app is a first-class native scanning client
(not a WebView wrapper around the PWA) that POSTs to the same REST API any
browser/PWA client reads from. See `docs/android-setup.md` for permission and
build details.

## Remote scanning control

The PWA can start and stop scanning on a phone, set the cadence, and pick
which radios run. What makes the design non-obvious is that **the backend can
never reach a phone**, so the control flow is inverted.

Two independent walls, either of which alone defeats a push-based design:

1. Since **Android 12** an app may not start a foreground service from the
   background at all (`ForegroundServiceStartNotAllowedException`). The
   exemptions are `BOOT_COMPLETED`, high-priority FCM, and notification
   interaction — "a server said so" isn't one.
2. Since **Android 11** a background-*started* location foreground service
   gets no location access without `ACCESS_BACKGROUND_LOCATION` — so even a
   service that did start would scan nothing.

Both restrictions exist specifically to prevent covert remote activation of a
device's sensors, which is a property worth keeping rather than working
around. Phones also sit behind carrier NAT with no inbound route.

So the phone asks. `ScanForegroundService` (the same service that already
runs scans — there is no second service) hosts a poll loop that calls
`POST /sensors/me/heartbeat/`, sending what it's doing and receiving what it
should be doing, then converges via the same `startContinuous`/`stopContinuous`
entry points the on-screen buttons use.

The backend stores **desired state**, not commands (`SensorScanPolicy`). That
makes "stop, then start again immediately" two writes where the last wins,
and lets a phone that was offline for an hour catch up correctly with no
queue to drain and no stale instruction to replay. `policy_revision` /
`reported_policy_revision` (Kubernetes' generation/observedGeneration idea)
let the UI distinguish *pending* from *applied but not scanning* — and the
structured `reported_*` fields then explain the latter ("Bluetooth is off",
"permissions not granted").

Consequences worth knowing:

- Remote control only reaches a phone whose agent is **already running**. It
  must be armed by hand once, in the app, and it stays off after a reboot or
  force-stop. The `/remote` page says so.
- While armed the foreground-service notification stays up permanently. That
  is the honest signal — the phone really is standing by to scan on command.
- Deliberately excluded: `ACCESS_BACKGROUND_LOCATION`, start-on-boot, FCM,
  and any silent mode.
- The service's `foregroundServiceType` stays `location|connectedDevice`.
  Adding `dataSync` for the polling would subject the whole service to
  Android 15's 6h/24h FGS timeout the moment `targetSdk` reaches 35.
- Long-polling was rejected: gunicorn runs 3 *sync* workers, so a handful of
  hanging requests would deadlock the backend, PWA included.

## Data model

One event = one `ScanSession` (a scan pass, or a LAN sweep), geotagged
(including accuracy + provider — see `location_accuracy_meters`/
`location_provider`) and timestamped, owned by a `Sensor` (the reporting
device). Each radio type that session observed gets rows in its own
`<Radio>Observation` table, all FK'd to that session: `WiFiObservation`,
`CellObservation`, `BLEObservation`, `SatelliteObservation`,
`LANObservation`. See `docs/api.md` for the ingest payload shape and read
endpoints.
