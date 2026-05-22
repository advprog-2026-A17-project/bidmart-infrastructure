# Grafana Cloud — BidMart production observability

BidMart's production stack is split across three platforms:

```
Vercel (frontend)         Heroku (auth + order)        VPS 43.157.208.68 (gateway + catalogue + auction + wallet)
        |                          |                                |
        +--------------------------+--------------------------------+
                                   |
                                   v
                       Grafana Alloy on the VPS
                                   |
                      remote_write + push (HTTPS)
                                   |
                                   v
              Grafana Cloud Hosted Prometheus + Hosted Loki
```

A single **Grafana Alloy** container runs on the VPS and does three jobs:

1. **Scrape metrics** from every Spring Boot / Rust app (Heroku public URLs,
   VPS public gateway URL, VPS internal services via Docker network).
2. **Tail Docker logs** for every container in `bidmart-staging_default` and
   `bidmart-prod_default` networks.
3. **Receive Heroku log drains** on port 8087 (optional; only when configured).

This setup is fully free-tier compatible (10k active series, 50 GB logs).

## Files

| Path | Purpose |
|------|---------|
| [`alloy.config.river`](./alloy.config.river) | Alloy pipeline (scrapes + log sources + remote writes) |
| [`alerts.yml`](./alerts.yml) | Mimir-managed alert rules (essential set) |
| [`SCRAPE_TARGETS.md`](./SCRAPE_TARGETS.md) | Authoritative list of jobs / URLs / labels |
| [`../../deploy/vps/docker-compose.monitoring.yml`](../../deploy/vps/docker-compose.monitoring.yml) | Alloy container, joins both compose networks |
| [`../../deploy/vps/grafana-cloud.env.example`](../../deploy/vps/grafana-cloud.env.example) | Template for `/etc/bidmart/grafana-cloud.env` |

## 1. Create the Grafana Cloud stack (one-time)

1. Sign up at <https://grafana.com/> → **Grafana Cloud Free**.
2. Click **+ Stack** → pick a region close to Singapore (e.g. *ap-southeast-1*).
3. Wait ~1 minute for the stack to provision.
4. Open the stack and copy these values:

   | Field | Source in the Grafana Cloud Portal |
   |-------|------------------------------------|
   | `GRAFANA_CLOUD_PROMETHEUS_URL`  | Cards → **Prometheus** → **Send Metrics** → "Remote Write Endpoint" |
   | `GRAFANA_CLOUD_PROMETHEUS_USER` | Same card → numeric "Username / Instance ID" |
   | `GRAFANA_CLOUD_LOKI_URL`        | Cards → **Loki** → **Send Logs** → "Remote Write Endpoint" |
   | `GRAFANA_CLOUD_LOKI_USER`       | Same card → numeric "User" |
   | `GRAFANA_CLOUD_API_KEY`         | **Security** → **Access Policies** → create one with `metrics:write` + `logs:write` |

5. Save the access token immediately — it cannot be retrieved later.

## 2. Mirror metrics basic-auth across every app

The same `METRICS_BASIC_USER` / `METRICS_BASIC_PASSWORD` pair must be set on:

- Every Heroku app (config vars):

  ```bash
  for app in bidmart-auth bidmart-auth-staging bidmart-order bidmart-order-staging; do
    heroku config:set -a "$app" METRICS_BASIC_USER=grafana METRICS_BASIC_PASSWORD='<strong>'
  done
  ```

- The VPS env files (`/etc/bidmart/staging.env` and `/etc/bidmart/prod.env`):
  see lines `METRICS_BASIC_USER=...` and `METRICS_BASIC_PASSWORD=...`. The
  current shared password is in `prod-env/staging.env` / `prod-env/prod.env`
  on the maintainer's laptop.

## 3. Drop credentials on the VPS

```bash
scp deploy/vps/grafana-cloud.env.example bidmart:/tmp/grafana-cloud.env
ssh bidmart "sudo mv /tmp/grafana-cloud.env /etc/bidmart/grafana-cloud.env \
             && sudo chmod 600 /etc/bidmart/grafana-cloud.env \
             && sudo chown root:root /etc/bidmart/grafana-cloud.env"
ssh bidmart "sudo ${EDITOR:-vi} /etc/bidmart/grafana-cloud.env"   # paste real values
```

## 4. Bring up Alloy

Alloy needs to join the existing `bidmart-staging_default` and
`bidmart-prod_default` Docker networks. These exist as soon as the regular
stacks are up (`deploy/vps/deploy.sh staging` / `prod`).

```bash
ssh bidmart "cd /opt/bidmart/staging/bidmart-infrastructure && \
             sudo docker compose \
               -f deploy/vps/docker-compose.monitoring.yml \
               --env-file /etc/bidmart/grafana-cloud.env \
               up -d"
ssh bidmart "docker logs --tail 30 bidmart-alloy"
```

The Alloy debug UI is bound to `127.0.0.1:12345` on the VPS — port-forward to
inspect locally:

```bash
ssh -L 12345:127.0.0.1:12345 bidmart
open http://127.0.0.1:12345
```

## 5. Verify in Grafana Cloud

In Grafana Cloud → **Explore**:

* Prometheus datasource → run `up{job=~"bidmart-.+"}` → all series should be `1`
  (Heroku Eco dynos may briefly be `0` while idle).
* Loki datasource → `{job="bidmart-vps-docker"}` → live container logs.

## 6. Import dashboards

Pre-built JSON dashboards live in `monitoring/grafana/dashboards/`. In
Grafana Cloud:

1. **Dashboards** → **New** → **Import** → upload each JSON file:
   - `bidmart-overview.json`
   - `bidmart-gateway.json`
   - `bidmart-auth-service.json`
   - `bidmart-order-service.json`
   - `bidmart-catalogue-service.json`
   - `bidmart-auction-service.json`
   - `bidmart-wallet-service.json`
2. When prompted, choose the **grafanacloud-prom** datasource.
3. The dashboards already filter by `env=$env` and `job=...` — pick
   `staging` or `prod` from the dashboard variable.

## 7. Wire alerts (optional but recommended)

The essential alert rules ship in `alerts.yml`. Two ways to install them:

### Option A — `mimirtool` (one command, repeatable)

```bash
mimirtool rules sync \
  --address="$GRAFANA_CLOUD_PROMETHEUS_URL" \
  --id="$GRAFANA_CLOUD_PROMETHEUS_USER" \
  --key="$GRAFANA_CLOUD_API_KEY" \
  monitoring/grafana-cloud/alerts.yml
```

### Option B — Grafana Cloud UI

1. **Alerts & IRM** → **Alerting** → **Alert rules**.
2. New rule → **Mimir or Loki managed alert** → **Import** YAML.
3. Paste each group from `alerts.yml`.
4. Set a contact point (Slack/email) under **Alerts & IRM → Contact points**
   and bind it to a default notification policy.

## 8. (Optional) Heroku log drains → Loki

If you also want Heroku stdout/stderr in Loki, expose Alloy's HTTP receiver
through Caddy on the VPS, then run on a workstation with the Heroku CLI:

```bash
DRAIN_URL="https://bidmart-staging.43.157.208.68.sslip.io:8443/heroku-drain"   # add Caddy route first
for app in bidmart-auth-staging bidmart-order-staging; do
  heroku drains:add "$DRAIN_URL?app=$app" -a "$app"
done
```

This is **off by default** because it requires a public route. Heroku's own
log UI is adequate for most debugging.

## 9. Free-tier limits / gotchas

| Topic | Note |
|-------|------|
| Active series cap | 10k on free tier. BidMart currently emits ~3k. |
| Loki retention   | 14 days on free tier. |
| Heroku Eco sleep | `up` may flap to `0` when dynos idle; wake before demos. |
| Self-signed sslip.io edge cert | Alloy uses `tls_config.insecure_skip_verify=true` for that target only. |
| Metrics security | Endpoints are public; do not put secrets in metric labels. |

## 10. Rubric evidence checklist

| Evidence | Where to capture | Filename pattern |
|----------|------------------|-----------------|
| All targets up | Grafana Cloud Explore → `up{job=~"bidmart-.+"}` | `docs/evidence/grafana-cloud-targets-YYYY-MM-DD.png` |
| Overview dashboard | Dashboard **BidMart Overview** | `docs/evidence/grafana-cloud-overview-YYYY-MM-DD.png` |
| One service dashboard | e.g. Auth or Gateway | `docs/evidence/grafana-cloud-auth-YYYY-MM-DD.png` |
| Loki logs query | Explore → Loki `{job="bidmart-vps-docker"} \|= "ERROR"` | `docs/evidence/grafana-cloud-logs-YYYY-MM-DD.png` |
| Alert rules listed | Alerts & IRM → Alert rules | `docs/evidence/grafana-cloud-alerts-YYYY-MM-DD.png` |

## Related docs

- [SCRAPE_TARGETS.md](./SCRAPE_TARGETS.md) — authoritative job list
- [`../../docs/10. Monitoring.md`](../../docs/10.%20Monitoring.md) — metric catalogue & sample PromQL
- [`../../docs/15. Deployment.md`](../../docs/15.%20Deployment.md) — Heroku/VPS deployment topology
- [`../../docs/4. Software Deployment.md`](../../docs/4.%20Software%20Deployment.md) — CI/CD reference
