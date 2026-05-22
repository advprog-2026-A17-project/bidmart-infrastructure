# CD secrets checklist

Configure these in GitHub repository **Settings → Secrets and variables → Actions** (and Environments `staging` / `production` where noted).

## Heroku (`bidmart-auth-service`, `bidmart-order-and-notification-service`)

| Secret | Description |
| --- | --- |
| `HEROKU_API_KEY` | Heroku account API key (`heroku auth:token`) |
| `HEROKU_APP_NAME_STAGING` | Staging app name (defaults in workflow if unset) |
| `HEROKU_APP_NAME_PROD` | Production app name (defaults in workflow if unset) |

Workflow: `.github/workflows/deploy-heroku.yml` — `heroku container:push` + `container:release` after CI succeeds.

## Vercel (`bidmart-frontend`)

| Secret | Description |
| --- | --- |
| `VERCEL_TOKEN` | Vercel personal/team token |
| `VERCEL_ORG_ID` | Team/org ID from `.vercel/project.json` |
| `VERCEL_PROJECT_ID` | Project ID from `.vercel/project.json` |

Workflow: `.github/workflows/deploy-vercel.yml` — `vercel pull` → `vercel build` → `vercel deploy --prebuilt`.

## VPS (`bidmart-infrastructure` + service dispatch)

| Secret | Repository |
| --- | --- |
| `BIDMART_SSH_PRIVATE_KEY`, `BIDMART_SSH_HOST`, `BIDMART_SSH_USER`, `BIDMART_SSH_PORT` | `bidmart-infrastructure` |
| `VPS_DEPLOY_DISPATCH_TOKEN` | `bidmart-catalogue-service`, `bidmart-auction-service-rust`, `bidmart-wallet-service-rust` |

Service repos trigger `deploy-vps-staging` / `deploy-vps-prod` on `bidmart-infrastructure` via `trigger-vps-deploy.yml`.
