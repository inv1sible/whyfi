# Roadmap

## Current survey and assessment foundation

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
- Scanning is always user-armed: either tapped on the phone, or remotely
  started from the PWA *after* the phone's owner has switched on Remote
  control in the app. Nothing scans unattended without that opt-in, and the
  foreground-service notification is visible the whole time.
- USB monitor-mode reception through a supported external WiFi adapter on
  Android: a bounded, explicitly started session with channel selection,
  nearby AP/client list, probe activity, and tentative device-type grouping.
  The Monitor tab requires a successful adapter test and Advanced mode opt-in.
  Its observations currently remain on the phone for the app session.
- The LAN scanner's TCP-connect probes are active network traffic. WiFi
  packet injection, deauthentication, and packet crafting are not implemented.

## Authorized pentesting track

The external USB WiFi adapter removes the earlier hardware blocker for
monitor mode on Android. whyfi is moving from a survey viewer toward an
authorized assessment workflow. Monitoring, evidence, and analysis come first;
packet injection and other active WiFi tests remain possible later. The next
increments should be independently verifiable:

1. Validate the Monitor tab on physical devices: USB permission, adapter
   compatibility, channel changes, stop behavior, long runs, and foreground
   notification. A successful build does not establish radio behavior.
2. Preserve useful evidence from a monitor session with explicit user choice:
   timestamps, adapter, channel, AP/client observations, probe names, and the
   limits of any inferred device type. The app now stores an optional local
   PCAP capped at 200 MB, a PDF report, and an append-only JSONL event log
   in a new UUID directory for each capture. The Missions tab shows the
   files, opens the PDF, exports the PCAP, and asks the operator to upload
   a completed mission. The backend keeps the files together under the
   same UUID and exposes authenticated WebUI downloads. Still needed:
   explicit retention/deletion controls, download auditing, and a fuller
   persisted summary for downstream analysis.
3. Add passive EAPOL key-frame accounting to the monitor session and report.
   Label it as EAPOL/handshake evidence, not a "WPA hash": hopping may miss
   frames and seeing key frames does not establish a complete usable
   handshake. Keep it receive-only and show AP/client/channel relationships.
4. Add authenticated WebUI search and analysis for opted-in monitor data,
   including AP/client relationships and probe activity. Keep raw observations
   distinct from guesses; rotating MAC addresses must not be treated as proof
   that two sightings belong to one device.
   Add a monitor-mission replay here: the backend processes an uploaded PCAP
   into a timestamped, seekable derived-event timeline while retaining the
   original PCAP for download and external tools such as Wireshark. The WebUI
   should replay derived AP/client/probe/EAPOL/channel events, not attempt to
   reproduce a full packet analyser in the browser. During capture, record
   consented GPS samples with timestamps in the same mission so the replay can
   correlate radio observations with positions and later feed existing heatmap
   and localization views. GPS samples remain measurements; any AP/device
   location is a derived estimate with its evidence and uncertainty.
   Store an append-only mission event log beside the artifacts: starts/stops,
   channel selections, filter changes, pins/favorites, operator markers,
   settings changes, and upload decisions. Replay merges this operator log
   with capture and GPS timelines rather than guessing later why a view or
   channel selection changed.
5. Design active WiFi validation as separate, explicitly armed actions with
   clear target and test boundaries, visible progress, and recorded results.
   The exact tests and supported adapters are still to be selected; the
   current Monitor implementation does not transmit frames.

## AP localization track (see `ap-localization-design.md`)

Locating physical access points from measurements taken at known positions,
rather than only mapping where signal was heard. Shipped:

- **V1-V2** — WiFi scanning, per-BSSID favouriting, scan sessions anchored to
  an observer position (these predate the design doc; it built on them).
- **V3** — Wi-Fi RTT/FTM ranging (`WifiRttManager`) against 802.11mc-responder
  APs, triggered per-BSSID from the app's scan detail screen, stored as
  `FtmRangingObservation` and uploaded through the normal offline outbox.
- **V4** — weighted least-squares multilateration solver
  (`backend/scans/localization.py`), no numpy/scipy dependency.
- **V5** — 95% confidence ellipse from the solve's covariance, plus an AP
  *position* probability surface. Distinct from the RSSI coverage heatmap
  below; the design doc is explicit that conflating the two is a mistake.
- **V6** — next-best-measurement suggestions (geometric heuristic: measure
  from the widest unobserved bearing). Information-gain/entropy-reduction is
  the intended later replacement.
- **V7** — RSSI coverage heatmap. Already existed as the Heatmap page.
- **V8** — probabilistic mesh/BSSID clustering into physical-AP hypotheses.
  Never asserts a verdict, and never merges on co-location alone.
- **V9** — printable localization report (PWA Localization page).

Layered on top of that track: the position estimator is **selectable**
(`centroid`, `strongest`, `rssi_multilateration`, `ftm_multilateration`), with
a global default in both the PWA Settings page and the Android app, applied to
every transceiver type. All four live in one place — `backend/scans/estimators.py`
— rather than being ported into TypeScript and Kotlin, so "compare estimators"
can never be comparing subtly different implementations. The Localization page
shows all four side by side with their disagreement and per-reading residuals;
Mission view shows the same comparison in the field.

Not done: **V10** (ESP32 CSI sensors) — needs real hardware and firmware to
develop against, and the design doc itself scopes CSI out of the first
implementation.

## Requested, not yet built

- ~~**Per-scan measurement points in the UI.**~~ **Shipped** as floor-plan
  surveying (`/floor-plans`): upload a plan, anchor it with two reference
  points, then click where each scan was taken. Placements are ordinary
  `OBSERVER` ground-truth pins, so they also correct the input to every
  estimator elsewhere in the app. Original note kept below for context.

- **(done) Per-scan measurement points in the UI.** Indoors GPS is unusable, so a
  survey needs the operator to place each measurement point by hand: pick a
  scan (the map's time/scan slider position), say "I was standing *here*", and
  have every estimate recomputed from the corrected position. The backend and
  data model already support this — `GroundTruthPosition` with
  `kind=OBSERVER` is keyed by scan session id and overrides that session's GPS
  fix for every estimator (`ground_truth_overrides` in `scans/views.py`) —
  but nothing in the PWA sends one yet, so the capability is currently
  unreachable. AP pins are deliberately the other shape: one per BSSID,
  applied across every measurement of that AP.

- **Export, delete, re-import as one safe workflow.** Export
  (`/scan-sessions/export/`) and bulk delete both exist, but aren't joined up:
  clearing out a survey while keeping the ability to restore it means
  exporting, verifying the file, then deleting, by hand and in the right
  order. Wanted as a guided flow filtered by SSID, date/time or location.
  Note a known bug to fix alongside it: export applies its `limit` slice
  *before* the area filter, so a location-filtered export silently searches
  only the newest N sessions rather than the whole area.

- **Walls and obstacles from floor plans** — designed, not built. See
  `docs/walls-obstacles-design.md`. Would replace the single path-loss
  exponent with a multi-wall model, enabling predicted (not just interpolated)
  coverage and a wall-aware estimator. Includes browser-side auto-detection of
  wall candidates from the plan image, since hand-drawing 20-40 segments is
  the thing most likely to make the feature go unused. Phased so each stage is
  independently useful, with an explicit stop condition: if the wall-aware
  estimator doesn't beat the existing four on pinned APs, the later stages
  aren't worth building.

## v-next (deferred, not forgotten)

- **Export WiFi observations to Kismet.** Kismet has a REST endpoint built
  for exactly this (`scan_report.cmd`, "Wi-Fi scanning mode") — no packet
  capture or custom datasource plugin needed, and its fields map almost
  directly onto whyfi's existing WiFi observation schema. Researched but not
  built; open question is whether the forwarder lives in the backend (one
  config, every sensor) or the app (per-device config). See
  `docs/kismet-export-outlook.md`.
- Matter/smart-home device discovery (BLE commissioning adverts + mDNS
  service records) — schema left open, not designed yet.
- Watch a *specific* WiFi/BLE/LAN device and alert when it comes online or
  goes offline. Remote scanning control (shipped) is a reasonable foundation
  — the backend already knows what each phone should be doing and hears from
  it regularly — but this needs its own watch-list model and a per-device
  notion of "seen recently".
- Additional external sensors beyond the Android USB adapter may contribute
  to the same sensor-agnostic ingest model once monitor observations have a
  defined backend schema.
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
