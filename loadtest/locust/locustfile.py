"""CIMS load test (NFR-PER-01/02) with Locust: https://locust.io — the same mix as loadtest/k6/cims.js.

    pip install locust
    locust -f loadtest/locust/locustfile.py --host http://localhost:8085            # web UI on http://localhost:8089
    locust -f loadtest/locust/locustfile.py --host http://localhost:8085 --headless -u 50 -r 10 -t 2m --csv results

Settings by environment variable: TENANT (DEMO), STAFF_USER / STAFF_PASSWORD (admin / Admin@2026),
STUDENT_USER / STUDENT_PASSWORD (students are left out when not given).
"""
import os
import random

from locust import HttpUser, between, events, task

TENANT = os.getenv("TENANT", "DEMO")
STAFF = {"username": os.getenv("STAFF_USER", "admin"), "password": os.getenv("STAFF_PASSWORD", "Admin@2026")}
STUDENT = ({"registrationNo": os.getenv("STUDENT_USER"), "password": os.getenv("STUDENT_PASSWORD")}
           if os.getenv("STUDENT_USER") else None)


class Visitor(HttpUser):
    """Applicants and visitors on the public admission portal."""
    weight = 7 if STUDENT else 8
    wait_time = between(2, 6)

    def on_start(self):
        self.client.headers["X-Tenant"] = TENANT

    @task
    def portal(self):
        self.client.get("/api/public/institution", name="public institution")
        self.client.get("/api/public/windows", name="public windows")
        self.client.get("/api/public/windows/upcoming", name="public upcoming windows")


class Staff(HttpUser):
    """Staff signed in once, working dashboards and lists."""
    weight = 2
    wait_time = between(3, 10)

    def on_start(self):
        self.client.headers["X-Tenant"] = TENANT
        r = self.client.post("/api/auth/login", json=STAFF, name="staff sign-in")
        if r.status_code == 200:
            self.client.headers["Authorization"] = "Bearer " + r.json()["accessToken"]

    @task(3)
    def dashboard(self):
        self.client.get("/api/dashboard", name="staff dashboard")

    @task(3)
    def students(self):
        self.client.get(f"/api/registry/students?page={random.randint(0, 3)}&size=25", name="staff student list")

    @task(2)
    def windows(self):
        self.client.get("/api/admission/windows", name="staff admission windows")

    @task(1)
    def overview(self):
        self.client.get("/api/dashboard/overview", name="principal overview")


if STUDENT:
    class Student(HttpUser):
        weight = 1
        wait_time = between(4, 12)

        def on_start(self):
            self.client.headers["X-Tenant"] = TENANT
            r = self.client.post("/api/student-auth/login", json=STUDENT, name="student sign-in")
            if r.status_code == 200:
                self.client.headers["Authorization"] = "Bearer " + r.json()["accessToken"]

        @task
        def portal(self):
            self.client.get("/api/student/dashboard", name="student dashboard")
            self.client.get("/api/student/account", name="student account")
            self.client.get("/api/student/notices", name="student notices")


@events.quitting.add_listener
def thresholds(environment, **_):
    """Fail the run (exit code 1) when NFR-PER-01 is not met."""
    s = environment.stats.total
    if s.num_requests == 0:
        return
    p95, p99, errors = s.get_response_time_percentile(0.95), s.get_response_time_percentile(0.99), s.fail_ratio
    ok = p95 <= 2000 and p99 <= 4000 and errors < 0.01
    print(f"NFR-PER-01: p95 {p95:.0f} ms (≤ 2000), p99 {p99:.0f} ms (≤ 4000), errors {errors:.2%} (< 1%) → {'MET' if ok else 'NOT MET'}")
    if not ok:
        environment.process_exit_code = 1
