# Monitor mission operator workflow: review and implementation plan

Status: historical, rejected draft. Superseded by [the operator's concrete improvement list](monitor-improvements-20261026.md). The decisions below are not implementation instructions.

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

## Settled requirements

- Preserve the existing Nearby and Location layouts and tab structure. Nearby has no Open mission bar; mission selection belongs in Missions.
- Start/Stop stays in the active mission until Reset. Reset retains evidence files. Continuing a saved mission creates a new UUID, including when the source was uploaded.
- Retain local evidence after upload. Upload remains an explicit operator action. Saved replay stays distinct from live capture.
- Keep favorites, SSID-wide hiding, configurable noise filtering, and Auto/Manual updates. Devices restored from saved evidence must show their historical status until heard again.
- Capture can proceed without a usable location fix; show the evidence limitation. Estimates retain their uncertainty rather than being suppressed.
- Publish a signed APK for each Android delivery and verify its installed version through Settings.
- WPS assessment is recorded for later. Active RF tests require their own scope and verified adapter capabilities before implementation.

## Open feature decisions

These are recommendations for discussion, not defaults to implement without an answer. Technical details that do not change operator behavior will follow existing project conventions.

| ID | Question | Choices and consequence |
| --- | --- | --- |
| D01 | How should Missions use screen space? | Recommend one active mission card plus compact saved rows that expand; alternatively retain full cards for every saved mission. |
| D02 | Should missions have human-readable names? | Recommend an optional name with a date/time default; alternatively keep date/time and UUID only. Naming must not delay capture. |
| D03 | How should Start behave when no mission is open? | Recommend start immediately with remembered settings and inline readiness status; alternatively show a compact settings/readiness sheet before the first capture of each new mission. |
| D04 | What should tapping a saved mission open? | Recommend its catch-up summary with Replay and Continue actions; alternatively open its Location replay directly. Continue remains an explicit action with a new ID. |
| D05 | What is the catch-up comparison boundary? | Recommend the last explicit Mark reviewed checkpoint; alternatively compare with the previous capture pass, or the last time its summary was opened. |
| D06 | Which events belong in the default catch-up summary? | Recommend new APs, favorite sightings, security changes, and evidence/receiver health; alternatively include every newly seen client as well. Client counts represent observed MAC identities. |
| D07 | How should catch-up content refresh? | Recommend honor the existing Auto/Manual choice; alternatively keep the summary fixed until its own Refresh action. Health indicators can still show current capture faults. |
| D08 | What evidence should Continue inherit? | Full log/PCAP copy as implemented today, or a new capture with parent-linked history and fresh evidence files. The latter requires lineage support in the backend and views; original evidence remains available through the parent. |
| D09 | Which operator choices should Continue restore? | Recommend favorites, hidden AP groups, and channel selection/mode; alternatively restore only favorites and hidden AP groups while retaining current capture settings. PCAP preference remains visible. |
| D10 | How should operators close and upload a mission? | Recommend a Finish and upload action in Missions that explicitly closes it and offers upload; alternatively retain Reset as the only close action, followed by a separate upload. Either path retains the source files. |
| D11 | What should Locate do to capture channels? | Recommend only focus the existing map in the first delivery; alternatively offer a visible target-channel lock with an action to restore the previous hopping selection. |
| D12 | How should location improvement advice appear? | Recommend a compact cue that expands on tap; alternatively display advice only inside location details. Advice must identify weak fix quality or insufficient receiver movement without implying a measured bearing. |
| D13 | Should the mission gain a quick operator bookmark? | Recommend a timestamped bookmark with optional short note and available receiver position; alternatively defer bookmarks. It would appear in replay and the report. |
| D14 | What should this round add to the WebUI? | Phone workflow only, or mission summaries and source/continuation relationships in the existing WebUI mission table. Parent-linked evidence in D08 requires the latter relationship support. |
| D15 | What should the later "disturb and infiltrate" assessment design cover? | Availability/resilience validation, access/credential validation, or both. This selects the later design scope; it does not enable a test or select its RF method. |

D08 needs a follow-up choice after its answer: with full copies, whether a capped source blocks continuation or allows metadata/GPS capture with an explicit PCAP-limit indication; with parent-linked history, whether the PDF shows the current continuation alone or a clearly labeled combined history. Exact active test, target selection, duration/attempt limits, and results are decisions for the later D15 design, not silently selected in this mission implementation.

## Implementation work packages

State handling shared by the packages:

| Trigger | Result | Evidence behavior |
| --- | --- | --- |
| Start with no active mission | Create an ID and begin capture, subject to D03 | Create evidence files according to the visible PCAP preference. |
| Stop or capture duration limit | Save report, then pause the mission | Keep the ID; append capture completion and retain existing files. |
| Start on a paused active mission | Begin another capture pass | Keep the ID and append according to the selected evidence strategy. |
| Continue saved mission | Prepare a new ID, then open Nearby | Apply D08/D09; preserve source and current active selection if preparation fails. |
| Reset | Close active mission and clear its operator selection | Preserve saved evidence; next Start receives a new ID. |
| Reopen app after the capture service has ended | Restore active mission and historical summary in a paused state | Require Start for new RF activity; distinguish history from current reception. |
| Open Replay | Read the selected saved mission and time | Keep active capture ownership and saved evidence intact. |

### A. Persist and expose the mission summary

Derive a summary from the existing mission log and snapshots: identity/name if selected, parent relationship, last capture, recorded AP/client identities, favorites, location coverage, file sizes, and completion/upload state. Read large files outside the UI thread. Store only small summary/checkpoint metadata locally; keep raw evidence authoritative. Invalidate summaries when the log changes. Do not label restored snapshots as live radio observations.

Code areas: `MonitorMission.kt`, `SettingsRepository.kt`, and a small pure summary parser if needed. Decisions: D02, D05, D06, D08, D09, D13. Acceptance: reopen a mission after process restart, recover its summary and historical inventory, and distinguish it from newly received frames. Missing or partial files produce a visible incomplete state.

### B. Quick mission actions in Missions

Use the selected D01 layout, existing capture handlers, and the existing USB flow. Render active/paused/saving/incomplete states from observed state and saved evidence. Apply D03 to initial start and D04 to saved mission navigation. Keep Reset and continuation identity rules consistent with the settled requirements. Put details behind expansion to preserve screen space.

Code areas: `MonitorMissionScreen.kt`, `MonitorScreen.kt`, existing `ScanForegroundService` actions. Acceptance: Start and Stop from Missions affect the same mission as Nearby; changing tabs preserves capture; disconnected USB and unavailable location show their distinct readiness states; no duplicate capture jobs are created.

### C. Catch-up summary and review checkpoint

Implement D05's boundary and D06's event selection using timestamped snapshots and operator events. Show the comparison window and observed identity counts. Apply D07 to refresh behavior. Tapping a result jumps to its target and time in the existing Location/replay flow. If D13 is selected, use the same event stream for bookmarks.

Code areas: mission summary parser, `MonitorMissionScreen.kt`, `MonitorReplayMission.kt`, `MonitorScreen.kt`. Acceptance: known fixtures produce the expected changes before/after the review boundary; reopening does not silently move an explicit checkpoint; jump actions select the intended mission and time.

### D. Continuation, lineage, and upload

Implement D08 and its follow-up while retaining the original UUID and artifacts. Restore D09's choices. Make source ID, destination ID, progress, storage needs, and recording limitations visible. Switch the active ID only after a complete successful preparation; failed/cancelled preparation preserves the previous active mission. Apply D10 to closing/upload. If selected by D08/D14, add backward-compatible parent/summary fields and expose them in the existing WebUI table. Existing uploads remain readable.

Code areas: `MonitorMission.kt`, `MonitorMissionUploader.kt`, both mission screens, and conditionally backend model/upload API/migration plus `MonitorMissionsPage.tsx`. Acceptance: continue an uploaded source, capture new evidence, and upload under the new ID; verify parent/source files remain intact, IDs agree across phone/PDF/backend, and storage failure cannot leave an apparently complete mission.

### E. Locate and operator guidance

Route a mission finding to its saved or live Location context. Implement D11's chosen channel behavior and D12's advice presentation. Retain existing map zoom, favorites, uncertainty, and receiver-position distinction. A selected historical target does not initiate RF activity. Persist bookmarks only if D13 is selected.

Code areas: `MonitorScreen.kt`, `MonitorMappingScreen.kt`, existing location policy/estimates, mission log and report. Acceptance: tap a target and focus its available measurement points; replay time is preserved; if a temporary channel lock is selected, exiting restores the exact previous selection.

### F. Later assessment design

After D15 is answered, draft the specific active test feature decisions and adapter verification procedure. Record the selected target, requested effect, operator action, observed outcome, and inconclusive results in mission evidence. Exact test behavior must be reviewed before code changes for transmitting frames.

## Verification and delivery gates

1. Unit checks for summary windows, restored choices, identity transitions, incomplete evidence, and any cancellation/storage failure path added by the selected features.
2. Required Android suite and signed distribution APK; inspect APK version/signature and new code markers. If backend changes are selected, run focused upload/lineage/compatibility tests, required migrations, and the frontend build.
3. Verify the Download endpoint offers the new build. Physical-phone acceptance: new mission → start/stop/start → reopen → catch up → continue uploaded source → capture → close/upload → replay/locate. A source build alone does not establish device behavior.
4. Deliver quick actions and catch-up first, then the selected continuation/backend/locate changes. Each delivery states what is implemented, what is tested, and what still needs device verification.
