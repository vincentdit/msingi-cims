package tz.co.msingi.cims.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.security.SignInThrottleFilter;
import tz.co.msingi.cims.support.AdmissionScenario;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** NFR-SEC-01: a short lock-out after repeated wrong passwords, a permanent one after three, and a limit per client address. */
@IntegrationTest
class SignInProtectionIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;

    @Test
    void wrongPasswordsLockForAWhileThenUntilUnlocked() throws Exception {
        String tenant = "L" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TestData.Institution inst = data.institution(tenant, BigDecimal.ZERO);
        String user = "clerk" + tenant.toLowerCase();
        data.staff(inst.tenant(), user, Role.REGISTRAR);
        AdmissionScenario s = new AdmissionScenario(mvc, json, tenant, inst);
        java.util.function.Consumer<String> sql = q -> tz.co.msingi.cims.platform.tenancy.TenantContext.runAs(inst.info(),
                () -> tx.executeWithoutResult(x -> jdbc.update(q, user)));
        String admin = s.staffToken("officer");   // the fixture officer is also the administrator
        String wrong = "{\"username\":\"" + user + "\",\"password\":\"Wrong-123\"}";
        String right = "{\"username\":\"" + user + "\",\"password\":\"" + TestData.STAFF_PASSWORD + "\"}";

        for (int i = 0; i < 4; i++) s.call(post("/api/auth/login"), null, wrong).andExpect(status().isUnauthorized());
        s.call(post("/api/auth/login"), null, wrong).andExpect(status().isUnauthorized());   // the fifth: locked for 15 minutes
        s.call(post("/api/auth/login"), null, right).andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Try again in 15 minutes")));
        // the time passes: the right password works again, and the count starts afresh
        sql.accept("update user_accounts set locked_until = now() - interval '1 second' where username = ?");
        s.call(post("/api/auth/login"), null, right).andExpect(status().isOk());
        assertThat(one(inst, "select failed_logins from user_accounts where username = ?", Integer.class, user)).isZero();

        // fifteen wrong in a row (three lock-outs): locked until the administrator unlocks it
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < 5; i++) s.call(post("/api/auth/login"), null, wrong).andExpect(status().isUnauthorized());
            if (round < 2) sql.accept("update user_accounts set locked_until = now() - interval '1 second' where username = ?");
        }
        assertThat(one(inst, "select locked_until from user_accounts where username = ?", java.sql.Timestamp.class, user)).isNull();
        s.call(post("/api/auth/login"), null, right).andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("administrator")));
        Long id = one(inst, "select id from user_accounts where username = ?", Long.class, user);
        s.call(post("/api/admin/users/" + id + "/enable"), admin, null).andExpect(status().isOk());
        s.call(post("/api/auth/login"), null, right).andExpect(status().isOk());
        assertThat(one(inst, "select count(*) from audit_logs where action = 'ACCOUNT_UNLOCKED'", Integer.class)).isGreaterThanOrEqualTo(1);

        // an unknown login gets the same answer as a wrong password
        s.call(post("/api/auth/login"), null, "{\"username\":\"nobody-" + tenant + "\",\"password\":\"x\"}").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid login or password"));
    }

    @Test
    void oneAddressMayTryOnlySoManySignIns() throws Exception {
        Instant[] now = {Instant.parse("2026-11-08T08:00:00Z")};
        Clock clock = new Clock() {
            public ZoneOffset getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId z) { return this; }
            public Instant instant() { return now[0]; }
        };
        SignInThrottleFilter f = new SignInThrottleFilter(3, 5, clock);
        assertThat(attempt(f, "POST", "/api/auth/login", "10.0.0.1")).isEqualTo(200);
        assertThat(attempt(f, "POST", "/api/student-auth/login", "10.0.0.1")).isEqualTo(200);
        assertThat(attempt(f, "POST", "/api/applicant-auth/forgot-password", "10.0.0.1")).isEqualTo(200);
        assertThat(attempt(f, "POST", "/api/auth/login", "10.0.0.1")).isEqualTo(429);        // a fourth in the minute
        assertThat(attempt(f, "POST", "/api/auth/login", "10.0.0.2")).isEqualTo(200);        // another address is not affected
        assertThat(attempt(f, "GET", "/api/auth/me", "10.0.0.1")).isEqualTo(200);            // only sign-in requests count
        assertThat(attempt(f, "POST", "/api/students", "10.0.0.1")).isEqualTo(200);
        assertThat(attempt(f, "POST", "/api/auth/password", "10.0.0.1")).isEqualTo(200);     // a signed-in person changing their password
        now[0] = now[0].plusSeconds(61);
        assertThat(attempt(f, "POST", "/api/platform/auth/login", "10.0.0.1")).isEqualTo(200);   // the fifth in the hour (the refused one counted)
        assertThat(attempt(f, "POST", "/api/auth/mfa/verify", "10.0.0.1")).isEqualTo(429);     // the hour is used up
        MockHttpServletResponse r = new MockHttpServletResponse();
        f.doFilter(request("POST", "/api/auth/login", "10.0.0.1"), r, new MockFilterChain());
        assertThat(r.getContentAsString()).contains("TOO_MANY_REQUESTS").contains("an hour");
        assertThat(r.getHeader("Retry-After")).isEqualTo("3600");
    }

    private <T> T one(TestData.Institution inst, String q, Class<T> type, Object... args) {
        return tz.co.msingi.cims.platform.tenancy.TenantContext.callAs(inst.info(), () -> tx.execute(x -> jdbc.queryForObject(q, type, args)));
    }

    private static int attempt(SignInThrottleFilter f, String method, String path, String ip) throws Exception {
        MockHttpServletResponse r = new MockHttpServletResponse();
        f.doFilter(request(method, path, ip), r, new MockFilterChain());
        return r.getStatus();
    }

    private static MockHttpServletRequest request(String method, String path, String ip) {
        MockHttpServletRequest q = new MockHttpServletRequest(method, path);
        q.setRemoteAddr(ip);
        return q;
    }
}
