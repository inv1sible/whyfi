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
    to inspect associated stations, and toggle type filters. Unassociated cards show their observed probe activity even while collapsed. Auto applies current observations to the RSSI/Latest sort;
    Manual freezes the display until the reload icon beside Manual is tapped.
    Check category tables and counts after switching. Their Manufacturer column
    comes from the bundled OUI table; a blank match is shown as —, and camera
    labels are tentative because many cameras use generic Wi-Fi chip addresses.
    PCAP recording is on by default and the bottom control says **PCAP on/off**; the choice persists across app restarts. Nearby's bottom statistics show whether recording is armed, off, or how large the current capture is. Stop and Start continue the same local mission, including its log, PDF and PCAP. The PDF is refreshed after each stop; earlier PCAP packets remain in the same file up to the 200 MB cap. Nearby's mission row always shows the app version and **Continue**/**Reset** actions. Reset closes the current mission and clears favorites and hidden APs without deleting its files. Continue opens a picker for local missions with a log that have not been uploaded, including missions interrupted before a PDF was made, and restores their favorites and hidden APs. The same action remains in Missions. The backend currently treats repeat uploads of the same mission ID as unchanged, so uploaded missions remain replayable but cannot be reopened for recording. Only closed missions can be uploaded or deleted.
    AP cards have a hide icon immediately left of the favorite star. Hiding an AP removes it from Nearby, the live AP table and the live all-AP map while retaining its snapshots, PDF and PCAP. The bottom **Hidden APs (N)** chip is off by default; select it to review hidden APs and restore any of them, including APs no longer heard in the current capture.
    Star a device while
    monitoring and allow the location prompt; approximate location is usable
    but produces a wider uncertainty region. Walk through several separated
    positions. The command bar shows the number of location-tagged radio
    samples and the currently selected GPS/network/fused source. Fixes up to
    180 seconds from a packet can be paired, with speed-aware travel uncertainty added. Open **Location** beside Nearby to see the phone's Android
    position on the map even before a radio sample is saved. The compact line below the legend
    shows the best recent fix by accuracy adjusted for age. The phone marker and replay route hold their position when a new fix falls within the previous fix's uncertainty; better accuracy or convincing movement resumes updates. Raw fixes remain in the mission log, and AP correlations still use those raw fixes. Tap ⓘ
    in the bottom command bar to slide down diagnostics showing permission, provider status, raw coordinates,
    accuracy, fix age, and why pinned radio readings were not geotagged.
    Pinned AP/station estimates and uncertainty circles appear on the same
    map; the favorite chips below the command bar filter All or one selected
    device and its sampled-RSSI cones. Location reloads saved readings and labels from the open mission even when a device is no longer being heard. The All AP estimates chip adds every AP with location-paired radio readings; tap an AP estimate marker to focus and star it. Dense maps show compact estimate markers until one is selected. Their labels use the known SSID/device
    name or vendor plus the complete MAC address. Every AP with an RSSI timestamp is also paired with the recorded phone fixes even if it was never starred. Search the AP table and tap a row to open its location readings and provisional estimate on the map. A single sighting only marks the receiver area, not the AP's proven position. The map opens around the most
    recent measurement points so a long mission does not force a distant view;
    older points remain available by panning or zooming out. Android receiver fixes are saved independently of favorites, so a mission can show the phone's route even without a device estimate. The ⌖ button follows the phone or replay position while active and keeps the chosen zoom; tap it again to stop following. Refresh is also in the bottom command bar. Channels remains available from Nearby.
    During monitoring, a field prompt appears after two minutes without a newly observed AP or a receiver position beyond the reported location uncertainty. It asks the operator to move; poor or missing location data does not count as proven movement.
    Select a saved mission or one of its position targets in **Missions** to jump
    to Location. The target opens centered on its estimate when geotagged
    readings exist; otherwise the map says that no location was recorded.
    During replay the compact bottom bar shows play/pause, mission time and status
    instead of Start/Stop and PCAP. Its slider drives Location and the
    AP/station/category tabs together; Nearby remains live and has no replay counter.
    Use **Live** to leave replay. Missions
    has no bottom command bar. New missions save periodic device metadata
    snapshots for those tabs; older missions can replay their geotagged
    favorites but cannot reconstruct unrecorded device roles or associations.
    Stop monitoring and confirm the foreground notification clears when the
    service is otherwise idle. Open **Missions** to verify the PDF, log, and
    optional PCAP exist with nonzero sizes. The PDF includes a count of
    observed EAPOL-Key frames; this count alone does not prove a complete
    handshake or a usable WPA hash. The first pages of a newly generated PDF
    list each pinned device's coordinates or the reason none could be
    calculated; the AP observations section lists receiver-correlated position hints for all observed APs. Detail pages for pinned devices include map-backed estimates, uncertainty, and
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
    records show a generic label and complete MAC. New logs retain receiver fixes and device snapshots, so a delayed Android fix can be paired with a pinned radio reading during replay. A mission with neither geotagged radio readings nor receiver fixes cannot be localized retroactively from its PCAP alone; backend postprocessing would need separately recorded position data.
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
