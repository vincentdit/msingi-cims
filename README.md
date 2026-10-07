# CIMS — College/Institution Management System

**Msingi Technologies Ltd** · One platform from application to graduation for NACTVET‑registered institutions.

This repository holds Release 1 build slices (**Foundation, Online Admission, Selection, NACTVET verification and offers, Admission letters, Registration hand-over, Fees, GePG reconciliation**), as specified in `docs/` (Documents 01–07).

| Area | Delivered in this slice |
|---|---|
| Platform | Multi-tenant (one tenant per institution), tenant resolved from host name or `X-Tenant` header, **PostgreSQL row-level security**, JWT security (local issuer, Keycloak-ready), role-based access, append-only audit trail, per-institution number sequences, RFC 7807 errors, OpenAPI docs |
| Academic structure | Academic years, intakes, departments, NVA/NTA programmes (levels validated) |
| Admissions | Admission windows with capacity and **NACTVET minimum entry rules (JSON rule engine)**, applicant sign-up with SMS OTP, password reset, NECTA results lookup, prior NTA/NVA awards, profile, application, eligibility per programme with reasons, choices, submission with reference number |
| Selection (BP-03) | Merit ranking, stable allocation of places across applicants' choices, Admissions Committee review (exclusions with reasons) and approval with segregation of duties, statistics, CSV for the committee pack |
| NACTVET verification and offers (BP-04) | Pre-validation, submission by API or NACTVET-template file, outcome fetch or file import (all-or-nothing), query resolution and resubmission, offers with a deadline, acceptance with NACTVET confirmation code for multiple admissions (attempt-limited), decline, automatic lapse, released places re-filled by the next round |
| Admission letters (FR-ADM-13, BP-16) | Joining instructions per window; PDF letter + joining instructions issued automatically on acceptance (or in bulk), QR code and 16-character verification code, public `/verify` page (rate-limited, minimal personal data), corrected editions (old code verifies as superseded), revocation, frozen snapshot so reprints match what was issued |
| Registration hand-over (BP-05, FR-REG-01/02) | Accepted applicants become students without re-entering data; registration numbers from a configurable pattern (never changed, never reused — enforced by the database); student account sign-in; first registration at reporting (originals checklist from the joining instructions, Registry approval → ACTIVE); student register with search, statistics and class-list CSV |
| Fees (BP-06, FR-FIN-01 to 04, 06, 12) | Fee items (GFS codes, allocation priority); fee structures per programme and year of study with local/foreign amounts and instalments, prepared by the Accountant and approved by the Bursar (segregation of duties), versioned; annual invoice with control number on offer acceptance, billing runs (duplicate-safe), invoice moves to the student at hand-over; payments allocated to items in order, overpayment held as credit; clearance rules (registration enforced at first registration); statements, collections and debtor ageing with CSV |
| GePG reconciliation (FR-FIN-05, BP-06 steps 6–7) | Daily import of GePG's reconciliation file (CSV, all-or-nothing validation), matched by GePG receipt number; exceptions: not received by CIMS (posted from the file with receipt and allocation), not in GePG's file (settles itself if a later file has it), amount differs, repeated line, unknown bill; each exception explained with name and time; day closed only with no open exceptions; month view and the Bursar's monthly sign-off once every day with collections is closed; CIMS payments of a day downloadable in GePG's layout |
| Finance | Itemised invoices, **GePG control numbers**, signed payment callback (idempotent), receipts, fee-paid event → application |
| Portal (React) | Branded, bilingual (English/Kiswahili), mobile-first admission portal and a staff back-office (dashboard, applications, windows, programmes) |

NECTA, NACTVET, GePG and SMS are **simulated** in development (see *What is simulated*).

---

## Project layout

```
backend/    Spring Boot 3.3 · Java 21 · PostgreSQL 16 · Flyway
  src/main/java/tz/co/msingi/cims/
    platform/      tenancy, security, audit, numbering
    academic/      years, intakes, departments, programmes
    admission/     windows, applicants, applications, eligibility engine
    registry/      students, registration numbers, hand-over, first registration
    finance/       fees, billing, invoices, control numbers, payments, GePG, clearance, reports
    integration/   NECTA client
    notification/  SMS
    dev/           demo data seeder (dev profile only)
  src/main/resources/db/migration/   V1–V11 (V5 = row-level security, V6 = selection, V7 = NACTVET verification and offers, V8 = admission letters, V9 = students, V10 = fees, V11 = GePG reconciliation)
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
| Staff — registry officer | `registry` / `Registry@2026` |
| Staff — bursar / accountant | `bursar` / `Bursar@2026`, `accountant` / `Accountant@2026` |
| Open window | 2027/2028 September Intake, fee TZS 10,000, four programmes |
| Staff back-office | http://localhost:5173/staff |

**Simulated NECTA index numbers** (any year):

| Index | Result |
|---|---|
| `S0101/0001` | Strong results — eligible for all demo programmes |
| `S0101/0002` | Weak results — not eligible for NTA programmes |
| `S0101/0000` | Not found |
| anything else in `S1234/5678` form | Deterministic random results |

**Simulated NACTVET outcomes** — decided by the last digit of the applicant's CSEE index number:

| Last digit | Outcome |
|---|---|
| 7 | Queried (name does not match NECTA); verified once the query is resolved and resubmitted |
| 9 | Not verified (does not meet minimum entry requirements) |
| 3 | Verified, also admitted by another institution: the applicant must enter NACTVET's confirmation code to accept |
| any other | Verified |

In the dev profile the SMS verification code and the NACTVET confirmation code are also shown on screen, and a **Simulate payment** button pays the GePG control number.

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
- `VerificationIT`, `SimulatedNactvetGatewayTest`, `CsvTest` — NACTVET submission by API and file, outcomes, queries, offers, multiple-admission confirmation and lock-out, lapse, released places, access rules;
- `LetterIT`, `AdmissionLetterRendererTest`, `RateLimiterTest` — letter issued on acceptance, PDF content and QR code decoded from the rendered page, public verification, reissue/revoke, access rules, escaping;
- `RegistryIT`, `RegistrationNumberPatternTest` — hand-over, numbering patterns, numbers skipped when already taken, database refusal to change or delete, first registration, class list, isolation;
- `FeesIT`, `PaymentAllocatorTest` — Bursar approval and versions, billing on acceptance and in runs, foreign rates, allocation and credit, registration clearance, reports, privacy;
- `ReconciliationIT` — every exception kind, posting a missed payment, close and re-import rules, a payment matched on a later day, monthly sign-off, invalid files, roles and isolation;
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
| `CIMS_NACTVET_MODE` | `simulated` | NACTVET admission verification adapter |
| `CIMS_OFFER_EXPIRY_CRON` | `0 5 * * * *` | When expired offers are closed (`-` disables) |
| `CIMS_VERIFICATION_URL` | `https://{host}/verify/{code}` (dev: `http://localhost:5173/verify/{code}`) | Link encoded in letter QR codes; `{host}` is the institution's primary domain |

Behind a reverse proxy, set `server.forward-headers-strategy=native` (or `framework`) so the public verification rate limit applies per real client address.
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
| GePG | `SimulatedGepgGateway`, HMAC-signed callback; daily reconciliation by file import | Live adapter: signed bill submission and RSA signature verification per the GePG specification; fetching the reconciliation file through GePG's API on a schedule |
| SMS | `LoggingSmsSender` (log only) | SMS aggregator adapter with delivery reports |
| NACTVET | `SimulatedNactvetGateway` (outcomes by index number) | Live NACTVET admission verification API adapter |
| Letters | HTML template (`templates/admission-letter.html`) → PDF | Per-institution letter templates and logo upload; email delivery |
| Reconciliation | Daily GePG file import, exception handling, day close and Bursar sign-off (FR-FIN-05) | Bank statement reconciliation; instalment plans, waivers, sponsors and refunds (R3) |
| Registration | Hand-over and first registration | Semester and module registration with financial clearance (needs curricula and fees), NACTVET student registration (FR-REG-07), ID cards, status changes (BP-14) |
| Identity | Local JWT issuer | Keycloak realm per institution, MFA for privileged roles |

---

© 2026 Msingi Technologies Ltd. All rights reserved.
