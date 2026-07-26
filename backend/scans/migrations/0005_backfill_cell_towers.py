from django.db import migrations


def backfill_cell_towers(apps, schema_editor):
    CellObservation = apps.get_model("scans", "CellObservation")
    CellTower = apps.get_model("scans", "CellTower")

    groupable = CellObservation.objects.exclude(cell_id="").exclude(tac_or_lac="")
    towers = {}

    for obs in groupable.order_by("observed_at"):
        tower_key = f"{obs.mcc}-{obs.mnc}-{obs.tac_or_lac}-{obs.cell_id}"
        tower = towers.get(tower_key)
        if tower is None:
            tower = CellTower.objects.create(
                tower_key=tower_key,
                mcc=obs.mcc,
                mnc=obs.mnc,
                tac_or_lac=obs.tac_or_lac,
                cell_id=obs.cell_id,
                carrier_name=obs.carrier_name,
                radio_type=obs.radio_type,
            )
            towers[tower_key] = tower
        obs.cell_tower_id = tower_key
        obs.save(update_fields=["cell_tower"])

    # auto_now on first_seen_at/last_seen_at stamps "now" at creation time
    # above — fix both up to the actual observed_at range per tower so
    # historical data doesn't all claim to have just been seen.
    for tower_key, tower in towers.items():
        observed_ats = groupable.filter(
            mcc=tower.mcc, mnc=tower.mnc, tac_or_lac=tower.tac_or_lac, cell_id=tower.cell_id,
        ).values_list("observed_at", flat=True)
        CellTower.objects.filter(pk=tower_key).update(
            first_seen_at=min(observed_ats), last_seen_at=max(observed_ats),
        )


def noop_reverse(apps, schema_editor):
    pass


class Migration(migrations.Migration):

    dependencies = [
        ("scans", "0004_celltower_scansession_fused_accuracy_meters_and_more"),
    ]

    operations = [
        migrations.RunPython(backfill_cell_towers, noop_reverse),
    ]
