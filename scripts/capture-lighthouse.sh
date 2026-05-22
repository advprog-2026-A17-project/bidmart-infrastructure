#!/usr/bin/env bash
# Capture full Lighthouse JSON/HTML for rubrik before/after evidence.
set -euo pipefail

TARGET_URL="${1:-http://localhost:5173/}"
OUT_DIR="${2:-../bidmart-frontend/artifacts}"
LABEL="${3:-lighthouse}"

mkdir -p "$OUT_DIR"
npx --yes lighthouse "$TARGET_URL" \
  --chrome-flags="--headless --no-sandbox" \
  --output=json,html \
  --output-path="${OUT_DIR}/${LABEL}" \
  --only-categories=performance,accessibility,best-practices,seo

echo "Saved ${OUT_DIR}/${LABEL}.report.json and ${OUT_DIR}/${LABEL}.report.html"
