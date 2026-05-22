#!/usr/bin/env bash
# Deploy the shared VPS Caddy edge for staging and production gateway URLs.
set -euo pipefail

VPS_HOST="${VPS_HOST:-43.157.208.68}"
VPS_USER="${VPS_USER:-root}"
REMOTE_EDGE_DIR="${REMOTE_EDGE_DIR:-/opt/bidmart/edge}"
REMOTE_EDGE_ENV="${REMOTE_EDGE_ENV:-/etc/bidmart/edge.env}"
if [[ -n "${BIDMART_SSH:-}" ]]; then
  SSH_TARGET="${BIDMART_SSH}"
  SSH_OPTS="${SSH_OPTS:-}"
else
  SSH_TARGET="${VPS_USER}@${VPS_HOST}"
  SSH_OPTS="${SSH_OPTS:--p 22}"
fi
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

ssh $SSH_OPTS "$SSH_TARGET" "mkdir -p '$REMOTE_EDGE_DIR'"
scp $SSH_OPTS "$SCRIPT_DIR/Caddyfile" "$SCRIPT_DIR/caddy-compose.yml" "${SSH_TARGET}:${REMOTE_EDGE_DIR}/"

ssh $SSH_OPTS "$SSH_TARGET" "set -euo pipefail
  if [ ! -f '$REMOTE_EDGE_ENV' ]; then
    cat >'$REMOTE_EDGE_ENV' <<'EOF'
BIDMART_PROD_HOST=bidmart-prod.43.157.208.68.sslip.io
BIDMART_STAGING_HOST=bidmart-staging.43.157.208.68.sslip.io
PUBLIC_HTTP_PORT=80
PUBLIC_HTTPS_PORT=443
EOF
  fi
  cp '$REMOTE_EDGE_ENV' '$REMOTE_EDGE_DIR/.env'
  cd '$REMOTE_EDGE_DIR'
  DOCKER=docker
  if ! docker info >/dev/null 2>&1; then
    DOCKER=\"sudo docker\"
  fi
  \$DOCKER compose -f caddy-compose.yml --env-file .env up -d
"

echo "VPS edge deployed."
