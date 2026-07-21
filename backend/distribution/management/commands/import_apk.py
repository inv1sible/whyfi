import os

from django.core.files import File
from django.core.management.base import BaseCommand, CommandError

from distribution.models import AppRelease


class Command(BaseCommand):
    help = "Register a built Android APK (from android-builder's output) as a new AppRelease."

    def add_arguments(self, parser):
        parser.add_argument("apk_path", type=str)
        parser.add_argument("--version-code", type=int, required=True)
        parser.add_argument("--version-name", type=str, required=True)
        parser.add_argument("--notes", type=str, default="")

    def handle(self, *args, **options):
        apk_path = options["apk_path"]
        if not os.path.exists(apk_path):
            raise CommandError(f"APK not found at {apk_path}")

        version_code = options["version_code"]
        if AppRelease.objects.filter(version_code=version_code).exists():
            raise CommandError(f"version_code {version_code} is already registered")

        with open(apk_path, "rb") as apk_fileobj:
            release = AppRelease(
                version_code=version_code,
                version_name=options["version_name"],
                release_notes=options["notes"],
            )
            release.apk_file.save(os.path.basename(apk_path), File(apk_fileobj), save=True)

        self.stdout.write(self.style.SUCCESS(f"Registered {release} as the latest release."))
