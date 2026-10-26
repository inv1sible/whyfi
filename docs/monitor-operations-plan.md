# Monitor mission operator workflow: review and implementation plan

Status: proposed plan, 2026-10-26. [Current mission behavior](monitor-mission-behavior.md) remains the implemented contract. This plan uses the existing Monitor tabs and bottom controls.

## Goal and terms

An operator should be able to start a capture quickly, understand what changed while away, resume the current mission, branch from a saved mission, locate a selected device, and record the outcome of a later active assessment without searching through files or losing evidence.

- **Start/Stop** controls capture passes in the active mission. Stop does not close it; Start uses the same ID until Reset.
- **Continue mission** selects a saved mission as the source of a *new* mission ID. Today it copies the entire log and PCAP, then opens Nearby. The original remains intact and can be uploaded independently. The new report is generated after its first capture.
- **Reset** closes the active mission and clears its favorites and hidden AP choices. It preserves all saved files.
- **Replay** reads a saved timeline and must remain visibly distinct from live capture.

## Findings in the current implementation

1. The bottom bar offers Start/Stop in Nearby, Location, and data tabs, but Missions has no capture action. The Missions list shows file sizes and per-mission actions without an at-a-glance active mission summary.
2. The active ID persists, while the live device table and service status are memory-backed. After returning to the app, the operator can see old files but lacks a concise "what happened / what should I do next" summary.
3. Continue duplicates the source PCAP and log. Copying a large PCAP takes time and storage; a copied PCAP already at the 200 MB cap cannot record more frames. The UI only says "Continuing…" and does not show required space or this limit before copying.
4. Location already supports favorite estimates, all AP estimates, map selection, and receiver fixes. The path from a mission finding to a target on that map and the quality of its evidence need a clearer operator cue.
5. The backend accepts each mission ID once and stores PDF, log, and optional PCAP. It does not expose the "continued from" relationship as a field, although the phone writes that event to the new log. It is an immutable upload, so a new ID is necessary for an updated upload.
6. Current WPS fields describe advertisements. The native monitor path receives frames but does not transmit. No active RF test is implemented.

## Small implementation increments

### 1. Mission summary and quick actions in the existing Missions tab

Add one compact active-mission card above the saved list: short ID, capture state, last capture time, PCAP size/limit, report readiness, and the latest usable location fix. Put **Start/Stop** and **Reset** there using the same service actions as Nearby. If the adapter is missing, the action opens the existing USB selector/test flow. Keep the current bottom bar elsewhere; no new tab or global navigation is needed.

Acceptance: from Missions, one tap starts or stops the active mission; tab changes do not stop capture; the card still identifies the mission after app restart. The card shows why Start is unavailable.

### 2. Catch-up summary for saved and active missions

Compute a compact summary from the saved JSONL snapshots and events: new APs and clients since the prior capture pass, favorites with new sightings, location coverage/quality, channel coverage, PCAP cap, and pending upload. Show the latest few events with a jump to the existing replay time or Location target. Mark the last reviewed event locally so "since you last looked" has a stable boundary. Keep raw observations and derived counts separate.

Acceptance: after reopening a mission, the operator can see what changed and reach the relevant map/replay point without reading the raw log; missing location or incomplete captures are called out rather than silently omitted.

### 3. Make continuation consequences visible

Before copying, show source ID, new-ID behavior, source log/PCAP size, available space, and whether the inherited PCAP is already capped. Show copy progress and allow a safe cancellation before switching the active ID. After copying, show the source/new-ID link in both mission cards and in the new PDF. Preserve the source, favorites, hidden choices, and event chronology. Keep Stop → Start on one ID until Reset.

Acceptance: an uploaded source can be continued and uploaded again under a different ID; copy failure leaves the original and active selection unchanged; the operator can tell which evidence is inherited and which was captured later.

### 4. Locate from a mission finding

Add a **Locate** action to a mission's favorite/AP finding that opens the existing Location tab with that target selected. Show estimate, uncertainty, number and spread of receiver positions, and a short next-movement hint when the evidence is weak. Keep the map zoom under operator control and label receiver positions separately from the estimated transmitter position.

Acceptance: a saved finding opens at its mission and replay time; a live finding opens the live map; the UI never presents a single RSSI reading as a resolved device location.

### 5. Later active assessment workflow

Treat any action that transmits or disrupts RF as a distinct, explicitly armed assessment attached to an AP/mission. Define the selected test, target BSSID/channel, time and attempt bounds, stop control, and result events before adding an action button. First verify USB transmit and simultaneous reception with the supported adapter on a lab AP. A WPS assessment can start with passive method/exchange analysis; active PIN validation needs its own scoped design. Do not mix replay controls or passive capture status with active-test status.

Acceptance for the *design* increment: a tested adapter capability matrix and a written test scope exist before any active RF UI is enabled. Report observed results and inconclusive outcomes separately.

## Delivery order

Implement 1 and 2 first, then 3 and 4. Review the results on a physical phone with a saved mission and an uploaded mission. Scope 5 separately after adapter validation. Every Android increment needs a signed test APK on the Download page and its installed build number checked in Settings.
