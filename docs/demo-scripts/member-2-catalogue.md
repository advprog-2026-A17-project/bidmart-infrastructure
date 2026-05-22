# Demo Script — Member 2 (Catalogue) — 15 min

**Role:** Listing lifecycle, search, seller ownership.

## Setup (2 min)

- Seller account logged in; gateway forwards `X-User-Id`

## Demo flow (10 min)

1. **Create listing** — POST `/api/v1/catalogue/listings`; note server overwrites `sellerId` from header.
2. **Publish** — transition to active; Rabbit event to auction if configured.
3. **Public search** — GET search without JWT; show open catalogue read.
4. **Filter by endTime** — demonstrate catalogue query filter (Phase 1).
5. **Wrong seller** — PUT with another seller's token → 403.

## Talking points (3 min)

- AuthInterceptor + `CatalogAccessPolicy` for admin routes
- gRPC read path for auction service (optional mention)

## Backup

- `ListingControllerTest` coverage report
