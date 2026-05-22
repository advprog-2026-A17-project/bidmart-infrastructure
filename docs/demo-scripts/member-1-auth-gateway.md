# Demo Script — Member 1 (Auth + Gateway) — 15 min

**Role:** Authentication, admin audit, API gateway identity.

## Setup (2 min)

- Stack: `docker compose up` in `bidmart-infrastructure`
- Open Grafana + gateway health: `http://localhost:8000/actuator/health`

## Demo flow (10 min)

1. **Register seller** via UI or `POST /api/v1/auth/register` — show email verification optional path.
2. **Login** — highlight JWT access token; show gateway adds `X-User-Id` on protected routes (DevTools → Network → catalogue POST).
3. **Admin audit API** — `GET /api/v1/auth/admin/audit-events?page=0&size=10` with admin token; show `UserDisabled` / `RoleCreated` outbox rows.
4. **Identity guard** — attempt POST listing with body `sellerId` ≠ token subject → `409 Conflict` at gateway.
5. **Disable user** — admin disable; mention `AuthEventConsumer` in order service revokes sessions.

## Talking points (3 min)

- Transactional outbox for audit events (ADR-001)
- Trusted identity at edge (ADR-003)
- JaCoCo gate 85%+ on auth module

## Backup if live fails

- Show `AuthControllerTest` + `GatewayIdentityEnforcementIntegrationTest` green in CI artifacts
