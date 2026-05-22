# Demo Script — Member 4 (Order + Notification) — 15 min

**Role:** Orders, disputes, STOMP notifications, push.

## Setup (2 min)

- Completed auction WON; buyer logged in

## Demo flow (10 min)

1. **Order created** from `auction.ended.v1` consumer — show order detail page.
2. **Confirm order** — trigger payout scheduler delay explanation.
3. **Dispute flow** — buyer opens dispute; seller/admin resolve with wallet action.
4. **STOMP** — browser WS to `/ws/notifications`; CONNECT with Bearer; subscribe own `/topic/notifications/users/{id}`.
5. **Deep link** — click notification → order detail (stable notification id).

## Talking points (3 min)

- Dual-publish pattern (`NotificationService`)
- `11. Keamanan stomp.md` subscription isolation test

## Backup

- `OrderApiContractTest` dispute tests
