package tz.co.msingi.cims.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.msingi.cims.admission.domain.AdmissionRepositories.ApplicationRepository;
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

/** BP-06: fee structures approved by the Bursar, annual billing, allocation, clearance for registration, reports. */
@IntegrationTest
class FeesIT {

    static final String INSTRUCTIONS = """
            {"reportingDate":"2027-09-27","reportingVenue":"Main Hall","documentsToBring":["Original CSEE certificate"],
             "signatoryName":"Dr. Amina Salum","signatoryTitle":"Principal"}""";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;
    @Autowired TransactionTemplate tx;
    @Autowired ApplicationRepository applications;

    AdmissionScenario s;
    String officer, chair, registrar, bursar, accountant, viewer;
    long yearId;

    @BeforeEach
    void setUp() throws Exception {
        String tenant = "F" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TestData.Institution inst = data.institution(tenant, BigDecimal.ZERO);
        data.staff(inst.tenant(), "chair", Role.DEPUTY_PRINCIPAL_ACADEMIC);
        data.staff(inst.tenant(), "registrar", Role.REGISTRAR);
        data.staff(inst.tenant(), "bursar", Role.BURSAR);
        data.staff(inst.tenant(), "accountant", Role.ACCOUNTANT);
        data.staff(inst.tenant(), "viewer", Role.MANAGEMENT_VIEWER);
        s = new AdmissionScenario(mvc, json, tenant, inst);
        officer = s.staffToken("officer");
        chair = s.staffToken("chair");
        registrar = s.staffToken("registrar");
        bursar = s.staffToken("bursar");
        accountant = s.staffToken("accountant");
        viewer = s.staffToken("viewer");
        s.call(put("/api/admission/windows/" + inst.windowId() + "/joining-instructions"), officer, INSTRUCTIONS).andExpect(status().isOk());
        yearId = s.body(s.call(get("/api/admission/windows/" + inst.windowId()), officer, null)).get("academicYearId").asLong();
    }

    @Test
    void feeStructuresAreApprovedByTheBursarAndVersioned() throws Exception {
        long reg = item("REG", "Registration fee", "REGISTRATION", 10);
        long tuition = item("TUITION", "Tuition fee", "TUITION", 50);
        s.call(post("/api/finance/fee-items"), accountant, itemJson("REG", "Again", "OTHER", 5)).andExpect(jsonPath("$.code").value("DUPLICATE_CODE"));

        JsonNode v1 = s.body(s.call(post("/api/finance/fee-structures"), accountant, structureJson(reg, tuition, 1)).andExpect(status().isOk()));
        assertThat(v1.get("status").asText()).isEqualTo("DRAFT");
        assertThat(v1.get("total").decimalValue()).isEqualByComparingTo("620000");
        assertThat(v1.get("foreignTotal").decimalValue()).isEqualByComparingTo("920000");
        assertThat(v1.get("firstInstalment").decimalValue()).isEqualByComparingTo("320000");
        s.call(post("/api/finance/fee-structures"), accountant, structureJson(reg, tuition, 2)).andExpect(jsonPath("$.code").value("INVALID_YEAR_OF_STUDY"));

        // only the Bursar approves
        s.call(post("/api/finance/fee-structures/" + v1.get("id").asLong() + "/approve"), accountant, null).andExpect(status().isForbidden());
        s.call(post("/api/finance/fee-structures/" + v1.get("id").asLong() + "/approve"), bursar, null)
                .andExpect(jsonPath("$.status").value("APPROVED")).andExpect(jsonPath("$.approvedByName").value("Staff bursar"));
        s.call(put("/api/finance/fee-structures/" + v1.get("id").asLong()), accountant, "{\"lines\":[{\"feeItemId\":" + reg + ",\"amount\":1,\"instalment\":1}]}")
                .andExpect(jsonPath("$.code").value("NOT_A_DRAFT"));

        // a new version while the approved one stays in use; one draft at a time
        JsonNode v2 = s.body(s.call(post("/api/finance/fee-structures/" + v1.get("id").asLong() + "/revise"), accountant, null));
        assertThat(v2.get("structureVersion").asInt()).isEqualTo(2);
        s.call(post("/api/finance/fee-structures"), accountant, structureJson(reg, tuition, 1)).andExpect(jsonPath("$.code").value("DRAFT_EXISTS"));
        s.call(post("/api/finance/fee-structures/" + v2.get("id").asLong() + "/approve"), bursar, null).andExpect(status().isOk());
        JsonNode list = s.body(s.call(get("/api/finance/fee-structures?academicYearId=" + yearId), viewer, null).andExpect(status().isOk()));
        assertThat(list.findValuesAsText("status")).containsExactly("APPROVED", "SUPERSEDED");

        // the person who prepared a version cannot approve it
        JsonNode v3 = s.body(s.call(post("/api/finance/fee-structures/" + v2.get("id").asLong() + "/revise"), bursar, null));
        assertThat(v3.get("canApprove").asBoolean()).isFalse();
        s.call(post("/api/finance/fee-structures/" + v3.get("id").asLong() + "/approve"), bursar, null)
                .andExpect(jsonPath("$.code").value("SEGREGATION_OF_DUTIES"));
        s.call(post("/api/finance/fee-structures"), viewer, structureJson(reg, tuition, 1)).andExpect(status().isForbidden());
    }

    @Test
    void acceptanceBillsAllocatesAndClearanceGatesRegistration() throws Exception {
        approvedStructure();
        s.call(put("/api/finance/clearance-gates/REGISTRATION"), bursar,
                "{\"enabled\":true,\"minPercentPaid\":0,\"firstInstalmentRequired\":true}").andExpect(status().isOk());
        s.call(put("/api/finance/clearance-gates/REGISTRATION"), accountant, "{\"enabled\":false,\"minPercentPaid\":0}").andExpect(status().isForbidden());

        Applicant a = s.apply("S0101/0001");
        s.admit(officer, chair, a);

        // the annual invoice was raised on acceptance, with a control number and the reporting date as due date
        JsonNode account = account(a);
        JsonNode annual = annualInvoice(account);
        long invoiceId = annual.get("id").asLong();
        assertThat(annual.get("total").decimalValue()).isEqualByComparingTo("620000");
        assertThat(annual.get("controlNo").asText()).startsWith("99");
        assertThat(annual.get("dueDate").asText()).isEqualTo("2027-09-27");
        assertThat(annual.get("payerType").asText()).isEqualTo("APPLICANT");

        // a part payment goes to the registration fee first, then tuition (first instalment)
        pay(a, invoiceId, "100000");
        annual = annualInvoice(account(a));
        assertThat(annual.get("status").asText()).isEqualTo("PARTIALLY_PAID");
        assertThat(lineByCode(annual, "REG", 1).get("paid").decimalValue()).isEqualByComparingTo("20000");
        assertThat(lineByCode(annual, "TUITION", 1).get("paid").decimalValue()).isEqualByComparingTo("80000");
        assertThat(lineByCode(annual, "TUITION", 2).get("paid").decimalValue()).isZero();

        // hand-over: the invoice and its payments move to the student
        long studentId = s.body(s.call(post("/api/registry/windows/" + s.inst.windowId() + "/handover"), registrar, "{}"))
                .at("/students/0/studentId").asLong();
        JsonNode statement = s.body(s.call(get("/api/finance/students/" + studentId + "/statement"), registrar, null).andExpect(status().isOk()));
        assertThat(annualInvoice(statement).get("payerType").asText()).isEqualTo("STUDENT");
        assertThat(statement.get("paid").decimalValue()).isEqualByComparingTo("100000");
        assertThat(statement.get("registrationNo").asText()).startsWith(s.tenant + "/");

        // registration is blocked until the first instalment is paid
        s.call(put("/api/registry/students/" + studentId + "/first-registration"), registrar,
                "{\"documents\":[{\"item\":\"Original CSEE certificate\",\"verified\":true}]}").andExpect(status().isOk());
        s.call(post("/api/registry/students/" + studentId + "/first-registration/approve"), registrar, null)
                .andExpect(jsonPath("$.code").value("FINANCIAL_CLEARANCE_REQUIRED"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("TZS 220,000 still to pay")));
        JsonNode clearance = s.body(s.call(get("/api/finance/students/" + studentId + "/clearance"), registrar, null));
        assertThat(clearance.get("cleared").asBoolean()).isFalse();
        assertThat(clearance.get("amountNeeded").decimalValue()).isEqualByComparingTo("220000");

        pay(a, invoiceId, "220000");
        assertThat(s.body(s.call(get("/api/finance/students/" + studentId + "/clearance"), registrar, null)).get("cleared").asBoolean()).isTrue();
        s.call(post("/api/registry/students/" + studentId + "/first-registration/approve"), registrar, null)
                .andExpect(jsonPath("$.summary.status").value("ACTIVE"));

        // an overpayment is kept as credit
        pay(a, invoiceId, "400000");
        account = account(a);
        assertThat(annualInvoice(account).get("status").asText()).isEqualTo("PAID");
        assertThat(account.get("credit").decimalValue()).isEqualByComparingTo("100000");

        // billing runs never bill twice
        s.call(post("/api/finance/billing-runs"), accountant, "{\"academicYearId\":" + yearId + "}")
                .andExpect(jsonPath("$.billed").value(0)).andExpect(jsonPath("$.alreadyBilled").value(1));

        // collections by fee item, including the credit; nothing owed
        String today = LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam")).toString();
        JsonNode c = s.body(s.call(get("/api/finance/reports/collections?from=" + today + "&to=" + today), bursar, null).andExpect(status().isOk()));
        assertThat(c.get("total").decimalValue()).isEqualByComparingTo("720000");
        assertThat(c.get("credit").decimalValue()).isEqualByComparingTo("100000");
        assertThat(amountFor(c.get("byFeeItem"), "REG")).isEqualByComparingTo("20000");
        assertThat(amountFor(c.get("byFeeItem"), "TUITION")).isEqualByComparingTo("600000");
        assertThat(amountFor(c.get("byFeeItem"), "CREDIT")).isEqualByComparingTo("100000");
        assertThat(s.body(s.call(get("/api/finance/reports/debtors?academicYearId=" + yearId), bursar, null)).get("count").asInt()).isZero();
        String csv = s.text(s.call(get("/api/finance/reports/collections.csv?from=" + today + "&to=" + today), accountant, null).andExpect(status().isOk()));
        assertThat(csv.lines().count()).isEqualTo(4); // header + three payments
    }

    @Test
    void billingRunCatchesUpAndForeignRatesApply() throws Exception {
        Applicant a = s.apply("S0101/0001");
        TenantContext.runAs(s.inst.info(), () -> tx.executeWithoutResult(t ->
                applications.findById(a.applicationId()).orElseThrow().getApplicant().setNationality("Kenyan")));
        s.admit(officer, chair, a);
        assertThat(account(a).get("invoices").findValuesAsText("purpose")).doesNotContain("ANNUAL_FEES"); // no structure yet

        approvedStructure();
        s.call(post("/api/finance/billing-runs"), accountant, "{\"academicYearId\":" + yearId + "}")
                .andExpect(jsonPath("$.billed").value(1));
        assertThat(annualInvoice(account(a)).get("total").decimalValue()).isEqualByComparingTo("920000"); // foreign rates
        s.call(post("/api/finance/billing-runs"), accountant, "{\"academicYearId\":" + yearId + "}")
                .andExpect(jsonPath("$.billed").value(0)).andExpect(jsonPath("$.alreadyBilled").value(1));

        JsonNode debtors = s.body(s.call(get("/api/finance/reports/debtors?academicYearId=" + yearId), bursar, null));
        assertThat(debtors.get("count").asInt()).isEqualTo(1);
        assertThat(debtors.at("/rows/0/bucket").asText()).isEqualTo("NOT_DUE");
        assertThat(debtors.get("totalBalance").decimalValue()).isEqualByComparingTo("920000");
    }

    @Test
    void accountsArePrivate() throws Exception {
        approvedStructure();
        Applicant a = s.apply("S0101/0001");
        Applicant b = s.applyEligibleEndingIn(5, 400);
        s.admit(officer, chair, a, b);
        long aInvoice = annualInvoice(account(a)).get("id").asLong();
        s.call(post("/api/applicant/invoices/" + aInvoice + "/simulate-payment"), b.token(), "{}").andExpect(status().isNotFound());
        s.call(post("/api/applicant/invoices/" + aInvoice + "/control-number"), b.token(), null).andExpect(status().isNotFound());
        s.call(get("/api/finance/reports/debtors"), registrar, null).andExpect(status().isForbidden());
        s.call(get("/api/finance/fee-structures?academicYearId=" + yearId), a.token(), null).andExpect(status().isForbidden());

        String other = "G" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TestData.Institution o = data.institution(other, BigDecimal.ZERO);
        data.staff(o.tenant(), "bursar", Role.BURSAR);
        AdmissionScenario os = new AdmissionScenario(mvc, json, other, o);
        assertThat(os.body(os.call(get("/api/finance/fee-structures?academicYearId=" + yearId), os.staffToken("bursar"), null))).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private long item(String code, String name, String category, int priority) throws Exception {
        return s.body(s.call(post("/api/finance/fee-items"), accountant, itemJson(code, name, category, priority)).andExpect(status().isOk()))
                .get("id").asLong();
    }

    private static String itemJson(String code, String name, String category, int priority) {
        return "{\"code\":\"" + code + "\",\"name\":\"" + name + "\",\"category\":\"" + category + "\",\"allocationPriority\":" + priority + ",\"active\":true}";
    }

    private String structureJson(long reg, long tuition, int yearOfStudy) {
        return "{\"academicYearId\":" + yearId + ",\"programmeId\":" + s.inst.programmeId() + ",\"yearOfStudy\":" + yearOfStudy + ",\"lines\":["
                + "{\"feeItemId\":" + reg + ",\"amount\":20000,\"instalment\":1},"
                + "{\"feeItemId\":" + tuition + ",\"amount\":300000,\"foreignAmount\":450000,\"instalment\":1},"
                + "{\"feeItemId\":" + tuition + ",\"amount\":300000,\"foreignAmount\":450000,\"instalment\":2}]}";
    }

    private void approvedStructure() throws Exception {
        long reg = item("REG", "Registration fee", "REGISTRATION", 10);
        long tuition = item("TUITION", "Tuition fee", "TUITION", 50);
        long id = s.body(s.call(post("/api/finance/fee-structures"), accountant, structureJson(reg, tuition, 1))).get("id").asLong();
        s.call(post("/api/finance/fee-structures/" + id + "/approve"), bursar, null).andExpect(jsonPath("$.status").value("APPROVED"));
    }

    private JsonNode account(Applicant a) throws Exception {
        return s.body(s.call(get("/api/applicant/account"), a.token(), null).andExpect(status().isOk()));
    }

    private void pay(Applicant a, long invoiceId, String amount) throws Exception {
        s.call(post("/api/applicant/invoices/" + invoiceId + "/simulate-payment"), a.token(), "{\"amount\":" + amount + "}")
                .andExpect(status().isOk());
    }

    private static JsonNode annualInvoice(JsonNode account) {
        for (JsonNode i : account.get("invoices")) if (i.get("purpose").asText().equals("ANNUAL_FEES")) return i;
        throw new AssertionError("no annual invoice in " + account);
    }

    private static JsonNode lineByCode(JsonNode invoice, String code, int instalment) {
        for (JsonNode l : invoice.get("lines")) if (l.get("feeItemCode").asText().equals(code) && l.get("instalment").asInt() == instalment) return l;
        throw new AssertionError("no line " + code + "/" + instalment);
    }

    private static BigDecimal amountFor(JsonNode amounts, String key) {
        for (JsonNode a : amounts) if (a.get("key").asText().equals(key)) return a.get("amount").decimalValue();
        throw new AssertionError("no " + key + " in " + amounts);
    }
}
