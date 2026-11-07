# Wi-Fi Signature Labels Implementation Plan

> **For agentic workers:** Execute task-by-task with TDD (failing test first), one commit per task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Label every Wi-Fi access point in whyfi with zero or more named signatures from the Fieldwatch catalog already vendored for BLE (`backend/scans/signatures/fieldwatch-signatures-v2.json`, 162 Wi-Fi-capable fleets: router/ISP vendors, DJI, Axon, Tesla, mesh systems, drone Remote ID beacons, …), with the matching rule kept as evidence. Follow-up to `2026-11-07-ble-signature-catalog.md`.

**Architecture:** Same as BLE: backend-authoritative matching in Python, labels stored on `WiFiObservation`/`AccessPoint` at ingest, recomputed by a relabel command, shown in the PWA. The Android app additionally sends the OUIs of the beacon's vendor-specific information elements (element ID 221), which Android exposes via `ScanResult.getInformationElements()` on API 30+. Without them only BSSID and SSID rules can match.

**Source:** Fieldwatch commit `2f79baf95a1a9cdfcc8261f3fb58079aeb75f191` (MIT): `SignatureEngine.kt` (Wi-Fi branches: `ouiHits`, `wifiVendorIeHitsOui`, `recoveredWifiOuiHits`, `dropCiscoWhenMeraki`), `MacUtil.wifiOui24Universal` in `Models.kt`, `WifiRadio.vendorIeOuisOf` / `ssidClean`.

## Decisions (locked)

- New module `backend/scans/wifi_signatures.py`, importing `Catalog`, `Rule`, `load_catalog`, `hex_only` from `ble_signatures.py`. `ble_signatures.py` keeps its name (migration 0021 imports it).
- Wi-Fi rule semantics (port of `SignatureEngine`'s *indexed fast path*, which is what every match-any fleet uses):
  - A rule with `radio == "BLE"` never applies. `SERVICE_UUID`, `SERVICE_DATA`, `MANUFACTURER_ID`, `MANUFACTURER_DATA` never hit Wi-Fi (no BLE payload). `RADIO_KIND` hits when `radio` is null or `"WIFI"`.
  - `NAME_CONTAINS` / `NAME_GLOB` test the SSID, same functions as BLE. A blank SSID never matches.
  - `HIDDEN_SSID` hits when the SSID is blank.
  - `OUI` hits when (a) the hex BSSID starts with the rule hex, or (b) the rule hex is 6 long and equals the **universal OUI** (`wifiOui24Universal`: first octet has the locally-administered bit 0x02 set and the multicast bit 0x01 clear → clear 0x02), or (c) any vendor IE OUI starts with the rule hex. Vendor IEs and rules whose first 6 hex are WPA `0050F2` or RSN `000FAC` are protocol tags and never count for (c).
  - `MAC_PREFIX` hits by (a) or (b) (b only for 6-hex rules, as the index does).
  - `VENDOR_IE_OUI` hits by (c) only.
  - `match_any == False`: every rule must hit. Clustered or rule-less fleets never match.
  - Post-filters: the BLE ones (no-ops for Wi-Fi) plus `dropCiscoWhenMeraki`: drop `fleet-cisco` when `fleet-meraki` hit.
- Evidence: `{"id", "rule"}` like BLE, plus `"via"` on `OUI`/`MAC_PREFIX` hits: `"bssid"`, `"bssid_universal"` or `"vendor_ie"`. The PWA says "address prefix only" when every hit came via the BSSID, and "vendor element" when via a vendor IE.
- Wire format for vendor IEs: `vendor_ie_ouis: ["AA:BB:CC", …]` (uppercase, colon-separated, distinct, in beacon order), optional, truncated to 32 server-side. Only OUIs are sent, not IE bodies (payload size: hundreds of APs per scan). Remote ID IE decoding is a separate item.
- Storage: `WiFiObservation.vendor_ie_ouis`, `signature_matches`, `signature_catalog_version`; `AccessPoint.signature_ids` (sorted union). Migration `0022_wifi_signatures` with a `RunPython` backfill. `relabel_all_wifi` memoizes on `(bssid, ssid, vendor_ie_ouis)`, takes the same per-batch row locks as BLE relabel, and ingest locks the `AccessPoint` row (the BLE review's race fix).
- Command: new `relabel_signatures` running BLE then Wi-Fi; `relabel_ble_signatures` stays.
- API: `AccessPointSerializer.signatures` (detail adds notes); `?signature=` / `?signature_class=` on `/access-points/`; `GET /wifi-signatures/` lists Wi-Fi-capable fleets; Wi-Fi coverage returns `signature_names`.
- PWA: WiFi page (SSID-grouped) shows the union of member chips in the (always-empty today) Vendor column, renamed "Type", plus a class filter; network detail gets the "Pattern match" section; heatmap Type uses the name for Wi-Fi too.
- Out of scope: GNSS (separate decision), vendor IE bodies / Remote ID decode, monitor-mission PCAP labeling.

## Global Constraints

Same as the BLE plan: optional new ingest fields, old APKs still labeled (BSSID/SSID), one atomic ingest endpoint, `IsAuthenticated` + `SessionAuthentication`, no new dependencies, "pattern match" copy, no alert UI.

## Review Focus

1. Virtual BSSIDs (locally-administered) still get their vendor via the universal OUI; random client-like addresses don't get a hit from an unrelated prefix. Test: `test_virtual_bssid_matches_universal_oui`.
2. Protocol IEs (WPA/RSN) never label anything. Test: `test_protocol_vendor_ies_ignored`.
3. Old APK payloads (no `vendor_ie_ouis`) ingest and label. Test: `test_legacy_wifi_payload_is_labeled`.
4. Relabel on 72k Wi-Fi rows runs in seconds and is idempotent. Tests: `test_relabel_wifi_*`.
5. Export/import keeps `vendor_ie_ouis`.

## Tasks

1. **Matcher** (`wifi_signatures.py`, fixture `fixtures/wifi_signature_cases.json`, tests). Commit `Match Wi-Fi access points against the signature catalog`.
2. **Ingest, storage, export/import, migration fields** (`models.py`, `0022`, `serializers.py`, `views.py`). Commit `Label Wi-Fi observations with catalog signatures at ingest`.
3. **Relabel + API** (backfill, `relabel_signatures`, serializer, filters, `/wifi-signatures/`, coverage names, docs/api.md). Commit `Relabel command and signature filters for access points`.
4. **Android** (`WifiIeFormat.kt` pure helper + test, `ScanResultMapper`, DTOs incl. response DTO). Commit `Send Wi-Fi vendor element OUIs`.
5. **PWA**. Commit `Show Wi-Fi signature matches in the PWA`.
6. **Deploy and verify** (rehearse migration on a DB copy, deploy, counts before/after, screenshots, test APK + dex grep, docs/roadmap, MEMORY.md, review, push).
