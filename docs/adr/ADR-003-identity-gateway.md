# ADR-003: Trusted Identity at API Gateway

**Status:** Accepted  
**Date:** 2026-05-21

## Context

Microservices must not trust client-supplied `sellerId`, `bidderId`, or `userId` in JSON bodies. Spoofed `X-User-Id` headers must be stripped on public routes.

## Decision

1. **Gateway JWT filter** verifies access tokens and sets `X-User-Id`, `X-User-Email`, `X-User-Roles`, `X-Internal-Service-Token`.
2. **Gateway body guard** on POST/PUT/PATCH to `/api/v1/catalogue/**`, `/api/v1/wallet/**`, `/api/v1/listings/**` returns `409` when body identity fields conflict with `X-User-Id`.
3. Downstream controllers read identity from headers only (catalogue overwrites `sellerId`; auction/wallet reject mismatches).

Public GET catalogue/auction reads strip identity headers entirely.

## Consequences

- **Pros:** Single enforcement point + defense in depth in services; aligns with zero-trust edge pattern.
- **Cons:** Gateway must buffer mutation bodies; WebSocket STOMP uses separate JWT-on-CONNECT interceptor in order service.

## Evidence

- `GatewayJwtAuthenticationFilter`, `GatewayIdentityBodyGuardFilter`
- Tests: `GatewayIdentityEnforcementIntegrationTest`, `IdentityBodyConflictCheckerTest`
