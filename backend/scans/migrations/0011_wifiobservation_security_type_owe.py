# Hand-written: adds the OWE choice to WiFiObservation.security_type.
#
# Choices-only AlterField, so this is a Django state change with no DDL on
# Postgres (the column stays a varchar(12) and choices aren't enforced at the
# database level). Written by hand rather than via makemigrations for exactly
# that reason — see AGENT.md on generating migrations against a real Postgres:
# there is nothing here that a live database needs to be consulted about.
from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('scans', '0010_backfill_ble_devices'),
    ]

    operations = [
        migrations.AlterField(
            model_name='wifiobservation',
            name='security_type',
            field=models.CharField(
                choices=[
                    ('OPEN', 'Open'),
                    ('WEP', 'WEP'),
                    ('WPA', 'WPA'),
                    ('WPA2', 'WPA2'),
                    ('WPA3', 'WPA3'),
                    ('WPA2_WPA3', 'WPA2/WPA3'),
                    ('OWE', 'Enhanced Open (OWE)'),
                    ('UNKNOWN', 'Unknown'),
                ],
                default='UNKNOWN',
                max_length=12,
            ),
        ),
    ]
