package tz.co.msingi.cims.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.msingi.cims.finance.domain.FinanceRepositories.PaymentRepository;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.support.AdmissionScenario;
import tz.co.msingi.cims.support.AdmissionScenario.Applicant;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FR-FIN-05: daily GePG reconciliation, exception handling, closing the day and the Bursar's monthly sign-off. */
@IntegrationTest
class ReconciliationIT {

    static final ZoneId EAT = ZoneId.of("Africa/Dar_es_Salaam");
    static final String HEADER = "PayRefId,SpBillId,BillCtrNum,PspTrxId,PspName,PaidAmt,Ccy,TrxDtTm,UsdPayChnl,PyrCellNum";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;
    @Autowired TransactionTemplate tx;
    @Autowired PaymentRepository payments;

    AdmissionScenario s;
    String officer, chair, bursar, accountant, viewer, registrar;
    long yearId;
    LocalDate today;

    @BeforeEach
    void setUp() throws Exception {
        String tenant = "R" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TestData.Institution inst = data.institution(tenant, BigDecimal.ZERO);
        data.staff(inst.tenant(), "chair", Role.DEPUTY_PRINCIPAL_ACADEMIC);
        data.staff(inst.tenant(), "bursar", Role.BURSAR);
        data.staff(inst.tenant(), "accountant", Role.ACCOUNTANT);
        data.staff(inst.tenant(), "viewer", Role.MANAGEMENT_VIEWER);
        data.staff(inst.tenant(), "registrar", Role.REGISTRAR);
        s = new AdmissionScenario(mvc, json, tenant, inst);
        officer = s.staffToken("officer");
        chair = s.staffToken("chair");
        bursar = s.staffToken("bursar");
        accountant = s.staffToken("accountant");
        viewer = s.staffToken("viewer");
        registrar = s.staffToken("registrar");
        s.call(put("/api/admission/windows/" + inst.windowId() + "/joining-instructions"), officer, FeesIT.INSTRUCTIONS).andExpect(status().isOk());
        yearId = s.body(s.call(get("/api/admission/windows/" + inst.windowId()), officer, null)).get("academicYearId").asLong();
        today = LocalDate.now(EAT);
    }

    @Test
    void matchesTheFileListsExceptionsAndClosesTheDay() throws Exception {
        Applicant a = admittedWithInvoice("S0101/0001");
        long invoiceId = annualInvoice(a).get("id").asLong();
        pay(a, invoiceId, "100000");
        pay(a, invoiceId, "50000");
        pay(a, invoiceId, "30000");

        // GePG's file: what CIMS has, in GePG's layout (three payments)
        List<String> rows = new ArrayList<>(s.text(s.call(get("/api/finance/reconciliations/cims-payments.csv?date=" + today), viewer, null)
                .andExpect(status().isOk())).lines().toList());
        assertThat(rows).hasSize(4);
        String[] first = rows.get(1).split(",", -1);
        String[] second = rows.get(2).split(",", -1);
        String billId = first[1];
        rows.remove(3);                                           // third CIMS payment missing from GePG's file
        rows.set(2, String.join(",", withAmount(second, "55000")));// GePG says 55,000 for the second
        rows.add(rows.get(1));                                    // the first repeated
        rows.add("GEPGMISSED01," + billId + ",," + "PSP77,CRDB,70000,TZS," + today + "T10:15:00,BANK,255700000001"); // callback never arrived
        rows.add("GEPGUNKNOWN1,BILL-NOT-OURS,,PSP78,NMB,9000,TZS," + today + " 11:00:00,MOBILE,");   // not our bill

        importFile(accountant, today, String.join("\n", rows)).andExpect(status().isOk());
        JsonNode r = s.body(importFile(accountant, today, String.join("\n", rows)));  // a second import replaces the first
        assertThat(r.get("gepgCount").asInt()).isEqualTo(5);
        assertThat(r.get("cimsCount").asInt()).isEqualTo(3);
        assertThat(r.get("cimsTotal").decimalValue()).isEqualByComparingTo("180000");
        assertThat(r.get("matchedCount").asInt()).isEqualTo(1);
        assertThat(r.get("exceptionCount").asInt()).isEqualTo(5);
        assertThat(kinds(r)).containsExactlyInAnyOrder("MATCHED", "AMOUNT_MISMATCH", "DUPLICATE", "MISSING_IN_CIMS", "UNKNOWN_BILL", "MISSING_IN_GEPG");
        assertThat(item(r, "MATCHED").get("payerName").asText()).isNotBlank();
        long reconId = r.get("id").asLong();

        // the day cannot be closed with open exceptions
        s.call(post("/api/finance/reconciliations/" + reconId + "/close"), accountant, null).andExpect(jsonPath("$.code").value("OPEN_EXCEPTIONS"));

        // the missed payment is posted: receipt, allocation and balance as for a callback
        s.call(post("/api/finance/reconciliations/items/" + item(r, "UNKNOWN_BILL").get("id").asLong() + "/post"), accountant, "{}")
                .andExpect(jsonPath("$.code").value("NOT_POSTABLE"));
        r = s.body(s.call(post("/api/finance/reconciliations/items/" + item(r, "MISSING_IN_CIMS").get("id").asLong() + "/post"), accountant, "{}")
                .andExpect(status().isOk()));
        JsonNode posted = item(r, "MISSING_IN_CIMS");
        assertThat(posted.get("status").asText()).isEqualTo("RESOLVED");
        assertThat(posted.get("resolution").asText()).isEqualTo("POSTED");
        assertThat(posted.get("receiptNo").asText()).isNotBlank();
        assertThat(annualInvoice(a).get("paid").decimalValue()).isEqualByComparingTo("250000");
        s.call(post("/api/finance/reconciliations/items/" + posted.get("id").asLong() + "/post"), accountant, "{}")
                .andExpect(jsonPath("$.code").value("NOT_OPEN"));

        // other exceptions need an explanation; read-only roles cannot resolve
        long mismatch = item(r, "AMOUNT_MISMATCH").get("id").asLong();
        s.call(post("/api/finance/reconciliations/items/" + mismatch + "/resolve"), accountant, "{\"note\":\" \"}").andExpect(status().isBadRequest());
        s.call(post("/api/finance/reconciliations/items/" + mismatch + "/resolve"), viewer, "{\"note\":\"x\"}").andExpect(status().isForbidden());
        for (String kind : List.of("AMOUNT_MISMATCH", "DUPLICATE", "UNKNOWN_BILL", "MISSING_IN_GEPG")) {
            r = s.body(s.call(post("/api/finance/reconciliations/items/" + item(r, kind).get("id").asLong() + "/resolve"), accountant,
                    "{\"note\":\"Confirmed with GePG helpdesk\"}").andExpect(status().isOk()));
        }
        assertThat(r.get("openExceptions").asInt()).isZero();

        // a file cannot replace a reconciliation with resolved lines; the closed day is final
        importFile(accountant, today, String.join("\n", rows)).andExpect(jsonPath("$.code").value("REIMPORT_BLOCKED"));
        s.call(post("/api/finance/reconciliations/" + reconId + "/close"), accountant, null)
                .andExpect(jsonPath("$.status").value("CLOSED")).andExpect(jsonPath("$.closedByName").value("Staff accountant"));
        importFile(accountant, today, String.join("\n", rows)).andExpect(jsonPath("$.code").value("RECONCILIATION_CLOSED"));

        JsonNode month = s.body(s.call(get("/api/finance/reconciliations?month=" + YearMonth.from(today)), viewer, null).andExpect(status().isOk()));
        JsonNode day = month.get("days").get(today.getDayOfMonth() - 1);
        assertThat(day.get("status").asText()).isEqualTo("CLOSED");
        assertThat(day.get("cimsCount").asInt()).isEqualTo(4);
        assertThat(month.get("canSignOff").asBoolean()).isFalse();
        assertThat(month.get("signOffBlocker").asText()).isEqualTo("The month has not ended yet");
    }

    @Test
    void aPaymentReportedOnALaterDayIsMatchedAutomatically() throws Exception {
        Applicant a = admittedWithInvoice("S0101/0001");
        pay(a, annualInvoice(a).get("id").asLong(), "40000");
        String row = s.text(s.call(get("/api/finance/reconciliations/cims-payments.csv?date=" + today), accountant, null)).lines().skip(1).findFirst().orElseThrow();
        LocalDate yesterday = today.minusDays(1);
        movePaymentsTo(yesterday.atTime(23, 50));

        // yesterday's GePG file does not have it: missing in GePG
        JsonNode y = s.body(importFile(accountant, yesterday, HEADER));
        assertThat(kinds(y)).containsExactly("MISSING_IN_GEPG");

        // today's file has it (GePG settled it after midnight): matched, and yesterday's exception settles itself
        JsonNode t = s.body(importFile(accountant, today, HEADER + "\n" + row));
        assertThat(item(t, "MATCHED").get("paidDateDiffers").asBoolean()).isTrue();
        assertThat(t.get("cimsCount").asInt()).isZero();
        JsonNode again = s.body(s.call(get("/api/finance/reconciliations/" + y.get("id").asLong()), viewer, null));
        assertThat(item(again, "MISSING_IN_GEPG").get("resolution").asText()).isEqualTo("AUTO_MATCHED");
        assertThat(again.get("openExceptions").asInt()).isZero();
    }

    @Test
    void theBursarSignsOffAMonthOnceEveryDayIsClosed() throws Exception {
        Applicant a = admittedWithInvoice("S0101/0001");
        pay(a, annualInvoice(a).get("id").asLong(), "60000");
        String row = s.text(s.call(get("/api/finance/reconciliations/cims-payments.csv?date=" + today), accountant, null)).lines().skip(1).findFirst().orElseThrow();
        YearMonth last = YearMonth.from(today).minusMonths(1);
        LocalDate day = last.atDay(15);
        movePaymentsTo(day.atTime(9, 30));

        JsonNode m = s.body(s.call(get("/api/finance/reconciliations?month=" + last), bursar, null));
        assertThat(m.get("collected").decimalValue()).isEqualByComparingTo("60000");
        assertThat(m.get("signOffBlocker").asText()).isEqualTo("Not reconciled yet: " + day);
        s.call(post("/api/finance/reconciliations/months/" + last + "/sign-off"), bursar, "{}").andExpect(jsonPath("$.code").value("CANNOT_SIGN_OFF"));

        JsonNode r = s.body(importFile(accountant, day, HEADER + "\n" + row));
        assertThat(r.get("exceptionCount").asInt()).isZero();
        assertThat(s.body(s.call(get("/api/finance/reconciliations?month=" + last), bursar, null)).get("signOffBlocker").asText())
                .isEqualTo("Still open: " + day);
        s.call(post("/api/finance/reconciliations/" + r.get("id").asLong() + "/close"), accountant, null).andExpect(status().isOk());

        s.call(post("/api/finance/reconciliations/months/" + last + "/sign-off"), accountant, "{}").andExpect(status().isForbidden());
        s.call(post("/api/finance/reconciliations/months/" + last + "/sign-off"), bursar, "{\"note\":\"Agrees with bank statement\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signedByName").value("Staff bursar"))
                .andExpect(jsonPath("$.signOffNote").value("Agrees with bank statement"))
                .andExpect(jsonPath("$.canSignOff").value(false));
        s.call(post("/api/finance/reconciliations/months/" + last + "/sign-off"), bursar, "{}").andExpect(jsonPath("$.code").value("CANNOT_SIGN_OFF"));
    }

    @Test
    void invalidFilesAreRejectedWholeAndDataIsPrivate() throws Exception {
        importFile(accountant, today, "foo,bar\n1,2").andExpect(jsonPath("$.code").value("INVALID_FILE"));
        importFile(accountant, today, HEADER + "\nR1,B1,,,,abc,TZS,,,\nR2,B1,,,,-5,TZS,,,\nR3,B1,,,,10,USD,,,\nR4,B1,,,,10,TZS,yesterday,,")
                .andExpect(jsonPath("$.code").value("INVALID_FILE"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("4 line(s)")));
        importFile(accountant, today.plusDays(1), HEADER).andExpect(jsonPath("$.code").value("FUTURE_DATE"));
        importFile(registrar, today, HEADER).andExpect(status().isForbidden());
        importFile(viewer, today, HEADER).andExpect(status().isForbidden());
        assertThat(s.body(s.call(get("/api/finance/reconciliations?month=" + YearMonth.from(today)), viewer, null))
                .get("days").get(today.getDayOfMonth() - 1).hasNonNull("reconciliationId")).isFalse();

        long id = s.body(importFile(accountant, today, "receipt,bill_id,amount\nX1,B1,1000")).get("id").asLong(); // plain column names work too

        String other = "Q" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TestData.Institution o = data.institution(other, BigDecimal.ZERO);
        data.staff(o.tenant(), "bursar", Role.BURSAR);
        AdmissionScenario os = new AdmissionScenario(mvc, json, other, o);
        os.call(get("/api/finance/reconciliations/" + id), os.staffToken("bursar"), null).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ helpers

    private Applicant admittedWithInvoice(String index) throws Exception {
        long reg = item("REG", "Registration fee", "REGISTRATION", 10);
        long tuition = item("TUITION", "Tuition fee", "TUITION", 50);
        String structure = "{\"academicYearId\":" + yearId + ",\"programmeId\":" + s.inst.programmeId() + ",\"yearOfStudy\":1,\"lines\":["
                + "{\"feeItemId\":" + reg + ",\"amount\":20000,\"instalment\":1},{\"feeItemId\":" + tuition + ",\"amount\":600000,\"instalment\":1}]}";
        long id = s.body(s.call(post("/api/finance/fee-structures"), accountant, structure)).get("id").asLong();
        s.call(post("/api/finance/fee-structures/" + id + "/approve"), bursar, null).andExpect(status().isOk());
        Applicant a = s.apply(index);
        s.admit(officer, chair, a);
        return a;
    }

    private long item(String code, String name, String category, int priority) throws Exception {
        return s.body(s.call(post("/api/finance/fee-items"), accountant,
                "{\"code\":\"" + code + "\",\"name\":\"" + name + "\",\"category\":\"" + category + "\",\"allocationPriority\":" + priority + ",\"active\":true}"))
                .get("id").asLong();
    }

    private JsonNode annualInvoice(Applicant a) throws Exception {
        for (JsonNode i : s.body(s.call(get("/api/applicant/account"), a.token(), null)).get("invoices")) {
            if (i.get("purpose").asText().equals("ANNUAL_FEES")) return i;
        }
        throw new AssertionError("no annual invoice");
    }

    private void pay(Applicant a, long invoiceId, String amount) throws Exception {
        s.call(post("/api/applicant/invoices/" + invoiceId + "/simulate-payment"), a.token(), "{\"amount\":" + amount + "}").andExpect(status().isOk());
    }

    private void movePaymentsTo(java.time.LocalDateTime when) {
        TenantContext.runAs(s.inst.info(), () -> tx.executeWithoutResult(t ->
                payments.findAll().forEach(p -> p.setPaidAt(when.atZone(EAT).toOffsetDateTime()))));
    }

    private org.springframework.test.web.servlet.ResultActions importFile(String token, LocalDate date, String csv) throws Exception {
        ObjectNode body = json.createObjectNode().put("date", date.toString()).put("fileName", "gepg-" + date + ".csv").put("csv", csv);
        return s.call(post("/api/finance/reconciliations"), token, body.toString());
    }

    private static String[] withAmount(String[] row, String amount) {
        String[] copy = row.clone();
        copy[5] = amount;
        return copy;
    }

    private static List<String> kinds(JsonNode r) {
        List<String> out = new ArrayList<>();
        r.get("items").forEach(i -> out.add(i.get("kind").asText()));
        return out;
    }

    private static JsonNode item(JsonNode r, String kind) {
        for (JsonNode i : r.get("items")) if (i.get("kind").asText().equals(kind)) return i;
        throw new AssertionError("no " + kind + " in " + r);
    }
}
