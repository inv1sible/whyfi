# Agent instructions for whyfi

Read this before making changes. `MEMORY.md` (same directory) has the *why*
behind non-obvious decisions — check it before reintroducing something that
looks missing; it may have been deliberately removed.

## Layout

- `backend/` — Django + DRF + Postgres. Apps: `sensors` (device identity/auth
  tokens), `scans` (all passive radio observations), `distribution` (APK
  hosting/versioning).
- `frontend/` — React + Vite PWA. Built as a Docker build stage and copied
  into the backend image; not run as its own container in production.
- `android/` — Kotlin/Compose native app. Built headlessly via
  `android/Dockerfile` (Gradle + Android SDK cmdline-tools). No Android
  Studio or emulator in this repo's toolchain.
- `docs/` — architecture, API reference, deployment, Android setup, roadmap.

## Commands

```bash
cp .env.example .env
docker compose up                              # postgres + backend + android-builder (single port, no nginx/Caddy)
docker compose exec backend python manage.py test
docker compose exec backend python manage.py migrate
docker compose exec backend python manage.py import_apk <path> --version-code=N --version-name=X  # only for externally-built APKs
```

The normal way to build the Android app is the PWA's Download page →
**Build Android App** button (`POST /android-build/trigger/`,
`GET /android-build/status/`) — `android-builder` runs always-on as a
watcher, not a one-shot `docker compose run`. See `docs/android-setup.md`
and `distribution/services.py`'s module docstring.

Frontend is built as part of `backend/Dockerfile`; there is no standalone
`npm run dev` server checked in as the primary workflow — if you add one for
local iteration, keep the production path as "build into the backend image."

## Conventions

- **Naming**: every radio-type table is `<Radio>Observation` —
  `WiFiObservation`, `CellObservation`, `BLEObservation`,
  `SatelliteObservation`, `LANObservation` — all FK'd to a single
  `ScanSession` (one physical scan pass, or one standalone LAN sweep).
  Follow this pattern for any new radio type; don't invent a parallel
  session/event concept.
- **Ingest is one endpoint**: `POST /api/v1/scan-sessions/` takes optional
  per-radio observation arrays in one payload, keyed by
  `client_scan_id` for idempotent retries. Don't add separate per-radio
  ingest endpoints — that breaks the atomicity/idempotency the single
  endpoint gives you for free.
- **Auth is two independent layers**: (1) the Android app authenticates
  ingest with a per-`Sensor` token, admin-managed; (2) every read endpoint
  (and the PWA itself) requires a logged-in Django session — the same
  superuser account as `/admin/`, no separate account system. Don't add a
  real multi-user system without checking with the maintainer first; do keep
  using `IsAuthenticated` + `SessionAuthentication` as the default for any
  new read endpoint (that's the project-wide `REST_FRAMEWORK` default in
  `config/settings.py`, so new viewsets get this for free unless you
  override it). See `docs/api.md` and `MEMORY.md`.
- **Serving**: Django (gunicorn + WhiteNoise for static, MEDIA for APKs) is
  the only container that serves HTTP. Do not add an nginx or Caddy
  container — see `MEMORY.md`.
- **Android scanning runs in `scan/ScanForegroundService`, not a
  Composable-scoped coroutine.** `ScanScreen`/`LanScreen` bind to it and
  observe its `StateFlow<ScanUiState>`; the service itself is independently
  *started* (`ScanForegroundService.start()`) so it survives tab switches
  and the app being backgrounded. Any new scan action (a new radio type, a
  new sweep mode) should go through this service, not a fresh
  `rememberCoroutineScope()` — that's exactly the bug this replaced (see
  MEMORY.md).
- **Theme is System/Light/Dark on both clients, implemented independently
  per stack.** PWA: `frontend/src/theme.ts` + `data-theme` attribute + CSS
  variables in `index.css`. Android: `ui/theme/Theme.kt`'s `WhyfiTheme` +
  `ThemePreference` in `SettingsRepository`. Same teal accent on both. If
  you change one palette, check the other still matches.
- **Sensor field richness**: capture whatever the platform API actually
  exposes for a radio type (see `WiFiObservation`/`CellObservation`/
  `BLEObservation`/`SatelliteObservation`'s extra fields) rather than only
  the minimum needed for the current UI — but every such field must be
  nullable/blank with a sensible default, since older Android versions or
  absent hardware won't populate it. Adding a field means touching the
  model, the ingest input serializer, the read serializer (`ModelSerializer`
  with `fields = [...]` needs the new field added explicitly; `"__all__"`
  ones pick it up automatically), the `create()` logic, and the matching
  Android DTO — see any of the `*ObservationInputSerializer`s for the
  pattern.
- **Generate migrations against a real Postgres, then copy the file to the
  host immediately.** `backend/` is not bind-mounted into the `backend`
  container, so `docker compose exec backend python manage.py
  makemigrations` writes the file into that container's ephemeral
  filesystem only — `docker cp` it into `backend/scans/migrations/` (or the
  relevant app) right away, or it silently disappears the next time the
  container is recreated even though the database itself already has the
  change. See MEMORY.md — this has already happened once.

## Things not to reintroduce

- No anti-stalking/tracker-alert correlation logic (`SuspectedTracker`,
  sighting-clustering, "mark as mine"/dismiss workflows). BLE detection is a
  plain passive observation log like every other radio type — nothing more.
  This was explicitly scoped out; see `MEMORY.md`.
- No offensive capability (deauth, injection, monitor-mode capture) in this
  codebase. That's an explicit v-next, external-hardware-sensor track, not
  something to add to the Android app directly.
- No internal reverse-proxy container (nginx/Caddy). The maintainer fronts
  this with their own Nginx Proxy Manager instance; the backend just needs to
  expose one plain port.
- No Docker socket access for the backend (or any other container). The
  "Build Android App" button works via a shared-volume file-signal protocol
  between `backend` and the always-on `android-builder` watcher instead
  (`distribution/services.py` + `android/docker-build.sh`) — don't reach for
  `docker.sock`/`docker-py`/`subprocess`-calling-the-Docker-CLI to "simplify"
  this; that's host-root-equivalent access from an internet-reachable
  container. See `MEMORY.md`.
- No NFC feature. It existed (foreground dispatch, `NfcObservation`), then
  was removed entirely at the maintainer's request — no dedicated screen
  ever existed for it, only tap-while-app-happens-to-be-open, and rather
  than build it a proper UI the call was to cut it. Don't re-add without an
  explicit new ask.
