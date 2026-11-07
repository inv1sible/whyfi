from django.core.management.base import BaseCommand

from scans.location_quality import clean_session_locations


class Command(BaseCommand):
    help = (
        "Correct implausible scan positions without losing the originals: a "
        "position far from its own fused fix, or a spike away from both "
        "neighbouring scans, becomes the fused fix or no position; the original "
        "moves to raw_*. Idempotent. --revert restores every original."
    )

    def add_arguments(self, parser):
        parser.add_argument("--dry-run", action="store_true", help="Report what would change, change nothing.")
        parser.add_argument("--revert", action="store_true", help="Restore every original position.")

    def handle(self, *args, **options):
        report = clean_session_locations(dry_run=options["dry_run"], revert=options["revert"])
        prefix = "dry run: " if options["dry_run"] else ""
        self.stdout.write(f"{prefix}{report}")
