# Roadmap

## v1 (this repo's current target)

- WiFi scanning (WifiManager) + visualization (dashboard, per-network signal
  history, channel congestion).
- Cellular serving/neighbor-cell info (TelephonyManager) — phone's own radio
  only, no SDR, no spectrum scanning.
- BLE device discovery — passive observation log with informational device
  type badges (e.g. "possible AirTag/Tile"). No alerting, no correlation, no
  "dismiss"/"mark as mine" workflow.
- GNSS satellite view (per-satellite Cn0/elevation/azimuth/used-in-fix) plus
  derived location (lat/lon, accuracy, provider).
- LAN device discovery (TCP-connect subnet sweep + common-port probe) — its
  own explicit "Scan LAN" action, not part of the regular radio pass.
- Geotagging + heatmap/map visualization across all of the above.
- Self-hosted Android build pipeline (Docker, no Android Studio/emulator) and
  self-hosted APK distribution + update-check via the backend's Download
  page.
- Foreground-only scanning (manual "Scan Now" + in-app auto-scan while the
  app is open).
- Zero offensive capability (the LAN scanner's TCP-connect probes are the
  one active exception — see `DISCLAIMER.md`).

## v-next (deferred, not forgotten)

- Matter/smart-home device discovery (BLE commissioning adverts + mDNS
  service records) — schema left open, not designed yet.
- Background/periodic Android scanning service (persistent notification,
  battery-optimization-exemption flow).
- SDR/external-hardware sensor track (Kali Linux box, RTL-SDR/HackRF)
  contributing via the same sensor-agnostic ingest API — the actual path to
  monitor-mode WiFi capture and packet crafting, kept off the phone.
- Multi-user authentication / per-user data isolation.
- WebSocket/live-push updates (v1 is request/poll-based).
- Self-hosted offline map tiles for fully air-gapped deployments.
- Server-managed/updatable BLE signature reference list (v1 ships a static
  bundled asset).

## Explicitly not planned

- Anti-stalking tracker correlation/alerting — designed, then rejected
  outright by the maintainer. See `MEMORY.md`.
- Play Store distribution — this project is self-hosted-distribution-only by
  design.
- NFC tag reads — built, then removed entirely (no dedicated screen ever
  existed, it was tap-while-app-happens-to-be-open only, and the maintainer
  chose to cut it rather than give it a proper UI). See `MEMORY.md`.
