# Functional Smoke Testing

Use the functional smoke suite after the Docker stack is running to avoid repeating the full manual demo path.

## What It Covers

The full scope exercises the system through the gateway:

- public catalogue search
- public auction list
- frontend shell reachability
- seller login
- buyer login
- buyer wallet creation/read
- Midtrans sandbox top-up intent creation
- deterministic payment settlement through the wallet simulation endpoint
- seller listing create and publish
- English auction create
- catalogue auction-created lifecycle transition
- buyer bid placement with wallet hold
- auction close after the configured end time
- buyer notification delivery

The suite is API-level, not browser UI automation. The frontend still uses the Midtrans web sandbox redirect; the suite uses the backend simulation endpoint so the run is deterministic and does not require clicking inside Midtrans.

## Run

From `bidmart-infrastructure`:

```bash
node scripts/functional-smoke.mjs
```

Or through Gradle:

```bash
./gradlew functionalSmoke
```

For a public-only readiness check that does not require accounts:

```bash
BIDMART_SMOKE_SCOPE=public node scripts/functional-smoke.mjs
```

## Required Full-Scope Environment

Use verified demo accounts. Freshly registered accounts may fail login until email verification is complete.

```bash
export BIDMART_SELLER_EMAIL=seller@example.com
export BIDMART_SELLER_PASSWORD='password'
export BIDMART_BUYER_EMAIL=buyer@example.com
export BIDMART_BUYER_PASSWORD='password'
```

If an account has 2FA enabled, also set:

```bash
export BIDMART_SELLER_2FA_CODE=123456
export BIDMART_BUYER_2FA_CODE=123456
```

You can skip login and supply existing tokens instead:

```bash
export BIDMART_SELLER_TOKEN='...'
export BIDMART_SELLER_USER_ID='...'
export BIDMART_BUYER_TOKEN='...'
export BIDMART_BUYER_USER_ID='...'
```

## Optional Configuration

```bash
export BIDMART_GATEWAY_URL=http://localhost:8000
export BIDMART_FRONTEND_URL=http://localhost
export BIDMART_SMOKE_SCOPE=full
export BIDMART_AUCTION_LIFETIME_SECONDS=5
export BIDMART_SMOKE_TOP_UP_CENTS=100000
export BIDMART_SKIP_FRONTEND_CHECK=1
export BIDMART_SKIP_NOTIFICATION_CHECK=1
```

If the browser normally calls `http://localhost/api/...` through the frontend proxy, set:

```bash
export BIDMART_GATEWAY_URL=http://localhost
```

## Expected Stack State

Start the stack before running the suite:

```bash
docker compose up -d --build
```

The suite expects RabbitMQ, PostgreSQL databases, gateway routes, wallet, catalogue, auction, order/notification, auth, and frontend services to be healthy enough to receive traffic.
