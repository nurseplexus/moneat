# Moneat Self-Hosting Overview

Moneat is a self-hostable, Sentry-compatible, OpenTelemetry-compatible observability platform:

- **Backend** — Kotlin/Ktor API
- **Dashboard** — React/Vite frontend
- **PostgreSQL** — relational metadata
- **ClickHouse** — high-volume event and trace analytics
- **Redis** — cache, rate limiting, and job queues

This document is an index. Detailed instructions are split by use case:

| Guide | When to use it |
|-------|----------------|
| **[LOCAL_DEVELOPMENT.md](./LOCAL_DEVELOPMENT.md)** | Developing on your machine — databases in Docker, backend and dashboard on the host with hot-reload. **Requires cloning the repo**, Java 17+, and Node.js 18+. |
| **[DEPLOYMENT.md](./DEPLOYMENT.md)** | Production deployment — requirements, databases, fork + Kotlin changes, custom Docker images, HTTPS, troubleshooting, and checklist. Also covers official-image install (no clone). |

---

## Quick Reference: What Runs Where

### Local development

```bash
git clone https://github.com/moneat-io/moneat.git && cd moneat
cp .env.example .env
docker compose up -d postgres clickhouse redis   # 3 required databases
cd backend && ./gradlew run                       # API :8080
cd dashboard && npm install && npm run dev        # UI :3000
```

### Production (official images, no clone)

```bash
bash <(curl -fsSL https://raw.githubusercontent.com/moneat-io/moneat/main/install.sh)
```

Or manually: download `docker-compose.yml` + `.env`, set secrets, `docker compose up -d`.

### Production (custom code from clone)

```bash
git clone … && cd moneat
cp .env.example .env   # set production secrets
# add docker-compose.override.yml with local build config
docker compose up --build -d
```

See [DEPLOYMENT.md](./DEPLOYMENT.md) for registry-based and on-server build strategies.

---

## Required Data Stores (Both Local and Production)

All three are mandatory — the backend will not run without them:

| Database | Purpose |
|----------|---------|
| **PostgreSQL** | Users, organizations, projects, API keys, alert rules, subscriptions |
| **ClickHouse** | Events, issues, sessions, replays, traces, logs, metrics |
| **Redis** | Caching, rate limits, background queues |

Production also uses the **`backend_storage`** Docker volume for profiling payloads and file assets.

---

## Further Reading

- [README.md](./README.md) — Features and SDK compatibility
- [AGENTS.md](./AGENTS.md) — Architecture and build commands
- [CONTRIBUTING.md](./CONTRIBUTING.md) — Contribution and enterprise module notes
- [.env.example](./.env.example) — Full environment variable reference
