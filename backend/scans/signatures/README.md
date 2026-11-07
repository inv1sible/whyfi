# BLE signature catalog

`fieldwatch-signatures-v2.json` is Fieldwatch's stock signature catalog,
vendored **unmodified**. Keep it that way, so an update is a plain file
replacement and a diff against upstream stays empty.

- Source: https://github.com/OffGridPete/Fieldwatch
- File: `dist/fieldwatch-signatures-v2.json`
- Commit: `2f79baf95a1a9cdfcc8261f3fb58079aeb75f191` (2026-11-05)
- `catalogVersion`: 98, 255 fleets (173 can match BLE)
- License: MIT, Copyright (c) 2026 Off Grid Pete LLC. See the root `NOTICE`.

The matcher is `backend/scans/ble_signatures.py`, a port of Fieldwatch's
`app/src/main/java/app/fieldwatch/domain/SignatureEngine.kt`. Only BLE is
labeled. Wi-Fi rules (BSSID OUI, vendor IE, hidden SSID) are parsed but never
applied, and clustered fleets (`minPeers` / `clusterByOui` / `sequentialMac`)
and `decode` blocks are not ported. Labels are pattern matches, not
identifications, and carry no alert state (see `MEMORY.md`).

## Updating the catalog

1. Replace `fieldwatch-signatures-v2.json` with the new upstream file
   (byte-for-byte; check `git hash-object` against upstream's blob).
   Update the commit and `catalogVersion` above.
2. Run the backend tests. `CatalogLoaderTests.test_stock_catalog_loads` pins
   the version and fleet count, and `fixtures/ble_signature_cases.json` pins
   real matches. Update them if upstream changed those fleets on purpose.
3. Deploy (`docker compose build backend && docker compose up -d backend`).
4. Relabel stored history:
   `docker compose exec backend python manage.py relabel_ble_signatures`.
   This recomputes every `BLEObservation.signature_matches` and every
   `BLEDevice.signature_ids`, drops ids of removed fleets, and is idempotent.
