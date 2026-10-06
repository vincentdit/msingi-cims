# CIMS — College/Institution Management System

**Msingi Technologies Ltd** · One platform from application to graduation for NACTVET‑registered institutions.

This repository holds the first build slice of Release 1 (**Foundation + Online Admission**), as specified in `docs/` (Documents 01–07).

| Area | Delivered in this slice |
|---|---|
| Platform | Multi-tenant (one tenant per institution), tenant resolved from host name or `X-Tenant` header, **PostgreSQL row-level security**, JWT security (local issuer, Keycloak-ready), role-based access, append-only audit trail, per-institution number sequences, RFC 7807 errors, OpenAPI docs |
| Academic structure | Academic years, intakes, departments, NVA/NTA programmes (levels validated) |
| Admissions | Admission windows with capacity and **NACTVET minimum entry rules (JSON rule engine)**, applicant sign-up with SMS OTP, password reset, NECTA results lookup, prior NTA/NVA awards, profile, application, eligibility per programme with reasons, choices, submission with reference number |
| Finance | Itemised invoices, **GePG control numbers**, signed payment callback (idempotent), receipts, fee-paid event → application |
| Portal (React) | Branded, bilingual (English/Kiswahili), mobile-first admission portal and a staff back-office (dashboard, applications, windows, programmes) |

NECTA, GePG and SMS are **simulated** in development (see *What is simulated*).

---

## Project layout

```
backend/    Spring Boot 3.3 · Java 21 · PostgreSQL 16 · Flyway
  src/main/java/tz/co/msingi/cims/
    platform/      tenancy, security, audit, numbering
    academic/      years, intakes, departments, programmes
    admission/     windows, applicants, applications, eligibility engine
    finance/       invoices, control numbers, payments, GePG
    integration/   NECTA client
    notification/  SMS
    dev/           demo data seeder (dev profile only)
  src/main/resources/db/migration/   V1–V6 (V5 = row-level security, V6 = selection)
frontend/   React 18 · TypeScript · Vite · MUI
docs/       Concept Note, BRD, SRS, Business Processes, Database Design, Architecture, Plan & Cost
docker/db-init/  database role and databases for Docker (non-superuser app role)
docker-compose.yml
```

## Prerequisites

- JDK 21 and Maven 3.9+
- Node.js 20+ (22 recommended)
- PostgreSQL 16 (local install or via Docker)

## Quick start with Docker

```bash
docker compose up --build
```

- Admission portal: http://localhost:8081
- API docs (Swagger UI): http://localhost:8085/swagger-ui.html

## Local development

**1. Database** (once). Easiest with Docker — PostgreSQL is published on host port **5434** so it does not clash with other local databases:

```bash
docker compose up -d db
```

On first start, `docker/db-init/01-cims-roles.sql` creates the application role `cims` and the databases `cims` and `cims_test` (for integration tests).

Or with a local PostgreSQL install (then set `CIMS_DB_URL=jdbc:postgresql://localhost:5432/cims`):

```sql
CREATE ROLE cims LOGIN PASSWORD 'cims' NOSUPERUSER NOBYPASSRLS;
CREATE DATABASE cims OWNER cims;
CREATE DATABASE cims_test OWNER cims;        -- for integration tests
```

#### Database role

The application must connect as an **ordinary role that owns the database** — never a superuser and never a role with `BYPASSRLS`. PostgreSQL does not apply row-level security to those roles, so every institution would see every other institution's data. The backend checks this at start-up and refuses to run if the role bypasses RLS. The same rule applies in production: create a dedicated application role; keep the `postgres` superuser for administration.

> **Upgrading a Docker database created before this change** (where `cims` was the superuser): the volume must be recreated. This deletes the local data; the dev profile re-seeds the demo institution.
>
> ```bash
> docker compose down -v
> docker compose up -d db
> ```

**2. Backend** (dev profile seeds a demo institution):

```bash
cd backend
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"     # Windows (PowerShell)
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev            # Linux / macOS
```

The Maven Wrapper (`mvnw`) uses Maven 3.9.15 and reuses it from `~/.m2/wrapper` if already downloaded. The backend listens on **8085** by default (`PORT` to change).

**3. Frontend:**

```bash
cd frontend
npm install
npm run dev          # http://localhost:5173 (proxies /api to 127.0.0.1:8085; override with CIMS_API_URL)
```

### Demo data (dev profile)

| What | Value |
|---|---|
| Institution | Msingi Demo Technical College (`DEMO`), mapped to `localhost` and `127.0.0.1` |
| Staff — administrator / approver | `admin` / `Admin@2026` |
| Staff — admissions officer | `admissions` / `Admissions@2026` |
| Open window | 2027/2028 September Intake, fee TZS 10,000, four programmes |
| Staff back-office | http://localhost:5173/staff |

**Simulated NECTA index numbers** (any year):

| Index | Result |
|---|---|
| `S0101/0001` | Strong results — eligible for all demo programmes |
| `S0101/0002` | Weak results — not eligible for NTA programmes |
| `S0101/0000` | Not found |
| anything else in `S1234/5678` form | Deterministic random results |

In the dev profile the SMS verification code is also shown on screen, and a **Simulate payment** button pays the GePG control number.

## Tests

```bash
cd backend
.\mvnw.cmd test            # unit + integration tests (needs the cims_test database on port 5434)
.\mvnw.cmd test "-DskipITs"  # unit tests only
```

Integration tests run against real PostgreSQL so that row-level security, triggers and constraints are exercised. They include:

- `TenantIsolationIT` — one institution cannot read or write another's rows (repository, native SQL, and API token checks);
- `ApplicantJourneyIT` — register → verify → NECTA → profile → GePG fee → choices → submit;
- `GepgCallbackIT` — signature check and idempotent payment posting;
- `EligibilityEngineTest` — NACTVET entry rule evaluation;
- `SelectionIT`, `SelectionAllocatorTest`, `MeritScorerTest` — merit ranking, stable allocation, committee exclusions and approval;
- `DatabaseRoleGuardTest` — start-up refuses a role that bypasses row-level security.

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `CIMS_DB_URL` / `CIMS_DB_USER` / `CIMS_DB_PASSWORD` | `jdbc:postgresql://localhost:5434/cims`, `cims`, `cims` | Database |
| `CIMS_SECURITY_MODE` | `local` | `local` (CIMS issues tokens) or `keycloak` |
| `CIMS_JWT_PRIVATE_KEY` / `CIMS_JWT_PUBLIC_KEY` | generated at start-up | RSA keys (PEM) for local mode — **set in production** |
| `CIMS_KEYCLOAK_ISSUER_URI` | — | Issuer when `CIMS_SECURITY_MODE=keycloak` |
| `CIMS_ACCESS_TOKEN_TTL` | `60m` | Access token lifetime |
| `PORT` | `8085` | Backend HTTP port |
| `CIMS_TEST_DB_URL` | `jdbc:postgresql://localhost:5434/cims_test` | Test database |
| `CIMS_CORS_ORIGINS` | `http://localhost:5173` | Allowed browser origins |
| `CIMS_GEPG_MODE` / `CIMS_GEPG_CALLBACK_SECRET` | `simulated` / `change-me` | GePG adapter |
| `CIMS_NECTA_MODE` | `simulated` | NECTA adapter |
| `VITE_TENANT` (frontend) | — | Force an institution code in development |
| `CIMS_API_URL` (frontend dev server) | `http://127.0.0.1:8085` | Where the Vite proxy sends `/api` |

## How multi-tenancy works

1. `TenantResolutionFilter` resolves the institution from the host name (`tenant_domains`) or the `X-Tenant` header, before security runs.
2. `TenantAwareJpaTransactionManager` starts every transaction with `set_config('app.tenant_id', …, true)`.
3. Row-level security policies (`V5__row_level_security.sql`) make other institutions' rows invisible and unwritable — including for native SQL.
4. JWTs carry a `tenant` claim; a token from one institution is rejected by another.

Production notes: connect the application with a role that is **not** a superuser; preferably run Flyway with a separate migration role and grant the application role DML only.

## What is simulated (and what comes next)

| Component | Now | Next |
|---|---|---|
| NECTA | `SimulatedNectaClient` | Live NECTA results API adapter |
| GePG | `SimulatedGepgGateway`, HMAC-signed callback | Live adapter: signed bill submission and RSA signature verification per the GePG specification, daily reconciliation |
| SMS | `LoggingSmsSender` (log only) | SMS aggregator adapter with delivery reports |
| NACTVET | — | Verification submission (BP-04), confirmation codes, multiple-admission handling |
| Selection | — | Ranking and Admissions Committee approval (BP-03), admission letters with QR codes |
| Identity | Local JWT issuer | Keycloak realm per institution, MFA for privileged roles |

---

© 2026 Msingi Technologies Ltd. All rights reserved.
