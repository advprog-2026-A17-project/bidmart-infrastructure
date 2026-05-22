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

echo "[deploy] Bringing up $ENVIRONMENT stack with $COMPOSE_FILE"
$DOCKER compose \
  -f "$COMPOSE_FILE" \
  --env-file .env \
  up -d --build --remove-orphans --pull always

echo "[deploy] Waiting for catalogue-service to become healthy (max 180s)"
deadline=$((SECONDS + 180))
project_name="${COMPOSE_PROJECT_NAME:-bidmart-${ENVIRONMENT}}"
catalogue_cid=""
while (( SECONDS < deadline )); do
  catalogue_cid="$($DOCKER compose -f "$COMPOSE_FILE" --env-file .env ps -q catalogue-service || true)"
  if [[ -n "$catalogue_cid" ]]; then
    state="$($DOCKER inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$catalogue_cid" 2>/dev/null || echo unknown)"
    if [[ "$state" == "healthy" || "$state" == "running" ]]; then
      echo "[deploy] catalogue-service state: $state"
      break
    fi
  fi
  sleep 5
done

echo "[deploy] Running gRPC privacy checks"
COMPOSE_FILE="$COMPOSE_FILE" ./scripts/verify-grpc-private.sh || {
  echo "[deploy] verify-grpc-private.sh failed; continuing to monitoring check" >&2
}
if [[ "$ENVIRONMENT" != "prod" ]]; then
  echo "[deploy] Running monitoring smoke tests (staging only)"
  USE_DOCKER=true ./scripts/verify-monitoring.sh || {
    echo "[deploy] verify-monitoring.sh failed; non-fatal in staging" >&2
  }
fi
REMOTE_SCRIPT

scp $SSH_OPTS "$remote_script" "${SSH_TARGET}:/tmp/bidmart-vps-deploy.sh"
ssh $SSH_OPTS "$SSH_TARGET" \
  "bash /tmp/bidmart-vps-deploy.sh '$ENVIRONMENT' '$BRANCH' '$REMOTE_ROOT' '$REMOTE_ENV_FILE' ${repos[*]@Q}"

echo "Deployment complete for ${ENVIRONMENT}."
