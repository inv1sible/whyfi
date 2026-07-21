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
- Background/periodic Android scanning (v1 is foreground-only, manual/auto
  "Scan Now" while the app is open).
