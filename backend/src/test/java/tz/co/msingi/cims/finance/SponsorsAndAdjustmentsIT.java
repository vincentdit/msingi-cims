package tz.co.msingi.cims.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tz.co.msingi.cims.finance.service.BillingService;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.tenancy.TenantWork;
import tz.co.msingi.cims.support.AdmissionScenario;
import tz.co.msingi.cims.support.AdmissionScenario.Applicant;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** BP-17 and FR-FIN-08 to 12: sponsors and sponsor invoices, waivers and refunds with two-level approval, the Bursar's dashboard. */
@IntegrationTest
class SponsorsAndAdjustmentsIT {

    static final String INSTRUCTIONS = """
            {"reportingDate":"2027-09-27","reportingVenue":"Main Hall","documentsToBring":["Original CSEE certificate"],
             "signatoryName":"Dr. Amina Salum","signatoryTitle":"Principal"}""";
    static final String TODAY = LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam")).toString();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;
    @Autowired TenantWork tenantWork;
    @Autowired BillingService billing;

    AdmissionScenario s;
    TestData.Institution inst;
    String officer, chair, registrar, bursar, accountant, viewer, auditor;
    long yearId;

    @BeforeEach
    void setUp() throws Exception {
        String tenant = "S" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        inst = data.institution(tenant, BigDecimal.ZERO);
        data.staff(inst.tenant(), "chair", Role.DEPUTY_PRINCIPAL_ACADEMIC);
        data.staff(inst.tenant(), "registrar", Role.REGISTRAR);
        data.staff(inst.tenant(), "bursar", Role.BURSAR);
        data.staff(inst.tenant(), "accountant", Role.ACCOUNTANT);
        data.staff(inst.tenant(), "viewer", Role.MANAGEMENT_VIEWER);
        data.staff(inst.tenant(), "auditor", Role.AUDITOR);
        s = new AdmissionScenario(mvc, json, tenant, inst);
        officer = s.staffToken("officer");
        chair = s.staffToken("chair");
        registrar = s.staffToken("registrar");
        bursar = s.staffToken("bursar");
        accountant = s.staffToken("accountant");
        viewer = s.staffToken("viewer");
        auditor = s.staffToken("auditor");
        s.call(put("/api/admission/windows/" + inst.windowId() + "/joining-instructions"), officer, INSTRUCTIONS).andExpect(status().isOk());
        yearId = s.body(s.call(get("/api/admission/windows/" + inst.windowId()), officer, null)).get("academicYearId").asLong();
    }

    @Test
    void sponsorsPayTheirShareWaiversAndRefundsNeedApprovalAndTheBursarSeesItAll() throws Exception {
        long reg = item("REG", "Registration fee", 10), tuition = item("TUITION", "Tuition fee", 50);
        long fs = s.body(s.call(post("/api/finance/fee-structures"), accountant, "{\"academicYearId\":" + yearId + ",\"programmeId\":" + inst.programmeId()
                + ",\"yearOfStudy\":1,\"lines\":[{\"feeItemId\":" + reg + ",\"amount\":20000,\"instalment\":1},{\"feeItemId\":" + tuition
                + ",\"amount\":300000,\"instalment\":1},{\"feeItemId\":" + tuition + ",\"amount\":300000,\"instalment\":2}]}")).get("id").asLong();
        s.call(post("/api/finance/fee-structures/" + fs + "/approve"), bursar, null).andExpect(status().isOk());
        s.call(put("/api/finance/clearance-gates/REGISTRATION"), bursar, "{\"enabled\":true,\"minPercentPaid\":0,\"firstInstalmentRequired\":true}")
                .andExpect(status().isOk());
        Applicant a = s.apply("S0101/0001");
        Applicant b = s.applyEligibleEndingIn(5, 400);
        s.admit(officer, chair, a, b);
        JsonNode handover = s.body(s.call(post("/api/registry/windows/" + inst.windowId() + "/handover"), registrar, "{}"));
        Map<String, Long> studentByPhone = new HashMap<>();
        Map<Long, String> regNo = new HashMap<>();
        for (JsonNode st : handover.get("students")) {
            long id = st.get("studentId").asLong();
            JsonNode d = s.body(s.call(get("/api/registry/students/" + id), registrar, null));
            studentByPhone.put(d.at("/summary/phone").asText(), id);
            regNo.put(id, d.at("/summary/registrationNo").asText());
        }
        long sa = studentByPhone.get(s.body(s.call(get("/api/applicant/profile"), a.token(), null)).get("phone").asText());
        long sb = studentByPhone.get(s.body(s.call(get("/api/applicant/profile"), b.token(), null)).get("phone").asText());
        long invA = annual(sa), invB = annual(sb);
        assertThat(s.body(s.call(get("/api/finance/students/" + sa + "/clearance"), registrar, null)).get("amountNeeded").decimalValue())
                .isEqualByComparingTo("320000");

        // the Accountant registers HESLB; a is sponsored for half the tuition, b comes from HESLB's list with a cap
        String heslbJson = "{\"code\":\"HESLB\",\"name\":\"Higher Education Students' Loans Board\",\"kind\":\"LOAN_BOARD\",\"contactPhone\":\"0754000111\"}";
        s.call(post("/api/finance/sponsors"), viewer, heslbJson).andExpect(status().isForbidden());
        long heslb = s.body(s.call(post("/api/finance/sponsors"), accountant, heslbJson).andExpect(status().isOk())).get("id").asLong();
        s.call(post("/api/finance/sponsors"), accountant, heslbJson).andExpect(jsonPath("$.code").value("SPONSOR_EXISTS"));
        long tpa = s.body(s.call(post("/api/finance/sponsors"), accountant, "{\"code\":\"TPA\",\"name\":\"Tanzania Ports Authority\",\"kind\":\"COMPANY\"}")).get("id").asLong();
        s.call(post("/api/finance/sponsors/" + heslb + "/sponsorships"), accountant, "{\"studentId\":" + sa + ",\"academicYearId\":" + yearId
                + ",\"coveragePercent\":50,\"feeItemCodes\":[\"TUITION\"],\"reference\":\"LOAN-001\"}").andExpect(jsonPath("$.share").value(300000));
        s.call(post("/api/finance/sponsors/" + tpa + "/sponsorships"), accountant, "{\"studentId\":" + sa + ",\"academicYearId\":" + yearId
                + ",\"coveragePercent\":10}").andExpect(jsonPath("$.code").value("ALREADY_SPONSORED"));
        String csv = "registrationNo,coveragePercent,capAmount,reference\n" + regNo.get(sb) + ",100,250000,HESLB-123\nNOBODY/1,50,,x\n";
        JsonNode imported = s.body(s.call(post("/api/finance/sponsors/" + heslb + "/import?academicYearId=" + yearId).contentType("text/csv").content(csv),
                accountant, null).andExpect(status().isOk()));
        assertThat(imported.get("created").asInt()).isEqualTo(1);
        assertThat(imported.get("errors")).hasSize(1);
        JsonNode d = s.body(s.call(get("/api/finance/sponsors/" + heslb + "?academicYearId=" + yearId), bursar, null));
        assertThat(d.get("shares").decimalValue()).isEqualByComparingTo("550000");
        assertThat(d.get("notYetInvoiced").decimalValue()).isEqualByComparingTo("550000");

        // the sponsor is invoiced for both shares; the share counts towards the student's clearance until it is paid
        JsonNode run = s.body(s.call(post("/api/finance/sponsors/" + heslb + "/invoices"), accountant, "{\"academicYearId\":" + yearId + "}").andExpect(status().isOk()));
        assertThat(run.get("students").asInt()).isEqualTo(2);
        assertThat(run.get("amount").decimalValue()).isEqualByComparingTo("550000");
        long sponsorInv = run.get("invoiceId").asLong();
        s.call(post("/api/finance/sponsors/" + heslb + "/invoices"), accountant, "{\"academicYearId\":" + yearId + "}")
                .andExpect(jsonPath("$.code").value("NOTHING_TO_INVOICE"));
        assertThat(s.body(s.call(get("/api/finance/students/" + sa + "/clearance"), registrar, null)).get("amountNeeded").decimalValue())
                .isEqualByComparingTo("20000");
        JsonNode stA = s.body(s.call(get("/api/finance/students/" + sa + "/statement"), registrar, null));
        assertThat(stA.get("sponsorPending").decimalValue()).isEqualByComparingTo("300000");
        assertThat(stA.at("/sponsors/0/sponsor").asText()).isEqualTo("Higher Education Students' Loans Board");

        // HESLB pays a's share by bank: it moves onto a's invoice; a duplicate reference is refused
        s.call(post("/api/finance/sponsor-invoices/" + sponsorInv + "/remittances"), accountant, "{\"amount\":300000,\"reference\":\"BOT-001\",\"paidOn\":\"" + TODAY + "\"}")
                .andExpect(jsonPath("$.paid").value(300000));
        s.call(post("/api/finance/sponsor-invoices/" + sponsorInv + "/remittances"), accountant, "{\"amount\":1000,\"reference\":\"BOT-001\",\"paidOn\":\"" + TODAY + "\"}")
                .andExpect(jsonPath("$.code").value("DUPLICATE_REMITTANCE"));
        stA = s.body(s.call(get("/api/finance/students/" + sa + "/statement"), registrar, null));
        assertThat(stA.get("paid").decimalValue()).isEqualByComparingTo("300000");
        assertThat(stA.get("sponsorPending").decimalValue()).isZero();
        assertThat(stA.get("payments").findValuesAsText("channel")).contains("SPONSOR");

        // HESLB's officer sees only HESLB, asks for a control number and pays the rest through GePG
        data.staffScoped(inst.tenant(), "heslb", Role.SPONSOR_OFFICER, "SPONSOR", heslb);
        String officerH = s.staffToken("heslb");
        JsonNode mine = s.body(s.call(get("/api/finance/sponsors"), officerH, null).andExpect(status().isOk()));
        assertThat(mine).hasSize(1);
        s.call(get("/api/finance/sponsors/" + tpa), officerH, null).andExpect(status().isForbidden());
        s.call(post("/api/finance/sponsors/" + heslb + "/invoices"), officerH, "{\"academicYearId\":" + yearId + "}").andExpect(status().isForbidden());
        s.call(get("/api/finance/students/" + sa + "/statement"), officerH, null).andExpect(status().isForbidden());
        String controlNo = s.body(s.call(post("/api/finance/sponsor-invoices/" + sponsorInv + "/control-number"), officerH, null).andExpect(status().isOk()))
                .get("controlNo").asText();
        tenantWork.run(inst.tenant(), () -> billing.recordPayment(new BillingService.PaymentNotification(null, controlNo, "GEPG-" + UUID.randomUUID().toString().substring(0, 12), "PSP1",
                "CRDB", "BANK", new BigDecimal("250000"), "255754000111", OffsetDateTime.now())));
        assertThat(s.body(s.call(get("/api/finance/students/" + sb + "/statement"), registrar, null)).get("paid").decimalValue()).isEqualByComparingTo("250000");
        var xlsx = s.call(get("/api/finance/sponsors/" + heslb + "/statement.xlsx?academicYearId=" + yearId), officerH, null).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(new String(xlsx.getContentAsByteArray(), 0, 2)).isEqualTo("PK");

        // only money received counts as collections: the bank remittance and the GePG payment, not the moves onto students' invoices
        JsonNode c = s.body(s.call(get("/api/finance/reports/collections?from=" + TODAY + "&to=" + TODAY), bursar, null));
        assertThat(c.get("total").decimalValue()).isEqualByComparingTo("550000");

        // a waiver within the threshold: requested by the Accountant, approved by the Bursar, posted as a credit note
        s.call(post("/api/finance/adjustments"), accountant, waiver(sa, invA, "1000000")).andExpect(jsonPath("$.code").value("WAIVER_TOO_LARGE"));
        JsonNode w1 = s.body(s.call(post("/api/finance/adjustments"), accountant, waiver(sa, invA, "50000")).andExpect(status().isOk()));
        assertThat(w1.get("status").asText()).isEqualTo("PENDING");
        assertThat(w1.get("needsSecondLevel").asBoolean()).isFalse();
        s.call(post("/api/finance/adjustments/" + w1.get("id").asLong() + "/approve"), accountant, "{}").andExpect(status().isForbidden());
        JsonNode inbox = s.body(s.call(get("/api/approvals"), bursar, null).andExpect(status().isOk()));
        assertThat(inbox.get("items").findValuesAsText("kind")).contains("FIN_ADJUSTMENT");
        JsonNode w1done = s.body(s.call(post("/api/finance/adjustments/" + w1.get("id").asLong() + "/approve"), bursar, "{}").andExpect(status().isOk()));
        assertThat(w1done.get("status").asText()).isEqualTo("APPROVED");
        assertThat(w1done.get("documentNo").asText()).contains("/CN/");
        assertThat(s.body(s.call(get("/api/finance/students/" + sa + "/statement"), registrar, null)).get("paid").decimalValue()).isEqualByComparingTo("350000");

        // above the threshold, the Principal approves as well; the Bursar cannot do both levels
        s.call(put("/api/finance/settings"), accountant, "{\"approvalThreshold\":100000}").andExpect(status().isForbidden());
        s.call(put("/api/finance/settings"), bursar, "{\"approvalThreshold\":100000}").andExpect(jsonPath("$.approvalThreshold").value(100000));
        long w2 = s.body(s.call(post("/api/finance/adjustments"), accountant, waiver(sb, invB, "150000"))).get("id").asLong();
        s.call(post("/api/finance/adjustments/" + w2 + "/approve"), bursar, "{\"note\":\"Orphan, letter from the ward office\"}").andExpect(jsonPath("$.status").value("ENDORSED"));
        s.call(post("/api/finance/adjustments/" + w2 + "/approve"), bursar, "{}").andExpect(status().isForbidden());
        assertThat(s.body(s.call(get("/api/approvals"), viewer, null)).get("items").findValuesAsText("kind")).contains("FIN_ADJUSTMENT_SECOND");
        s.call(post("/api/finance/adjustments/" + w2 + "/approve"), viewer, "{}").andExpect(jsonPath("$.status").value("APPROVED"));

        // a refund of credit: limited to the credit, approved, then paid out
        pay(a, invA, "300000");    // a owed 270,000: 30,000 is credit
        JsonNode opts = s.body(s.call(get("/api/finance/adjustments/options?studentId=" + sa), accountant, null));
        assertThat(opts.get("credit").decimalValue()).isEqualByComparingTo("30000");
        s.call(post("/api/finance/adjustments"), accountant, refund(sa, "40000")).andExpect(jsonPath("$.code").value("REFUND_TOO_LARGE"));
        long r1 = s.body(s.call(post("/api/finance/adjustments"), accountant, refund(sa, "30000"))).get("id").asLong();
        s.call(post("/api/finance/adjustments/" + r1 + "/reject"), bursar, "{}").andExpect(jsonPath("$.code").value("NOTE_REQUIRED"));
        JsonNode r1done = s.body(s.call(post("/api/finance/adjustments/" + r1 + "/approve"), bursar, "{}"));
        assertThat(r1done.get("documentNo").asText()).contains("/RFV/");
        assertThat(r1done.get("canMarkPaid").asBoolean()).isTrue();
        assertThat(s.body(s.call(get("/api/finance/adjustments/options?studentId=" + sa), accountant, null)).get("credit").decimalValue()).isZero();
        s.call(post("/api/finance/adjustments/" + r1 + "/paid"), accountant, "{\"reference\":\"CHQ 004512\"}").andExpect(jsonPath("$.status").value("PAID"));
        JsonNode sms = s.body(s.call(get("/api/messaging/messages?size=100"), viewer, null)).get("content");
        assertThat(sms.findValuesAsText("templateCode").stream().filter("FIN_ADJUSTMENT_DECIDED"::equals).count()).isEqualTo(3);
        assertThat(sms.findValuesAsText("templateCode")).contains("SPONSOR_ASSIGNED");

        // the Bursar's dashboard and targets
        s.call(put("/api/finance/targets?academicYearId=" + yearId), bursar, "{\"targets\":[{\"dueDate\":\"2026-01-01\",\"percent\":60},{\"dueDate\":\"2026-02-01\",\"percent\":40}]}")
                .andExpect(jsonPath("$.code").value("INVALID_TARGET"));
        s.call(put("/api/finance/targets?academicYearId=" + yearId), accountant, "{\"targets\":[]}").andExpect(status().isForbidden());
        s.call(put("/api/finance/targets?academicYearId=" + yearId), bursar, "{\"targets\":[{\"dueDate\":\"" + LocalDate.parse(TODAY).minusDays(1)
                + "\",\"percent\":90},{\"dueDate\":\"" + LocalDate.parse(TODAY).plusDays(60) + "\",\"percent\":100}]}").andExpect(status().isOk());
        JsonNode dash = s.body(s.call(get("/api/finance/dashboard?academicYearId=" + yearId), bursar, null).andExpect(status().isOk()));
        assertThat(dash.get("billed").decimalValue()).isEqualByComparingTo("1240000");
        assertThat(dash.get("paid").decimalValue()).isEqualByComparingTo("1020000");
        assertThat(dash.get("waived").decimalValue()).isEqualByComparingTo("200000");
        assertThat(dash.get("percent").decimalValue()).isEqualByComparingTo("82.3");
        assertThat(dash.get("onTrack").asBoolean()).isFalse();
        assertThat(dash.at("/currentTarget/percent").decimalValue()).isEqualByComparingTo("90");
        assertThat(dash.get("programmes")).hasSize(1);
        assertThat(dash.at("/work/refundsToPay").asInt()).isZero();
        assertThat(dash.get("next").findValuesAsText("text")).anyMatch(t -> t.contains("behind the 90% target"));
        s.call(get("/api/finance/dashboard"), auditor, null).andExpect(status().isOk());
        s.call(get("/api/finance/dashboard"), registrar, null).andExpect(status().isForbidden());
        String postings = s.text(s.call(get("/api/finance/postings.csv?from=" + TODAY + "&to=" + TODAY), accountant, null).andExpect(status().isOk()));
        assertThat(postings).contains("WAIVER").contains("BANK").contains("SPONSOR");
    }

    private long item(String code, String name, int priority) throws Exception {
        return s.body(s.call(post("/api/finance/fee-items"), accountant, "{\"code\":\"" + code + "\",\"name\":\"" + name + "\",\"category\":\"TUITION\","
                + "\"allocationPriority\":" + priority + ",\"active\":true}")).get("id").asLong();
    }

    private long annual(long studentId) throws Exception {
        for (JsonNode i : s.body(s.call(get("/api/finance/students/" + studentId + "/statement"), registrar, null)).get("invoices")) {
            if (i.get("purpose").asText().equals("ANNUAL_FEES")) return i.get("id").asLong();
        }
        throw new AssertionError("no annual invoice");
    }

    private void pay(Applicant a, long invoiceId, String amount) throws Exception {
        s.call(post("/api/applicant/invoices/" + invoiceId + "/simulate-payment"), a.token(), "{\"amount\":" + amount + "}").andExpect(status().isOk());
    }

    private static String waiver(long studentId, long invoiceId, String amount) {
        return "{\"kind\":\"WAIVER\",\"studentId\":" + studentId + ",\"invoiceId\":" + invoiceId + ",\"amount\":" + amount
                + ",\"reason\":\"Fee waiver approved by the board for hardship\",\"evidenceRef\":\"Board minute 14/2027\"}";
    }

    private static String refund(long studentId, String amount) {
        return "{\"kind\":\"REFUND\",\"studentId\":" + studentId + ",\"amount\":" + amount
                + ",\"reason\":\"Overpaid the annual fees by mistake\",\"evidenceRef\":\"Student letter dated today\"}";
    }
}
