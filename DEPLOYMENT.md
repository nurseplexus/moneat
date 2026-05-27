# Moneat Deployment Guide

Deploy Moneat on a server using Docker Compose. This guide covers **full production deployment**, with a primary focus on **deploying your own fork** after making **Kotlin backend changes** (and optional dashboard changes).

For day-to-day development with hot-reload, see [LOCAL_DEVELOPMENT.md](./LOCAL_DEVELOPMENT.md).

---

## Who should read which section

| Your situation | Start here |
|----------------|------------|
| Forked Moneat, changed Kotlin code, need to deploy | [Deploy a fork (full walkthrough)](#deploy-a-fork-full-walkthrough) |
| Want official images, no code changes | [Option A — Installer](#option-a-interactive-installer) or [Option B — Manual](#option-b-manual-setup-official-images) |
| Need to understand databases first | [Infrastructure & databases](#infrastructure--databases) |
| Replays / ingestion broken after deploy | [Troubleshooting](#troubleshooting) |

---

## Requirements (read this first)

Complete these **before** deploying to a server.

### 1. Server hardware

| Tier | CPU | RAM | Disk | When to use |
|------|-----|-----|------|-------------|
| **Minimum** | 2 vCPU | 4 GB | 20 GB SSD | Low traffic, testing |
| **Recommended** | 4+ vCPU | 8+ GB | 50+ GB SSD/NVMe | Production, replays, logs, profiling |

ClickHouse is memory-intensive. Session replays and high log volume need the recommended tier.

### 2. Server software

| Software | Version | Required for |
|----------|---------|--------------|
| **Linux** | Ubuntu 22.04+, Debian 12+, or similar | Production host |
| **Docker** | 20.10+ | All deployments |
| **Docker Compose** | v2 | All deployments |
| **Git** | any recent | Fork clone on server or CI |
| **OpenSSL** | any | Generating production secrets |

**On the production server you do NOT need Java or Node.js** if you build Docker images in CI or on a build machine and push them to a registry (recommended).

**You DO need Docker build resources** (locally, in CI, or on the server) when deploying a fork with Kotlin changes — the official `ghcr.io` images will not contain your code.

### 3. Network & domains

Decide before writing `.env`:

| Item | Example | Notes |
|------|---------|-------|
| **Dashboard URL** | `https://moneat.yourcompany.com` | → `FRONTEND_URL` |
| **API URL** | `https://api.yourcompany.com` | → `BACKEND_URL` |
| **TLS** | Let's Encrypt via Nginx/Caddy | Required for production browsers |
| **Open ports** | 80, 443 (public); 8080, 3000 (internal/proxy only) | Do not expose DB ports publicly |

The Sentry SDK and replay ingestion must reach **`BACKEND_URL`**, not the dashboard URL.

### 4. Secrets you must generate

Never use `moneat_dev_password` or `change-this-secret-in-production` in production.

```bash
# JWT (64 chars)
openssl rand -base64 64 | tr -d '/+=\n' | head -c 64

# Passwords & encryption key (32 chars each — run separately)
openssl rand -base64 32 | tr -d '/+=\n' | head -c 32
```

Generate **five distinct values**:

| Variable | Purpose |
|----------|---------|
| `JWT_SECRET` | Signs user login tokens |
| `DATA_SOURCE_ENCRYPTION_KEY` | Encrypts custom data source credentials (must differ from JWT) |
| `DATABASE_PASSWORD` | PostgreSQL |
| `CLICKHOUSE_PASSWORD` | ClickHouse |
| `REDIS_PASSWORD` | Redis auth |

Store them in `.env` on the server. **Back up `.env`** — losing it means losing access to encrypted data.

### 5. Optional but recommended

| Item | Purpose |
|------|---------|
| SMTP server | Email verification and alerts (`DISABLE_EMAIL_VERIFICATION=false`) |
| Container registry | Docker Hub, GHCR, ECR — for custom fork images |
| Reverse proxy | Nginx or Caddy for HTTPS and large upload limits |

Without SMTP: set `SELF_HOSTED=true` and `DISABLE_EMAIL_VERIFICATION=true`. The **first registered user becomes admin** automatically.

---

## Infrastructure & databases

Moneat runs **five Docker services**. Three are databases; all three are **mandatory**.

```text
                    ┌─────────────────┐
  Browser / SDK ──► │  Reverse proxy  │ (Nginx/Caddy — HTTPS)
                    └────────┬────────┘
              ┌──────────────┼──────────────┐
              ▼              ▼              │
       ┌──────────┐   ┌──────────┐          │
       │ frontend │   │ backend  │          │
       │ (Nginx)  │   │ (Kotlin) │          │
       │ :3000    │   │ :8080    │          │
       └──────────┘   └────┬─────┘          │
                           │                 │
              ┌────────────┼────────────┐    │
              ▼            ▼            ▼    │
        ┌──────────┐ ┌───────────┐ ┌───────┴──┐
        │ Postgres │ │ ClickHouse│ │  Redis   │
        │  :5432   │ │   :8123   │ │  :6379   │
        └──────────┘ └───────────┘ └──────────┘
              │            │            │
         postgres_data  clickhouse_data  redis_data
                                    backend_storage (files)
```

### Service summary

| Service | Docker image (default) | Port (host) | Persistent data |
|---------|------------------------|-------------|-----------------|
| **postgres** | `postgres:18-alpine` | 5499 | Volume `postgres_data` |
| **clickhouse** | `clickhouse/clickhouse-server:26.2-alpine` | 8123, 9000 | Volume `clickhouse_data` |
| **redis** | `redis:8-alpine` | 6379 | Volume `redis_data` |
| **backend** | Custom or `ghcr.io/moneat-io/moneat-backend` | 8080 | Volume `backend_storage` |
| **frontend** | Custom or `ghcr.io/moneat-io/moneat-dashboard` | 3000 | None (stateless) |

---

### PostgreSQL — what it stores

**Role:** Relational metadata. Small data, strong consistency.

**Default credentials** (set in `.env`):

| Setting | Value |
|---------|-------|
| Database | `moneat` |
| User | `moneat` |
| Password | `DATABASE_PASSWORD` from `.env` |
| Host (inside Docker) | `postgres:5432` |
| Host (from server) | `localhost:5499` |

**Examples of data in PostgreSQL:**

- Users, organizations, memberships
- Projects and Sentry DSN keys (`project_keys`)
- OTLP API keys, alert rules, notification settings
- Subscriptions, SSO config, feature flags config
- Uptime monitors, status pages (metadata)

**Schema creation:** You do **not** run SQL manually.

1. First container start → Docker runs `backend/src/main/resources/db/init.sql` (bootstrap).
2. Backend startup → **Flyway** applies all migrations in `backend/src/main/resources/db/migration/V*.sql`.
3. Flyway tracks applied migrations in table `flyway_schema_history`.

If you add a new Kotlin migration locally, it ships inside your custom backend JAR and runs automatically on next deploy.

---

### ClickHouse — what it stores

**Role:** High-volume time-series analytics. Events, logs, traces, replays.

**Default credentials:**

| Setting | Value |
|---------|-------|
| Database | `moneat` |
| User | `moneat` |
| Password | `CLICKHOUSE_PASSWORD` from `.env` |
| HTTP (inside Docker) | `http://clickhouse:8123` |
| HTTP (from server) | `http://localhost:8123` |

**Examples of data in ClickHouse:**

- Error events and grouped issues
- Session replays (`replay_events`, `replay_segments`)
- APM spans and traces
- OTLP logs and metrics
- Analytics events

**Schema creation:**

1. First container start → Docker runs `backend/src/main/resources/db/clickhouse_init.sql`.
2. Backend startup → custom migrator applies `backend/src/main/resources/db/clickhouse_migration/V*.sql`.

Replay playback fails if the backend never started successfully — replay tables are created by ClickHouse migrations, not by `init.sql` alone.

---

### Redis — what it stores

**Role:** Cache, rate limiting, background job queues. Not primary long-term storage.

| Setting | Value |
|---------|-------|
| Password | `REDIS_PASSWORD` from `.env` |
| URL (inside Docker) | `redis://:${REDIS_PASSWORD}@redis:6379` |

**Must match:** The same `REDIS_PASSWORD` in `.env` is used by both the Redis container (`--requirepass`) and the backend connection string in `docker-compose.yml`.

---

### File storage (`backend_storage` volume)

**Role:** Continuous profiling payloads and other backend files.

- Mounted at `/app/storage` inside the backend container
- Docker volume name: `backend_storage`
- Optional env: `PROFILE_STORAGE_PATH`, `PROFILE_MAX_PAYLOAD_BYTES`

---

### How Docker networking works (important for fork deploy)

Inside `docker-compose.yml`, the **backend container** always connects to:

```text
DATABASE_URL  → jdbc:postgresql://postgres:5432/moneat
CLICKHOUSE_URL  → http://clickhouse:8123
REDIS_URL       → redis://:PASSWORD@redis:6379
```

These use Docker **service names**, not `localhost`. Your `.env` `DATABASE_URL` with `localhost:5499` is for running `./gradlew run` on your dev machine — compose overrides DB URLs for the container.

What **must** be correct in `.env` for production:

- `FRONTEND_URL` — public dashboard URL
- `BACKEND_URL` — public API URL (used by frontend and SDKs)
- All passwords and secrets
- `SELF_HOSTED=true`

---

## Deploy a fork (full walkthrough)

This is the main path when you:

1. Forked [moneat-io/moneat](https://github.com/moneat-io/moneat)
2. Cloned your fork locally
3. Made Kotlin changes (e.g. `EventService.kt`, `ReplayService.kt`, routes, migrations)
4. Need to deploy to a server

Official images from `ghcr.io/moneat-io/*` **do not include your changes**. You must build and run **custom Docker images**.

### Phase 1 — Develop and test locally

Before deploying, validate on your machine:

```bash
git clone https://github.com/YOUR-ORG/moneat.git
cd moneat
git checkout your-feature-branch

cp .env.example .env
# Keep dev defaults for local work

docker compose up -d postgres clickhouse redis
cd backend && ./gradlew run          # test Kotlin changes
cd dashboard && npm install && npm run dev   # if you changed frontend too
```

Run tests:

```bash
cd backend && ./gradlew test
```

If you added PostgreSQL or ClickHouse migrations, confirm they apply:

```bash
# Watch backend startup logs for:
# "Running PostgreSQL migrations..."
# "Applied N PostgreSQL migration(s)"
```

See [LOCAL_DEVELOPMENT.md](./LOCAL_DEVELOPMENT.md) for full local setup.

---

### Phase 2 — Prepare production `.env`

On the **server** (or copy from a template):

```bash
cp .env.example .env
```

Edit `.env` — minimum production values:

```env
# Public URLs (HTTPS in production)
FRONTEND_URL=https://moneat.yourcompany.com
BACKEND_URL=https://api.yourcompany.com

# Secrets (generated — never use dev defaults)
JWT_SECRET=<64-char-secret>
DATA_SOURCE_ENCRYPTION_KEY=<32-char-secret>
DATABASE_PASSWORD=<32-char-password>
CLICKHOUSE_PASSWORD=<32-char-password>
REDIS_PASSWORD=<32-char-password>

# Self-hosting
SELF_HOSTED=true
DISABLE_EMAIL_VERIFICATION=true   # or false if SMTP configured

# Optional SMTP
# SMTP_HOST=...
# SMTP_PORT=587
# SMTP_USERNAME=...
# SMTP_PASSWORD=...
# EMAIL_FROM=noreply@yourcompany.com
```

---

### Phase 3 — Build custom Docker images

Create `docker-compose.override.yml` in the repo root (do not commit secrets into this file):

```yaml
services:
  backend:
    build:
      context: .
      dockerfile: backend/Dockerfile
    image: your-registry.com/moneat-backend:your-tag

  frontend:
    build:
      context: .
      dockerfile: dashboard/Dockerfile
    image: your-registry.com/moneat-dashboard:your-tag
    args:
      VITE_BACKEND_URL: ${BACKEND_URL}
```

Docker Compose automatically merges `docker-compose.override.yml` with `docker-compose.yml`.

**What gets built:**

| Image | Build steps |
|-------|-------------|
| **Backend** | Email templates (Node) → Gradle JDK 17 compiles Kotlin + `ee/backend` → shadow JAR → JRE runtime |
| **Frontend** | Node 24 production build → Nginx Alpine (bakes in `VITE_BACKEND_URL`) |

**Build locally or in CI:**

```bash
# From repo root
./scripts/docker-build.sh

# Or with explicit tag
docker compose build
docker tag moneat-backend:custom your-registry.com/moneat-backend:v1.0.0
docker tag moneat-frontend:custom your-registry.com/moneat-dashboard:v1.0.0
```

**Push to your registry (recommended):**

```bash
docker login your-registry.com
docker push your-registry.com/moneat-backend:v1.0.0
docker push your-registry.com/moneat-dashboard:v1.0.0
```

---

### Phase 4 — Deploy on the server

#### Strategy A — Pull pre-built images (recommended)

Server needs: Docker, Compose, `.env`, `docker-compose.yml`, init SQL files, `clickhouse-config/logging.xml`.

```bash
# On server
git clone https://github.com/YOUR-ORG/moneat.git
cd moneat
git checkout your-release-tag

cp .env.example .env
# Edit .env with production secrets and URLs
```

`docker-compose.override.yml` pointing at your registry:

```yaml
services:
  backend:
    image: your-registry.com/moneat-backend:v1.0.0
  frontend:
    image: your-registry.com/moneat-dashboard:v1.0.0
```

Start in order (same as installer):

```bash
docker compose pull
docker compose up -d postgres clickhouse redis
docker compose ps    # wait until healthy
docker compose up -d backend frontend
```

#### Strategy B — Build on the server

Requires 8+ GB RAM and patience (Gradle + npm inside Docker):

```bash
git clone https://github.com/YOUR-ORG/moneat.git
cd moneat
cp .env.example .env
# edit .env

# docker-compose.override.yml with build: sections (Phase 3)
docker compose up --build -d
```

---

### Phase 5 — Configure HTTPS reverse proxy

Example Nginx (two domains):

```nginx
# Dashboard
server {
    listen 443 ssl http2;
    server_name moneat.yourcompany.com;
    client_max_body_size 50M;

    location / {
        proxy_pass http://127.0.0.1:3000;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}

# API — replays and profiles need large body size
server {
    listen 443 ssl http2;
    server_name api.yourcompany.com;
    client_max_body_size 100M;
    proxy_read_timeout 300s;
    proxy_send_timeout 300s;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

After changing `BACKEND_URL`, recreate the frontend so it picks up the API URL:

```bash
docker compose up -d --force-recreate frontend
```

---

### Phase 6 — Verify deployment

```bash
# 1. All containers healthy
docker compose ps

# 2. Backend health
curl -s https://api.yourcompany.com/health

# 3. Migrations ran
docker compose logs backend | grep -i migration

# 4. ClickHouse replay tables exist
docker compose exec clickhouse clickhouse-client --user moneat --password "$CLICKHOUSE_PASSWORD" \
  -q "SHOW TABLES FROM moneat LIKE 'replay%'"

# 5. Open dashboard, sign up, create project, copy DSN
```

**SDK DSN format** (must use API domain):

```text
https://{public_key}@api.yourcompany.com/api/{project_id}
```

---

### Phase 7 — Upgrade after new Kotlin changes

```bash
# Build new images with new tag
docker compose build
docker push your-registry.com/moneat-backend:v1.0.1
docker push your-registry.com/moneat-dashboard:v1.0.1

# On server — update tag in override, then:
docker compose pull
docker compose up -d
```

New Flyway / ClickHouse migrations in your Kotlin release run automatically when the backend container starts.

---

## Option A: Interactive installer

For **official images only** (no fork, no Kotlin changes):

```bash
bash <(curl -fsSL https://raw.githubusercontent.com/moneat-io/moneat/main/install.sh)
```

Downloads compose, init SQL, generates secrets, starts stack. No clone required.

---

## Option B: Manual setup (official images)

Download files for a pinned release (replace `v1.0.0`):

```bash
mkdir moneat && cd moneat
curl -fsSL https://raw.githubusercontent.com/moneat-io/moneat/v1.0.0/docker-compose.yml -o docker-compose.yml
curl -fsSL https://raw.githubusercontent.com/moneat-io/moneat/v1.0.0/.env.example -o .env.example

mkdir -p backend/src/main/resources/db clickhouse-config
curl -fsSL https://raw.githubusercontent.com/moneat-io/moneat/v1.0.0/backend/src/main/resources/db/init.sql \
  -o backend/src/main/resources/db/init.sql
curl -fsSL https://raw.githubusercontent.com/moneat-io/moneat/v1.0.0/backend/src/main/resources/db/clickhouse_init.sql \
  -o backend/src/main/resources/db/clickhouse_init.sql
curl -fsSL https://raw.githubusercontent.com/moneat-io/moneat/v1.0.0/clickhouse-config/logging.xml \
  -o clickhouse-config/logging.xml

cp .env.example .env
# Set secrets and URLs — see Requirements section
docker compose up -d
```

**Do not skip** the init SQL files — compose mounts them into Postgres and ClickHouse containers.

---

## Authentication and ingestion

### Sentry SDK (errors, replays, transactions)

```text
https://{public_key}@{BACKEND_URL host}/api/{project_id}
```

Keys: **Project Settings** in dashboard.

### OpenTelemetry (OTLP)

| Signal | Endpoint |
|--------|----------|
| Logs | `POST {BACKEND_URL}/v1/logs/otlp` |
| Traces | `POST {BACKEND_URL}/v1/traces/otlp` |
| Metrics | `POST {BACKEND_URL}/v1/metrics/otlp` |

Header: `Authorization: Bearer <OTLP API key>` from **Settings → OTLP API Keys**.

### CORS (browser SDKs)

```env
ALLOWED_ORIGINS=https://app.yourcompany.com,https://staging.yourcompany.com
```

---

## Environment variables reference

### Critical (backend fails without these)

| Variable | Rules |
|----------|-------|
| `JWT_SECRET` | ≥ 32 characters |
| `DATA_SOURCE_ENCRYPTION_KEY` | ≥ 32 chars, different from JWT |
| `DATABASE_PASSWORD` | Same value used by Postgres container |
| `CLICKHOUSE_PASSWORD` | Same value used by ClickHouse container |
| `REDIS_PASSWORD` | Same value used by Redis `--requirepass` |
| `FRONTEND_URL` | Public dashboard URL |
| `BACKEND_URL` | Public API URL |
| `SELF_HOSTED` | `true` |

Validated in `EnvironmentValidator.kt` on startup.

### Email

| Scenario | Settings |
|----------|----------|
| No SMTP | `DISABLE_EMAIL_VERIFICATION=true` → first user is admin |
| With SMTP | `DISABLE_EMAIL_VERIFICATION=false` + SMTP vars |

### Optional

| Variable | Purpose |
|----------|---------|
| `MONEAT_LICENSE_KEY` | Enterprise: SAML SSO, On-Call |
| `TELEMETRY_ENABLED` | Set `false` to opt out |
| `ALLOWED_ORIGINS` | Extra CORS origins |
| `TRUSTED_PROXIES` | Trust load balancer IPs for rate limiting |

Full list: `.env.example`.

---

### Backend won't start

| Problem | Fix |
|---------|-----|
| JWT / encryption errors | Replace placeholder secrets in `.env` |
| Redis connection failed | `REDIS_PASSWORD` must match in `.env` and compose |
| Missing columns | Migrations failed — read backend logs, do not skip init SQL files |

### Frontend can't reach API

| Problem | Fix |
|---------|-----|
| Wrong API host in browser | Set `BACKEND_URL`; `docker compose up -d --force-recreate frontend` |
| Mixed content | Both URLs must be `https://` behind TLS |
| CORS | Add app origin to `ALLOWED_ORIGINS` |

### Inspecting PostgreSQL (pgAdmin / DBeaver)

| Field | Value |
|-------|-------|
| Host | Server IP or `localhost` |
| Port | `5499` (or `POSTGRES_PORT` from `.env`) |
| Database | `moneat` |
| User | `moneat` |
| Password | `DATABASE_PASSWORD` from `.env` |

Tables are created by Flyway when the backend starts — not manually in pgAdmin.

---

## Operations

### Daily commands

```bash
docker compose ps
docker compose logs -f backend
docker compose restart backend
```

### Backups

Backup `.env` and these volumes:

| Volume | Contents |
|--------|----------|
| `postgres_data` | Users, projects, config |
| `clickhouse_data` | Events, replays, logs, traces |
| `redis_data` | Queue/cache |
| `backend_storage` | Profile files |

```bash
docker exec -t moneat-postgres pg_dumpall -U moneat > postgres_backup.sql
```

### Health checks

| Service | Check |
|---------|-------|
| Backend | `GET {BACKEND_URL}/health` |
| Frontend | `GET {FRONTEND_URL}` |
| Postgres | `docker inspect --format='{{.State.Health.Status}}' moneat-postgres` |
| ClickHouse | `curl http://localhost:8123/ping` |
| Redis | `docker compose exec redis redis-cli -a $REDIS_PASSWORD ping` |

---

## Deployment checklist (fork + Kotlin changes)

Use this before going live:

- [ ] Fork cloned; Kotlin changes tested locally (`./gradlew test`, `./gradlew run`)
- [ ] Production secrets generated (5 distinct values)
- [ ] `.env` on server: `SELF_HOSTED=true`, `FRONTEND_URL`, `BACKEND_URL`
- [ ] `docker-compose.override.yml` builds or pulls **custom** images (not default GHCR if you changed code)
- [ ] Init SQL files present in repo (`init.sql`, `clickhouse_init.sql`, `logging.xml`)
- [ ] Databases started and healthy before backend
- [ ] Backend logs show migrations applied
- [ ] HTTPS reverse proxy with `client_max_body_size 100M` on API
- [ ] Dashboard signup works; first user admin (if no SMTP)
- [ ] Test error + replay from SDK using DSN on API domain
- [ ] `.env` backed up securely

---

## Related documentation

- [LOCAL_DEVELOPMENT.md](./LOCAL_DEVELOPMENT.md) — Hot-reload dev with `./gradlew run`
- [SELF_HOSTING.md](./SELF_HOSTING.md) — Quick overview index
- [README.md](./README.md) — Features and SDK compatibility
- [CONTRIBUTING.md](./CONTRIBUTING.md) — Migrations and enterprise modules
