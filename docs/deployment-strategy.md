# Deployment Strategy

BidMart uses a staging-first progressive promotion strategy.

## Strategy

The selected strategy is progressive promotion through two stable environments:

- `staging` is the release candidate environment.
- `main` is the production environment.
- Production deployment is allowed only after the same change has passed CI and been validated in staging.

This fits the project better than canary or full blue-green because the backend runs on a single VPS with Docker Compose, while frontend and selected backend services are deployed by managed platforms.

## Platform Mapping

| Surface | Platform | Staging trigger | Production trigger |
| --- | --- | --- | --- |
| Gateway | VPS Docker Compose | CI success on `staging` | CI success on `main` + GitHub production environment |
| Catalogue | VPS Docker Compose | service CI success dispatches infrastructure deploy | service CI success dispatches infrastructure deploy |
| Auction | VPS Docker Compose | service CI success dispatches infrastructure deploy | service CI success dispatches infrastructure deploy |
| Wallet | VPS Docker Compose | service CI success dispatches infrastructure deploy | service CI success dispatches infrastructure deploy |
| Auth | Heroku container | `deploy-heroku.yml` after CI on `staging` | `deploy-heroku.yml` after CI on `main` |
| Order/Notification | Heroku container | `deploy-heroku.yml` after CI on `staging` | `deploy-heroku.yml` after CI on `main` |
| Frontend | Vercel | `deploy-vercel.yml` after CI on `staging` (preview) | `deploy-vercel.yml` after CI on `main` (`--prod`) |

## Gates

VPS deployment workflows are triggered by `workflow_run` after Continuous Integration succeeds. Direct push no longer starts a VPS deployment before CI finishes.

Production uses the GitHub `production` environment so repository maintainers can add reviewer approval in GitHub settings without changing the workflow file.

## Health Checks

After deploying the VPS stack, the workflow runs smoke tests:

- internal gateway health check
- production public edge health check
- gRPC privacy verification
- monitoring smoke test on staging

## Rollback

Rollback is branch-based:

1. Revert the problematic commit on `main`.
2. Let CI pass.
3. The production deploy workflow redeploys the previous known-good state.

For urgent VPS recovery, the deploy script also performs automatic rollback when validation fails. Before each VPS deploy, it snapshots the currently running Docker images into `bidmart-rollback-<env>-<service>:<timestamp>` tags. If compose up, service readiness, gRPC privacy verification, or staging monitoring verification fails, the script recreates the stack from those previous image tags and writes evidence to `deploy/logs/<env>-<timestamp>.log`.

Managed-platform rollback remains platform-specific:

- Heroku services use Heroku release rollback.
- Vercel frontend uses Vercel instant rollback.
