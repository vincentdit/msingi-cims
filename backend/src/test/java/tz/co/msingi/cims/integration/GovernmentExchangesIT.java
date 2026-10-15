package tz.co.msingi.cims.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.msingi.cims.finance.domain.Invoice;
import tz.co.msingi.cims.finance.service.BillingService;
import tz.co.msingi.cims.finance.web.GepgCallbackController;
import tz.co.msingi.cims.integration.tamisemi.SimulatedTamisemiClient;
import tz.co.msingi.cims.integration.tamisemi.TamisemiClient;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.support.AdmissionScenario;
import tz.co.msingi.cims.support.AdmissionScenario.Applicant;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The government exchanges against CIMS's simulators (FR-INT-05/06/07/08, FR-REG-07): HESLB registration data, beneficiaries
 * and disbursements; NIN encrypted and checked with NIDA; NACTVET student registration; TAMISEMI selections into a checked
 * import; MUSE fee postings.
 */
@IntegrationTest
class GovernmentExchangesIT {

    static final String INSTRUCTIONS = """
            {"reportingDate":"2027-09-27","reportingTime":"08:00","reportingVenue":"Main Hall",
             "documentsToBring":["Original CSEE certificate"],"signatoryName":"Dr. Amina Salum","signatoryTitle":"Principal"}""";
    static final String SETTINGS = """
            {"gepgSpCode":"SP19921","gepgSubSpCode":"1001","gepgSystemId":"LCIMS001","gepgGfsCode":"140206","heslbEnabled":true,"heslbCode":null,
             "nidaEnabled":%s,"museEnabled":true,"museVoteCode":"V54","museCashAccount":"0150000000","tamisemiEnabled":true,"tamisemiCode":null}""";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired BillingService billing;
    @Autowired TamisemiClient tamisemi;

    TestData.Institution inst;
    AdmissionScenario s;
    String tenant, officer, registrar, bursar, admin;

    @BeforeEach
    void setUp() throws Exception {
        tenant = "X" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        inst = data.institution(tenant, BigDecimal.ZERO);
        data.staff(inst.tenant(), "registrar", Role.REGISTRAR);
        data.staff(inst.tenant(), "bursar", Role.BURSAR);
        data.staff(inst.tenant(), "chair", Role.DEPUTY_PRINCIPAL_ACADEMIC);
        data.staff(inst.tenant(), "admin", Role.INSTITUTION_ADMIN);
        s = new AdmissionScenario(mvc, json, tenant, inst);
        officer = s.staffToken("officer");
        registrar = s.staffToken("registrar");
        bursar = s.staffToken("bursar");
        admin = s.staffToken("admin");
    }

    <T> T db(Supplier<T> work) {
        return TenantContext.callAs(inst.info(), () -> tx.execute(x -> work.get()));
    }

    /** Two active first-year students: one whose index number ends in 2 (a HESLB beneficiary), one ending in 5. */
    List<Long> students() throws Exception {
        s.call(put("/api/admission/windows/" + inst.windowId() + "/joining-instructions"), officer, INSTRUCTIONS).andExpect(status().isOk());
        Applicant a = s.applyEligibleEndingIn(2, 100);
        Applicant b = s.applyEligibleEndingIn(5, 300);
        s.admit(officer, s.staffToken("chair"), a, b);
        s.call(post("/api/registry/windows/" + inst.windowId() + "/handover"), registrar, "{}").andExpect(status().isOk());
        return db(() -> {
            jdbc.update("update students set status = 'ACTIVE'");
            return jdbc.queryForList("select s.id from students s join applicants a on a.id = s.applicant_id order by a.necta_index_number", Long.class);
        });
    }

    @Test
    void settingsAreTheInstitutionsAndSayWhichSystemsAreLive() throws Exception {
        JsonNode v = s.body(s.call(put("/api/integration-settings"), admin, SETTINGS.formatted(false)).andExpect(status().isOk()));
        assertThat(v.at("/settings/gepgSpCode").asText()).isEqualTo("SP19921");
        assertThat(v.at("/modes/GEPG").asText()).isEqualTo("SIMULATED");
        assertThat(v.get("institutionCode").asText()).isEqualTo("REG/" + tenant + "/001");
        s.call(put("/api/integration-settings"), registrar, SETTINGS.formatted(false)).andExpect(status().isForbidden());
        s.call(put("/api/integration-settings"), admin, SETTINGS.formatted(false).replace("SP19921", "SP 1")).andExpect(status().isBadRequest());
    }

    @Test
    void heslbRegistrationBeneficiariesAndDisbursements() throws Exception {
        students();
        s.call(post("/api/heslb/registrations?kind=REGISTRATION"), registrar, null).andExpect(jsonPath("$.code").value("HESLB_OFF"));
        s.call(put("/api/integration-settings"), admin, SETTINGS.formatted(false)).andExpect(status().isOk());

        JsonNode sent = s.body(s.call(post("/api/heslb/registrations?kind=REGISTRATION"), registrar, null).andExpect(status().isOk()));
        assertThat(sent.get("sent").asInt()).isEqualTo(2);
        assertThat(sent.get("accepted").asInt()).isEqualTo(2);
        s.call(post("/api/heslb/registrations?kind=CONTINUATION"), registrar, null).andExpect(jsonPath("$.code").value("NOTHING_TO_SEND"));
        s.call(post("/api/heslb/beneficiaries"), registrar, null).andExpect(status().isForbidden());

        JsonNode ben = s.body(s.call(post("/api/heslb/beneficiaries"), bursar, null).andExpect(status().isOk()));
        assertThat(ben.get("summary").asText()).isEqualTo("1 beneficiaries: 1 new, 0 updated, 0 unchanged");
        Map<String, Object> sp = db(() -> jdbc.queryForMap("""
                select s.kind, x.cap_amount, x.coverage_percent, x.reference from sponsorships x join sponsors s on s.id = x.sponsor_id where s.code = 'HESLB'"""));
        assertThat(sp.get("kind")).isEqualTo("LOAN_BOARD");
        assertThat((BigDecimal) sp.get("cap_amount")).isEqualByComparingTo("1200000");
        assertThat((String) sp.get("reference")).startsWith("SIM27");
        assertThat(s.body(s.call(post("/api/heslb/beneficiaries"), bursar, null)).get("summary").asText()).endsWith("0 new, 0 updated, 1 unchanged");

        JsonNode dis = s.body(s.call(post("/api/heslb/disbursements"), bursar, null).andExpect(status().isOk()));
        assertThat(dis.get("accepted").asInt()).isEqualTo(1);
        assertThat(s.body(s.call(post("/api/heslb/disbursements"), bursar, null)).get("accepted").asInt()).isZero();   // never twice
        JsonNode o = s.body(s.call(get("/api/heslb"), bursar, null).andExpect(status().isOk()));
        assertThat(o.get("sponsored").asInt()).isEqualTo(1);
        assertThat(o.at("/disbursements/0/batchNo").asText()).isEqualTo("SIM/2027/B1");
        assertThat(o.at("/disbursements/0/amount").decimalValue()).isEqualByComparingTo("600000");
        assertThat(o.get("exchanges")).hasSize(5);
        Long otherInvoice = db(() -> billing.createInvoice(new BillingService.NewInvoice(Invoice.PayerType.APPLICANT, 1L, "X", null, null, "TEST", null, "X",
                List.of(new BillingService.NewInvoice.Line("TEST", "X", BigDecimal.TEN)))).getId());
        s.call(post("/api/heslb/disbursements/" + o.at("/disbursements/0/id").asLong() + "/record?invoiceId=" + otherInvoice), bursar, null)
                .andExpect(jsonPath("$.code").value("NOT_HESLB_INVOICE"));
    }

    @Test
    void ninIsEncryptedAndCheckedWithNida() throws Exception {
        List<Long> ids = students();
        long id = ids.get(0);
        String dob = db(() -> jdbc.queryForObject("select date_of_birth::text from students where id = ?", String.class, id));
        String nin = dob.replace("-", "") + "-11111-00001-12";
        // NIDA off: kept as given
        JsonNode v = s.body(s.call(put("/api/registry/students/" + id + "/nin"), registrar, "{\"nin\":\"" + nin + "\"}").andExpect(status().isOk()));
        assertThat(v.get("last4").asText()).isEqualTo("0112");
        assertThat(v.get("status").asText()).isEqualTo("UNVERIFIED");
        String stored = db(() -> jdbc.queryForObject("select nin_encrypted from students where id = ?", String.class, id));
        assertThat(stored).doesNotContain(nin.replace("-", "")).isNotBlank();
        s.call(put("/api/registry/students/" + id + "/nin"), registrar, "{\"nin\":\"123\"}").andExpect(jsonPath("$.code").value("INVALID_NIN"));
        s.call(put("/api/registry/students/" + ids.get(1) + "/nin"), registrar, "{\"nin\":\"" + nin + "\"}").andExpect(jsonPath("$.code").value("NIN_IN_USE"));

        // NIDA on: checked against the names and date of birth
        s.call(put("/api/integration-settings"), admin, SETTINGS.formatted(true)).andExpect(status().isOk());
        s.call(post("/api/registry/students/" + id + "/nin/verify"), registrar, null).andExpect(jsonPath("$.status").value("VALID"));
        s.call(put("/api/registry/students/" + ids.get(1) + "/nin"), registrar, "{\"nin\":\"19990101111110000112\"}")
                .andExpect(jsonPath("$.status").value("MISMATCH")).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("different details")));
        s.call(put("/api/registry/students/" + ids.get(1) + "/nin"), registrar, "{\"nin\":\"19990101111110000119\"}").andExpect(jsonPath("$.status").value("NOT_FOUND"));
        List<String> refs = db(() -> jdbc.queryForList("select reference from integration_calls where system = 'NIDA'", String.class));
        assertThat(refs).hasSize(3).allMatch(r -> r.matches("NIN …\\d{4}"));
        s.call(get("/api/registry/students/" + id + "/nin"), bursar, null).andExpect(status().isForbidden());
    }

    @Test
    void nactvetStudentRegistrationQueriesThoseWithoutVerification() throws Exception {
        List<Long> ids = students();
        db(() -> jdbc.update("update students set nactvet_verification_no = null where id = ?", ids.get(1)));
        JsonNode o = s.body(s.call(get("/api/nactvet-registration"), registrar, null).andExpect(status().isOk()));
        assertThat(o.get("waiting").asInt()).isEqualTo(2);
        assertThat(o.get("withoutVerification").asInt()).isEqualTo(1);

        JsonNode b = s.body(s.call(post("/api/nactvet-registration"), registrar, null).andExpect(status().isOk()));
        assertThat(b.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(b.get("registered").asInt()).isEqualTo(1);
        assertThat(b.get("queried").asInt()).isEqualTo(1);
        JsonNode items = s.body(s.call(get("/api/nactvet-registration/" + b.get("id").asLong() + "/items"), registrar, null));
        assertThat(items.findValuesAsText("reason")).contains("No NACTVET admission verification number");

        // the queried student is corrected and sent again on their own
        db(() -> jdbc.update("update students set nactvet_verification_no = 'NV/2027/999999' where id = ?", ids.get(1)));
        JsonNode again = s.body(s.call(post("/api/nactvet-registration"), registrar, null).andExpect(status().isOk()));
        assertThat(again.get("students").asInt()).isEqualTo(1);
        assertThat(again.get("registered").asInt()).isEqualTo(1);
        s.call(post("/api/nactvet-registration"), registrar, null).andExpect(jsonPath("$.code").value("NOTHING_TO_SEND"));
        List<String> statuses = db(() -> jdbc.queryForList("select nactvet_reg_status from students", String.class));
        assertThat(statuses).containsOnly("REGISTERED");
    }

    @Test
    void tamisemiSelectionsBecomeACheckedImport() throws Exception {
        s.call(put("/api/integration-settings"), admin, SETTINGS.formatted(false)).andExpect(status().isOk());
        int year = LocalDate.now().getYear();
        String code = "REG/" + tenant + "/001";
        s.call(post("/api/tamisemi/fetch?windowId=" + inst.windowId()), officer, null).andExpect(jsonPath("$.code").value("NO_SELECTIONS"));
        SimulatedTamisemiClient sim = AopTestUtils.getTargetObject(tamisemi);
        sim.add(code, year, new TamisemiClient.Selection("S0404/0012", 2025, "Asha", null, "Juma", "F", "2007-05-20", "Tanzanian", "0712000201", "BTCIT",
                "TAMISEMI/" + year + "/00001"));
        sim.add(code, year, new TamisemiClient.Selection("S0404/0022", 2025, "Baraka", null, "Said", "M", "2007-01-11", null, "0712000202", "BTCIT",
                "TAMISEMI/" + year + "/00002"));
        JsonNode f = s.body(s.call(post("/api/tamisemi/fetch?windowId=" + inst.windowId()), officer, null).andExpect(status().isOk()));
        assertThat(f.get("selections").asInt()).isEqualTo(2);
        long batch = f.get("importBatchId").asLong();
        JsonNode b = s.body(s.call(get("/api/imports/batches/" + batch), officer, null).andExpect(status().isOk()));
        assertThat(b.get("externalBody").asText()).isEqualTo("TAMISEMI");
        assertThat(b.get("totalRows").asInt()).isEqualTo(2);
        assertThat(b.get("errorRows").asInt()).isZero();
        s.call(post("/api/imports/batches/" + batch + "/apply"), admin, null).andExpect(jsonPath("$.status").value("APPLIED"));
        Integer apps = db(() -> jdbc.queryForObject("select count(*) from applications where external_body = 'TAMISEMI' and status = 'SELECTED'", Integer.class));
        assertThat(apps).isEqualTo(2);
        assertThat(s.body(s.call(get("/api/tamisemi/fetches"), officer, null))).hasSize(1);
    }

    @Test
    void museReceivesTheSummarisedPostingsOnce() throws Exception {
        s.call(put("/api/integration-settings"), admin, SETTINGS.formatted(false)).andExpect(status().isOk());
        LocalDate yesterday = LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam")).minusDays(1);
        Map<String, String> cn = db(() -> {
            jdbc.update("insert into fee_items (tenant_id, code, name, category, gfs_code) values (cims_current_tenant(), 'TUITION', 'Tuition fee', 'TUITION', '142202080040')");
            Invoice inv = billing.createInvoice(new BillingService.NewInvoice(Invoice.PayerType.APPLICANT, 1L, "Payer", "255700000009", null, "TEST", null, "Fees",
                    List.of(new BillingService.NewInvoice.Line("TUITION", "Tuition", new BigDecimal("300000")),
                            new BillingService.NewInvoice.Line("OTHER", "Other", new BigDecimal("20000")))));
            var c = billing.ensureControlNumber(inv.getId());
            return Map.of("bill", c.getBillId(), "cn", c.getControlNo());
        });
        String body = """
                {"billId":"%s","controlNo":"%s","gepgReceiptNo":"M%s","pspReceiptNo":"P1","pspName":"M-PESA","channel":"MOBILE","amount":320000,
                 "payerPhone":"255700000009","paidAt":"%sT10:00:00+03:00"}""".formatted(cn.get("bill"), cn.get("cn"), UUID.randomUUID().toString().substring(0, 10), yesterday);
        mvc.perform(post("/api/integrations/gepg/payment-notification").contentType(MediaType.APPLICATION_JSON)
                .header("X-Gepg-Signature", GepgCallbackController.sign(body, "test-secret")).content(body)).andExpect(status().isOk());

        JsonNode p = s.body(s.call(get("/api/muse/preview?from=" + yesterday + "&to=" + yesterday), bursar, null).andExpect(status().isOk()));
        assertThat(p.get("problems")).isEmpty();
        assertThat(p.get("total").decimalValue()).isEqualByComparingTo("320000");
        assertThat(p.at("/lines/0/account").asText()).isEqualTo("0150000000");
        assertThat(p.at("/lines/0/debit").decimalValue()).isEqualByComparingTo("320000");
        assertThat(p.get("lines").findValuesAsText("gfsCode")).contains("142202080040", "140206");   // OTHER has no GFS code: the default applies

        JsonNode posted = s.body(s.call(post("/api/muse/postings?from=" + yesterday + "&to=" + yesterday), bursar, null).andExpect(status().isOk()));
        assertThat(posted.get("status").asText()).isEqualTo("POSTED");
        assertThat(posted.get("museRef").asText()).startsWith("SIM/JV/");
        s.call(post("/api/muse/postings?from=" + yesterday + "&to=" + yesterday), bursar, null)
                .andExpect(jsonPath("$.code").value("CANNOT_POST"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("already posted")));
        s.call(get("/api/muse/preview?from=" + yesterday.plusDays(1) + "&to=" + yesterday.plusDays(1)), bursar, null).andExpect(jsonPath("$.code").value("PERIOD_OPEN"));
        assertThat(s.body(s.call(get("/api/muse/postings"), bursar, null))).hasSize(1);
    }
}
