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

## Data model

One event = one `ScanSession` (a scan pass, or a LAN sweep), geotagged
(including accuracy + provider — see `location_accuracy_meters`/
`location_provider`) and timestamped, owned by a `Sensor` (the reporting
device). Each radio type that session observed gets rows in its own
`<Radio>Observation` table, all FK'd to that session: `WiFiObservation`,
`CellObservation`, `BLEObservation`, `SatelliteObservation`,
`LANObservation`. See `docs/api.md` for the ingest payload shape and read
endpoints.
