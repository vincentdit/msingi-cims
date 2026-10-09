# CIMS — College/Institution Management System

**Msingi Technologies Ltd** · One platform from application to graduation for NACTVET‑registered institutions.

This repository holds Release 1 build slices (**Foundation, Online Admission, Selection, NACTVET verification and offers, Admission letters, Registration hand-over, Fees, GePG reconciliation, Student portal, Messaging, Reports and dashboards, Curricula and semester registration, Examinations and results**), as specified in `docs/` (Documents 01–07).

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
| Student portal (FR-STU-01/02, FR-REG-08) | Mobile-first, installable student app (`/student`, English/Kiswahili): sign-in with registration number or mobile number; dashboard with fee balance, next payment and control number, registration and examination clearance, latest notices; invoices with control numbers and payment history with **PDF receipts** (amount in words, items paid); notices from staff for all students or a programme/year, published now or scheduled, optional SMS alert, read tracking, withdrawal; requests to correct personal details, approved or rejected by Registry with reasons (name/date-of-birth changes flagged for original documents; phone change also moves the sign-in) |
| Messaging (FR-COM-01 to 03, FR-INT-04) | Every SMS is written to an outbox in the same transaction as the event and handed to the gateway after commit (nothing goes out for work that rolls back), retried up to three times, with delivery reports (signed callback) and a full message log; 18 event messages with built-in English and Kiswahili wording that each institution may reword or switch off, sent in the institution's chosen language; verification codes sent at once and never stored; bulk messages to students (status, programme, year, only those who owe fees), applicants (window, status) or a list of numbers, with personalisation ({firstName}, {balance}, …), a preview of recipients, SMS parts and cost, and a check against the prepaid SMS bundle; delivery per bulk message and resending of failures; typographic punctuation converted so messages stay in the cheaper GSM encoding |
| Reports and dashboards (FR-RPT-01/02, FR-ADM-16) | Role-based dashboard (admissions funnel, daily applications, programme demand against capacity; current students by programme and status; billed/collected/outstanding, 30-day collections, debtor ageing and reconciliation warnings), each section shown only to roles that may see it; standard report catalogue of 27 reports — Admissions (by programme, region and gender, qualification, status, NACTVET verification, acceptance rate, letters issued, applicant list), Registry (enrolment, nominal roll, age and region profiles, first registration, semester registration status, module class list), Finance (collections by item/day/channel, receipts register, debtors and ageing, reconciliation exceptions), Governance (user access review, audit log extract) — filterable by academic year, intake, window, programme, award, level, sex, status, year of study, semester, module and period; on screen, as Excel (frozen header, filters, real numbers and dates) or PDF; lists of named people hidden from management viewers; personal-data views and every export recorded in the audit log; all report SQL runs under row-level security |
| Curricula and semester registration (FR-ACA-02/03/04, FR-REG-03, FR-STU-02) | Module catalogue (code, credits, NTA level, graded or competence-based; code, credits and assessment locked once a module is in an approved curriculum); versioned curricula per programme placing modules in each year and semester as core or elective, with how many electives a student must choose; a draft is checked for empty semesters and unmet elective rules and must be approved by the Deputy Principal (Academic) or administrator who did not prepare it; new versions are copied from the approved one and apply from an intake year, and each student stays on the version they started; academic calendar of semesters with registration, closing and late-registration dates plus key dates (teaching, examinations, results, holidays); students register in the portal (core modules shown, electives chosen, details confirmed, fee clearance checked, late registrations flagged) and may change their electives until Registry approves; Registry approves one by one or all at once (clearance re-checked, uncleared students skipped) or returns a registration with a note; SMS on approval and return |
| Examinations and results (FR-ACA-05, FR-EXM-01/02/03/05/06/07/10/14, BP-08) | Examination regulations per award level, versioned by the academic year they apply from (grading scale with grade points and pass grades, CA/SE weights and component pass marks, GPA decimals and rounding, supplementary and discontinuation thresholds, award classification), seeded with the SRS's indicative NTA 4–5 and NTA 6 values and locked once used; module offerings set up from approved semester registrations, with lecturers allocated; assessment plans (tests, assignments, practicals and the examination, weights checked against the regulations); marks entered on screen or through a validated Excel template (nothing saved unless the whole file is valid), with CA, SE, total, grade and pass/fail computed live and ABS for absence; a marks-entry window set by the Examinations Officer; lecturer → Head of Department moderation (grade distribution and anomaly flags, return with comments; department-scoped HODs) → Department Board (optional); class result sheets per programme and year with GPA, CGPA, proposed decision (pass, supplementary, special exam, incomplete, repeat, discontinue) and provisional classification, each student graded on their own programme's scale; Academic Committee changes with reasons and recommendation; Governing Board confirmation by someone else, after which the database refuses changes to the results; publication to the student portal (Results tab, English/Kiswahili) with SMS, withheld for students without results clearance; reports for module performance, the class result sheet (Excel/PDF) and results by programme |
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
  src/main/resources/db/migration/   V1–V15 (V5 = row-level security, V6 = selection, V7 = NACTVET verification and offers, V8 = admission letters, V9 = students, V10 = fees, V11 = GePG reconciliation, V12 = student portal, V13 = messaging, V14 = curricula, calendar and semester registration, V15 = examinations and results)
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
| Staff — head of department / deputy principal (academic) | `hod` / `Hod@2026` (prepares curricula, moderates marks), `academic` / `Academic@2026` (approves curricula, keeps the calendar, chairs the Academic Committee) |
| Staff — lecturer / examinations officer | `lecturer` / `Lecturer@2026` (enters marks), `exams` / `Exams@2026` (sets up modules, computes and publishes results); `admin` confirms results for the Governing Board |
| Examination regulations | NTA Levels 4–5 and NTA Level 6, from the earliest academic year (indicative values: confirm against your institution's regulations); examination marks entry open on the current demo semester |
| Curricula and calendar | Approved BTCIT and ODIT curricula; 2027/2028 Semester 1 with registration open from two days before the first start, Semester 2 later |
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
- `StudentPortalIT`, `AmountInWordsTest` — student sign-in by registration number, dashboard, notice audiences and scheduling, read tracking, Registry decisions on detail changes, receipts and privacy;
- `MessagingIT`, `SmsTextTest` — templates and language, masked verification codes, no SMS on rollback, bulk audiences and placeholders, bundle balance and charging, gateway refusals and retries, resending, signed delivery reports, isolation;
- `ReportsIT` — catalogue by role, admissions and registry figures, filters and validation, Excel and PDF content, audit of exports, dashboards by role, isolation;
- `CurriculumAndRegistrationIT` — modules and duplicate codes, curriculum checks, approval by someone other than the preparer, locked modules, new versions, calendar dates, the semester registration states and windows, elective rules, fee clearance on submission and approval, approve all, return and resubmit, class-list reports, isolation;
- `GradingTest` — weighting and rounding, D is not a pass, CA/SE pass marks, absent and incomplete, the diploma scale, GPA rounding and truncation, classification, decisions, scale validation;
- `ResultsIT` — regulations checks and lock, set-up and allocation, assessment plans, marks on screen and by Excel (rejected file changes nothing), moderation and return, department approval before computation, GPA, decisions and classification, committee changes with reasons, segregation of duties, database lock, publication with withheld results and SMS, reports, isolation;
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
| SMS | `SimulatedSmsGateway` (log only; 000 = unreachable, 999 = refused), outbox, delivery-report callback | Aggregator adapter (`SmsGateway`) for the chosen provider; email channel |
| NACTVET | `SimulatedNactvetGateway` (outcomes by index number) | Live NACTVET admission verification API adapter |
| Letters | HTML template (`templates/admission-letter.html`) → PDF | Per-institution letter templates and logo upload; email delivery |
| Reconciliation | Daily GePG file import, exception handling, day close and Bursar sign-off (FR-FIN-05) | Bank statement reconciliation; instalment plans, waivers, sponsors and refunds (R3) |
| Registration | Hand-over, first registration, student requests to correct details, semester and module registration with fee clearance | Carry-over and repeat modules (needs results), late-registration fee, NACTVET student registration (FR-REG-07), ID cards, status changes (BP-14) |
| Examinations | NTA marks, moderation, approval, GPA, decisions and publication (BP-08) | Supplementary, special and carry-over examinations with grade caps (FR-EXM-08), NVA competence records (FR-EXM-09), NACTVET results file (FR-EXM-11), post-approval amendments, appeals (FR-EXM-12), statements of results and transcripts (FR-EXM-13), examination eligibility from attendance (FR-EXM-04) |
| Identity | Local JWT issuer | Keycloak realm per institution, MFA for privileged roles |

---

© 2026 Msingi Technologies Ltd. All rights reserved.
