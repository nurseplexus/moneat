# Moneat Local Development Guide

Run Moneat on your machine with **hot-reload** for backend and frontend development. Database services run in Docker; the Kotlin API and React dashboard run directly on your host.

For production or server deployment (Docker-only, no Java/Node required), see [DEPLOYMENT.md](./DEPLOYMENT.md).

---

## Architecture (Local Dev)

| Component | Role | How you run it locally |
|-----------|------|------------------------|
| **PostgreSQL** | Relational metadata (users, orgs, projects, API keys, rules, subscriptions) | Docker (`localhost:5499`) |
| **ClickHouse** | High-volume time-series data (events, issues, sessions, traces, logs, spans) | Docker (`localhost:8123` HTTP, `9000` native) |
| **Redis** | Caching, rate limiting, background job queues | Docker (`localhost:6379`) |
| **Backend (Kotlin/Ktor)** | Ingestion (`/api/{projectId}/envelope`, `/v1/*/otlp`) and dashboard API (`/v1/*`) | Host (`./gradlew run` → `http://localhost:8080`) |
| **Dashboard (React/Vite)** | Web UI | Host (`npm run dev` → `http://localhost:3000`) |

### Which databases do you need?

For local development you **must** run all three data stores:

1. **PostgreSQL** — required. The backend will not start without it. Schema is applied via Flyway migrations on startup (`backend/src/main/resources/db/migration/`).
2. **ClickHouse** — required. Stores ingested telemetry and analytics. Schema is applied via versioned migrations on startup (`backend/src/main/resources/db/clickhouse_migration/`).
3. **Redis** — required. Used for cache, rate limits, and async job scheduling.

You do **not** need to install PostgreSQL, ClickHouse, or Redis on the host — only the Docker containers from this repo's `docker-compose.yml`.

---

## Prerequisites

| Requirement | Version | Notes |
|-------------|---------|-------|
| **Git** | any recent | Clone the repository |
| **Docker** & **Docker Compose v2** | Docker 20.10+, Compose v2 | Runs database containers |
| **Java (JDK)** | 17+ | Backend build and `./gradlew run` |
| **Node.js** | 18+ | Dashboard dev server |
| **OpenSSL** | optional locally | Only needed if you generate custom secrets |

You do **not** need Java or Node.js for a container-only deployment — see [DEPLOYMENT.md](./DEPLOYMENT.md).

---

## 1. Clone the Repository

Local development always starts from a clone:

```bash
git clone https://github.com/moneat-io/moneat.git
cd moneat
```

Use your fork or branch if you are contributing changes.

---

## 2. Configure Environment

Copy the example env file at the repo root:

```bash
cp .env.example .env
```

For local dev, the defaults are intentional:

- `DATABASE_PASSWORD`, `CLICKHOUSE_PASSWORD`, `REDIS_PASSWORD` → `moneat_dev_password` (matches Docker Compose)
- `DATABASE_URL` → `jdbc:postgresql://localhost:5499/moneat`
- `CLICKHOUSE_URL` → `http://localhost:8123`
- `REDIS_URL` → `redis://:moneat_dev_password@localhost:6379`
- `FRONTEND_URL` → `http://localhost:3000`
- `BACKEND_URL` → `http://localhost:8080`
- `SELF_HOSTED` → `false` (localhost URLs are fine for dev)

The backend reads this root `.env` via `EnvConfig`. Keep `SELF_HOSTED=false` unless you are testing self-host-specific behavior (first-user auto-admin, telemetry, etc.).

---

## 3. Start Database Services

From the repo root, start **only** the data layer:

```bash
docker compose up -d postgres clickhouse redis
```

Verify health:

```bash
docker compose ps
```

Expected ports:

| Service | Host port | Container |
|---------|-----------|-----------|
| PostgreSQL | 5499 | `moneat-postgres` |
| ClickHouse HTTP | 8123 | `moneat-clickhouse` |
| ClickHouse native | 9000 | `moneat-clickhouse` |
| Redis | 6379 | `moneat-redis` |

Init scripts are mounted from the repo:

- `backend/src/main/resources/db/init.sql` → PostgreSQL
- `backend/src/main/resources/db/clickhouse_init.sql` → ClickHouse

---

## 4. Run the Backend

```bash
cd backend
./gradlew run
```

API available at **http://localhost:8080**. Health check: `http://localhost:8080/health`.

PostgreSQL and ClickHouse migrations run automatically on startup.

### Backend commands

```bash
./gradlew test              # Unit and integration tests (uses H2, not Docker DBs)
./gradlew test --tests com.moneat.services.EventServiceTest   # Single test class
./gradlew detekt            # Lint / static analysis
./gradlew detektFormat      # Auto-format
./gradlew seedE2EData       # Seed data for E2E apps (requires running infrastructure)
```

---

## 5. Run the Dashboard

In a second terminal:

```bash
cd dashboard
npm install
npm run dev
```

UI available at **http://localhost:3000**.

```bash
npm run lint    # ESLint
npm run build   # Production build check
```

The dev server uses `FRONTEND_URL` / API client config pointing at `http://localhost:8080`.

---

## Optional: Full Stack in Docker (No Hot-Reload)

To run the pre-built backend and frontend images locally (closer to production, no Java/Node on host):

```bash
cp .env.example .env
# Set JWT_SECRET, DATA_SOURCE_ENCRYPTION_KEY, and passwords before production-like use
docker compose up -d
```

This starts all five services. Use this to smoke-test images; use the database-only + host apps workflow above for day-to-day development.

---

## Optional: E2E Test Apps

The `e2e/` directory contains Android and KMP apps that send real errors to a local Moneat instance:

```bash
cd e2e
./seed-data.sh      # Creates test users, projects, DSNs
./run-android.sh    # Android test app
./run-kmp.sh        # KMP test app
```

Requires infrastructure running (`docker compose up -d postgres clickhouse redis`) and the backend at `localhost:8080`.

---

## Optional: Enterprise Features Locally

Enterprise modules (SAML SSO, On-Call) live under `ee/` and are compiled into the backend. They activate at runtime when `MONEAT_LICENSE_KEY` is set. OIDC SSO is part of the open core and does not require a license.

See [CONTRIBUTING.md](./CONTRIBUTING.md#working-on-enterprise-features) for details.

---

## Optional: Datadog Agent Profile

An optional Datadog Agent sidecar is available for testing Datadog compatibility:

```bash
docker compose --profile datadog up -d
```

Set `DD_API_KEY` in `.env`. This is not required for core development.

---

## Troubleshooting

| Issue | What to check |
|-------|----------------|
| Backend fails on startup | `.env` exists at repo root; Postgres/ClickHouse/Redis containers are healthy |
| Connection refused to DB | `docker compose ps` — ports 5499, 8123, 6379 listening |
| CORS errors from browser apps | Add client origins to `ALLOWED_ORIGINS` in `.env` |
| ClickHouse OOM / slow queries | Allocate at least 4 GB RAM if ingesting heavy replay or profile payloads |

View database logs:

```bash
docker compose logs -f postgres
docker compose logs -f clickhouse
docker compose logs -f redis
```

Stop infrastructure:

```bash
docker compose down
```

Data persists in Docker volumes (`postgres_data`, `clickhouse_data`, `redis_data`) until you remove them with `docker compose down -v`.

---

## Related Documentation

- [DEPLOYMENT.md](./DEPLOYMENT.md) — Production and server deployment
- [AGENTS.md](./AGENTS.md) — Architecture, conventions, and agent instructions
- [CONTRIBUTING.md](./CONTRIBUTING.md) — PR workflow and coding standards
