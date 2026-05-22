#!/usr/bin/env bash
# Deploy the shared VPS Caddy edge (GitHub Actions CD only).
set -euo pipefail

if [[ -z "${GITHUB_ACTIONS:-}" && -z "${ALLOW_LOCAL_VPS_DEPLOY:-}" ]]; then
  echo "Edge deploy is CI/CD only. Use GitHub Actions deploy-staging-vps / deploy-prod-vps workflows." >&2
  exit 2
fi

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
  if ! test -f '$REMOTE_EDGE_ENV' && ! sudo test -f '$REMOTE_EDGE_ENV' 2>/dev/null; then
    tmp_env=\$(mktemp)
    cat >\"\$tmp_env\" <<'EOF'
BIDMART_PROD_HOST=bidmart-prod.43.157.208.68.sslip.io
BIDMART_STAGING_HOST=bidmart-staging.43.157.208.68.sslip.io
PUBLIC_HTTP_PORT=80
PUBLIC_HTTPS_PORT=443
EOF
    sudo mkdir -p \"\$(dirname '$REMOTE_EDGE_ENV')\"
    sudo mv \"\$tmp_env\" '$REMOTE_EDGE_ENV'
    sudo chown root:root '$REMOTE_EDGE_ENV'
    sudo chmod 600 '$REMOTE_EDGE_ENV'
  fi
  if [ -r '$REMOTE_EDGE_ENV' ]; then
    cp '$REMOTE_EDGE_ENV' '$REMOTE_EDGE_DIR/.env'
  else
    sudo cp '$REMOTE_EDGE_ENV' '$REMOTE_EDGE_DIR/.env'
    sudo chown \"\$(id -u):\$(id -g)\" '$REMOTE_EDGE_DIR/.env'
  fi
  cd '$REMOTE_EDGE_DIR'
  DOCKER=docker
  if ! docker info >/dev/null 2>&1; then
    DOCKER=\"sudo docker\"
  fi
  \$DOCKER compose -f caddy-compose.yml --env-file .env up -d
"

echo "VPS edge deployed."
