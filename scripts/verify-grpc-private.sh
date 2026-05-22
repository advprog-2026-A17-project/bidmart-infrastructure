#!/usr/bin/env bash
# Verify that production/staging Compose uses private gRPC for auction dependencies.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-${ROOT_DIR}/docker-compose.yml}"
COMPOSE_FILES=(-f "$COMPOSE_FILE")
COMPOSE_ENV_ARGS=()
if [[ -f "${ROOT_DIR}/.env" ]]; then
  COMPOSE_ENV_ARGS=(--env-file "${ROOT_DIR}/.env")
fi

failures=0

ok() { printf '  OK   %s\n' "$1"; }
fail() { printf '  FAIL %s\n' "$1"; failures=$((failures + 1)); }

compose() {
  docker compose "${COMPOSE_FILES[@]}" "${COMPOSE_ENV_ARGS[@]}" "$@"
}

assert_not_published() {
  local service="$1"
  local port="$2"
  local published
  published="$(compose port "$service" "$port" 2>/dev/null || true)"
  if [[ -z "$published" ]]; then
    ok "${service}:${port} is not published to the host"
  else
    fail "${service}:${port} is publicly published as ${published}"
  fi
}

assert_env() {
  local name="$1"
  local expected="$2"
  local cid
  cid="$(compose ps -q auction-service)"
  if [[ -z "$cid" ]]; then
    fail "auction-service container is not running"
    return
  fi
  if docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$cid" | grep -qx "${name}=${expected}"; then
    ok "auction-service ${name}=${expected}"
  else
    fail "auction-service ${name} is not ${expected}"
  fi
}

grpc_list() {
  local label="$1"
  local proto_dir="$2"
  local proto_file="$3"
  local target="$4"
  local service="$5"
  local cid network
  cid="$(compose ps -q auction-service)"
  if [[ -z "$cid" ]]; then
    fail "auction-service container is not running"
    return
  fi
  network="$(docker inspect -f '{{range $name, $_ := .NetworkSettings.Networks}}{{println $name}}{{end}}' "$cid" | head -n 1)"
  if [[ -z "$network" ]]; then
    fail "could not discover Compose network from auction-service"
    return
  fi
  if docker run --rm \
    --network "$network" \
    -v "${proto_dir}:/protos:ro" \
    fullstorydev/grpcurl:latest \
    -plaintext -proto "/protos/${proto_file}" "$target" list "$service" >/dev/null; then
    ok "${label} gRPC reachable at ${target}"
  else
    fail "${label} gRPC unreachable at ${target}"
  fi
}

echo "== BidMart private gRPC verification =="

assert_not_published catalogue-service 9091
assert_not_published wallet-service 50051
assert_env CATALOGUE_GRPC_URL http://catalogue-service:9091
assert_env WALLET_GRPC_URL http://wallet-service:50051

grpc_list \
  "Catalogue" \
  "${ROOT_DIR}/../bidmart-catalogue-service/src/main/proto" \
  "catalogue.proto" \
  "catalogue-service:9091" \
  "catalogue.v1.CatalogueService"

grpc_list \
  "Wallet" \
  "${ROOT_DIR}/../bidmart-wallet-service-rust/proto" \
  "wallet.proto" \
  "wallet-service:50051" \
  "wallet.v1.WalletService"

if [[ "$failures" -gt 0 ]]; then
  echo "${failures} gRPC check(s) failed."
  exit 1
fi

echo "Private gRPC verification passed."
