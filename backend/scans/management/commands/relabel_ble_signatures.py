from django.core.management.base import BaseCommand

from scans.ble_signatures import relabel_all


class Command(BaseCommand):
    help = (
        "Recompute BLE signature labels for every stored BLE observation and "
        "device from the vendored catalog. Run after replacing "
        "scans/signatures/fieldwatch-signatures-v2.json. Idempotent."
    )

    def add_arguments(self, parser):
        parser.add_argument("--batch-size", type=int, default=2000)

    def handle(self, *args, **options):
        self.stdout.write(str(relabel_all(batch_size=max(1, options["batch_size"]))))
