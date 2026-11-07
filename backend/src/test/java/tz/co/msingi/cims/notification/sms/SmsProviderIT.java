package tz.co.msingi.cims.notification.sms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.msingi.cims.console.auth.PlatformUser;
import tz.co.msingi.cims.console.auth.PlatformUserRepository;
import tz.co.msingi.cims.notification.SmsGateway;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.support.AdmissionScenario;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real SMS through Msingi's provider account: set up and tested in the console (secrets never shown), each institution's sender
 * name asked for on its Messaging page and approved by Msingi (Msingi's name used until then), messages sent with the approved
 * name, and delivery reports applied whether the provider posts them to the platform address or CIMS asks for them.
 */
@IntegrationTest
class SmsProviderIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired PlatformUserRepository platformUsers;
    @Autowired PasswordEncoder encoder;
    @Autowired SmsProviderService providers;
    @Autowired tz.co.msingi.cims.notification.MessagingRepositories.MessageRepository messageRepo;

    TestData.Institution inst;
    AdmissionScenario s;
    String admin, platform, suffix;
    SmsStandIn provider;

    @BeforeEach
    void setUp() throws Exception {
        provider = new SmsStandIn();
        suffix = UUID.randomUUID().toString().substring(0, 6);
        String tenant = "S" + suffix.toUpperCase();
        inst = data.institution(tenant, BigDecimal.ZERO);
        data.staff(inst.tenant(), "admin", Role.INSTITUTION_ADMIN);
        s = new AdmissionScenario(mvc, json, tenant, inst);
        admin = s.staffToken("admin");
        tx.executeWithoutResult(x -> {
            PlatformUser u = new PlatformUser();
            u.setUsername("ops" + suffix);
            u.setFullName("Operations " + suffix);
            u.setEmail("ops" + suffix + "@msingi.co.tz");
            u.setPasswordHash(encoder.encode("Ops@2026x"));
            u.setMustChangePassword(false);
            platformUsers.save(u);
        });
        platform = s.body(mvc.perform(post("/api/platform/auth/login").contentType("application/json")
                .content("{\"username\":\"ops" + suffix + "\",\"password\":\"Ops@2026x\"}"))).get("accessToken").asText();
    }

    @AfterEach
    void tearDown() {
        provider.close();
        jdbc.update("update platform_sms_settings set active = false, provider = null, settings = null, secrets_enc = null, base_url = null,"
                + " default_sender = 'MSINGI' where id = 1");
    }

    ResultActions p(MockHttpServletRequestBuilder req, String body) throws Exception {
        req.header("Authorization", "Bearer " + platform);
        if (body != null) req.contentType("application/json").content(body);
        return mvc.perform(req);
    }

    <T> T db(Supplier<T> work) {
        return TenantContext.callAs(inst.info(), () -> tx.execute(x -> work.get()));
    }

    String account(boolean active, String password) {
        return "{\"provider\":\"NEXTSMS\",\"environment\":\"SANDBOX\",\"active\":" + active + ",\"fields\":{"
                + (password == null ? "" : "\"apiToken\":\"" + password + "\"") + "},\"baseUrl\":\"" + provider.base + "\",\"defaultSender\":\"MSINGI\"}";
    }

    @Test
    void msingiSendsThroughItsProviderUnderEachInstitutionsApprovedName() throws Exception {
        // ---- the provider account, in the console only
        s.call(get("/api/platform/sms/provider"), admin, null).andExpect(res -> assertThat(res.getResponse().getStatus()).isIn(401, 403));
        p(get("/api/platform/sms/provider"), null).andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("simulated"));
        p(put("/api/platform/sms/provider"), account(true, null)).andExpect(jsonPath("$.code").value("SMS_PROVIDER_INCOMPLETE"));
        p(put("/api/platform/sms/provider"), account(true, "pw-secret").replace("\"MSINGI\"", "\"MS INGI\"")).andExpect(status().isBadRequest());
        JsonNode v = s.body(p(put("/api/platform/sms/provider"), account(true, "pw-secret")).andExpect(status().isOk()));
        assertThat(v.toString()).doesNotContain("pw-secret");
        assertThat(v.get("fields").get(0).get("set").asBoolean()).isTrue();
        String notify = v.get("notifyUrl").asText();
        assertThat(notify).startsWith("https://app.cims.ac.tz/api/integrations/sms/nextsms/");
        // saving again without retyping the password keeps it
        p(put("/api/platform/sms/provider"), account(true, null)).andExpect(status().isOk());

        provider.answers.put("/api/v2/balance", "{\"sms_balance\":4520}");
        p(post("/api/platform/sms/provider/test"), null).andExpect(jsonPath("$.lastTestOk").value(true))
                .andExpect(jsonPath("$.lastTestNote").value("NextSMS accepted the credentials. Balance: 4520 SMS."));
        assertThat(provider.last()).contains("Authorization=Bearer pw-secret");
        provider.answers.put("/api/sms/v2/test/text/single", "{\"messages\":[{\"status\":{\"groupName\":\"PENDING\"},\"messageId\":\"NX-T1\"}]}");
        p(post("/api/platform/sms/provider/test-sms"), "{\"phone\":\"0712 345 678\"}").andExpect(jsonPath("$.lastTestOk").value(true));
        assertThat(provider.last()).contains("/api/sms/v2/test/text/single", "\"from\":\"MSINGI\"", "\"to\":\"255712345678\"");

        // ---- the institution asks for its sender name; Msingi's name is used until it is approved
        s.call(put("/api/messaging/settings"), admin, "{\"defaultLanguage\":\"EN\",\"senderId\":\"BAHARI 1\",\"pricePerSms\":25,\"lowBalanceWarning\":100}")
                .andExpect(status().isBadRequest());
        s.call(put("/api/messaging/settings"), admin, "{\"defaultLanguage\":\"EN\",\"senderId\":\"BAHARI\",\"pricePerSms\":25,\"lowBalanceWarning\":100}")
                .andExpect(jsonPath("$.senderStatus").value("REQUESTED")).andExpect(jsonPath("$.effectiveSender").value("MSINGI"));
        JsonNode names = s.body(p(get("/api/platform/sms/sender-names"), null));
        JsonNode mine = null;
        for (JsonNode n : names) if (n.get("tenantId").asLong() == inst.tenant().getId()) mine = n;
        assertThat(mine).isNotNull();
        assertThat(mine.get("senderId").asText()).isEqualTo("BAHARI");
        long id = mine.get("id").asLong();
        p(put("/api/platform/sms/sender-names/" + id), "{\"status\":\"REJECTED\"}").andExpect(jsonPath("$.code").value("SENDER_NAME_REASON"));
        p(put("/api/platform/sms/sender-names/" + id), "{\"status\":\"SUBMITTED\",\"note\":\"Sent to NextSMS with the letter\"}")
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
        p(put("/api/platform/sms/sender-names/" + id), "{\"status\":\"APPROVED\"}").andExpect(jsonPath("$.status").value("APPROVED"));
        s.call(get("/api/messaging/overview"), admin, null).andExpect(jsonPath("$.senderStatus").value("APPROVED"))
                .andExpect(jsonPath("$.effectiveSender").value("BAHARI"));

        // ---- a message goes out under the approved name; the provider's id is kept for its reports
        long messageId = db(() -> jdbc.queryForObject("""
                insert into messages (tenant_id, phone, kind, language, body, parts, encoding, status, queued_at)
                values (cims_current_tenant(), '255712000111', 'EVENT', 'EN', 'Karibu', 1, 'GSM7', 'QUEUED', now()) returning id""", Long.class));
        ProviderSmsGateway gateway = new ProviderSmsGateway(providers, jdbc);
        provider.answers.put("/api/sms/v2/test/text/single", "{\"messages\":[{\"status\":{\"groupName\":\"PENDING\"},\"messageId\":\"NX-" + messageId + "\"}]}");
        SmsGateway.Result r = db(() -> gateway.send("255712000111", "Karibu", "BAHARI", inst.tenant().getId() + "-" + messageId));
        assertThat(r.accepted()).isTrue();
        assertThat(provider.last()).contains("\"from\":\"BAHARI\"", "\"reference\":\"" + inst.tenant().getId() + "-" + messageId + "\"");
        db(() -> jdbc.update("update messages set status = 'SENT', provider_ref = ?, sent_at = now() where id = ?", "NX-" + messageId, messageId));

        // ---- the provider posts the report to the platform address: wrong secret refused, right one applied
        String report = "{\"results\":[{\"messageId\":\"NX-" + messageId + "\",\"to\":\"255712000111\",\"status\":{\"groupName\":\"DELIVERED\"}}]}";
        mvc.perform(post("/api/integrations/sms/nextsms/wrong-token").contentType("application/json").content(report)).andExpect(status().isUnauthorized());
        String path = notify.substring(notify.indexOf("/api/"));
        mvc.perform(post(path).contentType("application/json").content(report)).andExpect(status().isOk()).andExpect(jsonPath("$.applied").value(1));
        assertThat(db(() -> jdbc.queryForObject("select status from messages where id = ?", String.class, messageId))).isEqualTo("DELIVERED");

        // ---- or CIMS asks: an undelivered message
        provider.answers.put("/api/v2/reports", "{\"results\":[{\"messageId\":\"NX-9\",\"status\":{\"groupName\":\"UNDELIVERABLE\",\"description\":\"Absent subscriber\"}}]}");
        assertThat(db(() -> gateway.status("NX-9", "255712000111"))).get().satisfies(rep -> {
            assertThat(rep.status()).isEqualTo("FAILED");
            assertThat(rep.error()).isEqualTo("Absent subscriber");
        });

        // ---- a new name is asked for again: the approved one is withdrawn and Msingi's name is used until the new one is approved
        s.call(put("/api/messaging/settings"), admin, "{\"defaultLanguage\":\"EN\",\"senderId\":\"BIHAS\",\"pricePerSms\":25,\"lowBalanceWarning\":100}")
                .andExpect(jsonPath("$.senderStatus").value("REQUESTED")).andExpect(jsonPath("$.effectiveSender").value("MSINGI"));
        p(put("/api/platform/sms/sender-names/" + id), "{\"status\":\"APPROVED\"}").andExpect(jsonPath("$.code").value("SENDER_NAME_WITHDRAWN"));

        // ---- switched off: messages are refused (and retried) rather than lost
        p(put("/api/platform/sms/provider"), account(false, null)).andExpect(jsonPath("$.active").value(false));
        assertThat(db(() -> gateway.send("255712000111", "x", "BIHAS", inst.tenant().getId() + "-1")).error())
                .isEqualTo("No SMS provider is switched on in the Msingi console");
    }

    @Autowired SmsBalanceJob balanceJob;

    @Test
    void msingisProviderBalanceIsWatched() throws Exception {
        p(put("/api/platform/sms/provider"), account(true, "pw-secret")).andExpect(status().isOk());
        assertThat(balanceJob.runNow()).isNull();   // simulated: nothing to watch
        org.springframework.test.util.ReflectionTestUtils.setField((Object) org.springframework.test.util.AopTestUtils.<SmsProviderService>getTargetObject(providers), "mode", "live");
        try {
            provider.answers.put("/api/v2/balance", "{\"sms_balance\":\"3,200\"}");
            assertThat(balanceJob.runNow()).isEqualTo(3200L);
            assertThat(jdbc.queryForObject("select low_alert_at is not null from platform_sms_settings where id = 1", Boolean.class)).isTrue();
            p(get("/api/platform/sms/provider"), null).andExpect(jsonPath("$.balance").value(3200)).andExpect(jsonPath("$.outstandingUnits").isNumber());
            provider.answers.put("/api/v2/balance", "{\"sms_balance\":900000000}");
            assertThat(balanceJob.runNow()).isEqualTo(900000000L);
            assertThat(jdbc.queryForObject("select low_alert_at is null from platform_sms_settings where id = 1", Boolean.class)).isTrue();
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField((Object) org.springframework.test.util.AopTestUtils.<SmsProviderService>getTargetObject(providers), "mode", "simulated");
            jdbc.update("update platform_sms_settings set last_balance = null, last_balance_at = null, outstanding_units = null, low_alert_at = null where id = 1");
        }
    }

    @Test
    void simulatedMessagesCostNothingOnAServerAndEmailCanBeTested() throws Exception {
        db(() -> jdbc.update("""
                insert into messages (tenant_id, phone, kind, language, body, parts, encoding, status, queued_at, sent_at, provider_ref, charged_parts)
                values (cims_current_tenant(), '255712000111', 'EVENT', 'EN', 'a', 2, 'GSM7', 'DELIVERED', now(), now(), 'SIM-abc', 2),
                       (cims_current_tenant(), '255712000112', 'EVENT', 'EN', 'b', 1, 'GSM7', 'DELIVERED', now(), now(), 'NX-1', 1)"""));
        assertThat(db(() -> messageRepo.usedParts())).isEqualTo(3);
        assertThat(db(() -> messageRepo.usedPartsLive())).isEqualTo(1);   // what a server counts (cims.sms.charge-simulated=false)

        p(get("/api/platform/email"), null).andExpect(jsonPath("$.mode").value("simulated")).andExpect(jsonPath("$.from").isNotEmpty());
        p(post("/api/platform/email/test"), "{\"to\":\"not-an-email\"}").andExpect(status().isBadRequest());
        p(post("/api/platform/email/test"), "{\"to\":\"ops@msingi.co.tz\"}").andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.note").value(org.hamcrest.Matchers.startsWith("Written to the server log only")));
    }
}
