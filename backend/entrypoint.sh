#!/bin/sh
set -e

echo "Waiting for database..."
python - <<'PYEOF'
import os
import sys
import time

import psycopg2

for _ in range(30):
    try:
        psycopg2.connect(
            dbname=os.environ.get("POSTGRES_DB", "whyfi"),
            user=os.environ.get("POSTGRES_USER", "whyfi"),
            password=os.environ.get("POSTGRES_PASSWORD", "whyfi"),
            host=os.environ.get("POSTGRES_HOST", "postgres"),
            port=os.environ.get("POSTGRES_PORT", "5432"),
        ).close()
        sys.exit(0)
    except psycopg2.OperationalError:
        time.sleep(1)
print("Database never became available", file=sys.stderr)
sys.exit(1)
PYEOF

python manage.py migrate --noinput
python manage.py ensure_superuser

if [ "$LOAD_DEMO_DATA" = "true" ]; then
  python manage.py loaddata demo_seed || true
fi

python manage.py collectstatic --noinput

exec gunicorn config.wsgi:application --bind 0.0.0.0:8000 --workers 3 \
  --access-logfile - --error-logfile - \
  --access-logformat '%(t)s "%(r)s" %(s)s %(b)s "%(a)s"'
