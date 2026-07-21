# Build context is the repo root (see docker-compose.yml), so this Dockerfile
# can pull in both backend/ and frontend/.

# --- Stage 1: build the React/Vite PWA ---
FROM node:20-alpine AS frontend-builder
WORKDIR /frontend
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# --- Stage 2: Django runtime (gunicorn + WhiteNoise, no nginx/Caddy) ---
FROM python:3.12-slim AS backend

WORKDIR /app
ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1

RUN apt-get update \
    && apt-get install -y --no-install-recommends libpq-dev gcc \
    && rm -rf /var/lib/apt/lists/*

COPY backend/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY backend/ .
COPY --from=frontend-builder /frontend/dist ./frontend_dist

RUN chmod +x entrypoint.sh \
    && mkdir -p media staticfiles

EXPOSE 8000
ENTRYPOINT ["./entrypoint.sh"]
