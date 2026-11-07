from django.core.management.base import BaseCommand

from scans.ble_signatures import relabel_all
from scans.wifi_signatures import relabel_all_wifi


class Command(BaseCommand):
    help = (
        "Recompute signature labels for all stored BLE and Wi-Fi observations, "
        "BLE devices and access points from the vendored catalog. Run after "
        "replacing scans/signatures/fieldwatch-signatures-v2.json. Idempotent."
    )

    def add_arguments(self, parser):
        parser.add_argument("--batch-size", type=int, default=2000)

    def handle(self, *args, **options):
        batch_size = max(1, options["batch_size"])
        self.stdout.write(f"ble: {relabel_all(batch_size=batch_size)}")
        self.stdout.write(f"wifi: {relabel_all_wifi(batch_size=batch_size)}")
