package tz.co.msingi.cims.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.msingi.cims.finance.web.GepgCallbackController;
import tz.co.msingi.cims.notification.MessagingRepositories.MessageRepository;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.support.AdmissionScenario;
import tz.co.msingi.cims.support.AdmissionScenario.Applicant;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FR-COM-01 to 03: event messages from templates through the outbox, bulk messages against the bundle, delivery reports. */
@IntegrationTest
class MessagingIT {

    static final String INSTRUCTIONS = """
            {"reportingDate":"2027-09-27","reportingVenue":"Main Hall","documentsToBring":["Original CSEE certificate"],
             "signatoryName":"Dr. Amina Salum","signatoryTitle":"Principal"}""";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;
    @Autowired TransactionTemplate tx;
    @Autowired Messaging messaging;
    @Autowired MessageRepository messages;
    @Autowired MessageDispatcher dispatcher;

    AdmissionScenario s;
    String admin, registrar, bursar, accountant, viewer, officer, chair;

    @BeforeEach
    void setUp() throws Exception {
        String tenant = "M" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TestData.Institution inst = data.institution(tenant, BigDecimal.ZERO);
        data.staff(inst.tenant(), "admin", Role.INSTITUTION_ADMIN);
        data.staff(inst.tenant(), "registrar", Role.REGISTRAR);
        data.staff(inst.tenant(), "bursar", Role.BURSAR);
        data.staff(inst.tenant(), "accountant", Role.ACCOUNTANT);
        data.staff(inst.tenant(), "viewer", Role.MANAGEMENT_VIEWER);
        data.staff(inst.tenant(), "chair", Role.DEPUTY_PRINCIPAL_ACADEMIC);
        s = new AdmissionScenario(mvc, json, tenant, inst);
        admin = s.staffToken("admin");
        registrar = s.staffToken("registrar");
        bursar = s.staffToken("bursar");
        accountant = s.staffToken("accountant");
        viewer = s.staffToken("viewer");
        officer = s.staffToken("officer");
        chair = s.staffToken("chair");
    }

    @Test
    void eventMessagesUseTheInstitutionsWordingAndAreLogged() throws Exception {
        Applicant a = s.apply("S0101/0001");
        String phone = s.body(s.call(get("/api/applicant/profile"), a.token(), null)).get("phone").asText();

        JsonNode log = s.body(s.call(get("/api/messaging/messages?phone=" + phone), viewer, null).andExpect(status().isOk())).get("content");
        assertThat(s.body(s.call(get("/api/messaging/messages"), viewer, null).andExpect(status().isOk())).get("totalElements").asInt()).isGreaterThan(1);
        assertThat(s.body(s.call(get("/api/messaging/messages?status=DELIVERED&kind=EVENT"), viewer, null).andExpect(status().isOk()))
                .get("content").findValuesAsText("kind")).containsOnly("EVENT");
        JsonNode otp = byTemplate(log, "OTP_CODE");
        assertThat(otp.get("kind").asText()).isEqualTo("SECURITY");
        assertThat(otp.get("body").asText()).contains("******").doesNotContainPattern("^\\d{6}"); // the code is never stored
        JsonNode received = byTemplate(log, "APPLICATION_RECEIVED");
        assertThat(received.get("status").asText()).isEqualTo("DELIVERED");
        assertThat(received.get("body").asText()).startsWith("Your application " + s.tenant + "/APP/");
        assertThat(received.get("parts").asInt()).isEqualTo(1);

        // the institution rewords the message and switches to Kiswahili
        s.call(put("/api/messaging/templates/APPLICATION_RECEIVED"), admin, template("Thanks! {referenceNo} {nope}", "Asante {referenceNo}", true))
                .andExpect(jsonPath("$.code").value("UNKNOWN_PLACEHOLDER"));
        s.call(put("/api/messaging/templates/CONTROL_NUMBER"), admin, template("Pay {amount}", "Lipa {amount}", true))
                .andExpect(jsonPath("$.code").value("MISSING_PLACEHOLDER"));
        s.call(put("/api/messaging/templates/OTP_CODE"), admin, template("{code}", "{code}", false)).andExpect(jsonPath("$.code").value("CANNOT_DISABLE"));
        s.call(put("/api/messaging/templates/APPLICATION_RECEIVED"), registrar, template("x {referenceNo}", "y {referenceNo}", true)).andExpect(status().isForbidden());
        JsonNode t = s.body(s.call(put("/api/messaging/templates/APPLICATION_RECEIVED"), admin,
                template("Application {referenceNo} received by {institution}.", "Maombi {referenceNo} yamepokelewa na {institution}.", true)).andExpect(status().isOk()));
        assertThat(t.get("customised").asBoolean()).isTrue();
        assertThat(t.get("sampleSw").asText()).isEqualTo("Maombi DEMO/APP/2027/000123 yamepokelewa na Msingi Demo Technical College.");
        s.call(put("/api/messaging/settings"), admin, "{\"defaultLanguage\":\"SW\",\"senderId\":\"MSINGI\",\"pricePerSms\":22,\"lowBalanceWarning\":100}")
                .andExpect(jsonPath("$.defaultLanguage").value("SW"));

        Applicant b = s.applyEligibleEndingIn(5, 400);
        String bPhone = s.body(s.call(get("/api/applicant/profile"), b.token(), null)).get("phone").asText();
        JsonNode bLog = s.body(s.call(get("/api/messaging/messages?phone=" + bPhone), viewer, null)).get("content");
        assertThat(byTemplate(bLog, "APPLICATION_RECEIVED").get("body").asText()).startsWith("Maombi " + s.tenant + "/APP/").endsWith("yamepokelewa na " + s.tenant + " Technical College.");
        assertThat(byTemplate(bLog, "APPLICATION_RECEIVED").get("language").asText()).isEqualTo("SW");

        // switched off: nothing is sent; reset brings back the built-in wording
        s.call(put("/api/messaging/templates/APPLICATION_RECEIVED"), admin, template("{referenceNo}", "{referenceNo}", false)).andExpect(status().isOk());
        Applicant c = s.applyEligibleEndingIn(5, 800);
        String cPhone = s.body(s.call(get("/api/applicant/profile"), c.token(), null)).get("phone").asText();
        assertThat(s.body(s.call(get("/api/messaging/messages?phone=" + cPhone), viewer, null)).get("content").findValuesAsText("templateCode"))
                .doesNotContain("APPLICATION_RECEIVED");
        s.call(delete("/api/messaging/templates/APPLICATION_RECEIVED"), admin, null)
                .andExpect(jsonPath("$.customised").value(false)).andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void nothingIsSentWhenTheWorkRollsBack() {
        TenantContext.runAs(s.inst.info(), () -> {
            Long before = tx.execute(st -> messages.count());
            tx.executeWithoutResult(st -> {
                messaging.event("DETAILS_CHANGE_APPROVED", "255712000001", Map.of());
                st.setRollbackOnly();
            });
            tx.executeWithoutResult(st -> messaging.event("DETAILS_CHANGE_APPROVED", "255712000002", Map.of()));
            Long after = tx.execute(st -> messages.count());
            Message.Status sent = tx.execute(st -> messages.findAll().stream().filter(m -> m.getPhone().equals("255712000002")).findFirst().orElseThrow().getStatus());
            assertThat(after).isEqualTo(before + 1);
            assertThat(sent).isEqualTo(Message.Status.DELIVERED);
        });
    }

    @Test
    void bulkMessagesGoToFilteredStudentsAgainstTheBundle() throws Exception {
        handOverTwoStudentsOneOfWhomPaid();
        String studentsWithBalance = campaign("Fees reminder", "Dear {firstName}, your fee balance is {balance}. Please pay before 30 October.",
                "{\"group\":\"STUDENTS\",\"withFeeBalance\":true}");
        JsonNode p = s.body(s.call(post("/api/messaging/campaigns/preview"), registrar, studentsWithBalance).andExpect(status().isOk()));
        assertThat(p.get("recipients").asInt()).isEqualTo(1);
        JsonNode asha = s.lookup("S0101/0001"); // the student who has not paid; names come from (simulated) NECTA
        assertThat(p.at("/samples/0/text").asText()).isEqualTo("Dear " + asha.get("firstName").asText() + ", your fee balance is TZS 620,000. Please pay before 30 October.");
        assertThat(p.get("audienceLabel").asText()).isEqualTo("Students (admitted, registered), with a fee balance");
        assertThat(p.get("enoughBalance").asBoolean()).isFalse();
        s.call(post("/api/messaging/campaigns"), registrar, studentsWithBalance).andExpect(jsonPath("$.code").value("INSUFFICIENT_SMS_BALANCE"));

        s.call(post("/api/messaging/topups"), accountant, "{\"units\":100,\"amountPaid\":2500,\"reference\":\"X\"}").andExpect(status().isForbidden());
        long balance = s.body(s.call(post("/api/messaging/topups"), bursar, "{\"units\":100,\"amountPaid\":2500,\"reference\":\"INV-77\"}")
                .andExpect(status().isOk())).get("balance").asLong();
        assertThat(balance).isLessThan(100); // event messages already used some of it

        s.call(post("/api/messaging/campaigns"), viewer, studentsWithBalance).andExpect(status().isForbidden());
        JsonNode c = s.body(s.call(post("/api/messaging/campaigns"), registrar, studentsWithBalance).andExpect(status().isOk()));
        JsonNode detail = s.body(s.call(get("/api/messaging/campaigns/" + c.get("id").asLong()), viewer, null));
        assertThat(detail.at("/campaign/byStatus/DELIVERED").asInt()).isEqualTo(1);
        assertThat(detail.at("/messages/0/recipientName").asText()).isEqualTo(asha.get("fullName").asText());
        assertThat(s.body(s.call(get("/api/messaging/overview"), viewer, null)).get("balance").asLong()).isEqualTo(balance - 1);

        // all current students; placeholders must suit the group
        JsonNode all = s.body(s.call(post("/api/messaging/campaigns/preview"), registrar,
                campaign("Orientation", "Orientation is on Monday.", "{\"group\":\"STUDENTS\"}")));
        assertThat(all.get("recipients").asInt()).isEqualTo(2);
        s.call(post("/api/messaging/campaigns/preview"), registrar, campaign("x", "Hi {registrationNo}", "{\"group\":\"PHONES\",\"phones\":\"0712000001\"}"))
                .andExpect(jsonPath("$.code").value("UNKNOWN_PLACEHOLDER"));
    }

    @Test
    void failedMessagesAreReportedAndCanBeResent() throws Exception {
        s.call(post("/api/messaging/topups"), admin, "{\"units\":50,\"amountPaid\":1250,\"reference\":\"B1\"}").andExpect(status().isOk());
        String body = campaign("Test", "Meeting at 10:00 tomorrow.", "{\"group\":\"PHONES\",\"phones\":\"0712000000, 0713 000 999\\nabc\\n0712000000;0754123456\"}");
        JsonNode p = s.body(s.call(post("/api/messaging/campaigns/preview"), admin, body));
        assertThat(p.get("recipients").asInt()).isEqualTo(3);
        assertThat(p.get("skippedDuplicates").asInt()).isEqualTo(1);
        assertThat(p.get("invalidPhones").get(0).asText()).isEqualTo("abc");
        long id = s.body(s.call(post("/api/messaging/campaigns"), admin, body)).get("id").asLong();

        // the gateway refused one (retried up to three times), one handset was unreachable, one was delivered
        TenantContext.runAs(s.inst.info(), () -> {
            dispatcher.retryQueuedHere(Duration.ZERO);
            dispatcher.retryQueuedHere(Duration.ZERO);
        });
        JsonNode d = s.body(s.call(get("/api/messaging/campaigns/" + id), admin, null));
        assertThat(d.at("/campaign/byStatus/DELIVERED").asInt()).isEqualTo(1);
        assertThat(d.at("/campaign/byStatus/FAILED").asInt()).isEqualTo(2);
        JsonNode refused = byPhone(d.get("messages"), "255713000999");
        assertThat(refused.get("attempts").asInt()).isEqualTo(3);
        assertThat(refused.get("error").asText()).isEqualTo("Number rejected by the gateway");
        assertThat(byPhone(d.get("messages"), "255712000000").get("error").asText()).isEqualTo("Handset unreachable");

        long before = s.body(s.call(get("/api/messaging/overview"), admin, null)).get("balance").asLong();
        s.call(post("/api/messaging/campaigns/" + id + "/resend-failed"), admin, null).andExpect(status().isOk());
        d = s.body(s.call(get("/api/messaging/campaigns/" + id), admin, null));
        assertThat(byPhone(d.get("messages"), "255712000000").get("status").asText()).isEqualTo("FAILED");
        // the unreachable handset was charged again; the refused number holds one part while it waits for a retry...
        assertThat(s.body(s.call(get("/api/messaging/overview"), admin, null)).get("balance").asLong()).isEqualTo(before - 2);
        TenantContext.runAs(s.inst.info(), () -> {
            dispatcher.retryQueuedHere(Duration.ZERO);
            dispatcher.retryQueuedHere(Duration.ZERO);
        });
        // ...and releases it when it finally fails: refused messages are never charged
        assertThat(s.body(s.call(get("/api/messaging/overview"), admin, null)).get("balance").asLong()).isEqualTo(before - 1);

        // delivery reports from the aggregator are signed and tied to the institution and message
        long msgId = byPhone(d.get("messages"), "255754123456").get("id").asLong();
        String report = "{\"reference\":\"" + s.inst.tenant().getId() + "-" + msgId + "\",\"providerRef\":null,\"status\":\"UNDELIV\",\"error\":\"Phone switched off\"}";
        s.call(post("/api/integrations/sms/delivery-reports").header("X-Sms-Signature", "bad"), null, report).andExpect(status().isUnauthorized());
        s.call(post("/api/integrations/sms/delivery-reports").header("X-Sms-Signature", GepgCallbackController.sign(report, "test-sms-secret")), null, report)
                .andExpect(status().isOk());
        String unknown = "{\"reference\":\"999999-1\",\"status\":\"DELIVRD\"}";
        s.call(post("/api/integrations/sms/delivery-reports").header("X-Sms-Signature", GepgCallbackController.sign(unknown, "test-sms-secret")), null, unknown)
                .andExpect(status().isBadRequest());
        JsonNode m = byPhone(s.body(s.call(get("/api/messaging/campaigns/" + id), admin, null)).get("messages"), "255754123456");
        assertThat(m.get("status").asText()).isEqualTo("FAILED");
        assertThat(m.get("error").asText()).isEqualTo("Phone switched off");

        // another institution sees nothing of it
        String other = "N" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TestData.Institution o = data.institution(other, BigDecimal.ZERO);
        data.staff(o.tenant(), "admin", Role.INSTITUTION_ADMIN);
        AdmissionScenario os = new AdmissionScenario(mvc, json, other, o);
        String oAdmin = os.staffToken("admin");
        os.call(get("/api/messaging/campaigns/" + id), oAdmin, null).andExpect(status().isNotFound());
        assertThat(os.body(os.call(get("/api/messaging/campaigns"), oAdmin, null))).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private void handOverTwoStudentsOneOfWhomPaid() throws Exception {
        s.call(put("/api/admission/windows/" + s.inst.windowId() + "/joining-instructions"), officer, INSTRUCTIONS).andExpect(status().isOk());
        long yearId = s.body(s.call(get("/api/admission/windows/" + s.inst.windowId()), officer, null)).get("academicYearId").asLong();
        long reg = s.body(s.call(post("/api/finance/fee-items"), accountant,
                "{\"code\":\"REG\",\"name\":\"Registration fee\",\"category\":\"REGISTRATION\",\"allocationPriority\":10,\"active\":true}")).get("id").asLong();
        long tuition = s.body(s.call(post("/api/finance/fee-items"), accountant,
                "{\"code\":\"TUITION\",\"name\":\"Tuition fee\",\"category\":\"TUITION\",\"allocationPriority\":50,\"active\":true}")).get("id").asLong();
        long st = s.body(s.call(post("/api/finance/fee-structures"), accountant, "{\"academicYearId\":" + yearId + ",\"programmeId\":" + s.inst.programmeId()
                + ",\"yearOfStudy\":1,\"lines\":[{\"feeItemId\":" + reg + ",\"amount\":20000,\"instalment\":1},{\"feeItemId\":" + tuition
                + ",\"amount\":600000,\"instalment\":1}]}")).get("id").asLong();
        s.call(post("/api/finance/fee-structures/" + st + "/approve"), bursar, null).andExpect(status().isOk());
        Applicant a = s.apply("S0101/0001");
        Applicant b = s.applyEligibleEndingIn(5, 400);
        s.admit(officer, chair, a, b);
        for (JsonNode inv : s.body(s.call(get("/api/applicant/account"), b.token(), null)).get("invoices")) {
            if (inv.get("purpose").asText().equals("ANNUAL_FEES")) {
                s.call(post("/api/applicant/invoices/" + inv.get("id").asLong() + "/simulate-payment"), b.token(), "{}").andExpect(status().isOk());
            }
        }
        s.call(post("/api/registry/windows/" + s.inst.windowId() + "/handover"), registrar, "{}").andExpect(status().isOk());
    }

    private static String template(String en, String sw, boolean enabled) {
        return "{\"bodyEn\":\"" + en + "\",\"bodySw\":\"" + sw + "\",\"enabled\":" + enabled + "}";
    }

    private static String campaign(String title, String body, String audience) {
        return "{\"title\":\"" + title + "\",\"body\":\"" + body + "\",\"audience\":" + audience + "}";
    }

    private static JsonNode byTemplate(JsonNode list, String code) {
        for (JsonNode m : list) if (code.equals(m.path("templateCode").asText())) return m;
        throw new AssertionError("no " + code + " in " + list);
    }

    private static JsonNode byPhone(JsonNode list, String phone) {
        for (JsonNode m : list) if (phone.equals(m.get("phone").asText())) return m;
        throw new AssertionError("no " + phone + " in " + list);
    }

    @SuppressWarnings("unused")
    private static List<String> statuses(JsonNode list) {
        return list.findValuesAsText("status");
    }
}
