# Grafana Cloud scrape targets (BidMart)

Use **Metrics Endpoint** jobs in Grafana Cloud → Connections → Add scrape job.  
Basic auth: same `METRICS_BASIC_USER` / `METRICS_BASIC_PASSWORD` as Heroku (`heroku config:get METRICS_BASIC_PASSWORD -a bidmart-auth-staging`).

## Heroku (auth + order, container stack)

| Job | URL |
|-----|-----|
| `bidmart-auth-staging` | `https://bidmart-auth-staging-392ed0eda7b8.herokuapp.com/actuator/prometheus` |
| `bidmart-auth` | `https://bidmart-auth-876b62d83c69.herokuapp.com/actuator/prometheus` |
| `bidmart-order-staging` | `https://bidmart-order-staging-a5fd01b90144.herokuapp.com/actuator/prometheus` |
| `bidmart-order` | `https://bidmart-order-ced842eeb858.herokuapp.com/actuator/prometheus` |

Wake dynos before screenshots: `heroku ps:scale web=1 -a <app>`.

## VPS gateway (catalogue, auction, wallet via edge)

| Job | URL |
|-----|-----|
| `bidmart-gateway-staging` | `https://bidmart-staging.43.157.208.68.sslip.io/actuator/prometheus` |
| `bidmart-gateway-prod` | `https://bidmart-prod.43.157.208.68.sslip.io/actuator/prometheus` |

## Verify

```promql
up{job=~"bidmart-.*"} == 1
```

Import dashboards from `monitoring/grafana/dashboards/*.json`.  
Evidence: `docs/evidence/grafana-cloud-targets-YYYY-MM-DD.png`.
