"""Import uploaded USB monitor missions as WiFi observations (scans/monitor_import.py).

    manage.py import_monitor_missions            # every mission not imported yet
    manage.py import_monitor_missions --force    # re-import all (replaces their sessions)
    manage.py import_monitor_missions --mission <uuid>
"""
from django.core.management.base import BaseCommand

from scans.models import MonitorMission
from scans.monitor_import import import_mission


class Command(BaseCommand):
    help = "Turn uploaded USB monitor missions into WiFi observations under '<phone>_USB'."

    def add_arguments(self, parser):
        parser.add_argument("--force", action="store_true", help="re-import missions imported before")
        parser.add_argument("--mission", help="only this mission id")

    def handle(self, *args, **options):
        missions = MonitorMission.objects.select_related("sensor").order_by("uploaded_at")
        if options["mission"]:
            missions = missions.filter(pk=options["mission"])
        elif not options["force"]:
            missions = missions.filter(imported_at__isnull=True)
        for mission in missions:
            try:
                summary = import_mission(mission, force=options["force"] or bool(options["mission"]))
            except Exception as exc:  # noqa: BLE001 — report and continue with the next mission
                self.stderr.write(f"{mission.pk}: failed ({exc.__class__.__name__}: {exc})")
                continue
            self.stdout.write(f"{mission.pk}: {summary}")
