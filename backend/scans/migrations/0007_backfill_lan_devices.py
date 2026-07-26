from django.db import migrations


def backfill_lan_devices(apps, schema_editor):
    LANObservation = apps.get_model("scans", "LANObservation")
    LANDevice = apps.get_model("scans", "LANDevice")

    devices = {}
    for obs in LANObservation.objects.order_by("observed_at"):
        device = devices.get(obs.ip_address)
        if device is None:
            device = LANDevice.objects.create(
                ip_address=obs.ip_address,
                mac_address=obs.mac_address,
                hostname=obs.hostname,
                vendor_oui=obs.vendor_oui,
                device_type_guess=obs.device_type_guess,
            )
            devices[obs.ip_address] = device
        obs.lan_device_id = obs.ip_address
        obs.save(update_fields=["lan_device"])

    # auto_now on first_seen_at/last_seen_at stamps "now" at creation time
    # above — fix both up to the actual observed_at range per device so
    # historical data doesn't all claim to have just been seen.
    for ip_address, device in devices.items():
        observed_ats = LANObservation.objects.filter(ip_address=ip_address).values_list("observed_at", flat=True)
        LANDevice.objects.filter(pk=ip_address).update(
            first_seen_at=min(observed_ats), last_seen_at=max(observed_ats),
        )


def noop_reverse(apps, schema_editor):
    pass


class Migration(migrations.Migration):

    dependencies = [
        ("scans", "0006_landevice_lanobservation_lan_device"),
    ]

    operations = [
        migrations.RunPython(backfill_lan_devices, noop_reverse),
    ]
