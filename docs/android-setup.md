# Android app: build & test notes

## Building (no Android Studio, no terminal required)

The normal path is the PWA's Download page → **Build Android App** button
(see `docs/deployment.md`) — `docker compose up` already starts
`android-builder` as an always-on watcher, so this works out of the box.

Under the hood: the backend writes a build request to the shared
`build_output` volume; `android-builder`'s poll loop (`android/docker-build.sh`)
picks it up, runs a headless Gradle build (`gradle assembleRelease`) with
the Android SDK cmdline-tools and licenses pre-accepted, and writes the
resulting (signed, if a keystore is present) APK plus a state file back to
that same volume. The backend picks up "SUCCESS" on the next status poll
and registers the release automatically. No Docker socket is involved
anywhere in this — see `MEMORY.md` for why that was deliberately avoided.

If you want to trigger this from a script instead of the UI:
`POST /api/v1/android-build/trigger/` (session-authenticated, needs the
`X-CSRFToken` header — see `docs/api.md`), then poll
`GET /api/v1/android-build/status/`.

If you want to iterate with Android Studio locally instead, you can still
open `/android` as a normal Gradle project — the Docker path is just the one
this repo guarantees works with nothing but Docker installed.

The Dockerfile pins a specific Android cmdline-tools build number
(`ANDROID_CMDLINE_TOOLS_VERSION` build arg, currently `14742923`). Google
periodically removes old builds from its download server; if the build
fails on the `curl` step with a 404, look up the current build number at
https://developer.android.com/tools/releases/cmdline-tools and pass
`--build-arg ANDROID_CMDLINE_TOOLS_VERSION=<new-number>`.

## Why a physical device is required for testing

The build container only *compiles* — it does not emulate any radio hardware.
An emulator's virtual WiFi/cellular/BLE/GNSS do not produce real
`ScanResult`/`CellInfo`/BLE-advertisement/`GnssStatus` data, so none of this
app's actual functionality can be verified without a real device.

Manual test checklist on a physical device (API 28+ to properly exercise the
WiFi scan-throttle path):

1. Install the APK (enable "install unknown apps" for your browser/file
   manager first).
2. Grant location permission when prompted; confirm the app warns you if
   device location services are off (WiFi/BLE/GNSS results are empty or
   stale without it).
3. Grant Bluetooth permission (Android 12+: `BLUETOOTH_SCAN`).
4. Enter the sensor token in Settings (backend URL is pre-filled from
   `WHYFI_PUBLIC_URL` in `.env` at build time if set — still editable, and
   never pre-filled with a token, since a token baked into a shared APK
   would be the same for everyone who downloads it).
5. Tap "Scan Now" — confirm WiFi, cellular, BLE, and GNSS results all appear
   together in one session on the backend within one polling interval.
6. Switch to the LAN tab, tap "Scan LAN" — confirm it takes noticeably
   longer than a regular scan (it's sweeping the whole subnet) and that
   discovered devices show up under the PWA's LAN Devices page. MAC
   addresses will likely be blank on a non-rooted device — that's expected.
7. Turn on airplane mode mid-scan, confirm the scan still completes locally
   and queues in the outbox; turn connectivity back on and confirm it syncs
   without duplicating.
8. Install a second build with a higher version code over the first without
   uninstalling — confirms the signing keystore is stable across builds.

## Permissions this app requests

`ACCESS_FINE_LOCATION`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`,
`READ_PHONE_STATE`, `BLUETOOTH_SCAN`, `UWB_RANGING` (only if a "locate this
device" action is used), `INTERNET`, `ACCESS_NETWORK_STATE`. The LAN
scanner needs no additional permission beyond `INTERNET`/`ACCESS_NETWORK_STATE`
already listed — it's plain TCP sockets, not a privileged API.
