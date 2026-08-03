# whyfi

A self-hosted, open-source multi-radio scanner and visualizer. An Android app
scans WiFi, cellular, Bluetooth, GNSS, and your local LAN around you and
reports to a Dockerized Django backend; an installable PWA visualizes the
results (dashboards, signal history, channel congestion, and maps/heatmaps)
on any device, including iOS.

See [`docs/architecture.md`](docs/architecture.md) for the full design
rationale. Read [`DISCLAIMER.md`](DISCLAIMER.md) before scanning anything you
don't own.

## Why a native Android app at all, if there's a PWA?

No mobile browser (iOS or Android) exposes an API to enumerate nearby WiFi
networks, cellular cells, or Bluetooth devices — that capability simply
doesn't exist for web content, on either platform. iOS additionally never
exposes it to *native* apps either. So the PWA is a viewer everywhere, and the
Android app is the only thing that can actually scan. See
[`docs/architecture.md`](docs/architecture.md) for the full breakdown.

## Screenshots

<table>
<tr>
<td align="center" valign="top">Mobile PWA<br/><img src="screenshots/01-mobile_dashboard.png" width="200"/></td>
<td align="center" valign="top">Android app<br/><img src="screenshots/21-app_dashboard.png" width="200"/></td>
</tr>
</table>

<table>
<tr>
<td align="center" valign="top">WiFi<br/><img src="screenshots/20260803054951-desktop_wifi.png" width="400"/></td>
<td align="center" valign="top">Heatmap<br/><img src="screenshots/20260803055214-desktop_heatmap.png" width="400"/></td>
</tr>
<tr>
<td align="center" valign="top">Download APK<br/><img src="screenshots/20260803055313-desktop-download-apk.png" width="400"/></td>
<td align="center" valign="top">Remote devices<br/><img src="screenshots/20260803055520-desktop_remote-devices.png" width="400"/></td>
</tr>
</table>

More screenshots (mobile PWA, desktop PWA, and the native Android app) in
[`docs/screenshots.md`](docs/screenshots.md).

## Quickstart

```bash
cp .env.example .env
docker compose up
```

Then open `http://localhost:8000` (or wherever you point your own reverse
proxy) and log in with the credentials from `.env`
(`DJANGO_SUPERUSER_USERNAME`/`PASSWORD` — same account as `/admin/`). From
the **Download** page, click **Build Android App** — this builds a real APK
in the already-running `android-builder` container (Gradle + Android SDK,
no Android Studio, no terminal) and registers it automatically once it
finishes, typically a few minutes. Then download/sideload it onto your
phone, create a `Sensor` in `/admin/` for its token, and paste the backend
URL + token into the app's Settings screen to start scanning.

## Layout

- `backend/` — Django + DRF + Postgres API, serves the built PWA and hosts
  APK downloads.
- `frontend/` — React + Vite PWA, built into the backend's Docker image.
- `android/` — native Kotlin scanning app, built via Docker (no emulator
  needed to produce an APK; a physical device is needed to exercise real
  radios).
- `docs/` — architecture, API reference, deployment, and roadmap notes.

## Status

v1: passive scanning only (WiFi, cellular, Bluetooth device discovery, GNSS,
LAN device discovery). No offensive capability. See [`docs/roadmap.md`](docs/roadmap.md).
