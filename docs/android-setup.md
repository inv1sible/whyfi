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
3. Grant Bluetooth permission (Android 12+: `BLUETOOTH_SCAN`). On Android
   9–11 there is no prompt — the legacy `BLUETOOTH`/`BLUETOOTH_ADMIN`
   permissions are granted at install; confirm the Scan tab opens without
   crashing.
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
9. For USB Monitor, connect a supported RTL8812AU/8821AU adapter by OTG.
   In Settings, run **USB monitor test** until it reports a successful run
   with received frames. The always-visible **Monitor** tab enables itself
   after that test. Earlier tests did not save this unlock state, so the test
   must be repeated once with a build containing the unlock.
10. On the Monitor tab, confirm the USB selector opens when no adapter is
    present and collapses after selection. Start with **All** channels; the
    channel panel can narrow the next session to selected or observed channels.
    Confirm the top view tabs and the fixed Start/Stop, PCAP, and channel
    controls remain visible while Nearby cards scroll. Location and Missions
    sit next to Nearby. Tap the Favorites, Unassociated, or Access points
    heading to hide/show its cards. Favorite an AP or station, expand an AP
    to inspect associated stations, and toggle type filters. Auto applies current observations to the RSSI/Latest sort;
    Manual freezes the display until the reload icon beside Manual is tapped.
    Check category tables and counts after switching. Star a device while
    monitoring and allow the location prompt; approximate location is usable
    but produces a wider uncertainty region. Walk through several separated
    positions. The command bar shows the number of location-tagged radio
    samples and the currently selected GPS/network/fused source. Fixes up to
    60 seconds from a packet can be paired, with their age added to the
    uncertainty. Open **Location** beside Nearby to see the phone's Android
    position on the map even before a radio sample is saved. The compact line below the legend
    shows only the best recent fix by accuracy adjusted for age. Tap ⓘ
    in the bottom command bar to slide down diagnostics showing permission, provider status, raw coordinates,
    accuracy, fix age, and why pinned radio readings were not geotagged.
    Pinned AP/station estimates and uncertainty circles appear on the same
    map; the favorite chips below the command bar filter All or one selected
    device and its sampled-RSSI cones. Their labels use the known SSID/device
    name or vendor plus the last three MAC octets. Refresh and ⌖ Center are also
    in the bottom command bar; centering preserves map zoom. Channels remains available from Nearby.
    Select a saved mission or one of its position targets in **Missions** to jump
    to Location. The target opens centered on its estimate when geotagged
    readings exist; otherwise the map says that no location was recorded.
    During replay the bottom bar shows play/pause, mission time and status
    instead of Start/Stop and PCAP. Its slider drives Location, Nearby and the
    AP/station/category tabs together. Use **Live** to leave replay. Missions
    has no bottom command bar. New missions save periodic device metadata
    snapshots for those tabs; older missions can replay their geotagged
    favorites but cannot reconstruct unrecorded device roles or associations.
    Stop monitoring and confirm the foreground notification clears when the
    service is otherwise idle. Open **Missions** to verify the PDF, log, and
    optional PCAP exist with nonzero sizes. The PDF includes a count of
    observed EAPOL-Key frames; this count alone does not prove a complete
    handshake or a usable WPA hash. The first pages of a newly generated PDF
    list each pinned device's coordinates or the reason none could be
    calculated; detail pages include map-backed estimates, uncertainty, and
    route suggestions. Green-to-orange/red cones show relative RSSI toward sampled positions,
    not a measured antenna bearing; blue
    dots mark the phone's measurement positions. Map tiles use the same
    OpenStreetMap source as Mission and are cached. If tiles are unavailable,
    the GPS overlay and coordinate estimate remain in the PDF. Choose a mission
    or one of its named favorites in Missions to open the interactive Location
    replay; inspect the log or export the PCAP from the mission card. To remove
    a local mission, choose **Delete local mission** and confirm; this deletes
    its local PDF, log and PCAP but leaves any uploaded backend copy intact.
    The files live in the app-specific
    Android/data/com.whyfi.app/files/monitor-missions/<mission-id>/ directory
    and survive new captures and app updates; uninstalling the app removes
    this app-specific directory, so export or upload first. PDFs created by
    older builds are not rewritten; if their logs contain `geo_sample` events,
    the Location tab can still display them. Older logs without device-name
    records show a generic label and shortened MAC. A mission with no geotagged radio
    readings cannot be localized retroactively from its PCAP alone.
11. In Missions, tap **Upload to backend** on a completed capture. The WebUI's
    **Monitor Missions** page then lists the PDF, log, and optional PCAP for
    authenticated download. Uploading never removes the phone's files.
    A retry uses the same mission ID and does not make a duplicate. The
    reverse proxy must allow a request body above the 200 MB PCAP cap.
    Timeline replay covers saved geotagged readings and operator events; it
    does not decode or reconstruct every PCAP frame.

## Permissions this app requests

`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`,
`READ_PHONE_STATE`, `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `UWB_RANGING`
(only if a "locate this device" action is used), `INTERNET`,
`ACCESS_NETWORK_STATE`. On Android 9–11 (API 28–30) Bluetooth is instead
gated by the install-time `BLUETOOTH` and `BLUETOOTH_ADMIN` permissions,
declared with `maxSdkVersion="30"` — without them even reading
`BluetoothAdapter.isEnabled` throws `SecurityException`, which crashed the
Scan tab on those versions until PR #1. Don't drop them while `minSdk` is
below 31. The LAN
scanner needs no additional permission beyond `INTERNET`/`ACCESS_NETWORK_STATE`
already listed — it's plain TCP sockets, not a privileged API.
