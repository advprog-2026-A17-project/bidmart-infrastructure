#!/usr/bin/env bash
# Verify BidMart monitoring stack: actuator health endpoints and Prometheus scrape targets.
# Production: USE_HEROKU_URLS=true with HEROKU_*_METRICS_URL in .env (see .env.example).
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
if [[ -f "${ROOT_DIR}/.env" ]]; then
  # shellcheck disable=SC1091
  set -a
  source "${ROOT_DIR}/.env"
  set +a
fi

GATEWAY_URL="${GATEWAY_URL:-http://127.0.0.1:8000}"
PROMETHEUS_URL="${PROMETHEUS_URL:-http://127.0.0.1:9090}"
GRAFANA_URL="${GRAFANA_URL:-http://127.0.0.1:3001}"
GRAFANA_AUTH="${GRAFANA_AUTH:-}"
USE_HEROKU_URLS="${USE_HEROKU_URLS:-false}"

COMPOSE_FILE="${COMPOSE_FILE:-${ROOT_DIR}/docker-compose.yml}"
USE_DOCKER="${USE_DOCKER:-auto}"

failures=0

log_ok() { printf '  OK   %s\n' "$1"; }
log_fail() { printf '  FAIL %s\n' "$1"; failures=$((failures + 1)); }

curl_health() {
  local name="$1"
  local url="$2"
  if curl -sf --max-time 5 "$url" >/dev/null; then
    log_ok "$name ($url)"
  else
    log_fail "$name ($url)"
  fi
}

curl_metrics() {
  local name="$1"
  local base_url="$2"
  local path="$3"
  if [[ -z "$base_url" || "$base_url" == *REPLACE_WITH* ]]; then
    log_fail "$name (unset or placeholder URL)"
    return
  fi
  base_url="${base_url%/}"
  local url="${base_url}${path}"
  local body
  if body="$(curl -sf --max-time 15 "$url" 2>/dev/null)" && [[ -n "$body" ]]; then
    if echo "$body" | grep -qE '^# HELP|^# TYPE|^[a-zA-Z_:][a-zA-Z0-9_:]* '; then
      log_ok "$name ($url)"
    else
      log_fail "$name ($url — not Prometheus text format)"
    fi
  else
    log_fail "$name ($url)"
  fi
}

verify_heroku_metrics() {
  echo
  echo "-- Heroku metrics endpoints (production) --"
  curl_metrics "Gateway prometheus" "${HEROKU_GATEWAY_METRICS_URL:-}" "/actuator/prometheus"
  curl_metrics "Auth prometheus" "${HEROKU_AUTH_METRICS_URL:-}" "/actuator/prometheus"
  curl_metrics "Catalogue prometheus" "${HEROKU_CATALOGUE_METRICS_URL:-}" "/actuator/prometheus"
  curl_metrics "Auction metrics" "${HEROKU_AUCTION_METRICS_URL:-}" "/metrics"
  curl_metrics "Wallet metrics" "${HEROKU_WALLET_METRICS_URL:-}" "/metrics"
  curl_metrics "Order prometheus" "${HEROKU_ORDER_METRICS_URL:-}" "/actuator/prometheus"
  echo
  if [[ "$failures" -eq 0 ]]; then
    echo "All Heroku metrics checks passed. Configure Grafana Cloud scrape next."
    exit 0
  fi
  echo "${failures} check(s) failed. Wake Eco dynos or fix URLs in .env / HEROKU_*_METRICS_URL."
  exit 1
}

docker_health() {
  local name="$1"
  local service="$2"
  local port="$3"
  local path="$4"
  local url="http://${service}:${port}${path}"
  if docker compose -f "$COMPOSE_FILE" run --rm --no-deps curlimages/curl:8.5.0 \
    -sf --max-time 5 "$url" >/dev/null 2>&1; then
    log_ok "$name (network:${service})"
  else
    log_fail "$name (network:${service} $url)"
  fi
}

host_reachable() {
  curl -sf --max-time 2 "${GATEWAY_URL}/actuator/health" >/dev/null 2>&1
}

echo "== BidMart monitoring verification =="

if [[ "$USE_HEROKU_URLS" == "true" ]]; then
  verify_heroku_metrics
fi

if [[ "$USE_DOCKER" == "auto" ]]; then
  if host_reachable; then
    USE_DOCKER=false
  else
    USE_DOCKER=true
  fi
fi

echo
echo "-- Health endpoints --"

if [[ "$USE_DOCKER" == "true" ]]; then
  docker_health "Gateway actuator health" gateway 8000 "/actuator/health"
  docker_health "Auth actuator health" auth-service 8080 "/actuator/health"
  docker_health "Catalogue actuator health" catalogue-service 8081 "/actuator/health"
  docker_health "Order actuator health" order-notification-service 8084 "/actuator/health"
  docker_health "Auction metrics" auction-service 8082 "/metrics"
  docker_health "Wallet metrics" wallet-service 8083 "/metrics"
else
  curl_health "Gateway actuator health" "${GATEWAY_URL}/actuator/health"
  curl_health "Prometheus self-health" "${PROMETHEUS_URL}/-/healthy"
fi

echo
echo "-- Prometheus targets --"

if ! command -v jq >/dev/null 2>&1; then
  log_fail "jq is required to parse Prometheus targets (brew install jq)"
  failures=$((failures + 1))
else
  targets_json="$(curl -sf --max-time 10 "${PROMETHEUS_URL}/api/v1/targets" 2>/dev/null || true)"
  if [[ -z "$targets_json" ]]; then
    log_fail "Prometheus targets API (${PROMETHEUS_URL}/api/v1/targets)"
    failures=$((failures + 1))
  else
    log_ok "Prometheus targets API"
    while IFS= read -r line; do
      job="$(echo "$line" | jq -r '.labels.job')"
      health="$(echo "$line" | jq -r '.health')"
      if [[ "$health" == "up" ]]; then
        log_ok "target ${job}"
      else
        log_fail "target ${job} (health=${health})"
      fi
    done < <(echo "$targets_json" | jq -c '.data.activeTargets[] | select(.labels.job | startswith("bidmart-"))')
  fi
fi

echo
echo "-- Grafana (optional) --"
grafana_curl_args=(-sf --max-time 5)
if [[ -n "$GRAFANA_AUTH" ]]; then
  grafana_curl_args=(-u "$GRAFANA_AUTH" "${grafana_curl_args[@]}")
fi
if curl "${grafana_curl_args[@]}" "${GRAFANA_URL}/api/health" | grep -Eq '"database"[[:space:]]*:[[:space:]]*"ok"'; then
  log_ok "Grafana API health (${GRAFANA_URL})"
else
  log_fail "Grafana API health (${GRAFANA_URL})"
fi

echo
if [[ "$failures" -eq 0 ]]; then
  echo "All monitoring checks passed."
  exit 0
fi

echo "${failures} check(s) failed. Ensure docker compose is up: cd bidmart-infrastructure && docker compose up -d"
echo "Tip: run with USE_DOCKER=true to probe services inside the compose network."
exit 1
