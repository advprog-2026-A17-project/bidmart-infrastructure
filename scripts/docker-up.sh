#!/usr/bin/env bash
# Pre-pull Compose base images with retries, then build and start the stack.
# Use when Docker Hub metadata pulls fail with TLS handshake timeout.
set -euo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-$(cd "$(dirname "$0")/.." && pwd)/docker-compose.yml}"
MAX_RETRIES="${DOCKER_PULL_RETRIES:-5}"
INITIAL_BACKOFF_SEC="${DOCKER_PULL_BACKOFF_SEC:-5}"

BASE_IMAGES=(
  rust:1.88-bookworm
  eclipse-temurin:21-jdk-alpine
  eclipse-temurin:21-jre-alpine
  gradle:8.14.4-jdk21
  gradle:8.14.4-jdk21-alpine
  postgres:16-alpine
  rabbitmq:3-management-alpine
  redis:7-alpine
  prom/prometheus:v3.1.0
  grafana/grafana:11.5.2
  swaggerapi/swagger-ui:latest
)

pull_with_retry() {
  local image="$1"
  local attempt=1
  local backoff="$INITIAL_BACKOFF_SEC"

  while (( attempt <= MAX_RETRIES )); do
    echo "Pulling ${image} (attempt ${attempt}/${MAX_RETRIES})..."
    if docker pull "${image}"; then
      return 0
    fi
    if (( attempt == MAX_RETRIES )); then
      echo "Failed to pull ${image} after ${MAX_RETRIES} attempts." >&2
      return 1
    fi
    echo "Retrying ${image} in ${backoff}s..."
    sleep "${backoff}"
    attempt=$((attempt + 1))
    backoff=$((backoff * 2))
  done
}

echo "== Pre-pulling base images =="
for image in "${BASE_IMAGES[@]}"; do
  pull_with_retry "${image}"
done

echo "== Building and starting stack =="
docker compose -f "${COMPOSE_FILE}" up -d --build "$@"

echo "Done. Gateway: http://localhost:8000"
