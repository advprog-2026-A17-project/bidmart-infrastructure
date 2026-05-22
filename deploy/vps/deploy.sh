#!/usr/bin/env bash
# Deploy BidMart backend to a VPS with private Docker networking for gRPC.
# Usage:
#   deploy/vps/deploy.sh staging
#   deploy/vps/deploy.sh prod
set -euo pipefail

ENVIRONMENT="${1:-}"
if [[ "$ENVIRONMENT" != "staging" && "$ENVIRONMENT" != "prod" ]]; then
  echo "Usage: $0 <staging|prod>" >&2
  exit 2
fi

VPS_HOST="${VPS_HOST:-43.157.208.68}"
VPS_USER="${VPS_USER:-root}"
BRANCH="${BIDMART_BRANCH:-feat/checkpoint-100}"
REMOTE_ROOT="${REMOTE_ROOT:-/opt/bidmart/${ENVIRONMENT}}"
REMOTE_ENV_FILE="${REMOTE_ENV_FILE:-/etc/bidmart/${ENVIRONMENT}.env}"
SSH_TARGET="${VPS_USER}@${VPS_HOST}"
SSH_OPTS="${SSH_OPTS:-}"

repos=(
  "bidmart-auth-service:https://github.com/advprog-2026-A17-project/bidmart-auth-service.git"
  "bidmart-catalogue-service:https://github.com/advprog-2026-A17-project/bidmart-catalogue-service.git"
  "bidmart-auction-service-rust:https://github.com/advprog-2026-A17-project/bidmart-auction-service-rust.git"
  "bidmart-wallet-service-rust:https://github.com/advprog-2026-A17-project/bidmart-wallet-service-rust.git"
  "bidmart-order-and-notification-service:https://github.com/advprog-2026-A17-project/bidmart-order-and-notification-service.git"
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

if [[ ! -f "$REMOTE_ENV_FILE" ]]; then
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
  git -C "$name" pull --ff-only origin "$BRANCH"
done

cp "$REMOTE_ENV_FILE" "$REMOTE_ROOT/bidmart-infrastructure/.env"
cd "$REMOTE_ROOT/bidmart-infrastructure"

set -a
source .env
set +a

docker compose \
  -f docker-compose.yml \
  --env-file .env \
  up -d --build

./scripts/verify-grpc-private.sh
USE_DOCKER=true ./scripts/verify-monitoring.sh
REMOTE_SCRIPT

scp $SSH_OPTS "$remote_script" "${SSH_TARGET}:/tmp/bidmart-vps-deploy.sh"
ssh $SSH_OPTS "$SSH_TARGET" \
  "bash /tmp/bidmart-vps-deploy.sh '$ENVIRONMENT' '$BRANCH' '$REMOTE_ROOT' '$REMOTE_ENV_FILE' ${repos[*]@Q}"

echo "Deployment complete for ${ENVIRONMENT}."
