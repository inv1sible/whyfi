"""BLE signature catalog: loader, matcher, ingest labeling, relabel and API.

The catalog is Fieldwatch's stock signature export, vendored unmodified in
scans/signatures/. See backend/scans/signatures/README.md.
"""

from django.test import SimpleTestCase

from .ble_signatures import load_catalog, parse_catalog


def _catalog_dict(fleets):
    return {"format": "fieldwatch-signatures", "formatVersion": 1, "catalogVersion": 1, "fleets": fleets}


def _rule(kind, text="", company_id=0, prefix="", radio=None, enabled=True):
    return {
        "kind": kind, "text": text, "companyId": company_id, "dataPrefixHex": prefix,
        "radio": radio, "enabled": enabled,
    }


def _fleet(fleet_id, rules, kind="OTHER", match_any=True, enabled=True, **extra):
    return {
        "id": fleet_id, "name": fleet_id, "enabled": enabled, "matchAny": match_any,
        "kind": kind, "rules": rules, **extra,
    }


class CatalogLoaderTests(SimpleTestCase):
    def test_stock_catalog_loads(self):
        catalog = load_catalog()
        self.assertEqual(catalog.version, 98)
        self.assertEqual(len(catalog.fleets), 255)
        self.assertEqual(catalog.get("fleet-flipper").kind, "HACKING")
        self.assertIsNone(catalog.get("fleet-does-not-exist"))

    def test_rejects_foreign_format(self):
        with self.assertRaises(ValueError):
            parse_catalog({"format": "x", "formatVersion": 1, "fleets": []})
        with self.assertRaises(ValueError):
            parse_catalog({"format": "fieldwatch-signatures", "formatVersion": 2, "fleets": []})

    def test_disabled_rule_and_fleet_are_dropped(self):
        catalog = parse_catalog(_catalog_dict([
            _fleet("fleet-off", [_rule("NAME_CONTAINS", "x")], enabled=False),
            _fleet("fleet-on", [
                _rule("NAME_CONTAINS", "kept"),
                _rule("NAME_CONTAINS", "dropped", enabled=False),
            ]),
        ]))
        self.assertEqual([f.id for f in catalog.fleets], ["fleet-on"])
        self.assertEqual([r.text for r in catalog.get("fleet-on").rules], ["kept"])

    def test_cluster_fleet_flagged(self):
        catalog = parse_catalog(_catalog_dict([
            _fleet("fleet-cluster", [_rule("NAME_CONTAINS", "x")], minPeers=3),
            _fleet("fleet-plain", [_rule("NAME_CONTAINS", "x")]),
        ]))
        self.assertIs(catalog.get("fleet-cluster").clustered, True)
        self.assertIs(catalog.get("fleet-plain").clustered, False)
