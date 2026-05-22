# ADR-002: Auction Type Strategies (Create + Close)

**Status:** Accepted (English only enabled)  
**Date:** 2026-05-21

## Context

The product spec defines ENGLISH, SCHOLARSHIP, MULTI_SLOT_REGIONAL, and ENTERPRISE auction modes with different validation and close rules.

## Decision

Split concerns into two strategy traits:

1. **`AuctionStrategy`** — validates create payload per `AuctionType` (see `auction_strategy.rs`).
2. **`CloseStrategy`** — resolves WON/UNSOLD (or future multi-winner) at close (`close_strategy.rs`, default `EnglishReserveClose`).

Factory functions `resolve_strategy()` and `default_close_strategy()` select implementations by type. Non-English types return controlled errors until product enables them.

## Consequences

- **Pros:** Open/closed for extension without rewriting `AuctionService` orchestration.
- **Cons:** Two trait surfaces to keep in sync when adding a type; tests needed per strategy pair.

## Next steps

Implement `ScholarshipCloseStrategy` and `EnterpriseCloseStrategy` when SCHOLARSHIP/ENTERPRISE routes go live.
