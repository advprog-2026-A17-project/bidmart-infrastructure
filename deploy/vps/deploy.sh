#!/usr/bin/env bash
# Deploy BidMart VPS backend (catalogue, auction, wallet, gateway).
# Invoked by GitHub Actions only — see .github/workflows/deploy-*-vps.yml
set -euo pipefail

if [[ -z "${GITHUB_ACTIONS:-}" && -z "${ALLOW_LOCAL_VPS_DEPLOY:-}" ]]; then
  echo "VPS deploy is CI/CD only. Push to staging on bidmart-infrastructure or run the deploy workflow in GitHub Actions." >&2
  echo "Emergency local override: ALLOW_LOCAL_VPS_DEPLOY=1 $0 <staging|prod>" >&2
  exit 2
fi

ENVIRONMENT="${1:-}"
if [[ "$ENVIRONMENT" != "staging" && "$ENVIRONMENT" != "prod" ]]; then
  echo "Usage: $0 <staging|prod>" >&2
  exit 2
fi

VPS_HOST="${VPS_HOST:-43.157.208.68}"
VPS_USER="${VPS_USER:-root}"
if [ -n "${BIDMART_BRANCH:-}" ]; then
  BRANCH="$BIDMART_BRANCH"
elif [ "$ENVIRONMENT" = "prod" ]; then
  BRANCH=main
else
  BRANCH=staging
fi
REMOTE_ROOT="${REMOTE_ROOT:-/opt/bidmart/${ENVIRONMENT}}"
REMOTE_ENV_FILE="${REMOTE_ENV_FILE:-/etc/bidmart/${ENVIRONMENT}.env}"
if [[ -n "${BIDMART_SSH:-}" ]]; then
  SSH_TARGET="${BIDMART_SSH}"
  SSH_OPTS="${SSH_OPTS:-}"
else
  SSH_TARGET="${VPS_USER}@${VPS_HOST}"
  SSH_OPTS="${SSH_OPTS:--p 22}"
fi

repos=(
  "bidmart-catalogue-service:https://github.com/advprog-2026-A17-project/bidmart-catalogue-service.git"
  "bidmart-auction-service-rust:https://github.com/advprog-2026-A17-project/bidmart-auction-service-rust.git"
  "bidmart-wallet-service-rust:https://github.com/advprog-2026-A17-project/bidmart-wallet-service-rust.git"
  "bidmart-infrastructure:https://github.com/advprog-2026-A17-project/bidmart-infrastructure.git"
)

remote_script="$(mktemp)"
trap 'rm -f "$remote_script"' EXIT

cat >"$remote_script" <<'REMOTE_SCRIPT'
set -euo pipefail

ENVIRONMENT="$1"
BRANCH="$2"
REMOTE_ROOT="$3"
REMOTE_ENV_FILE="$4"
shift 4

if ! test -f "$REMOTE_ENV_FILE" && ! sudo test -f "$REMOTE_ENV_FILE"; then
  echo "Missing env file: $REMOTE_ENV_FILE" >&2
  echo "Create it from bidmart-infrastructure/deploy/vps/env.${ENVIRONMENT}.example and fill secrets." >&2
  exit 3
fi

mkdir -p "$REMOTE_ROOT"
cd "$REMOTE_ROOT"

for entry in "$@"; do
  name="${entry%%:*}"
  url="${entry#*:}"
  if [[ ! -d "$name/.git" ]]; then
    git clone "$url" "$name"
  fi
  git -C "$name" fetch origin "$BRANCH"
  git -C "$name" checkout "$BRANCH"
  git -C "$name" reset --hard "origin/$BRANCH"
done

if [[ -r "$REMOTE_ENV_FILE" ]]; then
  cp "$REMOTE_ENV_FILE" "$REMOTE_ROOT/bidmart-infrastructure/.env"
else
  sudo cp "$REMOTE_ENV_FILE" "$REMOTE_ROOT/bidmart-infrastructure/.env"
  sudo chown "$(id -u):$(id -g)" "$REMOTE_ROOT/bidmart-infrastructure/.env"
fi
chmod 600 "$REMOTE_ROOT/bidmart-infrastructure/.env"
cd "$REMOTE_ROOT/bidmart-infrastructure"

set -a
# shellcheck disable=SC1091
source .env
set +a

DOCKER="docker"
if ! docker info >/dev/null 2>&1; then
  DOCKER="sudo docker"
fi

if [[ "$ENVIRONMENT" == "prod" && -f "deploy/vps/docker-compose.prod.yml" ]]; then
  COMPOSE_FILE="deploy/vps/docker-compose.prod.yml"
elif [[ "$ENVIRONMENT" == "staging" && -f "deploy/vps/docker-compose.staging.yml" ]]; then
  COMPOSE_FILE="deploy/vps/docker-compose.staging.yml"
else
  COMPOSE_FILE="docker-compose.yml"
fi

DEPLOY_TS="$(date -u +%Y%m%dT%H%M%SZ)"
DEPLOY_LOG_DIR="$REMOTE_ROOT/bidmart-infrastructure/deploy/logs"
DEPLOY_LOG="$DEPLOY_LOG_DIR/${ENVIRONMENT}-${DEPLOY_TS}.log"
ROLLBACK_ENV_FILE="$REMOTE_ROOT/bidmart-infrastructure/.rollback-${ENVIRONMENT}-${DEPLOY_TS}.env"
mkdir -p "$DEPLOY_LOG_DIR"
exec > >(tee -a "$DEPLOY_LOG") 2>&1

image_var_for_service() {
  case "$1" in
    gateway) echo "GATEWAY_IMAGE" ;;
    catalogue-service) echo "CATALOGUE_IMAGE" ;;
    auction-service) echo "AUCTION_IMAGE" ;;
    wallet-service) echo "WALLET_IMAGE" ;;
    *) return 1 ;;
  esac
}

capture_rollback_images() {
  : >"$ROLLBACK_ENV_FILE"
  local captured=0
  local services=(gateway catalogue-service auction-service wallet-service)

  echo "[deploy] Capturing rollback image tags before deployment"
  for service in "${services[@]}"; do
    local cid image_id image_var rollback_tag
    cid="$($DOCKER compose -f "$COMPOSE_FILE" --env-file .env ps -q "$service" 2>/dev/null || true)"
    if [[ -z "$cid" ]]; then
      echo "[deploy] No running container found for $service; rollback image unavailable"
      continue
    fi

    image_id="$($DOCKER inspect -f '{{.Image}}' "$cid" 2>/dev/null || true)"
    if [[ -z "$image_id" ]]; then
      echo "[deploy] Could not inspect image for $service; rollback image unavailable"
      continue
    fi

    image_var="$(image_var_for_service "$service")"
    rollback_tag="bidmart-rollback-${ENVIRONMENT}-${service}:${DEPLOY_TS}"
    $DOCKER image tag "$image_id" "$rollback_tag"
    printf '%s=%s\n' "$image_var" "$rollback_tag" >>"$ROLLBACK_ENV_FILE"
    echo "[deploy] Rollback snapshot: $service -> $rollback_tag"
    captured=$((captured + 1))
  done

  if [[ "$captured" -eq 0 ]]; then
    rm -f "$ROLLBACK_ENV_FILE"
    echo "[deploy] No rollback image snapshots captured; automatic rollback will be skipped"
  else
    chmod 600 "$ROLLBACK_ENV_FILE"
    echo "[deploy] Rollback image map saved to $ROLLBACK_ENV_FILE"
  fi
}

print_stack_debug() {
  echo "[deploy] Stack status"
  $DOCKER compose -f "$COMPOSE_FILE" --env-file .env ps || true

  echo "[deploy] Recent service logs"
  for service in gateway catalogue-service auction-service wallet-service; do
    echo "[deploy] --- logs: $service ---"
    $DOCKER compose -f "$COMPOSE_FILE" --env-file .env logs --tail=80 "$service" || true
  done
}

automatic_rollback() {
  local reason="$1"
  echo "[deploy] Deployment validation failed: $reason"
  print_stack_debug

  if [[ ! -f "$ROLLBACK_ENV_FILE" ]]; then
    echo "[deploy] Automatic rollback skipped: no rollback image map was captured"
    echo "[deploy] Full deploy log: $DEPLOY_LOG"
    return 1
  fi

  echo "[deploy] Starting automatic rollback using previous image snapshots"
  set -a
  # shellcheck disable=SC1090
  source "$ROLLBACK_ENV_FILE"
  set +a

  $DOCKER compose \
    -f "$COMPOSE_FILE" \
    --env-file .env \
    up -d --no-build --force-recreate --remove-orphans

  echo "[deploy] Waiting after rollback (30s)"
  sleep 30
  print_stack_debug
  echo "[deploy] Automatic rollback finished"
  echo "[deploy] Full deploy log: $DEPLOY_LOG"
  return 1
}

wait_for_service_state() {
  local service="$1"
  local max_seconds="$2"
  local allow_running="${3:-false}"
  local deadline cid state
  deadline=$((SECONDS + max_seconds))

  while (( SECONDS < deadline )); do
    cid="$($DOCKER compose -f "$COMPOSE_FILE" --env-file .env ps -q "$service" || true)"
    if [[ -n "$cid" ]]; then
      state="$($DOCKER inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$cid" 2>/dev/null || echo unknown)"
      if [[ "$state" == "healthy" || ( "$allow_running" == "true" && "$state" == "running" ) ]]; then
        echo "[deploy] $service state: $state"
        return 0
      fi
      echo "[deploy] $service state: $state"
    else
      echo "[deploy] $service container not found yet"
    fi
    sleep 5
  done

  return 1
}

run_spec_regression() {
  if [[ "${BIDMART_DEPLOY_RUN_SPEC_REGRESSION:-1}" != "1" ]]; then
    echo "[deploy] Spec regression skipped by BIDMART_DEPLOY_RUN_SPEC_REGRESSION"
    return 0
  fi

  if ! command -v node >/dev/null 2>&1; then
    echo "[deploy] Node.js is required for scripts/spec-regression.mjs"
    return 1
  fi

  local default_gateway_port gateway_url frontend_url run_id
  if [[ "$ENVIRONMENT" == "prod" ]]; then
    default_gateway_port="127.0.0.1:28000"
  else
    default_gateway_port="127.0.0.1:18000"
  fi

  gateway_url="${BIDMART_GATEWAY_URL:-http://${GATEWAY_PORT:-$default_gateway_port}}"
  frontend_url="${BIDMART_FRONTEND_URL:-${FRONTEND_BASE_URL:-${BIDMART_PUBLIC_HOST:-}}}"
  run_id="${BIDMART_E2E_RUN_ID:-${ENVIRONMENT}-${DEPLOY_TS}}"

  echo "[deploy] Running full synthetic spec regression against $gateway_url"
  BIDMART_GATEWAY_URL="$gateway_url" \
    BIDMART_FRONTEND_URL="$frontend_url" \
    BIDMART_E2E_ENV="$ENVIRONMENT" \
    BIDMART_E2E_RUN_ID="$run_id" \
    BIDMART_E2E_INTERNAL_TOKEN="${BIDMART_E2E_INTERNAL_TOKEN:-${GATEWAY_INTERNAL_TOKEN:-}}" \
    node scripts/spec-regression.mjs --scope full
}

capture_rollback_images

echo "[deploy] Bringing up $ENVIRONMENT stack with $COMPOSE_FILE"
if ! $DOCKER compose \
  -f "$COMPOSE_FILE" \
  --env-file .env \
  up -d --build --remove-orphans --pull always; then
  automatic_rollback "docker compose up failed"
fi

echo "[deploy] Waiting for catalogue-service to become healthy (max 180s)"
project_name="${COMPOSE_PROJECT_NAME:-bidmart-${ENVIRONMENT}}"
if ! wait_for_service_state catalogue-service 180 true; then
  automatic_rollback "catalogue-service did not become healthy"
fi

echo "[deploy] Waiting for gateway to become healthy (max 120s)"
if ! wait_for_service_state gateway 120 true; then
  automatic_rollback "gateway did not become healthy"
fi

echo "[deploy] Waiting for auction-service to be running (max 120s)"
if ! wait_for_service_state auction-service 120 true; then
  automatic_rollback "auction-service did not start"
fi

echo "[deploy] Waiting for wallet-service to be running (max 120s)"
if ! wait_for_service_state wallet-service 120 true; then
  automatic_rollback "wallet-service did not start"
fi

echo "[deploy] Running gRPC privacy checks"
if ! COMPOSE_FILE="$COMPOSE_FILE" ./scripts/verify-grpc-private.sh; then
  automatic_rollback "verify-grpc-private.sh failed"
fi

if [[ "$ENVIRONMENT" != "prod" ]]; then
  echo "[deploy] Running monitoring smoke tests (staging only)"
  if ! USE_DOCKER=true ./scripts/verify-monitoring.sh; then
    automatic_rollback "verify-monitoring.sh failed"
  fi
fi

if [[ "$ENVIRONMENT" == "prod" ]]; then
  echo "[deploy] Skipping spec regression on production (validated on staging)"
else
  if ! run_spec_regression; then
    automatic_rollback "scripts/spec-regression.mjs failed"
  fi
fi

echo "[deploy] Deployment validation passed"
echo "[deploy] Full deploy log: $DEPLOY_LOG"
REMOTE_SCRIPT

scp $SSH_OPTS "$remote_script" "${SSH_TARGET}:/tmp/bidmart-vps-deploy.sh"
ssh $SSH_OPTS "$SSH_TARGET" \
  "bash /tmp/bidmart-vps-deploy.sh '$ENVIRONMENT' '$BRANCH' '$REMOTE_ROOT' '$REMOTE_ENV_FILE' ${repos[*]@Q}"

echo "Deployment complete for ${ENVIRONMENT}."
