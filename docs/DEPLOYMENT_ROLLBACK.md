# Deployment Rollback Drill

Tanggal: 2026-05-21  
PIC: Infrastructure / shared  
Tujuan: bukti rubrik **Software Deployment** — prosedur rollback terdokumentasi dan dapat diuji di staging atau compose lokal.

## Prerequisites

- Akses Heroku CLI (`heroku login`) atau Docker Compose stack lokal (`bidmart-infrastructure/docker-compose.yml`).
- Release sebelumnya masih tersedia (Heroku release history atau image tag sebelumnya).
- Smoke script: `bidmart-infrastructure/scripts/functional-smoke.mjs`.

## Rollback drill — Heroku (per service)

Gunakan untuk `bidmart-auth-service`, gateway, order, catalogue, dll. yang deploy ke Heroku.

1. **Catat release aktif**
   ```bash
   heroku releases -a <APP_NAME> -n 5
   ```
   Simpan `vNNN` yang sedang live dan `vNNN-1` sebagai target rollback.

2. **Rollback ke release sebelumnya**
   ```bash
   heroku rollback v<PREVIOUS> -a <APP_NAME>
   ```
   Contoh: jika live `v42`, rollback ke `v41`.

3. **Verifikasi health**
   ```bash
   heroku ps -a <APP_NAME>
   curl -fsS "https://<APP_NAME>.herokuapp.com/actuator/health" | jq .
   ```
   Gateway: `curl -fsS "https://<GATEWAY_APP>/actuator/health"`.

4. **Smoke fungsional (minimal)**
   ```bash
   cd bidmart-infrastructure
   BIDMART_BASE_URL=https://<staging-frontend-or-gateway> node scripts/functional-smoke.mjs
   ```
   Harapan: register/login atau health paths tidak 5xx; sesuaikan env di script jika perlu.

5. **Dokumentasi hasil drill**

   | Field | Value |
   |-------|-------|
   | Tanggal drill | 2026-05-22 |
   | App | bidmart-order-notification-service (compose local) |
   | From release | hot-deploy JAR (consumer reorder) |
   | To release | previous image tag `bidmart-order-and-notification-service:local` |
   | Rollback command | `docker compose up -d order-notification-service` (rebuild prior commit) |
   | Health after rollback | PASS |
   | Smoke after rollback | PASS — `functional-smoke-2026-05-22.log` |
   | Waktu recovery (menit) | 5 |

   | Tanggal drill | _YYYY-MM-DD_ |
   | App | _e.g. bidmart-order-staging_ |
   | From release | _v42_ |
   | To release | _v41_ |
   | Rollback command | `heroku rollback v41 -a ...` |
   | Health after rollback | PASS / FAIL |
   | Smoke after rollback | PASS / FAIL |
   | Waktu recovery (menit) | _e.g. 3_ |

## Rollback drill — Docker Compose (lokal / VM)

1. **Tag image / commit baseline**
   ```bash
   git rev-parse HEAD
   docker compose -f bidmart-infrastructure/docker-compose.yml ps
   ```

2. **Simulasi deploy buruk** — rebuild satu service dari branch/commit bermasalah (opsional) atau scale service ke 0.

3. **Rollback**
   ```bash
   cd bidmart-infrastructure
   git checkout <KNOWN_GOOD_TAG_OR_COMMIT>
   docker compose pull   # jika pakai registry
   docker compose up -d --build <service-name>
   ```
   Atau redeploy image digest sebelumnya jika disimpan di registry.

4. **Verifikasi**
   ```bash
   docker compose ps
   curl -fsS http://localhost:8080/actuator/health   # gateway port sesuai compose
   node scripts/functional-smoke.mjs
   ```

5. Isi tabel hasil drill (sama seperti Heroku).

## Blue/green note (feature flags)

Rollback cepat tanpa rebuild penuh dapat memakai env di compose/Heroku:

- `BIDMART_WALLET_HTTP_ENABLED=true|false` — paksa jalur HTTP wallet vs gRPC.
- Matikan consumer Rabbit sementara dengan menonaktifkan worker (scale 0) jika event storm setelah deploy.

Dokumentasikan flag yang dipakai saat drill di baris "Catatan" tabel hasil.

## Disaster recovery (tabletop singkat)

| Skenario | Deteksi | Tindakan | RTO target |
|----------|---------|----------|------------|
| Postgres volume corrupt (compose) | service health DOWN | Restore dari backup volume / `pg_dump` terakhir | < 1 jam (lokal) |
| RabbitMQ queue backlog | Grafana queue depth | Scale consumer, purge DLQ setelah root-cause | < 30 menit |
| Gateway mis-route | 404 pada `/api/v1/*` | Rollback gateway release + cek route YAML | < 15 menit |

## Evidence for rubric

- [ ] Minimal satu baris tabel drill terisi (Heroku **atau** compose).
- [ ] Screenshot atau log `heroku releases` / `docker compose ps` disimpan di folder tim (opsional: `bidmart-infrastructure/docs/evidence/rollback-YYYY-MM-DD/`).
- [ ] Referensi di `FINAL_RUBRIC_CHECKLIST.md` § B4 / C3 setelah drill dijalankan.

## Related docs

- `bidmart-infrastructure/docs/DEPLOYMENT.md` (prosedur deploy normal)
- `bidmart-infrastructure/docs/functional-testing.md`
- `FINAL_RUBRIC_CHECKLIST.md`
