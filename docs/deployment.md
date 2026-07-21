# Deployment

## First run

```bash
cp .env.example .env
docker compose up
```

This starts `postgres`, `backend`, and `android-builder` (the last one just
idles in a poll loop, ready to build on demand — see below). On first boot
the backend:

1. Waits for Postgres to become healthy.
2. Runs migrations.
3. Creates a default admin superuser if `DJANGO_SUPERUSER_*` env vars are set
   and none exists yet (change the password after first login).
4. Loads demo seed data if `LOAD_DEMO_DATA=true` (the default), so the
   dashboard isn't empty on first load.

Open `http://<host>:8000/`. Put your own reverse proxy (e.g. Nginx Proxy
Manager) in front of that port for TLS/hostnames — there is no nginx/Caddy
container in this stack by design (see `MEMORY.md`).

## Logging in

The PWA itself requires login — same account as `/admin/`
(`DJANGO_SUPERUSER_USERNAME`/`DJANGO_SUPERUSER_PASSWORD` from `.env`; change
the default password before exposing this past your LAN). There's no
separate account system: one operator account covers both the admin panel
and viewing the app. See `docs/api.md` for the underlying `/auth/` endpoints.

## Creating a sensor (device)

In the PWA, go to Settings > Sensors, enter a name, and click **Create
sensor** — the token is shown once, right there; copy it and paste it into
the Android app's Settings screen along with the backend URL. Sensor tokens
are independent of the login above — they authenticate the Android app's
scan uploads only, not the web UI. If you lose a token, use **Regenerate
token** on that sensor's row (the old token stops working immediately).
`/admin/` → Sensors still works too, if you prefer it.

## Building and distributing the Android app

Log into the PWA, go to the **Download** page, and click **Build Android
App** — no terminal needed. This POSTs to `/api/v1/android-build/trigger/`,
which writes a request signal to the `build_output` volume; the always-on
`android-builder` container (Gradle + Android SDK, no Docker socket access
— see `MEMORY.md`) picks it up, builds, and writes the result back to that
same volume. The Download page polls `/api/v1/android-build/status/` every
few seconds and shows the live log tail; once it reports `SUCCESS`, the APK
is already registered and the download link/QR code appear automatically.
A build takes a few minutes (faster on repeat builds — Gradle's dependency
cache persists in the container). Version code auto-increments; version
name defaults to a timestamp unless you pass one.

If you'd rather build with Android Studio or CI instead, `manage.py
import_apk <path> --version-code=N --version-name=X` still works for
registering an externally-built APK.

The signing keystore used by `android-builder` (`android/keystore/release.keystore`)
must be generated once and kept outside git (referenced via `.env`) —
regenerating it breaks in-place updates for anyone who already installed a
build signed with the old key. Without a keystore present, builds are
unsigned (fine for `adb install`, not for in-place updates).

## Environment variables

See `.env.example` for the full list with working defaults: Postgres
credentials, `DJANGO_SECRET_KEY`, `DJANGO_ALLOWED_HOSTS`,
`DJANGO_CSRF_TRUSTED_ORIGINS` (needed behind an HTTPS reverse proxy — see
above), `DJANGO_SUPERUSER_*`, `LOAD_DEMO_DATA`, the Android keystore
passwords, and `WHYFI_PUBLIC_URL` (pre-fills the Android app's backend URL
at build time — set it to the same value as `DJANGO_CSRF_TRUSTED_ORIGINS`).
