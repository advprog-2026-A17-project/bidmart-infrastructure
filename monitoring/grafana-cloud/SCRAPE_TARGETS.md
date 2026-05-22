# BidMart Grafana Cloud — scrape targets (current as of 2026-05-22)

A single Grafana Alloy container running on the VPS collects every metric and
every Docker log line in BidMart, then forwards them to Grafana Cloud Hosted
Prometheus + Hosted Loki.

> Deployment: `deploy/vps/docker-compose.monitoring.yml`  
> Config:     `monitoring/grafana-cloud/alloy.config.river`  
> Credentials: `/etc/bidmart/grafana-cloud.env` (chmod 600, NOT in git)

## Metrics jobs

All Spring Boot apps expose Prometheus at `/actuator/prometheus`. Basic auth
(`METRICS_BASIC_USER` / `METRICS_BASIC_PASSWORD`) protects every public
endpoint. The same credentials must be set on every Heroku app and in
`/etc/bidmart/{staging,prod}.env`.

### Heroku (auth + order, container stack)

| Series labels | URL |
|---------------|-----|
| `job=bidmart-auth, env=staging, platform=heroku` | `https://bidmart-auth-staging-392ed0eda7b8.herokuapp.com/actuator/prometheus` |
| `job=bidmart-auth, env=prod`                     | `https://bidmart-auth-876b62d83c69.herokuapp.com/actuator/prometheus` |
| `job=bidmart-order, env=staging`                 | `https://bidmart-order-staging-a5fd01b90144.herokuapp.com/actuator/prometheus` |
| `job=bidmart-order, env=prod`                    | `https://bidmart-order-ced842eeb858.herokuapp.com/actuator/prometheus` |

> Eco dynos sleep after 30m idle. Wake before screenshots:
> `heroku ps:scale web=1 -a <app>` (no-op restart) or send a single HTTP request.

### VPS (gateway, via sslip.io edge — port 8443)

SSH currently occupies VPS port 443, so Caddy serves HTTPS on 8443. All public
URLs include `:8443`.

| Series labels | URL |
|---------------|-----|
| `job=bidmart-gateway, env=staging, platform=vps` | `https://bidmart-staging.43.157.208.68.sslip.io:8443/actuator/prometheus` |
| `job=bidmart-gateway, env=prod`                  | `https://bidmart-prod.43.157.208.68.sslip.io:8443/actuator/prometheus` |

### VPS internal (catalogue, auction, wallet — Docker network)

Alloy joins both `bidmart-staging_default` and `bidmart-prod_default` Docker
networks, so it can scrape backend services directly without going through
Caddy or the gateway.

| Series labels | Target (in-cluster) | Notes |
|---------------|---------------------|-------|
| `job=bidmart-catalogue, env=staging` | `http://bidmart-staging-catalogue-service-1:8081/actuator/prometheus` | Java Spring Boot |
| `job=bidmart-catalogue, env=prod`    | `http://bidmart-prod-catalogue-service-1:8081/actuator/prometheus`    | Java Spring Boot |
| `job=bidmart-auction,   env=staging` | `http://bidmart-staging-auction-service-1:8082/metrics`                | Rust / `/metrics` |
| `job=bidmart-auction,   env=prod`    | `http://bidmart-prod-auction-service-1:8082/metrics`                   | Rust / `/metrics` |
| `job=bidmart-wallet,    env=staging` | `http://bidmart-staging-wallet-service-1:8083/metrics`                 | Rust / `/metrics` |
| `job=bidmart-wallet,    env=prod`    | `http://bidmart-prod-wallet-service-1:8083/metrics`                    | Rust / `/metrics` |

## Log streams (Loki)

| Stream labels | Source |
|---------------|--------|
| `job=bidmart-vps-docker, container=<name>, compose_project=<bidmart-staging\|prod>, service=<gateway\|catalogue-service\|...>` | `loki.source.docker` tails `/var/lib/docker/containers/*` |
| `job=bidmart-heroku-drain, app=<heroku-app-name>` | `loki.source.heroku` HTTP listener on `:8087` (only if a Heroku drain is configured to point at it) |

## Verification

```bash
# 1. From the VPS, check Alloy is healthy
docker logs --tail 50 bidmart-alloy

# 2. From Grafana Cloud Explore -> Prometheus datasource
up{job=~"bidmart-.+"}

# 3. From Grafana Cloud Explore -> Loki datasource
{job="bidmart-vps-docker"} |= "ERROR"
```

All series should report `1`. Heroku Eco dynos may flap to `0` while sleeping —
this is expected.
