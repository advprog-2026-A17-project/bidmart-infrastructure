# BidMart Spec Test Traceability

This matrix is the deployment evidence map for the requested BidMart regression flows. The canonical API runner is `scripts/spec-regression.mjs`; service-level gates cover narrow implementation details that are cheaper and safer below the gateway; Playwright remains for browser-critical evidence only.

Synthetic production rule: all production rows use only entities prefixed with `bidmart-e2e-{env}-{runId}` and the runner refuses `prod` unless `BIDMART_E2E_ALLOW_PROD_SYNTHETIC=1`.

## Evidence Commands

| Evidence | Command |
|---|---|
| Canonical API regression | `cd bidmart-infrastructure && BIDMART_GATEWAY_URL=$URL BIDMART_FRONTEND_URL=$FRONTEND BIDMART_E2E_ADMIN_EMAIL=$ADMIN BIDMART_E2E_ADMIN_PASSWORD=$PASSWORD BIDMART_E2E_RUN_ID=$RUN_ID node scripts/spec-regression.mjs --scope full` |
| Production synthetic guard | Same as above plus `BIDMART_E2E_ENV=prod BIDMART_E2E_ALLOW_PROD_SYNTHETIC=1` |
| Public smoke only | `cd bidmart-infrastructure && node scripts/spec-regression.mjs --scope public` |
| Auth service | `cd bidmart-auth-service && ./gradlew clean check integrationTest` |
| Catalogue service | `cd bidmart-catalogue-service && ./gradlew clean check` |
| Auction service | `cd bidmart-auction-service-rust && cargo test --locked` |
| Wallet service | `cd bidmart-wallet-service-rust && cargo test --locked` |
| Order/notification service | `cd bidmart-order-and-notification-service && ./gradlew clean check` |
| Browser flows | `cd bidmart-frontend && npm run lint && npm test && npm run build && npm run test:e2e` |

## Flow Matrix

| Requested flow group | Requested bullets covered | Primary test ids | Module owner | Environment scope | Evidence |
|---|---|---|---|---|---|
| 1. Registration and email verification | Valid registration, duplicate email, invalid email, blank/weak password, boundary length validation, verification, replay-safe synthetic verification, expired-token behaviour, unverified login denial | `REG-001`, `REG-SELLER-001`, `REG-BUYER-001`, auth unit/integration tests | Auth | local, staging, prod-synthetic, service | API regression, auth service gate |
| 2. Login and session management | Correct login, wrong password, disabled account, nonexistent email without enumeration, brute-force/rate limit, expired access token, refresh, revoked refresh, multi-session listing, session revocation | `AUTH-001`, `SEC-001`, auth unit/integration tests | Auth | local, staging, prod-synthetic, service | API regression, auth service gate |
| 3. Two-factor authentication | TOTP setup, correct TOTP, wrong TOTP, expired/out-of-window TOTP via service tests, partial challenge session, disable 2FA, method switch/no stale method by auth tests, mid-flow deactivation via session invalidation | `2FA-001`, auth integration tests | Auth | local, staging, prod-synthetic, service | API regression, auth service gate |
| 4. Role and permission management | Custom role creation, unknown permission rejection, role assignment, permission gain/revocation without redeploy, admin bid permission policy, user deactivation/reactivation, buyer blocked from admin endpoint | `RBAC-001`, `AUTH-001`, auth service RBAC tests | Auth, Gateway | local, staging, prod-synthetic, service | API regression, auth service gate |
| 5. Seller create/manage listing | Valid draft listing, publish/active transition, required-field validation, reserve/start rule, image URL contract for unsupported uploads, draft edits, active edit denial, cancellation rules, category hierarchy, own-listing status filters, seller bid notification | `SELLER-001`, `AUCTION-SETUP-001`, `LIFECYCLE-001`, catalogue tests, Playwright seller flow | Catalogue, Auction, Notification | local, staging, prod-synthetic, browser, service | API regression, catalogue service gate, Playwright |
| 6. Catalogue browsing | Pagination, top-level category, leaf category, price range, ending-soon sort, keyword search, safe special-character search, empty search, active detail current price, closed detail read-only, cancelled status visibility | `API-001`, `BID-001`, `SEC-001`, catalogue search/filter tests, Playwright catalogue flow | Catalogue, Frontend | local, staging, prod-synthetic, browser, service | API regression, catalogue service gate, Playwright |
| 7. Wallet top-up and withdrawal | Valid top-up, invalid amount, available/held split, withdrawal <= balance, insufficient withdrawal, exact balance, held-balance exclusion, transaction history, concurrent top-up/withdrawal, withdrawal double-submit idempotency | `WALLET-001`, wallet API/concurrency tests | Wallet | local, staging, prod-synthetic, service | API regression, wallet service gate |
| 8. Placing a bid happy path | Active auction bid, minimum increment, wallet hold, catalogue highest price update, outbid release/new hold, proxy-bid flow, auto-bid outbid notification | `BID-001`, `LIFECYCLE-001`, auction service tests | Auction, Wallet, Catalogue, Notification | local, staging, prod-synthetic, service | API regression, auction and wallet gates |
| 9. Placing a bid edge cases | Equal bid, below start, zero/negative amount, insufficient wallet, draft bid, closed/won/unsold bid, self-bid forbidden, after-end bid, simultaneous identical bid winner consistency | `BID-EDGE-001`, `BID-RACE-001`, auction service tests | Auction, Wallet | local, staging, prod-synthetic, service | API regression, auction and wallet gates |
| 10. Anti-sniping | Last-two-minute extension, bid-time based extension, repeated extension, outside-window no extension, no-cap repeated extension, realtime updated end time | `ANTI-SNIPE-001`, auction state tests, Playwright realtime flow | Auction, Catalogue, Frontend | local, staging, prod-synthetic, browser, service | API regression, auction gate, Playwright |
| 11. Auction lifecycle | Draft start, draft to active, invalid backwards transition, active remains active on bid, active to extended, extended re-extension, reserve win, reserve unsold, zero-bid unsold, expired extended bid rejection | `AUCTION-SETUP-001`, `BID-001`, `ANTI-SNIPE-001`, `LIFECYCLE-001`, auction domain tests | Auction | local, staging, prod-synthetic, service | API regression, auction gate |
| 12. Winner determination and settlement | Winner hold converted to payment, losing holds released, deactivated winner handling, seller sale notification/order, unsold hold release, concurrent top-up/release safety | `LIFECYCLE-001`, `WALLET-001`, wallet settlement tests | Auction, Wallet, Order, Notification | local, staging, prod-synthetic, service | API regression, auction and wallet gates |
| 13. Order and fulfillment | Auto-create order on win, seller packed/shipped tracking, buyer detail/tracking, buyer receipt completion, buyer dispute, admin buyer-favour refund, admin seller-favour retain, seller timeout/dispute deadline | `LIFECYCLE-001`, order service tests, Playwright order flow | Order, Notification, Wallet | local, staging, prod-synthetic, browser, service | API regression, order service gate, Playwright |
| 14. Notifications | Outbid, winning auction, seller bid placed, wallet-balance notification, email preference off, push preference on, WebSocket/STOMP live updates, offline inbox recovery | `LIFECYCLE-001`, notification service tests, Playwright live-update flow | Notification, Frontend | local, staging, prod-synthetic, browser, service | API regression, order/notification gate, Playwright |
| 15. Admin moderation and oversight | User list filters, user search, deactivate highest bidder, deactivate active seller, all active auctions, moderate active listing with hold release/notifications, transaction logs, admin endpoint access, admin bid policy | `RBAC-001`, `AUTH-001`, `LIFECYCLE-001`, auth/order/admin tests | Auth, Auction, Wallet, Catalogue | local, staging, prod-synthetic, service | API regression, service gates |
| 16. Security and auth edge cases | Foreign/tampered JWT, malformed bearer, missing token, buyer to admin endpoint, CORS rejection, XSS listing/search, overflow bid, path traversal filename/image URL | `SEC-001`, `API-CONTRACT-001`, service security tests | Gateway, Auth, Catalogue, Auction | local, staging, prod-synthetic, service | API regression, auth/catalogue/auction gates |
| 17. Concurrency and race conditions | 100 concurrent bids, bid/close race, withdrawal/hold race, simultaneous outbid cycles, proxy-bid max resolution, listing/category deletion race | `BID-RACE-001`, wallet concurrency tests, auction repository tests, catalogue tests | Auction, Wallet, Catalogue | local, staging, prod-synthetic, service | API regression, auction/wallet/catalogue gates |
| 18. API contract and integration checks | HTTP statuses, structured errors, catalogue price eventual consistency, auction wallet validation, `BidPlaced`, `WinnerDetermined`, `UserDeactivated`, wallet-down degradation, queue-down retry behaviour | `API-CONTRACT-001`, `BID-001`, `LIFECYCLE-001`, service integration tests | Gateway, Auction, Wallet, Catalogue, Auth, Order | local, staging, prod-synthetic, service | API regression, all service gates |

## Design Pattern Traceability

| Pattern | BidMart context | Evidence |
|---|---|---|
| State | Auction lifecycle delegates bid eligibility and extension behaviour through explicit state handlers while preserving the persisted enum. | `cd bidmart-auction-service-rust && cargo test --locked domain_model_tests` |
| Strategy + Factory | Auction strategy selection is backed by a registry; MBG-style stub registration proves a new type can be added without changing core orchestration. | `cd bidmart-auction-service-rust && cargo test --locked registry_can_add_mbg_strategy_without_changing_core_resolver` |
| Observer | Transactional outbox remains the inter-module event backbone for bid, winner, deactivation, and order/notification consumers. | API regression `BID-001`, `LIFECYCLE-001`; service outbox tests |
| Chain of Responsibility | Auth/gateway checks are traceable as token, session/2FA, and permission stages; endpoint evidence verifies each failure mode. | API regression `AUTH-001`, `2FA-001`, `RBAC-001`, `SEC-001`; auth service gate |
| Command | Wallet top-up/withdrawal and bid placement carry operation ids/idempotency metadata and append auditable transaction history. | API regression `WALLET-001`; wallet service gate |
| Proxy | Auction wallet calls are wrapped with a timeout/degradation proxy so wallet outage returns controlled service-unavailable behaviour. | Auction service gate and integration degradation tests |

## Deployment Gate Mapping

| Deployment | Required checks |
|---|---|
| Local | `docker compose up -d --build`, `node scripts/spec-regression.mjs --scope full`, service gates, frontend gate |
| Staging | deploy validation, gRPC privacy, monitoring smoke, `node scripts/spec-regression.mjs --scope full` with `BIDMART_E2E_ENV=staging` |
| Production | deploy validation, gRPC privacy, edge health, `node scripts/spec-regression.mjs --scope full` with `BIDMART_E2E_ENV=prod BIDMART_E2E_ALLOW_PROD_SYNTHETIC=1`; failure triggers VPS rollback snapshot restoration |
