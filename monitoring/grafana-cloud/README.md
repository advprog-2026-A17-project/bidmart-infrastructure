# Grafana Cloud — production observability

BidMart runs **Prometheus + Grafana locally** via `docker compose` (see [../prometheus/prometheus.yml](../prometheus/prometheus.yml)).  
For **Heroku production**, use **Grafana Cloud** (free tier) to scrape public metrics endpoints — no extra Heroku dyno for monitoring.

## Architecture

```
Heroku apps (HTTPS)          Grafana Cloud
  /actuator/prometheus  -->  Hosted Prometheus (scrape)
  /metrics (Rust)       -->  Grafana (dashboards)
```

Scrape **each service directly** (not through the gateway JWT). Job list mirrors local config: [prometheus-heroku.yml](../prometheus/prometheus-heroku.yml).

| Job | Metrics path |
|-----|----------------|
| `bidmart-gateway` | `/actuator/prometheus` |
| `bidmart-auth-service` | `/actuator/prometheus` |
| `bidmart-catalogue-service` | `/actuator/prometheus` |
| `bidmart-auction-service` | `/metrics` |
| `bidmart-wallet-service` | `/metrics` |
| `bidmart-order-service` | `/actuator/prometheus` |

## 1. Create Grafana Cloud stack

1. Sign up at [grafana.com](https://grafana.com/) → **Grafana Cloud Free**.
2. Create a stack (e.g. `bidmart-team`).
3. Open **My Account** → note:
   - Grafana URL (`https://<stack>.grafana.net`)
   - **Prometheus** remote write / scrape credentials (if using Alloy)
4. **Do not commit** API keys or passwords to git.

## 2. Verify metrics endpoints (before scrape)

From `bidmart-infrastructure`:

```bash
export HEROKU_GATEWAY_METRICS_URL=https://<gateway-app>.herokuapp.com
export HEROKU_AUTH_METRICS_URL=https://bidmart-authentication-service-5f40f293e67d.herokuapp.com
export HEROKU_CATALOGUE_METRICS_URL=https://<catalogue-app>.herokuapp.com
export HEROKU_AUCTION_METRICS_URL=https://bidmart-auction-service-c30c7668b800.herokuapp.com
export HEROKU_WALLET_METRICS_URL=https://<wallet-app>.herokuapp.com
export HEROKU_ORDER_METRICS_URL=https://<order-app>.herokuapp.com

USE_HEROKU_URLS=true ./scripts/verify-monitoring.sh
```

Each check should return HTTP 200 with Prometheus text format.

## 3. Configure scrape in Grafana Cloud (UI)

Use **Hosted Collector** (Custom setup → scrape public HTTPS endpoints).

**Prerequisite:** set the same HTTP Basic credentials on every Heroku app (required by Grafana Cloud; dummy credentials are rejected):

```bash
# Use one strong password; repeat on each app you deploy.
heroku config:set METRICS_BASIC_USER=grafana METRICS_BASIC_PASSWORD='your-strong-password' -a <app-name>
```

Apps: `bidmart-infrastructure`, `bidmart-authentication-service`, `bidmart-catalogue`, `bidmart-auction-service`, `bidmart-wallet-service-rust`, `bidmart-order-and-notification`.

After deploy, **Test Connection** in Grafana must succeed with that username/password.

1. Grafana Cloud → **Connections** → **Metrics Endpoint**.
2. For each service, create a scrape job:
   - **URL:** `https://<app>.herokuapp.com/actuator/prometheus` or `.../metrics` (Rust)
   - **Interval:** 30s (Grafana Cloud minimum on free tier)
   - **Auth:** Basic — same `METRICS_BASIC_USER` / `METRICS_BASIC_PASSWORD`
3. Job names: see [prometheus-heroku.yml](../prometheus/prometheus-heroku.yml).

### Optional: Grafana Alloy agent

If you run Alloy on a laptop/VM with outbound HTTPS:

1. Copy [alloy.config.river](./alloy.config.river).
2. Replace `REPLACE_WITH_*` hostnames with live Heroku apps.
3. Set env vars from Grafana Cloud **Send metrics** page:
   - `GRAFANA_CLOUD_PROMETHEUS_URL`
   - `GRAFANA_CLOUD_PROMETHEUS_USER`
   - `GRAFANA_CLOUD_API_KEY`
4. Run: `alloy run monitoring/grafana-cloud/alloy.config.river`

## 4. Import dashboards

Source JSON (already in repo):

- [../grafana/dashboards/bidmart-overview.json](../grafana/dashboards/bidmart-overview.json)
- Per-service: `bidmart-auth-service.json`, `bidmart-catalogue-service.json`, etc.

Steps:

1. Grafana Cloud → **Dashboards** → **New** → **Import**.
2. Upload JSON or paste content.
3. Select datasource: **Prometheus** (Grafana Cloud hosted).
4. Repeat for all seven dashboards.

Verify in **Explore**:

```promql
up{job=~"bidmart-.*"}
```

All series should be `1` while Heroku dynos are awake.

## 5. Deploy order (with observability)

1. Deploy Heroku backend apps (container stack + config vars).
2. Gateway last — set `GATEWAY_*_SERVICE_URL` to public Heroku URLs.
3. Run `USE_HEROKU_URLS=true ./scripts/verify-monitoring.sh`.
4. Enable Grafana Cloud scrape + import dashboards.
5. Point Vercel `VITE_API_BASE_URL` at gateway.

## Limitations

| Topic | Note |
|-------|------|
| **Heroku Eco sleep** | `up` may drop to `0` when dynos idle; wake apps before demos/screenshots. |
| **Free tier limits** | Retention and active series caps — sufficient for AdvProg demo. |
| **Metrics security** | Endpoints are public on each app URL; do not put secrets in metric labels. |
| **Local compose** | Unchanged — still use localhost:9090 / :3001 for dev. |

## Rubric evidence checklist

Capture after Grafana Cloud is wired:

| Evidence | Where to capture | Suggested filename |
|----------|------------------|-------------------|
| All targets up | Grafana **Explore** → `up{job=~"bidmart-.*"}` | `docs/evidence/grafana-cloud-targets-YYYY-MM-DD.png` |
| Overview dashboard | Dashboard **BidMart Overview** | `docs/evidence/grafana-cloud-overview-YYYY-MM-DD.png` |
| One service dashboard | e.g. Auth or Gateway | `docs/evidence/grafana-cloud-auth-YYYY-MM-DD.png` |
| Local parity (optional) | `http://localhost:9090/targets` | `docs/evidence/prometheus-targets-YYYY-MM-DD.png` |

See also [../../../SCREENSHOT_CAPTURE_GUIDE.md](../../../SCREENSHOT_CAPTURE_GUIDE.md) and [../../docs/20. Screenshot capture guide.md](../../docs/20.%20Screenshot%20capture%20guide.md).

## Related docs

- [../../docs/10. Monitoring.md](../../docs/10.%20Monitoring.md) — metrics catalog & PromQL
- [../../docs/15. Deployment.md](../../docs/15.%20Deployment.md) — Heroku app status
- [../../docs/4. Software Deployment.md](../../docs/4.%20Software%20Deployment.md) — gateway CI/CD
