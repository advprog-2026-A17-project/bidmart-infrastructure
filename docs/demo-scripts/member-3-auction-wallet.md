# Demo Script — Member 3 (Auction + Wallet) — 15 min

**Role:** Bidding, holds, close, escrow.

## Setup (2 min)

- Buyer with wallet balance; active listing + auction session

## Demo flow (10 min)

1. **Create auction** from listing — English type; show `CloseStrategy` / `EnglishReserveClose` in README.
2. **Place bid** — wallet hold; show balance held in wallet detail API.
3. **Outbid** — second bidder; outbox `Outbid` event → notification.
4. **Proxy/max bid** — cursor bid endpoint; mention hold compensation on failure.
5. **Close auction** — WON vs UNSOLD; convert + seller escrow on WON.

## Talking points (3 min)

- O(1) bid path profiling (`12. Before after perf.md`)
- `cargo test` + llvm-cov in CI

## Backup

- Run `cargo test profiling -- --nocapture` snippet
