# ADR-001: Transactional Outbox for Domain Events

**Status:** Accepted  
**Date:** 2026-05-21

## Context

BidMart services must publish integration events (auth audit, wallet provisioning, auction ended, outbid) without losing messages when the HTTP request commits but the broker is down.

## Decision

Use a **transactional outbox** table in the same database as the aggregate mutation. A scheduled publisher reads `PENDING` rows, publishes to RabbitMQ, and marks `PUBLISHED` or schedules retry with backoff.

## Consequences

- **Pros:** At-least-once delivery aligned with DB commits; replayable; observable via outbox status columns.
- **Cons:** Eventual consistency latency (seconds); requires idempotent consumers and dedup keys downstream.

## Implementations

- Auth: `auth_outbox_events` + `AuthAuditOutboxPublisher` / `WalletProvisioningOutboxPublisher`
- Auction (Rust): `auction_outbox` + `rabbitmq_outbox_publisher` scheduler
- Order: consumes Rabbit with in-memory dedup + Flyway-backed order state
