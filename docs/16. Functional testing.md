# Functional Smoke Testing

Use the functional smoke suite after the Docker stack is running to avoid repeating the full manual demo path.

## What It Covers

The full scope exercises the system through the gateway:

- public catalogue search
- bidding route reachability (`/api/v1/listings`)
- frontend shell reachability
- seller and buyer login
- buyer wallet creation/read and sandbox top-up
- seller listing create (with auction timing fields) and publish
- listing auction session open (`POST /api/v1/listings`, id = `listingId`)
- buyer bid placement with wallet hold (`POST /api/v1/listings/{id}/bids`)
- catalogue price sync after bid (async MQ → catalogue `currentPrice`)
- listing settlement after end time (`POST /api/v1/listings/{id}/close`)
- buyer notification delivery (optional)

### Win-path verification (order + wallet + payout)

After auction close with a winning bidder, validate the financial path manually or extend smoke with:

1. **Order auto-create** — `GET /api/v1/orders` as buyer shows order for `listingId` / `auctionId` with status `CREATED`.
2. **Seller shipping** — `PUT /api/v1/orders/{id}/status` → `PACKED` then `SHIPPED` (seller token).
3. **Buyer confirm** — `POST /api/v1/orders/{id}/confirm` → status `CONFIRMED`.
4. **Wallet** — buyer: winning hold converted (`CONVERT` in history); seller: escrow/held proceeds visible before payout.
5. **Payout** — after `bidmart.order.payout-delay-minutes` (default 5), seller active balance increases; order `payoutReleasedAt` set.

Quick checks:

```bash
# Buyer orders after close
curl -s -H "Authorization: Bearer $BIDMART_BUYER_TOKEN" \
  "$BIDMART_GATEWAY_URL/api/v1/orders" | jq .

# Seller wallet detail (held → active after payout scheduler)
curl -s -H "Authorization: Bearer $BIDMART_SELLER_TOKEN" \
  "$BIDMART_GATEWAY_URL/api/v1/wallet/$BIDMART_SELLER_USER_ID/detail" | jq .
```

Playwright win path: `bidmart-frontend/e2e/auction-win.spec.ts` (bid → close → order → ship → confirm), plus `wallet.spec.ts` and `auction-war.spec.ts` (run headed locally; CI nightly job uploads HTML report).

The suite is API-level, not browser UI automation.

## Listing-as-auction flow under test

```text
Catalogue:  POST /catalogue/listings → POST .../publish (ACTIVE)
Bidding:    POST /listings { listingId }  (listing auction session id = listing id)
Buyer:      POST /listings/{id}/bids
Catalogue:  GET /catalogue/listings/{id}  (currentPrice, hasBids)
Seller:     POST /listings/{id}/close      (settlement)
```

## Run

From `bidmart-infrastructure`:

```bash
node scripts/functional-smoke.mjs
```

Or through Gradle:

```bash
./gradlew functionalSmoke
```

Public-only readiness (no accounts):

```bash
BIDMART_SMOKE_SCOPE=public node scripts/functional-smoke.mjs
```

## Required full-scope environment

```bash
export BIDMART_SELLER_EMAIL=seller@example.com
export BIDMART_SELLER_PASSWORD='password'
export BIDMART_BUYER_EMAIL=buyer@example.com
export BIDMART_BUYER_PASSWORD='password'
```

Optional 2FA:

```bash
export BIDMART_SELLER_2FA_CODE=123456
export BIDMART_BUYER_2FA_CODE=123456
```

Or supply tokens:

```bash
export BIDMART_SELLER_TOKEN='...'
export BIDMART_SELLER_USER_ID='...'
export BIDMART_BUYER_TOKEN='...'
export BIDMART_BUYER_USER_ID='...'
```

## Optional configuration

```bash
export BIDMART_GATEWAY_URL=http://localhost:8000
export BIDMART_FRONTEND_URL=http://localhost
export BIDMART_SMOKE_SCOPE=full
export BIDMART_AUCTION_LIFETIME_SECONDS=5
export BIDMART_SMOKE_TOP_UP_CENTS=100000
export BIDMART_SKIP_FRONTEND_CHECK=1
export BIDMART_SKIP_NOTIFICATION_CHECK=1
```

If the browser uses the frontend proxy at `http://localhost`, set `BIDMART_GATEWAY_URL=http://localhost`.

## Expected stack state

```bash
docker compose up -d --build
```

Requires RabbitMQ, PostgreSQL, gateway, wallet, catalogue, auction (Rust), order/notification, auth, and frontend services healthy enough to receive traffic.

Run catalogue migration **V10** (`minimum_increment`, `start_time`) before full-scope smoke if the catalogue DB was created before the listing-as-auction refactor.
