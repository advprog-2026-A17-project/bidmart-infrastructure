# BidMart Full-Score Rubrik Evidence (Non-CD)

Tanggal eksekusi: 19 Mei 2026  
Scope: coverage, Sonar/quality gate, CI artifact, APDEX/profiling, observability.  
Catatan: CD dikecualikan sesuai instruksi (deployment Heroku otomatis).

## 1) Coverage Report Evidence

Coverage diambil dari JaCoCo XML terbaru:

- Auth service: `91%` line coverage (`61/67`)
  - `bidmart-auth-service/build/reports/jacoco/test/jacocoTestReport.xml`
- Catalogue service: `100%` line coverage (`16/16`) setelah exclude generated gRPC classes dari report
  - `bidmart-catalogue-service/build/reports/jacoco/test/jacocoTestReport.xml`
- Order & Notification service: `89%` line coverage (`26/29`)
  - `bidmart-order-and-notification-service/build/reports/jacoco/test/jacocoTestReport.xml`
- Infrastructure gateway: `100%` line coverage (`7/7`)
  - `bidmart-infrastructure/build/reports/jacoco/test/jacocoTestReport.xml`

Laporan HTML tersedia di:

- `bidmart-auth-service/build/reports/jacoco/test/html/index.html`
- `bidmart-catalogue-service/build/reports/jacoco/test/html/index.html`
- `bidmart-order-and-notification-service/build/reports/jacoco/test/html/index.html`
- `bidmart-infrastructure/build/reports/jacoco/test/html/index.html`

## 2) Sonar / Quality Gate Evidence

Status SonarCloud yang berhasil diambil:

- `advprog-2026-A17-project_bidmart-auth-service`: `OK`
- `advprog-2026-A17-project_bidmart-frontend`: `OK`
- `advprog-2026-A17-project_bidmart-catalogue-service`: `NONE` (project ada tapi gate belum aktif/terbaca)
- `advprog-2026-A17-project_bidmart-infrastructure`: `NONE`
- `advprog-2026-A17-project_bidmart-order-and-notification-service`: `not found` (key belum tersedia publik)
- `advprog-2026-A17-project_bidmart-auction-service-rust`: `not found`
- `advprog-2026-A17-project_bidmart-wallet-service-rust`: `not found`

API yang dipakai:

- `https://sonarcloud.io/api/qualitygates/project_status?projectKey=<project-key>`

## 3) CI Pipeline Artifact Evidence

Workflow CI diperkuat agar selalu mengunggah artifact test/coverage:

- Auth: upload `build/reports/tests/test/**`, `build/reports/jacoco/test/**`
- Catalogue: upload `build/reports/tests/test/**`, `build/reports/jacoco/test/**`
- Order-notification: upload `build/reports/tests/test/**`, `build/reports/jacoco/test/**`
- Infrastructure: upload `build/reports/tests/test/**`, `build/reports/jacoco/test/**`
- Frontend: upload `test-output.log`, `dist/**`
- Auction Rust: upload `test-output.log` (hasil `cargo test -- --nocapture`)
- Wallet Rust: upload `test-output.log` (hasil `cargo test -- --nocapture`)

## 4) APDEX & Profiling Evidence

Profiling/load harness (auction service) dijalankan:

- Command: `cargo test run_seeded_bidding_load_harness -- --ignored --nocapture`
- Hasil:
  - attempts: `300`
  - accepted: `5`
  - p50: `53 ms`
  - p95: `85 ms`
  - max: `89 ms`
  - APDEX(T=100ms): `1.000` (`satisfied=300`, `tolerating=0`)

Source output berasal dari:

- `bidmart-auction-service-rust/tests/load_performance_harness_tests.rs`

## 5) Lighthouse / Usability Evidence

Lighthouse report berhasil digenerate untuk frontend lokal:

- Command:
  - `npx --yes lighthouse http://localhost --quiet --chrome-flags='--headless --no-sandbox' --output=json --output=html --output-path=./artifacts/lighthouse-report`
- Output file:
  - `bidmart-frontend/artifacts/lighthouse-report.report.html`
  - `bidmart-frontend/artifacts/lighthouse-report.report.json`
- Ringkasan skor:
  - Performance: `44`
  - Accessibility: `96`
  - Best Practices: `77`
  - SEO: `92`
  - FCP: `11205 ms`
  - LCP: `20300 ms`
  - TBT: `387 ms`
  - CLS: `0.091757`

## 6) Observability Evidence

Stack observability aktif:

- `docker compose ps` menunjukkan Prometheus + Grafana aktif.
- Prometheus target health:
  - bidmart-auction-service: `up`
  - bidmart-auth-service: `up`
  - bidmart-catalogue-service: `up`
  - bidmart-gateway: `up`
  - bidmart-order-service: `up`
  - bidmart-wallet-service: `up`
  - prometheus: `up`

Sumber konfigurasi:

- `bidmart-infrastructure/monitoring/prometheus/prometheus.yml`
- `bidmart-infrastructure/monitoring/grafana/dashboards/bidmart-overview.json`

## 7) PASS/GAP Matrix (Full-Score Non-CD)

| Rubrik Poin | Status | Evidence |
|---|---|---|
| CI berjalan + artifact report tersedia | PASS | Workflow CI semua modul sudah upload artifact |
| Unit/functional testing dijalankan lintas modul | PASS | Gradle + cargo + Playwright sudah hijau pada flow inti |
| Coverage report tersedia dan terdokumentasi | PASS | JaCoCo XML/HTML + Lighthouse JSON/HTML + Rust test logs |
| Coverage >= 90% di semua modul | GAP | Order-notification masih 89% |
| Sonar quality gate visible untuk semua modul | GAP | Sebagian module key belum found / status NONE |
| Profiling evidence & APDEX tersedia | PASS | Load harness + APDEX(T=100ms)=1.000 |
| Lighthouse evidence tersedia | PASS | Report HTML/JSON tersedia |
| Monitoring/observability aktif | PASS | Prometheus targets seluruh service `up` |

