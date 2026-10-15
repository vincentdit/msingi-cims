package tz.co.msingi.cims.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.support.AdmissionScenario;
import tz.co.msingi.cims.support.AdmissionScenario.Applicant;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Instalment reminders, late-payment penalties, discounts and scholarships (BR-24, FR-FIN-07, FR-FIN-08). */
@IntegrationTest
class InstalmentsAndDiscountsIT {

    static final String INSTRUCTIONS = """
            {"reportingDate":"2027-09-27","reportingTime":"08:00","reportingVenue":"Main Hall",
             "documentsToBring":["Original CSEE certificate"],"signatoryName":"Dr. Amina Salum","signatoryTitle":"Principal"}""";
    static final LocalDate TODAY = LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam"));

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    AdmissionScenario s;
    TestData.Institution inst;
    String bursar, accountant, officer, chair, registrar;
    long yearId, studentId, invoiceId;

    @BeforeEach
    void setUp() throws Exception {
        String tenant = "F" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        inst = data.institution(tenant, BigDecimal.ZERO);
        data.staff(inst.tenant(), "chair", Role.DEPUTY_PRINCIPAL_ACADEMIC);
        data.staff(inst.tenant(), "registrar", Role.REGISTRAR);
        data.staff(inst.tenant(), "bursar", Role.BURSAR);
        data.staff(inst.tenant(), "accountant", Role.ACCOUNTANT);
        s = new AdmissionScenario(mvc, json, tenant, inst);
        officer = s.staffToken("officer");
        chair = s.staffToken("chair");
        registrar = s.staffToken("registrar");
        bursar = s.staffToken("bursar");
        accountant = s.staffToken("accountant");
        s.call(put("/api/admission/windows/" + inst.windowId() + "/joining-instructions"), officer, INSTRUCTIONS).andExpect(status().isOk());
        yearId = s.body(s.call(get("/api/admission/windows/" + inst.windowId()), officer, null)).get("academicYearId").asLong();

        long reg = item("REG", "Registration", "REGISTRATION");
        long tuition = item("TUITION", "Tuition", "TUITION");
        long st = s.body(s.call(post("/api/finance/fee-structures"), accountant, "{\"academicYearId\":" + yearId + ",\"programmeId\":" + inst.programmeId()
                + ",\"yearOfStudy\":1,\"lines\":[{\"feeItemId\":" + reg + ",\"amount\":20000,\"instalment\":1},"
                + "{\"feeItemId\":" + tuition + ",\"amount\":300000,\"instalment\":1},{\"feeItemId\":" + tuition + ",\"amount\":300000,\"instalment\":2}]}")
                .andExpect(status().isOk())).get("id").asLong();
        s.call(post("/api/finance/fee-structures/" + st + "/approve"), bursar, null).andExpect(status().isOk());

        Applicant a = s.apply("S0101/0001");
        s.admit(officer, chair, a);
        s.call(post("/api/registry/windows/" + inst.windowId() + "/handover"), registrar, "{}").andExpect(status().isOk());
        studentId = s.body(s.call(get("/api/registry/students"), registrar, null)).get("content").get(0).get("id").asLong();
        invoiceId = TenantContext.callAs(inst.info(), () -> tx.execute(x -> jdbc.queryForObject(
                "select id from invoices where payer_type = 'STUDENT' and payer_id = ? and purpose = 'ANNUAL_FEES'", Long.class, studentId)));
    }

    @Test
    void remindersAndPenaltiesFollowTheInstalmentPlan() throws Exception {
        s.call(put("/api/finance/instalment-dates?academicYearId=" + yearId), bursar,
                "{\"dates\":[{\"instalment\":1,\"dueDate\":\"" + TODAY.plusDays(7) + "\"},{\"instalment\":2,\"dueDate\":\"" + TODAY + "\"}]}")
                .andExpect(jsonPath("$.code").value("DATES_OUT_OF_ORDER"));
        s.call(put("/api/finance/instalment-dates?academicYearId=" + yearId), accountant,
                "{\"dates\":[{\"instalment\":1,\"dueDate\":\"" + TODAY + "\"}]}").andExpect(status().isForbidden());
        s.call(put("/api/finance/instalment-dates?academicYearId=" + yearId), bursar,
                "{\"dates\":[{\"instalment\":1,\"dueDate\":\"" + TODAY.minusDays(20) + "\"},{\"instalment\":2,\"dueDate\":\"" + TODAY.plusDays(7) + "\"}]}")
                .andExpect(status().isOk());
        s.call(put("/api/finance/collection-rules"), bursar,
                "{\"remindersEnabled\":true,\"reminderDaysBefore\":7,\"penaltyMode\":\"PERCENT\",\"penaltyValue\":60,\"penaltyGraceDays\":14}")
                .andExpect(jsonPath("$.code").value("PENALTY_TOO_HIGH"));
        s.call(put("/api/finance/collection-rules"), bursar,
                "{\"remindersEnabled\":true,\"reminderDaysBefore\":7,\"penaltyMode\":\"PERCENT\",\"penaltyValue\":5,\"penaltyGraceDays\":14}")
                .andExpect(jsonPath("$.penaltyMode").value("PERCENT"));
        s.call(post("/api/finance/reminders/run?date=" + TODAY.plusDays(1)), bursar, null).andExpect(jsonPath("$.code").value("FUTURE_DATE"));

        // instalment 1 is 20 days late (reminded, and penalised after 14 days' grace); instalment 2 is due in 7 days (reminded)
        JsonNode run = s.body(s.call(post("/api/finance/reminders/run"), bursar, null).andExpect(status().isOk()));
        assertThat(run.get("reminders").asInt()).isEqualTo(2);
        assertThat(run.get("penalties").asInt()).isEqualTo(1);
        assertThat(run.get("penaltyTotal").decimalValue()).isEqualByComparingTo("16000");   // 5% of TZS 320,000 still owed on instalment 1
        // running again changes nothing
        JsonNode again = s.body(s.call(post("/api/finance/reminders/run"), bursar, null));
        assertThat(again.get("reminders").asInt() + again.get("penalties").asInt()).isZero();

        BigDecimal total = TenantContext.callAs(inst.info(), () -> tx.execute(x ->
                jdbc.queryForObject("select total_amount from invoices where id = ?", BigDecimal.class, invoiceId)));
        assertThat(total).isEqualByComparingTo("636000");
        JsonNode penalties = s.body(s.call(get("/api/finance/penalties"), accountant, null).andExpect(status().isOk()));
        assertThat(penalties.get(0).get("rule").asText()).contains("5% of TZS 320,000");
        Integer sms = TenantContext.callAs(inst.info(), () -> tx.execute(x -> jdbc.queryForObject(
                "select count(*) from messages where template_code in ('FEE_REMINDER', 'FEE_PENALTY')", Integer.class)));
        assertThat(sms).isEqualTo(3);
    }

    @Test
    void discountsAndScholarshipsComeFromSchemesAndAreApproved() throws Exception {
        s.call(post("/api/finance/fee-schemes"), accountant, scheme("SIBLING", "DISCOUNT", "PERCENT", "10", "TUITION")).andExpect(status().isForbidden());
        long sibling = s.body(s.call(post("/api/finance/fee-schemes"), bursar, scheme("SIBLING", "DISCOUNT", "PERCENT", "10", "TUITION"))
                .andExpect(status().isOk())).get("id").asLong();
        long merit = s.body(s.call(post("/api/finance/fee-schemes"), bursar, scheme("MERIT", "SCHOLARSHIP", "FIXED", "100000", null))
                .andExpect(status().isOk())).get("id").asLong();
        s.call(post("/api/finance/fee-schemes"), bursar, scheme("SIBLING", "DISCOUNT", "PERCENT", "5", null)).andExpect(jsonPath("$.code").value("CODE_EXISTS"));

        // the amount is worked out from the scheme: 10% of TZS 600,000 tuition
        JsonNode d = s.body(s.call(post("/api/finance/adjustments"), accountant, adjustment("DISCOUNT", sibling, null)).andExpect(status().isOk()));
        assertThat(d.get("amount").decimalValue()).isEqualByComparingTo("60000");
        assertThat(d.get("schemeName").asText()).isEqualTo("SIBLING scheme");
        s.call(post("/api/finance/adjustments"), accountant, adjustment("SCHOLARSHIP", null, "50000")).andExpect(jsonPath("$.code").value("SCHEME_REQUIRED"));
        s.call(post("/api/finance/adjustments"), accountant, adjustment("SCHOLARSHIP", sibling, null)).andExpect(jsonPath("$.code").value("SCHEME_KIND"));
        JsonNode sch = s.body(s.call(post("/api/finance/adjustments"), accountant, adjustment("SCHOLARSHIP", merit, null)).andExpect(status().isOk()));
        assertThat(sch.get("amount").decimalValue()).isEqualByComparingTo("100000");

        // the Bursar approves (the person who asked cannot); both become credit notes on the invoice
        s.call(post("/api/finance/adjustments/" + d.get("id").asLong() + "/approve"), accountant, "{}").andExpect(status().isForbidden());
        s.call(post("/api/finance/adjustments/" + d.get("id").asLong() + "/approve"), bursar, "{}").andExpect(jsonPath("$.status").value("APPROVED"));
        s.call(post("/api/finance/adjustments/" + sch.get("id").asLong() + "/approve"), bursar, "{}").andExpect(jsonPath("$.status").value("APPROVED"));
        BigDecimal paid = TenantContext.callAs(inst.info(), () -> tx.execute(x ->
                jdbc.queryForObject("select paid_amount from invoices where id = ?", BigDecimal.class, invoiceId)));
        assertThat(paid).isEqualByComparingTo("160000");
        Integer channels = TenantContext.callAs(inst.info(), () -> tx.execute(x -> jdbc.queryForObject(
                "select count(distinct channel) from payments where invoice_id = ? and channel in ('DISCOUNT', 'SCHOLARSHIP')", Integer.class, invoiceId)));
        assertThat(channels).isEqualTo(2);
    }

    private long item(String code, String name, String category) throws Exception {
        return s.body(s.call(post("/api/finance/fee-items"), accountant, "{\"code\":\"" + code + "\",\"name\":\"" + name + "\",\"category\":\"" + category
                + "\",\"allocationPriority\":50,\"active\":true}").andExpect(status().isOk())).get("id").asLong();
    }

    private static String scheme(String code, String kind, String basis, String value, String category) {
        return "{\"code\":\"" + code + "\",\"name\":\"" + code + " scheme\",\"kind\":\"" + kind + "\",\"basis\":\"" + basis + "\",\"value\":" + value
                + (category == null ? "" : ",\"feeCategory\":\"" + category + "\"") + ",\"active\":true}";
    }

    private String adjustment(String kind, Long scheme, String amount) {
        return "{\"kind\":\"" + kind + "\",\"studentId\":" + studentId + ",\"invoiceId\":" + invoiceId
                + (scheme == null ? "" : ",\"schemeId\":" + scheme) + (amount == null ? "" : ",\"amount\":" + amount)
                + ",\"reason\":\"Second child of the same family enrolled\",\"evidenceRef\":\"Letter 12/2026\"}";
    }
}
