# Load tests (NFR-PER-01/02/03)

Two equivalent scripts with the same mix of users: 70–80 % applicants and visitors on the public portal, 20 % staff
working dashboards and lists, 10 % students (when a student account is given). Each virtual user signs in once and stays
signed in, as people do.

| Requirement | Target | How it is checked |
|---|---|---|
| NFR-PER-01 | 95 % of requests ≤ 2 s, 99 % ≤ 4 s, normal load | thresholds in both scripts (the run fails when missed) |
| NFR-PER-02 | 2,000 concurrent users per large institution, at most 50 % slower | k6 `peak` profile (3 s / 6 s thresholds) |
| NFR-PER-03 | 5,000 invoices < 10 min; GPA for 5,000 students < 5 min | timed billing run and result computation on staging data (below) |

## k6

```bash
k6 run -e BASE_URL=https://demo.staging.cims.co.tz -e TENANT=DEMO -e PROFILE=smoke loadtest/k6/cims.js
k6 run -e BASE_URL=… -e PROFILE=peak -e STAFF_USER=… -e STAFF_PASSWORD=… -e STUDENT_USER=… -e STUDENT_PASSWORD=… loadtest/k6/cims.js
```

Profiles: `smoke` (10 users, 1 min), `normal` (200 users, 10 min), `peak` (ramp to 2,000 users, 22 min). The GitHub
workflow **Load test** runs it by hand against staging. Run `peak` from a machine outside the server's network (or k6
Cloud), never against production during working hours.

## Locust

```bash
pip install locust
locust -f loadtest/locust/locustfile.py --host http://localhost:8085                     # web UI on :8089
locust -f loadtest/locust/locustfile.py --host http://localhost:8085 --headless -u 100 -r 20 -t 2m --csv results
```

The run prints whether NFR-PER-01 was met and exits with code 1 when it was not.

## Results so far

| Date | Where | Users | Requests | Errors | p50 | p95 | p99 | Note |
|---|---|---|---|---|---|---|---|---|
| 2026-10-14 | development container (backend, PostgreSQL and Locust on one shared machine), demo data | 100 | 7,380 in 2 min (61/s) | 0 | 9 ms | 37 ms | 1.0 s | NFR-PER-01 met. Staff sign-in takes 1.4 s at the median by design (bcrypt). |

The first run found that the same account signing in twice at the same moment made one sign-in fail (409, optimistic
lock on the last-login time); sign-in now records that time without the lock.

These numbers are from a development machine, not the production server. The `normal` and `peak` profiles are run on
the staging server before go-live and recorded here.

## Batch work (NFR-PER-03)

On staging, with 5,000 students imported (Data import → Students) in one programme and year, an approved fee structure
for that year, and marks entered for one semester:

1. **Invoices**: Fees → Billing run for the year; the run reports how many invoices it created. Time it from the request
   to the answer (`curl -w '%{time_total}'` on `POST /api/finance/billing-runs`). Target: under 10 minutes.
2. **GPA**: Results → Compute the semester's result sheet (`POST /api/exams/sheets/compute`). Target: under 5 minutes.

Record both times above.
