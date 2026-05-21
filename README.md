# Modul 7 — Infrastructure

API Gateway (Spring Cloud Gateway), orkestrasi **Docker Compose**, konfigurasi env terpusat, dan titik masuk observability platform (modul 8).

## Menjalankan

### Backend (Docker)

```bash
cp .env.example .env
docker compose up -d --build
```

If a build fails with `TLS handshake timeout` while pulling from Docker Hub, retry with `./scripts/docker-up.sh` (pre-pulls base images with backoff).

| Layanan | URL / port |
|---------|------------|
| Gateway | http://localhost:8000 |
| Swagger agregat | http://localhost:8088 |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3001 |
| RabbitMQ UI | http://localhost:15672 |

### Frontend (di host — pola dev yang disarankan)

Frontend **tidak** dijalankan lewat Compose. Jalankan Vite di laptop:

```bash
cd ../bidmart-frontend
cp .env.example .env
npm ci
npm run dev
```

| UI | URL |
|----|-----|
| Frontend (Vite) | http://localhost:5173 |

Set `FRONTEND_BASE_URL=http://localhost:5173` di `.env` infrastructure agar link email/reset password mengarah ke Vite.

## Dokumentasi

| No. | Dokumen |
|-----|---------|
| 0 | [docs/0. Overview.md](docs/0.%20Overview.md) |
| 8 | [docs/8. Gateway.md](docs/8.%20Gateway.md) |
| 9 | [docs/9. Alur sistem.md](docs/9.%20Alur%20sistem.md) |
| 10 | [docs/10. Monitoring.md](docs/10.%20Monitoring.md) |
| 11 | [docs/11. Rubrik.md](docs/11.%20Rubrik.md) |
| 12–17 | Performa, desain, deploy — lihat [docs/README.md](docs/README.md) |

## Modul lain

| No. | Modul | Repo |
|-----|-------|------|
| 1 | Autentikasi dan Manajemen Pengguna | `bidmart-auth-service` |
| 2 | Katalog dan Manajemen Listing | `bidmart-catalogue-service` |
| 3 | Lelang dan Penawaran | `bidmart-auction-service-rust` |
| 4 | Dompet dan Manajemen Saldo | `bidmart-wallet-service-rust` |
| 5 | Pemesanan dan Notifikasi | `bidmart-order-and-notification-service` |
| 6 | Frontend | `bidmart-frontend` |
| 8 | Monitoring | [docs/10. Monitoring.md](docs/10.%20Monitoring.md) |
