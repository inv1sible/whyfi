# Project memory — whyfi

A committed decision log, distinct from any AI tool's personal memory system.
This is for humans and agents working *in this repo*, across any tool.
Update it when a non-obvious decision is made or reversed — don't let this go
stale, and don't duplicate what's already obvious from code/tests.

## Architecture decisions

- **No PWA/browser WiFi/cellular/Bluetooth scanning exists on iOS or Android.**
  This is a hard platform limitation, not a choice. iOS also blocks it for
  native apps. Consequence: the Android app is the only scanner; the PWA is
  always a viewer, and on iOS it can only ever view data an Android device
  collected. Don't try to "fix" this by looking for a web API — there isn't
  one.

- **No nginx or Caddy container.** The maintainer runs Nginx Proxy Manager in
  front of all their projects already, so TLS termination and vhost routing
  are handled externally. Adding an internal reverse proxy would just be a
  second layer doing nothing NPM doesn't already do. The backend serves
  everything (API, WhiteNoise-served SPA static assets, Django-media-served
  APK downloads) on one plain HTTP port. If you're tempted to add nginx
  "for production," don't — ask first, this was a deliberate simplification.

- **WiFi security is parsed from the *key management* token, not the protocol
  prefix.** Android builds `ScanResult.capabilities` as
  `[<protocol>-<key mgmt>-<cipher>]` and calls the protocol `RSN` — never
  `WPA3` — for everything SAE/OWE/Suite-B based. So a WPA3 network reads
  `[RSN-SAE-CCMP][ESS][MFPR][MFPC]` and contains the string "WPA3" nowhere at
  all. The original parser matched protocol names, which meant *every* WPA3,
  WPA2/WPA3-transition and OWE network was stored as `UNKNOWN`: grey "Unknown"
  badge in the PWA, and `?security=WPA3` matching nothing, ever. Same class of
  bug had the OPEN check as an exact `== "[ESS]"`, so the very common
  `[ESS][WPS]` was UNKNOWN too. Now keyed on SAE / PSK+SAE / EAP_SUITE_B_192 /
  OWE / PSK, with `RSN` treated as a WPA2 spelling. `OWE` is its own
  `SecurityType` value — Enhanced Open is encrypted but takes no credential,
  so folding it into OPEN (flagged red as "unencrypted") or WPA2 (implies a
  password) would both be wrong. `SecurityParsingTests` pins the exact strings;
  don't "simplify" this back to substring-matching scheme names.

- **Ingest atomicity is a decorator, and it's load-bearing.** `@transaction.atomic`
  on `ScanSessionIngestSerializer.create` — there is no `ATOMIC_REQUESTS` and
  the read endpoints don't need one. This was missing originally while three
  places (the serializer comment, AGENT.md, docs/api.md) claimed the endpoint
  was atomic. It matters *because* ingest is idempotent: a session committed
  with only some of its observations makes the device's retry a no-op — it
  matches on `client_scan_id`, gets a 201, deletes the payload from its outbox
  — so the missing observations are never written by anyone, silently and
  permanently. Tested by failing the satellite loop specifically (after WiFi,
  cell and BLE have already inserted) and asserting nothing survives.

- **`agent_online` is judged against the device's *actual* poll cadence.**
  A device that's armed but not scanning backs off to
  `min(heartbeat_interval * 4, 60s)` (`RemoteControlAgent.IDLE_BACKOFF` /
  `IDLE_MAX_MS`) — there's nothing to be responsive to and it's a phone on
  battery. The staleness window was originally `heartbeat_interval * 3 + 15`,
  computed from the configured interval rather than the real one: at the
  default 10s that's a 40s idle poll against a 45s window (5s of margin, so
  ordinary jitter reads as offline), and at `heartbeat_interval_seconds=15` the
  60s window and the capped 60s idle poll coincided *exactly*, so a healthy
  armed device flapped online/offline indefinitely. Now
  `expected_heartbeat_interval_seconds * 2 + 15`, where the expected interval
  mirrors the client's backoff — and keys on desired **and** reported scanning
  state agreeing, so enabling scanning doesn't tighten the window during the
  one poll before the device can possibly have picked it up. The constants in
  `sensors/models.py` and `RemoteControlAgent`'s companion object have to move
  together.

- **`/media/` (APK downloads) is login-gated, not a bare `static_serve`.**
  It was wired directly to `django.views.static.serve` with no auth, which
  contradicted the "only `/health/` and `/auth/*` are public" rule below — the
  APK was fetchable by anyone with the URL, and the names are predictable
  (`whyfi-<version_name>.apk`, version_name defaulting to a timestamp). Now
  `config/views.protected_media`, which takes either a logged-in session or a
  short-lived signed `?t=` token naming that one path
  (`distribution/download_tokens.py`).
  **The token is not optional convenience** — without it this change silently
  breaks the Download page's QR code, which exists precisely so you scan it
  with the phone you're about to sideload, and that browser has no whyfi
  session. That's also why the token can't be dropped in favour of "just log
  in on the phone too": the QR is the flow. `download_url` in `/app/latest/`
  therefore always carries a token; it's only ever minted into a response that
  required auth to fetch, it names a single path, and it expires (30 min,
  checked at request start so a slow mobile download isn't cut off).
  Returns JSON 403 rather than redirecting, because there is no Django login
  *view* here — a redirect would land on the SPA catch-all and return HTML to
  something that asked for a binary.

- **Coverage/heatmap responses are `{results, truncated, observation_limit}`
  envelopes.** They group raw observations in Python rather than paginating, so
  they're bounded (20 000 coverage / 5 000 heatmap). They used to be bare
  arrays with the cap applied as a silent slice, which made an incomplete
  answer indistinguishable from a complete one — on a map page, missing APs
  look exactly like a quiet neighborhood, and there's prior form for that
  confusion here (see "the heatmap showing nothing" below, where the data was
  fine and the *display* was the problem). `truncated` is computed by fetching
  one row past the cap rather than a second `COUNT(*)`. The PWA shows a
  `.warning-text` line above the map; keep new consumers doing the same
  instead of reading `results` and dropping the flag.

- **Anti-stalking tracker detection was designed, then explicitly cut.** An
  earlier plan draft included a `trackers` app with `SuspectedTracker`
  correlation/alerting ("this device has followed you across N locations").
  The maintainer rejected it outright — not deferred, rejected. BLE detection
  is just another passive `BLEObservation` log (device type is an
  informational badge, e.g. "possible AirTag"), with no derived alert state,
  no sighting-clustering job, no dismiss/"mark as mine" workflow. Do not
  reintroduce this without an explicit new ask.

- **UWB precision ranging only works with compatible trackers.** Apple's
  AirTag U1 ranging protocol is proprietary and not accessible to
  third-party Android apps. `UwbLocateManager` only attempts real UWB ranging
  when both the phone has UWB hardware *and* the target supports an
  Android-compatible ranging profile (e.g. Google Find My Device network
  partner trackers). Otherwise it degrades to BLE-RSSI proximity. Don't
  imply in UI copy that this works for any tracker — it doesn't.

- **APK signing keystore must persist across builds.** Android refuses
  in-place updates if the signing key changes between versions. The
  `android-builder` service is pointed at a keystore kept outside git
  (referenced via `.env`/docker secret, generated once). If you regenerate
  the keystore, every existing install will need to be uninstalled and
  reinstalled — treat that as a breaking, deliberate action, not routine
  maintenance.

- **Single ingest endpoint (`POST /api/v1/scan-sessions/`) for all radio
  types**, not one endpoint per radio. One physical scan pass on the phone
  naturally produces WiFi + cellular + BLE + GNSS readings from the same
  instant/location; splitting them into N calls breaks atomicity and
  multiplies retry/idempotency states. `client_scan_id` is the idempotency
  key for the whole session.

- **Android build pipeline is Docker-only by design** (Gradle + Android SDK
  cmdline-tools, no Android Studio, no emulator in the toolchain). Physical
  device is still required to exercise real radios; nothing in this repo
  emulates WiFi/cellular/BLE/GNSS/NFC hardware.

- **The "Build Android App" button does not give the backend Docker socket
  access.** The obvious way to let a web app trigger a Docker build is
  mounting `/var/run/docker.sock` into the container and shelling out to
  `docker`/`docker compose` — that's host-root-equivalent access from a
  container that's reachable via NPM, which directly contradicts "safe and
  secure." Instead: `android-builder` runs always-on (`restart:
  unless-stopped`, no `profiles:` gate) as a dumb poll loop
  (`android/docker-build.sh`) watching the shared `build_output` volume for
  a `request.txt` file containing a release UUID; it writes
  `<uuid>.state`/`.log`/`.apk` files back. `distribution/services.py`
  (`trigger_build`/`sync_build_status`) is the only thing that reads/writes
  those files on the Django side — the builder container never touches the
  database or the Docker API, and the backend never touches Docker at all.
  `AppRelease` doubles as both "a published release" and "a build attempt
  in flight" (`build_status` QUEUED/BUILDING/SUCCESS/FAILED) rather than a
  separate tracking model — `/app/latest/` filters to `SUCCESS` with a
  non-empty `apk_file` so an in-progress attempt never gets served as the
  current release. Don't reach for Docker socket access as a "simpler"
  alternative if this gets extended — it isn't simpler, it's a different
  risk category entirely.

- **CSRF bit everyone forgets, including this project once**: the trigger
  endpoint is a session-authenticated POST — Django's CSRF protection
  correctly rejected it (403) the first time it was exercised against a
  real login session, because `login`/`logout` deliberately skip
  `SessionAuthentication` (see below) and nothing else had ever set the
  `csrftoken` cookie. Fixed by decorating `/auth/session/` with
  `@ensure_csrf_cookie` (called on every app load, before anything else) and
  having `api/client.ts`'s `post()` read that cookie into an `X-CSRFToken`
  header. All of this was invisible to the test suite until a dedicated
  test used Django's real `Client(enforce_csrf_checks=True)` instead of
  DRF's `force_authenticate` (which bypasses CSRF middleware entirely) — see
  `distribution/tests.py`'s `CsrfProtectionTests`. If you add another
  session-authenticated write endpoint, use the existing `post()` helper
  rather than a bespoke fetch call, and consider adding a matching
  real-`Client` CSRF test, not just a `force_authenticate` one.

- **All API reads require login; only `/health/` and `/auth/*` are public.**
  Originally v1 shipped with open (`AllowAny`) read endpoints on the
  assumption of a LAN-trusted deployment. The maintainer is fronting this
  with Nginx Proxy Manager (i.e., potentially internet-reachable) and asked
  for it locked down. Reused Django's existing superuser/session auth rather
  than building a separate account system — logging into the PWA *is*
  logging into `/admin/`, same credentials, same session cookie. Sensor
  tokens (Android ingest) are untouched, layered independently via
  `ScanSessionViewSet.get_authenticators()` being action-aware (token for
  `create`, session for everything else) — don't collapse these back into
  one auth path. `/app/latest/` accepts *either* (session for the PWA's
  Download page, sensor token for the installed app's own update check).
  Login/logout deliberately skip DRF's `SessionAuthentication` (no session
  exists yet at login; a CSRF-forced logout is harmless/idempotent).
  **Correction, since superseded**: this originally said no CSRF-token
  plumbing was needed anywhere since all session-protected endpoints were
  GET — that stopped being true the moment `/android-build/trigger/` (a
  session-authenticated POST) was added, and it broke exactly as you'd
  expect (403 CSRF Failed) the first time it was actually exercised.
  Fixed by decorating `/auth/session/` with `@ensure_csrf_cookie` (the
  frontend calls it on every app load, before anything else, so the
  `csrftoken` cookie is guaranteed to exist by the time any POST happens)
  and having `api/client.ts`'s `post()` helper read that cookie and send it
  as `X-CSRFToken`. Any *new* session-authenticated write endpoint gets this
  for free through the same `post()` helper — don't reintroduce a
  CSRF-exempt shortcut for one instead of using it.
  Cross-origin use of the Settings page's "point at a different backend"
  override does **not** carry the session cookie (no CORS configured) — that
  path is now effectively view-only-if-served-from-that-origin-directly
  until someone asks for CORS+credentials to be wired up.

- **`api/client.ts` throws `ApiError(status, body, path)` on non-2xx, not a
  bare `Error` with a generic message.** The Sensors tab's first version
  caught any failure and always displayed "Could not create sensor." —
  which meant a real reported bug (sensor creation failing in the browser)
  couldn't be diagnosed at all from the user's description, even though the
  identical request via curl worked fine. Every catch block showing an
  error to the user should include `err.message` (see `describeError()` in
  `SensorsTab.tsx`/`DownloadPage.tsx`) — don't add a new "Could not X."
  catch-all without it; you'll regret it exactly the same way.

- **Gunicorn had no access logging at all originally** — `entrypoint.sh`'s
  `exec gunicorn ...` had no `--access-logfile`/`--error-logfile`, so when a
  scan silently failed to reach the backend, `docker compose logs backend`
  showed *nothing*, not even a rejected request. Fixed with
  `--access-logfile - --error-logfile -`. If you're debugging "the phone
  says it scanned but nothing shows up," check these logs first — before
  this fix, that check was impossible.

- **The Android app's backend URL is pre-fillable at build time
  (`WHYFI_PUBLIC_URL` → `BuildConfig.DEFAULT_BACKEND_URL`), the token
  deliberately isn't.** Same reasoning as the signing keystore: unlike the
  keystore, a token baked into the APK would be identical for every phone
  that downloads the same build from the Download page, defeating the
  entire point of per-device sensor tokens. Don't add a "pre-fill token"
  version of this without rethinking the whole one-token-per-device model
  first.

- **APK downloads are fetched with `downloadWithProgress()` (streaming
  `fetch()` + manual byte-count comparison against the server-reported
  `apk_size`), not a bare `<a href={download_url}>`.** A real APK install
  failure was reported after a build that both `apksigner verify` and a
  byte-identical signing-certificate comparison against a previously
  working build confirmed was fine server-side — meaning the corruption (if
  that's what it was) most likely happened in transit (flaky mobile
  network, or the reverse proxy mishandling a large binary response), and a
  plain anchor download gives the page no way to detect that; Android's
  installer just fails with an unhelpful generic "app not installed" and no
  clue why. Don't go back to a bare `<a href>` for this download — the
  size-mismatch check is the only thing that would actually surface that
  failure mode to the user instead of silently repeating it.

- **`SettingsRepository` uses plain `SharedPreferences`, not
  `androidx.security.crypto.EncryptedSharedPreferences`.** It originally
  did. A build that changed nothing about `SettingsRepository` (only
  `CellularManager`, `ScanCoordinator`, `ScanScreen`, and a `BuildConfig`
  field) started crashing immediately on launch — before any UI even
  rendered — specifically on GrapheneOS. `EncryptedSharedPreferences`
  initializes eagerly and unconditionally in `MainActivity.onCreate()`, and
  it touches Android Keystore directly; that library has been stuck at
  `1.1.0-alpha06` for years with a genuine history of Keystore-related
  crashes on hardened/nonstandard Keystore implementations. This was a
  reasoned best-guess fix made *without* an actual device crash log/stack
  trace in hand — if crashes persist after this change, the next step is
  getting a real `adb logcat` capture rather than guessing again. Don't
  reintroduce `EncryptedSharedPreferences` for this without a concrete
  reason; a sensor token in app-private `SharedPreferences` (standard
  Android sandboxing, same threat model as the vast majority of Android
  apps storing API tokens) is a reasonable tradeoff against crashing the
  whole app.

- **NFC was removed entirely, on purpose, not deferred.** It existed (a
  working `NfcReaderManager` + foreground dispatch + `NFCObservation`
  model/endpoints), but there was never a dedicated screen for it — it only
  fired if you tapped a tag while the app happened to be open, surfaced via
  a small status line. When asked "do we even scan NFC? if not cut it off,"
  the maintainer chose removal over building it a proper UI. Don't re-add
  `NfcReaderManager`/`NFCObservation`/the NFC manifest permission without an
  explicit new ask — this wasn't a bug fix, it was a deliberate feature cut.

- **The heatmap "showing nothing" turned out to be real data at a zoom
  level where it was invisible, not missing data.** Verified via direct API
  checks: the points were there, spanning ~250km because the user had
  traveled with the phone between scans. `fitBounds` correctly zoomed out
  to fit that whole span, which made the fixed-pixel-radius heat blobs
  imperceptibly small — indistinguishable from an empty map. Fixed with a
  time-range filter (Last hour/24h/7 days/All time, defaulting to 24h) so
  the default view is local and visible; "All time" is still available but
  explicitly warns about this exact effect. `channel-congestion` had a
  related issue (only ever looked at the single most-recent scan session,
  under-representing "what's actually around here") — now defaults to a
  rolling 24h window via the same `since` param the heatmap already
  supported. Lesson: when a user reports "I see nothing," verify the data
  exists server-side *before* assuming it's a bug in the query — here it
  was a zoom/visibility bug, not a missing-data bug, and those need
  different fixes.

- **LAN scanner uses a TCP-connect sweep, not ICMP ping.** Android apps
  can't open raw ICMP sockets without root, so `LanScanner` probes a
  handful of common ports per candidate host — a connection succeeding (or
  even just responding fast enough to imply a live host) serves as both the
  "is anyone home" check and the port scan in one pass, rather than two
  separate operations. MAC-address/vendor lookup via `/proc/net/arp` is
  attempted but usually returns nothing on modern non-rooted Android
  (restricted since API 23+) — degrades to a blank MAC rather than failing
  the scan. The sweep refuses subnets wider than /24 (>512 hosts) rather
  than trying to enumerate something like a /16. This is the one place in
  the app that makes active outbound connections rather than passively
  listening — see the updated `DISCLAIMER.md`.

- **Android's `Location` object's `.accuracy`/`.provider` were being
  captured and then thrown away** — `ScanCoordinator` read a `Location` for
  lat/lon but never looked at the other two fields. Added
  `location_accuracy_meters`/`location_provider` end to end (model →
  ingest serializer → Android DTO) since "where am I, how much can I trust
  this fix, and by what method" is a materially different (and more
  useful) question than a bare coordinate pair.

- **BLE scan "ending instantly" was `BluetoothAdapter.getDefaultAdapter()`
  silently returning null (or `bluetoothLeScanner` null when Bluetooth was
  off), not a timing bug.** `BleDeviceScanner.scan()` returned `emptyList()`
  immediately in either case — the `delay(durationMs)` was never reached, so
  a 6-second scan appeared to finish in ~0ms. Fixed by switching to
  `BluetoothManager`-based adapter lookup (the static getter is deprecated
  since API 33 and known-unreliable on hardened ROMs like GrapheneOS) and
  adding `unavailableReason()` so the UI states *why* a radio returned
  nothing instead of silently finishing. The same pattern was then applied
  to WiFi (`wifiManager.isWifiEnabled`) and cellular
  (`ServiceState.STATE_POWER_OFF`, covers airplane mode) — all three check
  the *actual radio state*, not the airplane-mode flag directly, so e.g.
  WiFi re-enabled individually while airplane mode stays on doesn't trigger
  a false warning. If you add a new radio type, give it the same
  `unavailableReason()` shape rather than letting it fail silently.

- **Scans used to get cancelled just by switching tabs.** `ScanScreen`/
  `LanScreen` ran scans via `rememberCoroutineScope()`, and
  `MainActivity`'s `when(selectedTab)` fully removes the losing tab's
  composable from composition — which cancels that scope immediately,
  including whatever `runScan()`/`runLanScan()` coroutine was mid-flight.
  Backgrounding the whole app made it worse (BLE scanning is also
  OS-throttled for backgrounded apps). Fixed by moving scan execution into
  `ScanForegroundService`: independently *started* (survives its UI client
  unbinding) with a `StateFlow<ScanUiState>` the screens bind to and
  observe. The persistent notification while scanning is intentional, not
  just an Android requirement for foreground services — it's the honest way
  to signal "this is still scanning even though you switched away." Don't
  move scan logic back onto a Composable-scoped coroutine.

- **Android app had no dark/light theme logic at all** — `MainActivity` used
  a bare `MaterialTheme { }` with no `colorScheme` argument, which resolves
  to `lightColorScheme()` regardless of the OS setting. Added `WhyfiTheme`
  (System/Light/Dark, same teal accent as the PWA) with the preference
  persisted in `SettingsRepository`. The PWA's theme (`frontend/src/
  theme.ts`, `data-theme` attribute + CSS variables) and the Android app's
  are independent implementations (different rendering stacks) — if you
  touch one, check whether the other's default is still visually
  consistent.

- **`AccessPoint.last_seen_at` (auto_now) only updates when `.save()` is
  actually called** — the ingest path only called `.save()` when the SSID
  changed, so an AP seen again with an unchanged SSID (the common case)
  silently kept its old `last_seen_at`, making genuinely-just-scanned
  networks look stale/absent from anything sorted or filtered by recency.
  Fixed by always saving the `AccessPoint` on every ingested observation for
  it, not just on SSID changes. If you add another `get_or_create`-then-
  conditionally-save pattern anywhere, check whether an `auto_now` field is
  depending on that save actually firing every time.

- **`python manage.py makemigrations` run via `docker compose exec` writes
  the migration file into the *container's* filesystem, not the host's** —
  `backend/` isn't bind-mounted (see `docker-compose.yml`), so a migration
  generated this way only persists as long as that specific container
  exists. It happened once already: `0003_bleobservation_device_name_and_
  more.py` was generated, applied to the dev database, and the backend
  image kept building "successfully" for several rebuilds afterward — but
  the file itself only lived in a container that eventually got recreated,
  silently deleting it. The failure only surfaced later as a fresh test
  database erroring with `column ... does not exist` (the dev DB still had
  the columns from the original `migrate` run against the same Postgres
  volume; a brand new test DB did not, because the migration file was
  gone). **Always `docker cp` a freshly generated migration file out of the
  container and into the host's `backend/scans/migrations/` right after
  `makemigrations`, before doing anything else** — don't trust that a
  passing `docker compose exec ... test` run right after generating a
  migration means the file made it to disk.

## PWA update gotcha: an open tab never reloads itself

`vite.config.ts` uses `registerType: "autoUpdate"`, and the generated
service worker does call `skipWaiting()`/`clientsClaim()` — but that only
makes the *new* SW take over future network requests. It does **not**
reload a tab that's already open, so an installed PWA left running keeps
executing the *old* in-memory JS bundle indefinitely after every deploy,
no matter how many times the server-side code changes. This produced
several rounds of "I made this fix, the user says nothing changed" even
though the exact same fix was independently verified correct via a direct
authenticated HTTP request against the running container.

Fixed in `src/main.tsx` by calling `registerSW({ immediate: true,
onNeedRefresh: () => window.location.reload() })` from
`virtual:pwa-register` — forces an actual reload the moment a new version
is detected, rather than silently updating the SW and leaving the open
page on stale code. Needs `/// <reference types="vite-plugin-pwa/client"
/>` in `vite-env.d.ts` for the `virtual:pwa-register` import to typecheck.

Since the *old* SW (from before this fix existed) won't have this
reload-on-update logic yet, the very first update after shipping this
still needs one manual close-and-reopen (or hard refresh) of the
installed app — every update after that should auto-reload on its own.

## "Current scan only" display mode (global, percent-based slider)

`FilterContext` has `mapDisplayMode: "accumulate" | "current-scan"` plus a
`scanIndexPercent` (0-100) and a `scanTimelineLabel` set by whichever map
page is mounted. This restores the old per-page TimeSlider/Path-mode
concept (removed earlier this project, see the "Detail pages: remove Path
mode" work) but as one shared global control instead of N independent
per-page ones — the same pattern as `compactTables`/`showScanPoints`.

Key design point: the slider position is a **percent**, not a raw index,
because every page has a different number of distinct scans within the
current time/scan filter. Each page independently resolves that shared
percent against its own local chronological list
(`src/currentScan.ts::resolveCurrentScan`), so `scanIndexPercent=100`
always means "latest scan" regardless of which page is open.

For the combined Heatmap page (multiple devices, not one), a plain
per-device percent index would desync — device A's "scan 3 of 8" and
device B's "scan 3 of 12" aren't the same physical scan pass.
`resolveCurrentScanMultiDevice` instead builds ONE shared timeline of
distinct `scan_session_id`s across every active device (a single scan
pass can observe several APs/towers/BLE devices at once), then shows only
that one scan's reading per device. This needed `observed_at` added to
the coverage endpoints' per-point payload (alongside the `scan_session_id`
already added for the location-pin feature) purely to sort/build that
timeline.

`RadioMap`'s `MapPoint.normalizedWeight` is an escape hatch for this mode:
normally heat-cell color/size is normalized against the min/max weight of
whatever's in the current render batch, but "current scan only" passes
just one point per device — nothing else in that batch to normalize
against. Callers pre-compute `normalizedWeight` against that device's full
signal range instead, so a single visible cell's size/color still means
the same thing as you scrub between scans, and RadioMap uses it in place
of the local min/max calculation when present.

## Coverage shape: convex hull, NOT a fitted ellipse

`classifyCoverage` (frontend/src/geo.ts) draws each device's coverage area
as the **convex hull of the actual measurement points**. This has now been
round-tripped twice — original convex hull → weighted-covariance ellipse →
back to convex hull — so don't "improve" it back into an ellipse without
re-reading this.

Why the ellipse lost: a 95%-confidence covariance ellipse *extrapolates*
outward from the spread of sightings, so it routinely claimed coverage
tens of meters past anywhere a reading was actually taken, and looked like
"ovals all over" the map. Capping the semi-axes (`maxRadiusMeters`) treated
the symptom, not the cause. The hull can never claim area you didn't
physically stand in, which is the honest thing for a passive survey tool
that only knows where the *phone* was.

Consequences that are deliberate, not bugs:
- Collinear or near-duplicate readings produce no polygon (hull returns
  `[]`) and fall back to `{kind:"points"}` — a zero-area sliver would be
  worse than showing the raw points.
- The hull's edge passes exactly *through* the outermost readings, so the
  gradient's weak/orange edge lands on real measurements.
- The weighted centroid still marks the estimated device location (gradient
  center + radio icon) inside the hull, keeping "where the device is" and
  "where I measured it from" visually distinct.

## Solo mode = cone from the known AP, or an RSSI range estimate if not known yet

The two map display modes (see MapDisplayMode in FilterContext.tsx) answer
different questions, and the shapes are built from different math:

- **Accumulate** — convex hull of every reading up to the slider position
  (see the coverage-shape note above). Answers "where did I detect this?"
- **Solo** — one scan only. Answers "given this one reading, where's the
  device?" `soloShapes` (coverageConfig.ts) has two paths, tried in order:
  1. **Cone** (`conePolygon` in geo.ts) — used whenever the device's
     position is already known from its *entire* sighting history (the
     weighted centroid, computed by the caller from the *full* geotagged
     list, not the slider-filtered one — so the AP's known position stays
     stable as you scrub Solo's slider). The cone runs from that real,
     known apex to wherever the phone stood for this one reading — a
     measured `haversineDistanceMeters`, not a guess. Green at the apex,
     fading to that one reading's own `signalStrengthColor` at the far
     end. A radio-type icon marks the apex, same as Accumulate's centroid.
  2. **Range blob** (`circlePolygon` + `estimateRangeMeters`, log-distance
     path loss) — the fallback when there's no known position yet (a
     brand-new device with only this one sighting ever) or apex and
     reading are too close for `conePolygon` to have a meaningful bearing
     (returns null under ~5m). Flat-colored by that reading's own
     `signalStrengthColor`, no center icon — a single reading with no
     anchor gives a distance, never a direction, so marking an exact spot
     would be a lie.
  Forced-mobile BLE devices (worn headphones/wearables) always skip
  straight to the blob: their sightings scatter with wherever the person
  walked, so a weighted centroid of that scatter isn't a meaningful "AP
  position" to point a cone at.

**Bug that shipped once in the blob path, don't reintroduce it**: blobs
originally set `gradientCenter` to the phone's own reading position,
reusing Accumulate's green→orange gradient. That gradient means "green =
strong signal here = the device is near this exact spot" — true for
Accumulate's centroid, false for a lone reading's own position. A weak
reading taken far from the device rendered *green at the phone's own
position*, exactly backwards (caught by an actual bug report: user
standing in a garden, weak signal, saw green under their feet). The blob
path must stay a flat `color`, never `gradientCenter`.

**Cone gradient positioning is NOT the default 50%/50%.** Unlike
Accumulate's hull (symmetric around its own centroid by construction) or
the old blob (a circle drawn *around* its center point, so trivially
centered), a cone's apex sits at one *corner* of the shape, not the middle.
`ensureGradientDef` in RadioMap.tsx takes explicit `cx`/`cy` fractions for
this reason, computed by `fractionalPosition` from the apex's actual
position within the polygon's own bounding box — and the y-axis is
inverted (north = smaller fraction) because Leaflet's SVG renderer draws in
screen space, where higher latitude is further *up* the screen. Verified
with a standalone script before shipping: an eastward cone's apex lands at
x=0 (west edge), a north-pointing cone's apex lands at y=1 (south/bottom
edge) — get this backwards and green renders at the wrong end of every
cone. `r` is fixed at 150% (not the default 50%) since an off-center
anchor's farthest corner can be up to ~141% away in a unit bounding box.

Cellular's path-loss reference (used only by the blob fallback) is **+10
dBm at 1 m** while WiFi is -40 and BLE is -59. That looks wrong at a glance
but isn't: a macro cell transmits orders of magnitude harder, and
calibrating it like a short-range radio put -100 dBm at ~50 m when it
really means well over a kilometer. Cellular also gets its own 1500 m
ceiling since `RADIUS_CAP_METERS` is Infinity for it.

Both shapes are drawn soft — corner-smoothed (`smoothPolygon`, Chaikin,
1 pass so a cone's apex stays a recognizable point rather than fully
rounding away) plus a CSS blur (`.coverage-soft`) — because both are
estimates (the blob from an uncalibrated path-loss model; the cone's
*length* is real but its ~22° half-angle is stylized, not a measured
antenna pattern). A crisp edge would claim precision neither model has.

## The android-builder built stale source for days (green builds, missing code)

`android/Dockerfile` does `COPY . .` into `/workspace`, and docker-compose
originally bind-mounted **only** the keystore and the build-output volume —
not the app source. So the always-on watcher built whatever source was frozen
into the image when it was last `docker compose build`-ed. Clicking "Build
Android App" produced a perfectly green `SUCCESS` and a working APK that
simply did not contain recent work: the remote-control feature was entirely
absent from a freshly built APK while every backend test passed.

This is the *same trap* as the `backend/` migrations one further up — a
container that doesn't see host source — and it's worse here because there is
no error at all, just silently old code.

Fixed by bind-mounting `./android:/workspace` in docker-compose, so the
watcher always compiles current source. The `COPY` stays as a fallback for
running the image standalone.

Two things that fall out of that mount:

- The `ENTRYPOINT` had to change from `["./docker-build.sh"]` to
  `["sh", "./docker-build.sh"]`. The image's `chmod +x` only applied to the
  baked copy; the bind-mounted file carries the *host's* permissions, and
  this repo lives on a filesystem that hands out 0666 and doesn't preserve
  exec bits, so the old entrypoint would fail with permission denied.
- Gradle now writes `android/app/build/` and `android/.gradle/` on the host
  (root-owned, both already gitignored). That's the price of the mount, and
  it buys incremental builds across runs.

**Verification lesson:** compile-checking with `docker run -v "$PWD/android":/workspace`
proved nothing about the button, because the button's container had no such
mount. When verifying a build pipeline, exercise the pipeline — trigger a real
build and grep the resulting APK's dex for a symbol that only exists in the
new code (`unzip -o app.apk 'classes*.dex' && grep -a RemoteControlAgent classes*.dex`).

## Remote scanning control: reconciliation, not push

The PWA can start/stop scanning on a phone. It is **not** implemented by the
backend telling the phone anything, because the backend cannot: the phone is
behind carrier NAT, and there are two independent Android walls.

Since **Android 12** an app can't start a foreground service from the
background (`ForegroundServiceStartNotAllowedException`; exemptions are
`BOOT_COMPLETED`, high-priority FCM, notification interaction). Since
**Android 11** a background-started *location* FGS gets no location access
without `ACCESS_BACKGROUND_LOCATION`. Clearing only the first — which is all
FCM would do — yields a service that starts and scans nothing.

**So don't reach for FCM.** It wouldn't work without also adding background
location, it contradicts this project's Play-Services-free posture (see the
`FUSED_PROVIDER` decision), and both restrictions exist precisely to stop
covert remote activation of a device's sensors — a property worth keeping
rather than engineering around.

The phone polls `POST /sensors/me/heartbeat/` instead, sending observed state
and receiving desired state. Desired state (`SensorScanPolicy`), not a
command queue: idempotent, no stale command replayed after an offline
stretch, and "stop then start again" is just two writes where the last wins.
`policy_revision`/`reported_policy_revision` is the Kubernetes
generation/observedGeneration trick — without it the UI can only say "sent",
never "the phone has it and still isn't scanning".

Other decisions here that would otherwise look arbitrary:

- The poll lives inside the existing `ScanForegroundService`, not a second
  service. Two FGSs means two persistent notifications; users kill one and
  you'd have no idea which.
- `foregroundServiceType` stays `location|connectedDevice`. **Do not add
  `dataSync`** for the polling: it carries Android 15's 6h-per-24h FGS
  timeout at `targetSdk` 35+, which would silently kill this feature on the
  next SDK bump. `location` has no timeout, and the real work is scanning.
- `stopIfIdle()` became `stopIfNothingKeepsUsAlive()`. The service used to
  die whenever idle, which would leave nothing alive to be reached.
- Remote stop is *graceful* (finishes the pass in flight); the local button
  still stops immediately. `enqueueForUpload()` is the last thing a pass
  does, so cancelling mid-pass silently discards its data — tolerable when a
  human just pressed the button, not when nobody is watching.
- No `RECEIVE_BOOT_COMPLETED`: a location FGS started from boot gets no
  location access anyway, so it'd be a running service that scans nothing —
  worse than being honestly off.
- Long-polling/WebSocket rejected: `entrypoint.sh` runs gunicorn with 3
  **sync** workers, so a few hanging requests deadlock the backend, PWA
  included.
- `Sensor.last_seen_at` now means "last contact" (heartbeats bump it every
  few seconds via `SensorTokenAuthentication`). `last_scan_upload_at` was
  added to preserve "last actually contributed data".

## Android outbox: retry policy and the MB quota

Two latent bugs that only became dangerous once scanning could run
unattended.

`UploadWorker` used to `Result.retry()` on *any* non-2xx, including a
permanent 400 from the ingest serializer. One poison payload would wedge the
entire queue forever: the phone keeps scanning, the map stays empty, and
nothing anywhere says why. It now deletes on 4xx except 401/403/408/429 —
those four are about the token or timing rather than the payload, so the scan
data is still good and stays queued.

The queue cap is a **storage quota in MB (user-set, default 100)**, not a
scan count. A count is the wrong unit: payload size swings by an order of
magnitude between a quiet street and an apartment block, so a fixed count
means wildly different disk use. Eviction is oldest-first, and never evicts
the last row — a single scan larger than the whole quota would otherwise
discard every scan forever. Sizes come from
`SUM(LENGTH(CAST(payloadJson AS BLOB)))`; the `CAST` matters because SQLite's
`LENGTH()` on TEXT counts characters, not bytes. No Room schema change, so no
version bump.

## Tab navigation: HorizontalPager (experimental API, opt-in)

The four main tabs (Dashboard/Scan/LAN/Settings) use
`HorizontalPager` from `androidx.compose.foundation.pager` for swipe-between-tabs.
In Compose BOM 2024.06.00 (foundation 1.6.8) this API is still
`@ExperimentalFoundationApi` — `WhyfiApp` carries `@OptIn(ExperimentalFoundationApi::class)`
rather than waiting for a stable annotation, since the pager is the standard
Compose approach and the API surface (`HorizontalPager`, `rememberPagerState`,
`animateScrollToPage`) hasn't changed between 1.6 and 1.7. When the BOM is
bumped past foundation 1.7 (where the API stabilised), the opt-in can be removed.

The pager wraps only the four tab screens. Drill-downs (ScanDetailScreen,
MissionScreen) early-return above the pager, so swipe never reaches them —
the system back button closes those via their existing `BackHandler`s. This is
load-bearing: a swipe that switched tabs while a detail screen was open would
feel like the back button broke.

Bidirectional sync between `pagerState.currentPage` and `selectedTab` uses two
`LaunchedEffect`s (one per direction) rather than `derivedStateOf` or a single
observer, because each side needs to call `animateScrollToPage` (tab tap) or
write `selectedTab` (swipe) — the write direction differs, so collapsing them
into one observer risks feedback loops.

## Button label shortening on Scan tab

"Scan once" and "Start continuous scanning" were stacked vertically as
full-width buttons. They now share a row at equal width (`Modifier.weight(1f)`).
Labels were shortened to "Scan once" / "Start" (idle) and "Scan once" / "Stop"
(running) — "Throttled" replaces the longer "Scan throttled — try again shortly"
when the WiFi throttle is active. The original labels would overflow a
half-width button on narrow screens. The `enabled` and `onClick` logic is
unchanged.

## In-app sensor enable: what can and can't be turned on without leaving the app

The Scan tab shows a one-tap enable button below each radio's "turned off"
unavailable-reason message. The enable paths differ per radio because Android
restricts which settings a normal app can change:

- **Bluetooth** — CAN enable in-app. `BluetoothAdapter.ACTION_REQUEST_ENABLE`
  via `ActivityResultContracts.StartActivityForResult()` shows a system dialog
  overlay ("Allow whyfi to turn on Bluetooth?"); the app stays visible. The
  existing 2-second availability polling loop picks up the new state
  automatically — no manual refresh needed.
- **WiFi** — CANNOT enable in-app on API 29+. `WifiManager.setWifiEnabled()`
  is restricted to system apps on Android 10+; calling it from a normal app
  throws. The button opens `Settings.ACTION_WIFI_SETTINGS` (API 29+) or
  `Settings.ACTION_WIRELESS_SETTINGS` (older), which leaves the app briefly
  and returns on back. Don't try `setWifiEnabled()` — it will crash.
- **GPS/Location** — CANNOT enable in-app. The button opens
  `Settings.ACTION_LOCATION_SOURCE_SETTINGS`. Same leave-and-return pattern.
- **Cellular (airplane mode)** — CANNOT toggle in-app on modern Android.
  The button opens `Settings.ACTION_AIRPLANE_MODE_SETTINGS`.

The Bluetooth launcher is registered unconditionally at the top of
`ScanScreen` (launchers cannot live inside conditionals). The "no hardware"
unavailable reasons ("This device has no Bluetooth adapter", "This device
has no GPS hardware") intentionally show no button — there's nothing to
enable. `PermissionHelper.isBluetoothEnabled()` and `isWifiEnabled()` were
added alongside the existing `isLocationServicesEnabled()` as the central
place for sensor-state checks.

## Open/deferred (v-next, not forgotten, just not now)

- Matter/Thread device discovery (via BLE commissioning adverts + mDNS) —
  schema intentionally left open (new radio-type Observation model would
  follow the existing pattern), not designed yet.
- SDR/external-hardware sensor track (Kali Linux box, RTL-SDR/HackRF) for
  real monitor-mode WiFi capture and packet crafting — the only path to
  actual offensive/active RF capability, deliberately kept out of the phone
  app.
- Self-hosted offline map tiles (v1 uses public OSM tiles, requires internet
  on the viewing device).
- Watching a *specific* WiFi/BLE/LAN device for online/offline transitions.
  Remote scanning control is a reasonable foundation, but it needs its own
  watch-list model and a per-device "seen during this scan" notion.

## mockloc: dev-only emulator GPS/RSSI spoofing tool

`mockloc/` is a standalone dev-only Android app (Java, not Kotlin) for
testing the scanner on an Android emulator without a physical device. It
spoofs GPS via `LocationManager.addTestProvider()` along a configurable
walk path (circle/oval/rectangle, live-adjustable) and models a
directional RSSI antenna pattern (36-sector smoothed, per-session) exposed
over a local HTTP server on port 8080.

**Why standalone, not part of the main app:** it's a dev tool, not a
feature. Keeping it separate means the main APK ships no mock-location
code, no `ACCESS_MOCK_LOCATION` permission (which AGP rejects in release
builds anyway — it lives in `mockloc/app/src/debug/AndroidManifest.xml`),
and no test-provider registration that could accidentally ship to a real
device. It's built on demand via the Gradle Docker image, not part of
docker-compose or the APK distribution.

**Why the RSSI is HTTP-only:** Android has no "test WiFi provider" API —
the emulator's WiFi HAL always returns "AndroidWifi" at a fixed -50 dBm.
mockloc can only *compute* synthetic RSSI; it cannot inject it into the
emulator's scan results. The HTTP server is the bridge: whyfi can read it
from a debug hook to overlay synthetic signal data on its own scans.
**Wiring that into whyfi's scan path is a separate, not-yet-done task** —
the model and server exist, the integration does not.

**The `addTestProvider` powerRequirement gotcha:** the 9th arg to
`addTestProvider` is `powerRequirement`, which must be 1-3 (Criteria
constants), NOT 0. Passing 0 throws `IllegalArgumentException: powerUsage
is out of range of [1, 3] (too low)`. The 10th arg is `accuracy`, also
1-3. This bit us during initial bring-up — don't reintroduce it.

## Local dev APK signing: persistent keystore

When testing the Android app on a physical device or emulator, always
sign with the **persistent debug keystore** at `~/.android/whyfi-debug.keystore`
(alias `whyfi-debug`, password `android`), NOT an ad-hoc `/tmp/debug.keystore`.
A stable signature means `adb install -r` updates the app in place,
preserving the user's backend URL + sensor token settings. A signature
mismatch forces an uninstall, which wipes those settings — frustrating
for the user who has to reconfigure every time.

Sign command:
```bash
apksigner sign --ks ~/.android/whyfi-debug.keystore --ks-pass pass:android \
  --ks-key-alias whyfi-debug --key-pass pass:android \
  --out app-signed.apk app-aligned.apk
```
The keystore was generated once and lives outside the repo (it's a dev
credential, not committed). If it's ever regenerated, every installed
copy needs one final reinstall before updates work again.
