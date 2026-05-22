#!/usr/bin/env bash
# Rebuild catalogue + order images after Flyway/Rabbit fixes (bypasses stale Docker layer cache).
set -euo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-$(cd "$(dirname "$0")/.." && pwd)/docker-compose.yml}"

echo "== Rebuilding catalogue-service and order-notification-service (no cache) =="
docker compose -f "$COMPOSE_FILE" build --no-cache catalogue-service order-notification-service

echo "== Restarting stack (optional: pass --reset-volumes to run compose down -v first) =="
if [[ "${1:-}" == "--reset-volumes" ]]; then
  docker compose -f "$COMPOSE_FILE" down -v
fi

docker compose -f "$COMPOSE_FILE" up -d catalogue-service order-notification-service

echo "Done. Verify:"
echo "  docker compose -f \"$COMPOSE_FILE\" logs -f catalogue-service order-notification-service"
echo "  curl -sf http://127.0.0.1:8000/api/v1/catalogue/listings/search?page=0&size=1"
