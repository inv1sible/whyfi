#!/bin/sh
# Regenerate every derived icon from the masters in icons/.
#
# sharp is a native module and only ever needed when the artwork changes, so
# it's installed into a throwaway container instead of becoming a project
# dependency. Nothing is written outside the repo.
#
#   ./scripts/generate-icons.sh
#
# Then rebuild to actually ship the result:
#   docker compose build backend && docker compose up -d backend   # favicon + PWA
#   ...and trigger an APK build from the PWA's Download page       # Android
set -eu

REPO=$(cd "$(dirname "$0")/.." && pwd)

docker run --rm \
    -v "$REPO/icons:/src:ro" \
    -v "$REPO:/repo" \
    -v "$REPO/scripts:/scripts:ro" \
    -w /tmp/icongen \
    node:22-slim \
    sh -c 'mkdir -p /tmp/icongen && cd /tmp/icongen &&
           npm init -y >/dev/null 2>&1 &&
           npm install --silent sharp png-to-ico >/dev/null 2>&1 &&
           cp /scripts/generate-icons.mjs . &&
           node generate-icons.mjs'
