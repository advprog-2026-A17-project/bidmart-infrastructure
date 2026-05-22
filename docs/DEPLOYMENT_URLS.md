# Deployment URLs (source of truth)

Update env templates and platform config when these change. **Do not commit database passwords.**

## Public API (browser / frontend)

| Environment | Gateway (all `/api/v1`, `/ws`) | Frontend (Vercel) |
|-------------|----------------------------------|-------------------|
| Local | `http://localhost:8000` | `http://localhost:5173` |
| Staging | `https://bidmart-staging.43.157.208.68.sslip.io:8443` | Preview deploy of branch `staging` (set `VITE_API_BASE_URL` to staging gateway) |
| Production | `https://bidmart-prod.43.157.208.68.sslip.io:8443` | `https://bidmart-frontend.vercel.app` |

## Heroku (auth + order only, container)

| Environment | Auth | Order |
|-------------|------|-------|
| Staging | `https://bidmart-auth-staging-392ed0eda7b8.herokuapp.com` | `https://bidmart-order-staging-a5fd01b90144.herokuapp.com` |
| Production | `https://bidmart-auth-876b62d83c69.herokuapp.com` | `https://bidmart-order-ced842eeb858.herokuapp.com` |

Gateway routes to these URLs (see `deploy/vps/env.*.example` → `GATEWAY_AUTH_*`, `GATEWAY_ORDER_*`).

## VPS Compose (catalogue, auction, wallet, gateway edge)

| Service | Staging | Production |
|---------|---------|------------|
| Gateway bind | `127.0.0.1:18000` | `127.0.0.1:28000` |
| Public host | `bidmart-staging.43.157.208.68.sslip.io` | `bidmart-prod.43.157.208.68.sslip.io` |

## Supabase Postgres (hosts only)

| Service | Project host |
|---------|----------------|
| Auth | `db.wogkzhlcliqtnbqjylkz.supabase.co` (Heroku config) |
| Order | `db.hcsrkmlqekuhouenbusg.supabase.co` (Heroku config) |
| Catalogue | `db.mekfvqtoeuwjiafltier.supabase.co` (VPS `.env`) |
| Auction | `db.begunupzsnlftbsdgmxw.supabase.co` (VPS `.env`) |
| Wallet | `db.pctktjwzbmoiftvkersb.supabase.co` (VPS `.env`) |

Use **Session pooler** host from Supabase dashboard when connecting from Heroku (IPv4).

## Cross-service env (production-shaped)

```bash
# Order (Heroku)
BIDMART_AUTH_BASE_URL=https://bidmart-auth-staging-392ed0eda7b8.herokuapp.com   # or bidmart-auth for prod
WALLET_SERVICE_URL=https://bidmart-staging.43.157.208.68.sslip.io/api/v1/wallet  # via gateway

# Auth (Heroku)
AUTH_EMAIL_VERIFICATION_BASE_URL=https://bidmart-frontend.vercel.app/verify-email
AUTH_PASSWORD_RESET_BASE_URL=https://bidmart-frontend.vercel.app/reset-password

# Wallet (VPS)
FRONTEND_BASE_URL=https://bidmart-frontend.vercel.app
```

## Metrics (Grafana Cloud)

| Target | URL |
|--------|-----|
| Gateway staging | `https://bidmart-staging.43.157.208.68.sslip.io/actuator/prometheus` |
| Gateway prod | `https://bidmart-prod.43.157.208.68.sslip.io/actuator/prometheus` |
| Auth / order | Heroku app URLs + `/actuator/prometheus` |

See [monitoring/grafana-cloud/SCRAPE_TARGETS.md](../monitoring/grafana-cloud/SCRAPE_TARGETS.md).

## VPS continuous deployment (GitHub Actions)

| Environment | Trigger | Workflow | Git branch on VPS |
|-------------|---------|----------|-------------------|
| **Staging** | Push to `staging` on `bidmart-infrastructure` (paths: `deploy/**`, `src/**`, …) | `deploy-staging-vps.yml` | `staging` |
| **Staging** | Push to `staging` on catalogue / auction / wallet | `trigger-vps-deploy.yml` → `deploy-vps-staging` | branch that was pushed |
| **Production** | Push to `main` on `bidmart-infrastructure` | `deploy-prod-vps.yml` | `main` |
| **Production** | Push to `main` on catalogue / auction / wallet | `trigger-vps-deploy.yml` → `deploy-vps-prod` | `main` |
| **Production** | Manual | Actions → **Deploy production VPS** → branch `main` (default) | input branch |

**Secrets** (repo `bidmart-infrastructure`): `BIDMART_SSH_PRIVATE_KEY`, optional `BIDMART_SSH_HOST` / `BIDMART_SSH_USER` / `BIDMART_SSH_PORT` (default `43.157.208.68`, `ubuntu`, `443`).

**Secrets** (catalogue, auction, wallet): `VPS_DEPLOY_DISPATCH_TOKEN` (PAT with `repo` + `workflow` on infrastructure).

Prod job uses GitHub Environment `production` (optional approval gate). Merge `staging` → `main` when promoting a release so prod CD runs on `main`.
