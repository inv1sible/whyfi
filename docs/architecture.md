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

## Sighting tables

Every page with a "Sighting history" table renders `components/SightingTable`.
It takes the radio-specific columns and appends **Coordinates** and
**Observed** itself, so those two are always present, always last, and always
in that order — consistency by construction rather than by convention. Before
it existed there were six hand-rolled versions: the SSID group page omitted
the coordinates entirely (the data was there, the row mapping dropped it), and
none of them sorted.

Sorting comes from the existing `useSortableData` + `SortableTh` pair used by
the overview tables. Coordinates sort by latitude — an ordering has to pick an
axis, and north-to-south at least groups a walked route sensibly.

The search box is wired here too. It was already rendered above these tables
but filtered nothing, which is worse than not having one.

## Printable reports

Every map page — the Heatmap and all five entity detail pages — has a **Print
report** button producing an A4 report: a header stating what is shown and the
exact view that produced it, the coverage map, then the charts and tables.

**Print CSS alone cannot do this**, which is the whole reason for the button:

- `RadioMap` fits its bounds *once* (`hasFitOnceRef`) so polling can't yank the
  view, meaning the printed map would otherwise be wherever you last panned.
- Leaflet caches its container's pixel size, so the stylesheet's mm-based box
  leaves it addressing tiles for the old screen width until `invalidateSize()`.
- Tiles load asynchronously and `onbeforeprint` is synchronous — the native
  print flow has nowhere to wait.

So `RadioMapHandle.prepareForPrint()` resizes, re-frames and awaits tiles, and
`PrintReportButton` calls it before `window.print()`. Shared wiring lives in
`hooks/useDeviceReport.tsx` (`useReportPrinting`, `useReportViewSettings`).

Two things that are deliberate rather than incidental:

- **Preparation failure and print failure are handled separately.** They used
  to share one `try`, so anything thrown while re-framing skipped
  `window.print()` entirely and the button just went quiet. If the browser
  never fires `beforeprint`, the button now says so on screen — a control that
  silently does nothing is indistinguishable from a broken app.
- **Print reorders, rather than hides, the page** (`.app-main > section`
  becomes a flex column). The order is title (`-3`), summary (`-2`), coverage
  map (`-1`), everything else (`0`), spec lists last (`1`). The map is hoisted
  above all the page content so it is always on page 1 regardless of what
  precedes it in the DOM, but stays *below* the summary — a reader needs to
  know what they are looking at before they look at it. `ReportHeader` renders
  its title and field block as two siblings rather than one wrapper so those
  three can be ordered independently. ARFCN/PHY/open-ports detail is still
  printed, as an appendix.
- **Tile settling is polled, not event-driven.** Waiting on a single `load`
  hangs when the re-frame needed no new tiles; checking `isLoading()` once
  reads false *before Leaflet has queued the new requests*, which is what made
  the printed basemap intermittently blank. `waitForTiles` now requires the
  layer to be idle continuously for a settle window, after a minimum wait,
  capped so an unreachable tile server can't wedge the report.
  `PrintReportButton`'s own timeout must stay above that cap or it pre-empts
  the settling it exists to allow.

The report header always states the range, focus area, display mode and
selected scan. That is not decoration: a coverage map with no record of the
filter that produced it isn't reportable, and an unstated focus area would make
it a lie. OSM attribution is kept visible in print for the same reason — it's a
licence condition, not chrome.

## On-phone results: two passes, no history

The app can show what a scan found, not just how many — tap a radio chip on
the Scan tab (or on the Dashboard) for a table of that radio's rows, and a
Dashboard tab totals everything heard so far.

The constraint shaping this is that **the phone deliberately keeps nothing**.
Its only table is a write-then-delete upload outbox (`PendingScanEntity`,
dropped the moment upload succeeds), so it has no scan history to browse and
gaining one would mean a Room migration, a retention policy, and a phone that
holds survey data instead of forwarding it.

It doesn't need one. `ScanCoordinator.runScan()` already returns the full
payload and `runOnePass()` used to discard it; `ScanUiState` now keeps the
**last two** — enough for "what's here" and "what changed", bounded by
construction, and matching how `lanDevices` already retains LAN rows. Longer
history is the backend's job, and it already has every pass ever uploaded.

Three things follow, each surfaced in the UI rather than hidden:

- **`SurveyTally` counts unique devices, not passes.** One compact record per
  distinct BSSID/tower/MAC, so an hour of continuous scanning costs a few
  hundred bytes per device rather than a payload every 30 seconds.
- **The tally lives in `ScanForegroundService` and dies with it.** That makes
  it honestly a "this session" figure, which the Dashboard says outright.
- **An empty previous list is ambiguous** — radio switched off, or nothing
  heard? The payload doesn't record which, so `ScanDiff` reports *no
  comparison* rather than calling everything new. BLE additionally warns that
  MAC rotation makes new/gone partly an artifact; no attempt is made to
  re-link a rotated address to its device.

`RadioFormat.kt` transcribes `band_for_frequency`, `channel_for_frequency` and
`security_type_from_capabilities` from `backend/scans/serializers.py` so the
phone and the PWA can't disagree about the same sighting. That is a mirrored
pair like `RemoteControlAgent.IDLE_BACKOFF` — change one side, change both.
`RadioFormatTest` is pinned to the same cases as the backend's
`SecurityParsingTests`.

One subtlety in the UI layer: the service is bound **once**, in `WhyfiApp`,
and passed down. Per-screen binding leaves a moment with zero clients on every
tab switch, and since the service calls `stopSelf()` when idle, that moment is
enough for it to be destroyed and recreated empty — taking the retained passes
with it.

## LAN sweep: which network is "the" network

The LAN sweep derives its address range from `java.net.NetworkInterface`, not
from `ConnectivityManager.activeNetwork`. That is a correction, not a
preference: with a VPN up, `activeNetwork` *is* the VPN, and taking the first
IPv4 address on it yields the tunnel's own `/32`. The scanner then reported
"the current network has no other addresses on it" while the phone was sitting
on a populated `/24` — a confident, wrong claim about the user's network.

`LanScanner.inspectNetworks()` returns every IPv4 interface with a verdict
(`usable`, or why not), classified by name: `wlan*`/`ap*` WiFi, `eth*`/`usb*`
wired, `tun*`/`ppp*`/`ipsec*`/`wg*` tunnel, `rmnet*`/`ccmni*` mobile. Tunnels
and mobile interfaces are refused with their own reasons — a tunnel has no
broadcast domain behind it, and sweeping a carrier subnet would be scanning
other people's machines. WiFi outranks wired outranks anything else.

The same list is rendered on the LAN screen under **Network interfaces**,
whether or not the sweep runs, so a refusal can be checked against what a tool
like Portdroid reports rather than taken on trust. Two related invariants:

- **An empty device list means "swept, nobody answered" — never "couldn't
  sweep."** Callers check `unavailableReason()` first. The two were previously
  the same value, which is what made this bug invisible.
- **A sweep that can't run uploads nothing.** It used to create a
  zero-observation `ScanSession`, which then consumed a slot in the
  "last N scans" filter while carrying no information.

## Adaptive scan cadence

A phone on a desk re-scans the same airwaves all day; a phone in a car covers
new ground every second. Those deserve very different intervals, so the
scanner picks one from its own motion state — Stationary (10 min default),
Walking (1 min), Driving (30 s).

**Detection is fused, from platform sensors only.** Play Services' Activity
Recognition API would hand over STILL/WALKING/IN_VEHICLE ready-made, but this
project ships without Play Services (same reason FCM was rejected), so
`motion/MotionDetector.kt` uses three tiers, best first: the API 24
`STATIONARY_DETECT`/`MOTION_DETECT` hardware triggers, then
`SIGNIFICANT_MOTION` plus duty-cycled accelerometer windows, then the
accelerometer alone. **No new runtime permission** — step counting would
classify walking more directly but needs `ACTIVITY_RECOGNITION` on API 29+,
and asking for "physical activity" access in order to save battery is a bad
trade on a scanner app.

The two signals are fused because they fail in opposite directions:

- The **motion sensor** is nearly free and reacts in seconds, but cannot tell
  walking from driving.
- **Speed** separates them precisely, but only arrives when a pass takes a
  position — once every 10 minutes while stationary, far too slow to notice
  you've started walking. It is therefore read from positions the scanner
  already records, never by waking the GPS to ask.

`motion/MotionClassifier.kt` holds the judgement and is deliberately pure (no
Android types, every method takes the time), so it can be unit-tested. Its
non-obvious rules:

- Leaving **Driving** needs a five-minute still period, against ninety seconds
  from Walking. A car at a red light is still for a minute, and a smooth ride
  produces little accelerometer signal — so dropping to the 10-minute cadence
  there risks stranding a moving survey.
- A **stale speed sample decays to Walking**, not to whatever was last
  believed. "Moving with no position fix" is usually indoors on foot.
- Repeated "still" reports must not restart the dwell timer, or the tier-3
  poller (which reports still every 30 s) would never reach Stationary at all.

When movement resumes, the detector sets a flag that cuts the current sleep
short — otherwise walking away from a desk would go unnoticed for the rest of
a 10-minute interval, which is most of the ground worth mapping.

**The three intervals live on `SensorScanPolicy`, not only on the phone**, so
the app and `/remote` configure the same thing; while remote control is armed
the policy wins and is written into local settings, so the two can't show
different numbers for the same behaviour. The device reports
`reported_motion_state` and `reported_effective_interval_seconds` — without
those, a phone deliberately idling at 10-minute intervals is indistinguishable
from a broken one, both in the web UI and on the Scan screen.

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
