from django.db import migrations


def backfill_ble_devices(apps, schema_editor):
    BLEObservation = apps.get_model("scans", "BLEObservation")
    BLEDevice = apps.get_model("scans", "BLEDevice")

    devices = {}
    for obs in BLEObservation.objects.order_by("observed_at"):
        device_key = obs.ble_mac or obs.stable_identifier
        if not device_key:
            continue
        device = devices.get(device_key)
        if device is None:
            device = BLEDevice.objects.create(
                device_key=device_key,
                device_name=obs.device_name,
                device_type_guess=obs.device_type_guess,
            )
            devices[device_key] = device
        obs.ble_device_id = device_key
        obs.save(update_fields=["ble_device"])

    # auto_now on first_seen_at/last_seen_at stamps "now" at creation time
    # above — fix both up to the actual observed_at range per device so
    # historical data doesn't all claim to have just been seen.
    for device_key, device in devices.items():
        observed_ats = BLEObservation.objects.filter(ble_device_id=device_key).values_list("observed_at", flat=True)
        BLEDevice.objects.filter(pk=device_key).update(
            first_seen_at=min(observed_ats), last_seen_at=max(observed_ats),
        )


def noop_reverse(apps, schema_editor):
    pass


class Migration(migrations.Migration):

    dependencies = [
        ("scans", "0009_bledevice_bleobservation_ble_device"),
    ]

    operations = [
        migrations.RunPython(backfill_ble_devices, noop_reverse),
    ]
