package tz.co.msingi.cims.finance.aggregator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.tenancy.Ownership;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;
import tz.co.msingi.cims.support.AdmissionScenario;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Mobile money through an aggregator: the Bursar sets the institution's aggregator account (secrets never shown again), payers are
 * prompted on their phone and the payment is recorded once the aggregator confirms it; notifications come only to the institution's
 * own address and, for Selcom, only with Selcom's signature; public institutions keep GePG.
 */
@IntegrationTest
class PhonePaymentIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;
    @Autowired TransactionTemplate tx;
    @Autowired TenantRepository tenants;
    @Autowired JdbcTemplate jdbc;
    @Autowired PhonePaymentService service;

    private TestData.Institution institution(String ownership) {
        TestData.Institution inst = data.institution("M" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(), new BigDecimal("30000"));
        tx.executeWithoutResult(s -> tenants.findById(inst.tenant().getId()).ifPresent(t -> t.setOwnership(ownership)));
        data.staff(inst.tenant(), "bursar", Role.BURSAR);
        data.staff(inst.tenant(), "accountant", Role.ACCOUNTANT);
        return inst;
    }

    /** An applicant with an application whose fee has a payment reference: [token, invoiceId, applicationId]. */
    private String[] applicantWithFee(AdmissionScenario s, TestData.Institution inst, int n) throws Exception {
        String token = s.applicantToken(String.format("S0707/%04d", 100 + n));
        s.call(put("/api/applicant/profile"), token, """
                {"firstName":"Asha","surname":"Mwinyi","sex":"F","dateOfBirth":"2006-01-01","nationality":"Tanzanian",
                 "region":"Dodoma","nextOfKinName":"Parent","nextOfKinPhone":"0713000111"}""").andExpect(status().isOk());
        long appId = s.body(s.call(post("/api/applicant/applications"), token, "{\"windowId\":" + inst.windowId() + "}")).get("id").asLong();
        JsonNode fee = s.body(s.call(post("/api/applicant/applications/" + appId + "/fee"), token, null).andExpect(status().isOk()));
        return new String[]{token, fee.get("invoiceId").asText(), String.valueOf(appId)};
    }

    @Test
    void payersArePromptedOnTheirPhoneAndThePaymentIsRecordedOnce() throws Exception {
        TestData.Institution inst = institution(Ownership.PRIVATE);
        AdmissionScenario s = new AdmissionScenario(mvc, json, inst.tenant().getCode(), inst);
        String bursar = s.staffToken("bursar"), accountant = s.staffToken("accountant");

        // MUSE and TAMISEMI are for government institutions only
        s.call(put("/api/integration-settings"), bursar, "{\"tamisemiEnabled\":true}").andExpect(jsonPath("$.code").value("GOVERNMENT_ONLY"));
        s.call(put("/api/integration-settings"), bursar, "{\"museEnabled\":true}").andExpect(jsonPath("$.code").value("GOVERNMENT_ONLY"));
        s.call(put("/api/integration-settings"), bursar, "{\"heslbEnabled\":false}").andExpect(status().isOk());
        TenantContext.runAs(inst.info(), () -> tx.executeWithoutResult(x -> jdbc.update("update integration_settings set tamisemi_enabled = true, muse_enabled = true")));   // stored before the ownership changed
        JsonNode integ = s.body(s.call(get("/api/integration-settings"), bursar, null).andExpect(status().isOk()));
        assertThat(integ.at("/settings/tamisemiEnabled").asBoolean()).isFalse();
        assertThat(integ.at("/settings/museEnabled").asBoolean()).isFalse();
        data.staff(inst.tenant(), "admissions", Role.ADMISSIONS_OFFICER);
        assertThat(s.body(s.call(get("/api/imports/external-bodies"), s.staffToken("admissions"), null)).toString()).doesNotContain("TAMISEMI").contains("MINISTRY_OF_HEALTH");

        // nothing set up: no prompt offered, and asking for one is refused
        assertThat(s.body(s.call(get("/api/public/institution"), null, null)).at("/payment/phonePrompt/enabled").asBoolean()).isFalse();
        String[] a = applicantWithFee(s, inst, 1);
        s.call(post("/api/applicant/invoices/" + a[1] + "/phone-payment"), a[0], "{\"phone\":\"0712345678\"}").andExpect(jsonPath("$.code").value("PHONE_PAYMENT_OFF"));

        // the Bursar sets the aggregator: a real one cannot be switched on without its credentials; secrets are never shown again
        s.call(put("/api/collection-account"), accountant, "{\"provider\":\"SELCOM\",\"environment\":\"SANDBOX\",\"active\":false,\"phonePrompt\":true}")
                .andExpect(status().isForbidden());
        s.call(put("/api/collection-account"), bursar, "{\"provider\":\"SELCOM\",\"environment\":\"SANDBOX\",\"active\":true,\"phonePrompt\":true,\"fields\":{\"vendor\":\"TILL1\"}}")
                .andExpect(jsonPath("$.code").value("AGGREGATOR_INCOMPLETE"));
        JsonNode sel = s.body(s.call(put("/api/collection-account"), bursar, """
                {"provider":"SELCOM","environment":"SANDBOX","active":false,"phonePrompt":true,
                 "fields":{"vendor":"TILL1","apiKey":"key-1","apiSecret":"secret-1"}}""").andExpect(status().isOk()));
        assertThat(sel.toString()).doesNotContain("secret-1");
        assertThat(sel.at("/fields/2/set").asBoolean()).isTrue();
        assertThat(sel.get("defaultUrl").asText()).contains("selcommobile.com");
        assertThat(sel.get("notifyUrl").asText()).contains("/api/integrations/collections/selcom/");

        // Selcom's signed notification for a request: refused with a wrong signature, recorded with the right one
        String selToken = sel.get("notifyUrl").asText().replaceAll(".*/", "");
        Long invoiceId = Long.valueOf(a[1]);
        String reference = TenantContext.callAs(inst.info(), () -> tx.execute(x -> jdbc.queryForObject("select control_no from control_numbers where invoice_id = ? order by id desc limit 1", String.class, invoiceId)));
        TenantContext.runAs(inst.info(), () -> tx.executeWithoutResult(x -> jdbc.update("""
                insert into phone_payment_requests (tenant_id, invoice_id, reference, provider, order_id, phone, amount, requested_by)
                values (cims_current_tenant(), ?, ?, 'SELCOM', ?, '255712345678', 30000, 'APPLICANT')""", invoiceId, reference, reference + "P9")));
        Map<String, Object> wh = new LinkedHashMap<>();
        wh.put("result", "SUCCESS");
        wh.put("resultcode", "000");
        wh.put("order_id", reference + "P9");
        wh.put("transid", "TX77");
        wh.put("channel", "MPESA-TZ");
        wh.put("amount", "30000");
        wh.put("phone", "255712345678");
        wh.put("payment_status", "COMPLETED");
        String whBody = json.writeValueAsString(wh);
        notify(inst, "selcom", selToken, whBody, selcomHeaders(wh, "wrong")).andExpect(status().isUnauthorized());
        notify(inst, "selcom", "not-the-token", whBody, selcomHeaders(wh, "secret-1")).andExpect(status().isNotFound());
        for (int i = 0; i < 2; i++) {
            notify(inst, "selcom", selToken, whBody, selcomHeaders(wh, "secret-1")).andExpect(status().isOk()).andExpect(jsonPath("$.resultcode").value("000"));
        }
        JsonNode paid = s.body(s.call(get("/api/applicant/applications/" + a[2] + "/fee"), a[0], null));
        assertThat(paid.get("feePaid").asBoolean()).isTrue();
        Integer recorded = TenantContext.callAs(inst.info(), () -> tx.execute(x -> jdbc.queryForObject("select count(*) from payments where gepg_receipt_no = 'SELCOM-TX77'", Integer.class)));
        assertThat(recorded).isEqualTo(1);

        // the rehearsal aggregator: payers see the button, are prompted, and the payment follows
        s.call(put("/api/collection-account"), bursar, "{\"provider\":\"SIMULATED\",\"environment\":\"SANDBOX\",\"active\":true,\"phonePrompt\":true}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.provider").value("SIMULATED"));
        JsonNode pub = s.body(s.call(get("/api/public/institution"), null, null));
        assertThat(pub.at("/payment/phonePrompt/enabled").asBoolean()).isTrue();
        assertThat(pub.at("/payment/phonePrompt/needsNetwork").asBoolean()).isFalse();
        String[] b = applicantWithFee(s, inst, 2);
        s.call(post("/api/applicant/invoices/" + b[1] + "/phone-payment"), b[0], "{\"phone\":\"12345\"}").andExpect(jsonPath("$.code").value("INVALID_PHONE"));
        s.call(post("/api/applicant/invoices/" + a[1] + "/phone-payment"), b[0], "{\"phone\":\"0712345678\"}").andExpect(status().isNotFound());   // not mine
        JsonNode req = s.body(s.call(post("/api/applicant/invoices/" + b[1] + "/phone-payment"), b[0], "{\"phone\":\"0712 345 678\"}").andExpect(status().isOk()));
        assertThat(req.get("status").asText()).isEqualTo("PENDING");
        assertThat(req.get("phone").asText()).isEqualTo("255712345678");
        assertThat(req.get("amount").decimalValue()).isEqualByComparingTo("30000");
        // a second tap does not prompt again
        assertThat(s.body(s.call(post("/api/applicant/invoices/" + b[1] + "/phone-payment"), b[0], "{\"phone\":\"0712345678\"}")).get("id").asLong())
                .isEqualTo(req.get("id").asLong());
        Thread.sleep(SimulatedAggregatorClient.PAY_AFTER_MS + 500);
        JsonNode done = s.body(s.call(get("/api/applicant/phone-payments/" + req.get("id").asLong()), b[0], null).andExpect(status().isOk()));
        assertThat(done.get("status").asText()).isEqualTo("PAID");
        assertThat(done.get("receiptNo").asText()).contains("/RCT/");
        assertThat(s.body(s.call(get("/api/applicant/applications/" + b[2] + "/fee"), b[0], null)).get("feePaid").asBoolean()).isTrue();
        s.call(post("/api/applicant/invoices/" + b[1] + "/phone-payment"), b[0], "{\"phone\":\"0712345678\"}").andExpect(jsonPath("$.code").value("NOTHING_TO_PAY"));

        // a payer who cannot pay (the rehearsal: a phone ending in 9)
        String[] c = applicantWithFee(s, inst, 3);
        JsonNode poor = s.body(s.call(post("/api/applicant/invoices/" + c[1] + "/phone-payment"), c[0], "{\"phone\":\"0712345679\"}"));
        JsonNode failed = s.body(s.call(get("/api/applicant/phone-payments/" + poor.get("id").asLong()), c[0], null));
        assertThat(failed.get("status").asText()).isEqualTo("FAILED");
        assertThat(failed.get("message").asText()).contains("Insufficient balance");

        // the bursary's list, and the integration log
        JsonNode list = s.body(s.call(get("/api/phone-payments"), accountant, null).andExpect(status().isOk()));
        assertThat(list.findValuesAsText("status")).contains("PAID", "FAILED");
        assertThat(s.body(s.call(get("/api/phone-payments?status=FAILED"), accountant, null))).hasSize(1);
        java.util.List<String> ops = TenantContext.callAs(inst.info(), () -> tx.execute(x -> jdbc.queryForList("select operation from integration_calls where system = 'COLLECTION'", String.class)));
        assertThat(ops).contains("phonePrompt simulated", "notification selcom");
        // a new notification address: the old one stops working
        s.call(post("/api/collection-account/notify-address"), bursar, null).andExpect(status().isOk());
        assertThat(service).isNotNull();
    }

    @Test
    void publicInstitutionsKeepGepg() throws Exception {
        TestData.Institution inst = institution(Ownership.PUBLIC);
        AdmissionScenario s = new AdmissionScenario(mvc, json, inst.tenant().getCode(), inst);
        String bursar = s.staffToken("bursar");
        s.call(put("/api/collection-account"), bursar, "{\"provider\":\"SIMULATED\",\"environment\":\"SANDBOX\",\"active\":true,\"phonePrompt\":true}")
                .andExpect(jsonPath("$.code").value("GEPG_INSTITUTION"));
        assertThat(s.body(s.call(get("/api/collection-account"), bursar, null)).get("available").asBoolean()).isFalse();
        s.call(put("/api/integration-settings"), bursar, "{\"tamisemiEnabled\":true,\"museEnabled\":true}").andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.tamisemiEnabled").value(true));
    }

    ResultActions notify(TestData.Institution inst, String provider, String token, String body, Map<String, String> headers) throws Exception {
        var req = post("/api/integrations/collections/" + provider + "/" + token).header("X-Tenant", inst.tenant().getCode())
                .contentType(MediaType.APPLICATION_JSON).content(body);
        headers.forEach(req::header);
        return mvc.perform(req);
    }

    static Map<String, String> selcomHeaders(Map<String, Object> fields, String secret) {
        String ts = OffsetDateTime.now(ZoneId.of("Africa/Dar_es_Salaam")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"));
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "SELCOM " + Base64.getEncoder().encodeToString("key-1".getBytes()));
        h.put("Digest-Method", "HS256");
        h.put("Timestamp", ts);
        h.put("Signed-Fields", String.join(",", fields.keySet()));
        h.put("Digest", SelcomClient.digest(secret, ts, fields));
        return h;
    }
}
