# Demo Script — Member 5 (Frontend + Ops) — 15 min

**Role:** E2E UX, observability, rubric evidence.

## Setup (2 min)

- Frontend `npm run dev` or compose nginx; Playwright report optional

## Demo flow (10 min)

1. **E2E auction win** — Playwright `auction-win.spec.ts` or manual buyer journey.
2. **Admin auth page** — disputes tab, user list (Phase 1).
3. **Lighthouse** — show after report; explain before stub + capture instructions.
4. **Grafana** — open overview dashboard; all Prometheus targets up.
5. **Smoke + CI** — `node bidmart-infrastructure/scripts/functional-smoke.mjs`; mention GitHub `functional-smoke` job on main.

## Talking points (3 min)

- [11. Rubrik.md](../11. Rubrik.md) — indeks bukti modul 6 & 8
- Rollback drill table in `14. Deployment rollback.md`

## Backup

- Screenshots from `docs/evidence/` placeholders
