# BLE Signature Catalog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the placeholder `BleSignatureMatcher` labels with Fieldwatch's 255-signature catalog, so every BLE sighting in whyfi is labeled with zero or more named signatures (e.g. "Flipper Zero", "DJI", "Axon", "Apple AirTags") grouped into classes (DRONE, HACKING, LAW_ENFORCEMENT, FINDER, …), with the rule that matched kept as evidence.

**Architecture:** Matching is **backend-authoritative**, in one Python module, for the same reason the position estimators live only in `backend/scans/estimators.py`: one implementation, and history can be relabeled whenever the catalog changes. The Android app's only job is to capture the *complete* advertisement (every manufacturer record and all service data; today it sends only the first manufacturer record and no service data). Labels are computed at ingest, stored on `BLEObservation`/`BLEDevice`, recomputed by a management command, and shown in the PWA.

**Tech Stack:** Django 4/DRF + Postgres JSONField, Kotlin (Android `ScanRecord`), React/TS PWA.

**Spec:** No separate spec doc. Requirements come from the 2026-11-07 session (maintainer: "BLE grouping and identification from Fieldwatch … a massive advance recognizing Bluetooth devices") and the decisions below. Source repo: https://github.com/OffGridPete/Fieldwatch at commit `2f79baf95a1a9cdfcc8261f3fb58079aeb75f191` (2026-11-05), MIT. Reference implementation: `app/src/main/java/app/fieldwatch/domain/SignatureEngine.kt` plus `uuidAliases`/`TextMatch`/`MacUtil` in `Models.kt`.

## Decisions (locked)

- Catalog file: Fieldwatch `dist/fieldwatch-signatures-v2.json` (`format: "fieldwatch-signatures"`, `formatVersion: 1`, `catalogVersion: 98`, 255 `fleets`). Vendored **unmodified**, so a later update is a file replacement.
- Only BLE is labeled in this plan. Wi-Fi labeling (6,036 OUI rules, vendor-IE rules) is a later plan. The matcher still honours `radio` scoping so it doesn't need rewriting later.
- Not ported: peer clustering (`minPeers`/`clusterByOui`/`sequentialMac`; **no stock fleet uses it**, so such a fleet simply never matches), `decode` payload field decoding (19 fleets: TPMS, Remote ID; that's the separate Remote ID item), the user signature editor, and anything from `PlaceMemory`/co-travel. Repo `MEMORY.md` records that anti-stalking correlation was rejected; labels stay informational and carry no alert state.
- `device_type_guess` and `BleSignatureMatcher.kt` remain as they are (old data, old APKs). The PWA shows signature chips and falls back to the legacy badge only when there are no signature matches.
- On-device (Android live screen) labeling is **out of scope**. It's a follow-up that would reuse `backend/scans/fixtures/ble_signature_cases.json` (Task 2) as a shared conformance fixture.

## Global Constraints

- Keep Fieldwatch's copyright line `Copyright (c) 2026 Off Grid Pete LLC` and its MIT text in a new root `NOTICE`, and note that the catalog's OUI / Bluetooth company-ID values derive from IEEE RA and Bluetooth SIG assigned numbers.
- One ingest endpoint, `POST /api/v1/scan-sessions/`, stays atomic. Don't add a per-radio endpoint (AGENT.md).
- New fields must be optional on ingest. APKs already in the field send only `manufacturer_data` (`"%04x:%s"` = company id hex : data hex) and `service_uuids`, and they must still ingest **and** get labels.
- Any new read endpoint uses the project default `IsAuthenticated` + `SessionAuthentication`.
- No new Python, npm or Gradle dependencies.
- PWA copy calls a hit a "pattern match", never an identification, matching Fieldwatch's own "Pattern match, not that camera" wording.
- Backend tests run against edited source with the mounted-source `docker run … manage.py test scans` command from the `backend-image-bakes-source` memory. Android tests run with `docker compose exec android-builder gradle testDebugUnitTest`.

## Review Focus

1. **Old APK payload** (only legacy `manufacturer_data` such as `"004c:1219…"`, no `manufacturer_records`/`service_data`): must still be labeled (here, Apple AirTags). Test: Task 3 `test_legacy_payload_is_labeled`.
2. **Long manufacturer data** (extended advertising, more than 255 hex chars): today `manufacturer_data` is `max_length=255` and a long value rejects the **whole scan session** with a 400. Test: Task 3 `test_long_manufacturer_record_does_not_reject_session`. Task 5 also changes Android to leave the legacy field blank when the value would exceed 255.
3. **Random BLE address that happens to start with a catalogued OUI** gives a weak, possibly false hit. The match records `rule: "OUI"`, and the PWA labels it "address prefix only". Tests: Task 2 `test_oui_match_records_rule_kind`, Task 6 manual check.
4. **Catalog replaced with a newer version** (fleet removed or rules changed): stored ids for removed fleets must not crash the API, and `relabel_ble_signatures` must recompute everything, idempotently. Tests: Task 4 `test_relabel_drops_removed_fleet`, `test_relabel_is_idempotent`; Task 4 `test_unknown_signature_id_is_served_without_crash`.
5. **Export → import round trip** must carry the new raw fields, so an imported session gets the same labels. Test: Task 3 `test_export_import_preserves_signature_inputs`.

---

### Task 1: Vendor catalog, NOTICE, and loader

**Files:**
- Create: `backend/scans/signatures/fieldwatch-signatures-v2.json` (byte-for-byte copy from the Fieldwatch commit above)
- Create: `backend/scans/signatures/README.md` (source URL, commit, how to update: replace file → deploy → `manage.py relabel_ble_signatures`)
- Create: `NOTICE` (root; Fieldwatch MIT copyright and permission notice; IEEE / Bluetooth SIG note)
- Create: `backend/scans/ble_signatures.py`
- Test: `backend/scans/test_ble_signatures.py`

**Interfaces:**
- Produces:
  - `@dataclass(frozen=True) class Rule: kind: str; text: str; company_id: int; data_prefix_hex: str; radio: str | None`. Only enabled rules are kept.
  - `@dataclass(frozen=True) class Fleet: id: str; name: str; kind: str; match_any: bool; rules: tuple[Rule, ...]; notes: str; attention_note: str; clustered: bool`. `clustered` = `minPeers > 0 or clusterByOui or sequentialMac`.
  - `@dataclass(frozen=True) class Catalog: version: int; fleets: tuple[Fleet, ...]` with `get(fleet_id) -> Fleet | None`
  - `parse_catalog(data: dict) -> Catalog`. Raises `ValueError` unless `format == "fieldwatch-signatures"` and `formatVersion == 1`. Drops `enabled: false` fleets.
  - `load_catalog() -> Catalog`, `functools.cache`d, reading the vendored file.

- [ ] **Step 1:** Write the failing tests. `test_stock_catalog_loads`: `load_catalog().version == 98`, `len(fleets) == 255`, `get("fleet-flipper").kind == "HACKING"`. `test_rejects_foreign_format`: `{"format": "x", "formatVersion": 1, "fleets": []}` raises `ValueError`. `test_disabled_rule_and_fleet_are_dropped`: inline catalog dict with one disabled fleet and one disabled rule. `test_cluster_fleet_flagged`: `minPeers: 3` gives `clustered is True`.
- [ ] **Step 2:** Run `… manage.py test scans.test_ble_signatures`. Expected: FAIL (ImportError).
- [ ] **Step 3:** Copy the catalog, write `README.md` and `NOTICE`, and implement the loader.
- [ ] **Step 4:** Run the tests. Expected: PASS.
- [ ] **Step 5:** Commit: `Vendor Fieldwatch BLE signature catalog with loader`.

### Task 2: Matcher

**Files:**
- Modify: `backend/scans/ble_signatures.py`
- Create: `backend/scans/fixtures/ble_signature_cases.json` (list of `{"name", "advert": {mac, name, service_uuids, manufacturer_records, service_data}, "expect": [fleet ids]}`, run against the stock catalog)
- Test: `backend/scans/test_ble_signatures.py`

**Interfaces:**
- Consumes: Task 1 `Catalog`, `Fleet`, `Rule`, `load_catalog`.
- Produces:
  - `@dataclass(frozen=True) class BleAdvert: mac: str; name: str; service_uuids: tuple[str, ...]; manufacturer_records: tuple[tuple[int, str], ...]; service_data: tuple[tuple[str, str], ...]`. Data hex is uppercase with no separators. Manufacturer data excludes the 2 company-ID bytes, as Android's `SparseArray` values already do.
  - `parse_advert(mac, name, service_uuids: list[str], manufacturer_records: list[str], service_data: list[str], legacy_manufacturer_data: str = "") -> BleAdvert`. Wire format is `"cccc:HEX"` (company id as hex) and `"uuid:HEX"`. Split on the **first** `:`. Malformed entries are skipped, not raised. `legacy_manufacturer_data` is used only when `manufacturer_records` is empty.
  - `match_ble(advert: BleAdvert, catalog: Catalog | None = None) -> list[dict]` returns `[{"id": fleet_id, "rule": rule_kind}]` in catalog order. `rule` is the kind of the **first** rule (in catalog order) that hit.

Rule semantics. Port these from `SignatureEngine.kt`; every bullet is a test case in the fixture:
- A rule with `radio == "WIFI"` never applies. `VENDOR_IE_OUI` and `HIDDEN_SSID` never apply to BLE. `RADIO_KIND` hits when `radio` is null or `"BLE"`.
- `OUI`/`MAC_PREFIX`: the hex-only MAC starts with the hex-only rule text (prefixes may be longer than 6).
- `NAME_CONTAINS`: case-insensitive substring. `NAME_GLOB`: `*`→`.*` and `?`→`.`, everything else literal, full match, case-insensitive. A blank name never matches.
- `SERVICE_UUID`: `uuid_aliases()` intersection against `service_uuids` **plus** service-data UUIDs. Port `uuidAliases` exactly: a 4-hex text also yields `0x…`, `0000XXXX-0000-1000-8000-00805F9B34FB` and the dashless form; a SIG-base 128-bit UUID also yields its 16-bit short forms; anything else is compared as raw-upper and hex-only.
- `MANUFACTURER_ID`: any record whose company id equals the rule's. `MANUFACTURER_DATA`: (`company_id == 0` or equal) and record hex starts with the hex prefix.
- `SERVICE_DATA`: optional UUID alias filter. If text is blank and a prefix is given, the prefix is a **contains** check in either byte order. Otherwise it's a starts-with check.
- `match_any == False`: every rule must hit. Fleets with `clustered` set, or no rules, never match.
- Post-filters, in this order:
  - (a) drop `fleet-ibeacon` if any other hit's fleet `kind != "BEACON"`;
  - (a′) never hit `fleet-ibeacon` when a `0x004C` record starts with `021574278BDAB64445208F0C720EAF059935` (Tesla phone key);
  - (b) drop `fleet-dji` when `fleet-osmo` or `fleet-dji-power` hit;
  - (c) drop `fleet-gopro` when `fleet-polar` hit;
  - (d) drop `fleet-airtag` unless the name contains "AirTag" or FD44 is present, whenever `fleet-apple-device`/`fleet-apple-audio` hit or a `0x004C` record's first byte is one of `05 07 08 09 0A 0B 0C 0D 0E 0F 10`.

- [ ] **Step 1:** Write the fixture cases (real values from the stock catalog) and `test_fixture_cases` iterating them. Minimum cases:
  - Flipper: name `"Flipper Zero"` gives `fleet-flipper`.
  - DJI: company `0x08AA` with data `"0600…"` gives `fleet-osmo` and **not** `fleet-dji`.
  - Polar: company `0x006B` plus UUID `FEA5` gives `fleet-polar` and not `fleet-gopro`.
  - AirTag: `0x004C` with `"12…"` gives `fleet-airtag`.
  - iPhone: `0x004C` with `"10…"` and `"12…"` gives `fleet-apple-device` and not `fleet-airtag`.
  - Tile: full SIG UUID `0000feed-0000-1000-8000-00805f9b34fb` gives `fleet-tile`.
  - SmartTag: service data only, UUID `FD5A` gives `fleet-smarttag`.
  - Generic iBeacon: `0x004C` with `"0215…"` gives `fleet-ibeacon`.
  - Tesla: an iBeacon with the Tesla prefix gives no `fleet-ibeacon`.
  - RayNeo (`match_any` false): name `RayNeo X2` alone gives nothing; with company `3014` (decimal) it gives `fleet-rayneo`.
  - An empty advert gives `[]`.
- [ ] **Step 2:** Add unit tests not covered by the fixture:
  - `test_oui_match_records_rule_kind`: MAC `0C:FA:22:11:22:33` gives `[{"id": "fleet-flipper", "rule": "OUI"}]`.
  - `test_wifi_scoped_rule_ignored_for_ble`.
  - `test_parse_advert_skips_malformed_entries`: `["zz:00", "nocolon"]` gives `()`.
  - `test_legacy_field_used_only_without_records`.
  - `test_glob_is_literal_except_wildcards`: `Pineapple_*` doesn't match `PineappleX`, and `.` in a pattern is literal.
- [ ] **Step 3:** Run them. Expected: FAIL.
- [ ] **Step 4:** Implement it. Index the catalog once per `Catalog` instance (dict of 6-hex OUI → fleet indices, plus a long-prefix list), as `Compiled` does in the Kotlin version. 255 fleets × every BLE row is otherwise slow on relabel.
- [ ] **Step 5:** Run them. Expected: PASS.
- [ ] **Step 6:** Commit: `Match BLE adverts against the signature catalog`.

### Task 3: Store raw adverts and labels at ingest; export/import

**Files:**
- Modify: `backend/scans/models.py` (`BLEObservation`, `BLEDevice`)
- Create: `backend/scans/migrations/0021_ble_signatures.py`
- Modify: `backend/scans/serializers.py` (`BLEObservationInputSerializer`, ingest loop around the `ble_observations` block, `BLEObservationSerializer`)
- Modify: `backend/scans/views.py` (session export dict, `ble_observations` entry near line 314)
- Test: `backend/scans/test_ble_signatures.py`

**Interfaces:**
- Consumes: `parse_advert`, `match_ble`, `load_catalog` (Tasks 1–2).
- Produces:
  - `BLEObservation`: `manufacturer_records = JSONField(default=list, blank=True)`, `service_data = JSONField(default=list, blank=True)`, `signature_matches = JSONField(default=list, blank=True)`, `signature_catalog_version = IntegerField(null=True, blank=True)`.
  - `BLEDevice`: `signature_ids = JSONField(default=list, blank=True)`, the sorted union of ids over all of its observations.
  - Ingest input: `manufacturer_records = ListField(child=CharField(max_length=4096), max_length=32, default=list)`, `service_data` the same.
  - `label_ble_observation(obs: BLEObservation, catalog: Catalog) -> None` in `ble_signatures.py` sets `signature_matches` and `signature_catalog_version`. It does not save.

- [ ] **Step 1:** Write failing tests in `IngestSignatureTests` (POST through the real endpoint with a sensor token, as existing ingest tests do):
  - `test_new_payload_is_labeled`: `device_name: "Flipper Zero"` gives `obs.signature_matches == [{"id": "fleet-flipper", "rule": "NAME_CONTAINS"}]` (or whichever Flipper rule comes first in catalog order; assert on `id` only), `obs.signature_catalog_version == 98`, and `BLEDevice.signature_ids == ["fleet-flipper"]`.
  - `test_legacy_payload_is_labeled`: only `manufacturer_data: "004c:12190000"` gives `fleet-airtag`.
  - `test_long_manufacturer_record_does_not_reject_session`: one record of 600 hex chars gives 201.
  - `test_device_signature_ids_accumulate`: two sessions for the same MAC, first with a Flipper name and then without a name, still gives `["fleet-flipper"]`.
  - `test_export_import_preserves_signature_inputs`: ingest with `service_data: ["0000fd5a-0000-1000-8000-00805f9b34fb:01"]`, export, delete, import, and the imported obs has `fleet-smarttag`.
- [ ] **Step 2:** Run them. Expected: FAIL.
- [ ] **Step 3:** Add the fields and migration. In the ingest loop, call `label_ble_observation` before `create` and union into `ble_device.signature_ids`. Add the two raw lists to the export dict. Raise `BLEObservationInputSerializer.manufacturer_data` to `max_length=1024` and widen `BLEObservation.manufacturer_data_raw` to `TextField` in the same migration (Review Focus 2).
- [ ] **Step 4:** Run the tests plus the full `scans` suite. Expected: PASS, with no regressions in existing ingest/export tests.
- [ ] **Step 5:** Commit: `Label BLE observations with catalog signatures at ingest`.

### Task 4: Relabel command and read API

**Files:**
- Create: `backend/scans/management/commands/relabel_ble_signatures.py`
- Modify: `backend/scans/migrations/0021_ble_signatures.py` (add a `RunPython` backfill that calls the same function; reverse is a no-op)
- Modify: `backend/scans/serializers.py` (`BLEDeviceSerializer`), `backend/scans/views.py` (`BLEDeviceViewSet.get_queryset`, new `BLESignatureViewSet`), URL router
- Test: `backend/scans/test_ble_signatures.py`

**Interfaces:**
- Produces:
  - `relabel_all(catalog: Catalog | None = None, batch_size: int = 2000) -> dict` in `ble_signatures.py` returns `{"observations": n, "devices": m, "catalog_version": v}`. It iterates with `.iterator()` and `bulk_update`, then recomputes every `BLEDevice.signature_ids` from its observations. The command prints that dict.
  - `BLEDeviceSerializer.signatures`: `[{"id", "name", "kind", "rules": [distinct rule kinds that hit across the device's observations]}]`. An id that isn't in the current catalog is returned as `{"id", "name": id, "kind": "OTHER", "rules": […]}`. The detail view also adds `notes` and `attention_note`.
  - `GET /api/v1/ble-devices/?signature=<fleet id>` and `?signature_class=<KIND>` filter with `signature_ids__contains=[id]`; a class is an OR over its fleet ids.
  - `GET /api/v1/ble-signatures/` returns `{"catalog_version": 98, "results": [{"id", "name", "kind"}]}` for BLE-capable fleets only (at least one rule whose `radio != "WIFI"`, not `VENDOR_IE_OUI`/`HIDDEN_SSID`). There are 173 in the stock catalog.

- [ ] **Step 1:** Write the failing tests:
  - `test_relabel_labels_unlabeled_rows`: a row created with empty `signature_matches` (bypass ingest) gets labeled.
  - `test_relabel_drops_removed_fleet`: relabel with a `parse_catalog` catalog that lacks `fleet-flipper` gives `signature_ids == []`.
  - `test_relabel_is_idempotent`: run twice and compare.
  - `test_unknown_signature_id_is_served_without_crash`: stored `["fleet-gone"]` gives 200 with `name == "fleet-gone"`.
  - `test_filter_by_signature_and_class`: `signature_class=HACKING` returns the Flipper device and not the AirTag device.
  - `test_signature_list_is_ble_only_and_authenticated`: 173 results, and 403 when logged out.
- [ ] **Step 2:** Run them. Expected: FAIL.
- [ ] **Step 3:** Implement it.
- [ ] **Step 4:** Run the full `scans` suite. Expected: PASS.
- [ ] **Step 5:** Commit: `Relabel command and signature filters for BLE devices`.

### Task 5: Android captures the full advertisement

**Files:**
- Modify: `android/app/src/main/java/com/whyfi/app/ble/BleDeviceScanner.kt` (`toDto`)
- Create: `android/app/src/main/java/com/whyfi/app/ble/BleAdvertFormat.kt`
- Modify: `android/app/src/main/java/com/whyfi/app/data/remote/Dtos.kt` (`BleObservationDto`, plus any parallel BLE DTO at lines ~380/418 that is round-tripped)
- Test: `android/app/src/test/java/com/whyfi/app/ble/BleAdvertFormatTest.kt`

**Interfaces:**
- Produces, as pure Kotlin with no Android types so it can be unit-tested:
  - `object BleAdvertFormat { fun manufacturerRecords(records: List<Pair<Int, ByteArray>>): List<String>; fun serviceData(entries: List<Pair<String, ByteArray>>): List<String>; fun legacyManufacturerData(records: List<String>): String }`. Formats are `"%04x:%s"` and `"<uuid lowercase>:<hex lowercase>"`, matching Task 2's `parse_advert`. `legacyManufacturerData` returns `records.firstOrNull()` if its length is ≤ 255, else `""`.
  - `BleObservationDto` gains `@SerializedName("manufacturer_records") val manufacturerRecords: List<String> = emptyList()` and `@SerializedName("service_data") val serviceData: List<String> = emptyList()`.
- `toDto` reads **every** `SparseArray` entry of `scanRecord.manufacturerSpecificData` (not just `keyAt(0)`) and every `scanRecord.serviceData` entry (`ParcelUuid.uuid.toString()`).

- [ ] **Step 1:** Write the failing tests:
  - `formatsCompanyIdAsFourHex`: `(0x004C, [0x12, 0x19])` gives `"004c:1219"`.
  - `keepsAllRecordsInOrder`.
  - `serviceDataUsesFullUuid`: `("0000fd5a-0000-1000-8000-00805f9b34fb", [0x01])` gives `"0000fd5a-0000-1000-8000-00805f9b34fb:01"`.
  - `legacyFieldBlankWhenTooLong`: a 200-byte record gives `""`.
- [ ] **Step 2:** Run `docker compose exec android-builder gradle testDebugUnitTest --tests '*BleAdvertFormatTest*'`. Expected: FAIL.
- [ ] **Step 3:** Implement it and wire it into `toDto` and the DTO.
- [ ] **Step 4:** Run the full `gradle testDebugUnitTest`. Expected: PASS (including `ScanDiffTest`, which builds a `BleObservationDto`).
- [ ] **Step 5:** Commit: `Send every BLE manufacturer record and service data`.

### Task 6: PWA signature chips, filter, and detail notes

**Files:**
- Modify: `frontend/src/api/types.ts` (BLE device type: `signatures`, `notes`, `attention_note`)
- Create: `frontend/src/components/SignatureChips.tsx`
- Modify: `frontend/src/pages/BLEDevicesPage.tsx`, `frontend/src/pages/BLEDeviceDetailPage.tsx`, `frontend/src/pages/HeatmapPage.tsx` (`describeType`: prefer the first signature name)

**Interfaces:**
- Consumes: Task 4 API shapes.
- Produces: `SignatureChips({ signatures }: { signatures: BleSignatureRef[] })`. One `badge` per signature shows `name` and a muted class label. A signature whose only `rules` entry is `"OUI"` or `"MAC_PREFIX"` gets the suffix " · address prefix only". The component renders `DeviceTypeBadge` with the legacy guess when `signatures` is empty.

- [ ] **Step 1:** BLE list gets a class `<select>` filled from `/ble-signatures/` kinds, sent as `signature_class`, plus the chips in the Type column. Detail page shows chips, each signature's `notes`, and an `attention_note` (when non-empty) under the heading "Pattern match". Copy for that section: "A pattern match on the advertisement, not proof of what the device is." Keep the existing "informational only" comment and add no alert UI.
- [ ] **Step 2:** `cd frontend && npm run build`. Expected: `tsc` with no errors and the Vite build succeeds.
- [ ] **Step 3:** Commit: `Show BLE signature matches in the PWA`.

### Task 7: Deploy and verify live

- [ ] **Step 1:** Run `docker compose build backend && docker compose up -d backend`, then `docker compose exec backend python manage.py migrate`. The `RunPython` backfill labels existing rows. Record the printed counts or check them with `docker compose exec backend python manage.py relabel_ble_signatures`.
- [ ] **Step 2:** Check the existing data: `docker compose exec backend python manage.py shell -c "from scans.models import BLEDevice; print(BLEDevice.objects.exclude(signature_ids=[]).count(), BLEDevice.objects.count())"`. Expected: a non-zero labeled count (legacy `manufacturer_data` alone covers Apple and other manufacturer-data fleets).
- [ ] **Step 3:** Browser check of the BLE Devices page: chips are visible, the class filter narrows the list, and the detail page shows the Pattern match notes.
- [ ] **Step 4:** Build and publish a test APK through the Download page's **Build Android App**, as AGENT.md describes. Then on a physical phone, run one scan near a known device (an AirTag, AirPods, or any BLE tracker). Expected: the new observation row has a non-empty `service_data` or more than one `manufacturer_records` entry where the device sends them, and it's labeled.
- [ ] **Step 5:** Update `docs/roadmap.md`: change the BLE bullet ("informational device type badges") to describe catalog signatures, and point to `backend/scans/signatures/README.md` for updates. Commit `Document BLE signature catalog`, then push (AGENT.md authorizes non-force push after verification).
