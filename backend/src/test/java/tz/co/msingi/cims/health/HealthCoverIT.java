package tz.co.msingi.cims.health;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Students' health insurance with NHIF's Student Registration API, against CIMS's NHIF simulator: own cards checked with NHIF;
 * students without cover registered with NHIF, a card application with NHIF's control numbers, and NHIF's payment and card
 * callbacks (with the token CIMS issues to NHIF); the registration gate and the reports.
 */
@IntegrationTest
class HealthCoverIT {

    static final String INSTRUCTIONS = """
            {"reportingDate":"2027-09-27","reportingTime":"08:00","reportingVenue":"Main Hall",
             "documentsToBring":["Original CSEE certificate"],"signatoryName":"Dr. Amina Salum","signatoryTitle":"Principal"}""";
    static final String SETTINGS = """
            {"heslbEnabled":false,"nidaEnabled":false,"museEnabled":false,"tamisemiEnabled":false,
             "nhifEnabled":%s,"nhifCode":null,"nhifContribution":50400,"nhifRequired":%s}""";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired HealthCoverService health;
    @Autowired tz.co.msingi.cims.platform.security.mfa.SecretCipher cipher;

    TestData.Institution inst;
    AdmissionScenario s;
    String tenant, officer, registrar, bursar, admin;

    @BeforeEach
    void setUp() throws Exception {
        tenant = "H" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
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

    /** Two active students, by registration number. */
    List<Map<String, Object>> students() throws Exception {
        s.call(put("/api/admission/windows/" + inst.windowId() + "/joining-instructions"), officer, INSTRUCTIONS).andExpect(status().isOk());
        Applicant a = s.applyEligibleEndingIn(2, 100);
        Applicant b = s.applyEligibleEndingIn(5, 300);
        s.admit(officer, s.staffToken("chair"), a, b);
        s.call(post("/api/registry/windows/" + inst.windowId() + "/handover"), registrar, "{}").andExpect(status().isOk());
        return db(() -> {
            jdbc.update("update students set status = 'ACTIVE'");
            return jdbc.queryForList("select id, registration_no, surname from students order by registration_no");
        });
    }

    String studentToken(String regNo) throws Exception {
        return s.body(s.call(post("/api/student-auth/login"), null, "{\"login\":\"" + regNo + "\",\"password\":\"Kifaru#Mto47\"}").andExpect(status().isOk()))
                .get("accessToken").asText();
    }

    @Test
    void ownCardsAreCheckedAndTheInstitutionRegistersTheOthersThroughNhif() throws Exception {
        List<Map<String, Object>> st = students();
        long a = ((Number) st.get(0).get("id")).longValue();
        long b = ((Number) st.get(1).get("id")).longValue();
        String regB = (String) st.get(1).get("registration_no");
        String student = studentToken((String) st.get(0).get("registration_no"));

        // switched off: the student sees it is not in use, and nothing can be recorded
        s.call(get("/api/student/health-cover"), student, null).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        s.call(put("/api/student/health-cover/card"), student, "{\"cardNo\":\"SN10234561\"}").andExpect(jsonPath("$.code").value("NHIF_OFF"));
        s.call(put("/api/integration-settings"), admin, SETTINGS.formatted(true, true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.modes.NHIF").value("SIMULATED"));

        // own cards, as the simulator answers them: unknown, someone else's, then the student's own
        s.call(put("/api/student/health-cover/card"), student, "{\"cardNo\":\"SN1023-4560\"}")
                .andExpect(jsonPath("$.cover.status").value("INVALID")).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("does not know")));
        s.call(put("/api/student/health-cover/card"), student, "{\"cardNo\":\"SN10234568\"}")
                .andExpect(jsonPath("$.cover.status").value("INVALID")).andExpect(jsonPath("$.cover.reason").value(org.hamcrest.Matchers.containsString("NEEMA JOHN KIMARO")));
        s.call(put("/api/student/health-cover/card"), student, "{\"cardNo\":\"x\"}").andExpect(jsonPath("$.code").value("INVALID_CARD"));
        JsonNode mine = s.body(s.call(put("/api/student/health-cover/card"), student, "{\"cardNo\":\"sn 1023 4561\"}").andExpect(status().isOk()));
        assertThat(mine.at("/cover/status").asText()).isEqualTo("ACTIVE");
        assertThat(mine.at("/cover/cardNo").asText()).isEqualTo("SN10234561");
        assertThat(mine.get("covered").asBoolean()).isTrue();
        s.call(put("/api/registry/students/" + b + "/health-cover/card"), registrar, "{\"cardNo\":\"SN10234561\"}").andExpect(jsonPath("$.code").value("CARD_IN_USE"));
        s.call(put("/api/registry/students/" + b + "/health-cover/card"), bursar, "{\"cardNo\":\"SN10234562\"}").andExpect(status().isForbidden());

        // the Registrar puts everyone still without cover on the institution route: only B, ready to be registered with NHIF
        s.call(post("/api/health-covers/enrol"), bursar, null).andExpect(status().isForbidden());
        JsonNode enrolled = s.body(s.call(post("/api/health-covers/enrol"), registrar, null).andExpect(status().isOk()));
        assertThat(enrolled.get("done").asInt()).isEqualTo(1);
        JsonNode coverB = s.body(s.call(get("/api/registry/students/" + b + "/health-cover"), registrar, null));
        assertThat(coverB.at("/cover/status").asText()).isEqualTo("READY");
        assertThat(coverB.at("/cover/invoice").isValueNode() || coverB.at("/cover/invoice").isMissingNode()).isTrue();   // the student pays NHIF, not the institution
        Long yearId = coverB.get("academicYearId").asLong();
        assertThat(db(() -> health.coveredForRegistration(b, yearId))).isFalse();   // required and not paid
        assertThat(db(() -> health.coveredForRegistration(a, yearId))).isTrue();
        s.call(post("/api/health-covers/card-applications"), registrar, null).andExpect(jsonPath("$.code").value("NOTHING_TO_APPLY"));

        JsonNode o = s.body(s.call(get("/api/health-covers/overview"), bursar, null).andExpect(status().isOk()));
        assertThat(o.get("students").asInt()).isEqualTo(2);
        assertThat(o.get("setUp").asBoolean()).isTrue();   // simulated: no credentials needed
        assertThat(o.at("/counts/ACTIVE").asInt()).isEqualTo(1);
        assertThat(o.at("/counts/READY").asInt()).isEqualTo(1);
        assertThat(s.body(s.call(get("/api/health-covers?status=READY"), registrar, null)).findValuesAsText("registrationNo")).containsExactly(regB);

        // step 1: registration with NHIF (the NIDA number goes with it; NHIF's file has CreateStudentDto's columns)
        db(() -> jdbc.update("update students set nin_encrypted = ? where id = ?", cipher.encrypt("20060304-11111-00001-12"), b));
        String file = s.text(s.call(get("/api/health-covers/registration-file"), registrar, null).andExpect(status().isOk()));
        assertThat(file).contains("FormFourIndexNo", "EducationLevelID", regB, "20060304111110000112", "READY");
        JsonNode reg = s.body(s.call(post("/api/health-covers/registrations"), registrar, null).andExpect(status().isOk()));
        boolean refused = regB.endsWith("7");   // the simulator refuses admission numbers ending in 7 (2111, member already exists)
        assertThat(reg.get("done").asInt()).isEqualTo(refused ? 0 : 1);
        if (refused) {
            assertThat(s.body(s.call(get("/api/registry/students/" + b + "/health-cover"), registrar, null)).at("/cover/reason").asText()).contains("2111");
            return;
        }
        assertThat(s.body(s.call(get("/api/registry/students/" + b + "/health-cover"), registrar, null)).at("/cover/status").asText()).isEqualTo("REGISTERED");

        // step 2: the card application; NHIF's control number reaches the student by SMS
        JsonNode intakes = s.body(s.call(get("/api/health-covers/nhif-intakes"), registrar, null).andExpect(status().isOk()));
        assertThat(intakes).hasSize(2);
        JsonNode applied = s.body(s.call(post("/api/health-covers/card-applications?intakeCode=" + intakes.get(0).get("code").asText()), registrar, null)
                .andExpect(status().isOk()));
        assertThat(applied.get("done").asInt()).isEqualTo(1);
        assertThat(applied.get("summary").asText()).contains("1 control number(s) received");
        JsonNode bill = s.body(s.call(get("/api/registry/students/" + b + "/health-cover"), registrar, null));
        assertThat(bill.at("/cover/status").asText()).isEqualTo("AWAITING_PAYMENT");
        assertThat(bill.at("/cover/category").asInt()).isEqualTo(1);
        String controlNo = bill.at("/cover/nhifBill/controlNo").asText();
        assertThat(controlNo).startsWith("995");
        assertThat(bill.at("/cover/nhifBill/amount").decimalValue()).isEqualByComparingTo("50400");
        String batchNo = bill.at("/cover/batchRef").asText();
        assertThat(batchNo).matches(".+/\\d{4}/\\d{4}/\\d{5}-.+");
        assertThat(db(() -> jdbc.queryForObject("select count(*) from messages where template_code = 'NHIF_CONTROL_NUMBER'", Integer.class))).isEqualTo(1);
        assertThat(db(() -> health.coveredForRegistration(b, yearId))).isFalse();

        // NHIF's callbacks: the Institution Admin makes the credentials NHIF uses; NHIF gets a token at CIMS and calls back with it
        s.call(post("/api/nhif-setup/callback-credentials"), registrar, null).andExpect(status().isForbidden());
        JsonNode creds = s.body(s.call(post("/api/nhif-setup/callback-credentials"), admin, null).andExpect(status().isOk()));
        assertThat(creds.get("callbackUrl").asText()).endsWith("/api/integrations/nhif/callback");
        token(creds.get("clientId").asText(), "wrong").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("invalid_client"));
        String token = json.readTree(token(creds.get("clientId").asText(), creds.get("clientSecret").asText()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("access_token").asText();
        callback(null, "{\"controlNo\":\"" + controlNo + "\",\"status\":\"Paid\",\"paymentDate\":\"2027-10-02T10:00:00\"}")
                .andExpect(status().isUnauthorized());

        // payment: the student may register; a repeated callback changes nothing
        callback(token, "{\"controlNo\":\"" + controlNo + "\",\"status\":\"Paid\",\"paymentDate\":\"2027-10-02T10:00:00\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.statusCode").value(200));
        assertThat(db(() -> health.coveredForRegistration(b, yearId))).isTrue();
        callback(token, "{\"controlNo\":\"" + controlNo + "\",\"status\":\"Paid\",\"paymentDate\":\"2027-10-02T10:00:00\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Payment already recorded."));
        // the card (NHIF writes some field names with a stray space)
        callback(token, "{\"batchNo\":\"" + batchNo + "\",\"formFourIndexNo\":\"\",\"identificationNo\":\"20060304111110000112\",\" controlNo\":\""
                + controlNo + "\",\"cardno\":\"SN987654321\"}").andExpect(status().isOk());
        JsonNode after = s.body(s.call(get("/api/registry/students/" + b + "/health-cover"), registrar, null));
        assertThat(after.at("/cover/status").asText()).isEqualTo("ACTIVE");
        assertThat(after.at("/cover/cardNo").asText()).isEqualTo("SN987654321");
        assertThat(after.at("/cover/scheme").asText()).isEqualTo("Student package");
        assertThat(after.at("/cover/nhifBill/paidOn").asText()).isEqualTo("2027-10-02");
        assertThat(db(() -> jdbc.queryForObject("select count(*) from messages where template_code in ('NHIF_PAYMENT_CONFIRMED', 'NHIF_CARD_ISSUED')", Integer.class)))
                .isEqualTo(2);
        callback(token, "{\"controlNo\":\"995000000000\",\"status\":\"Paid\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unrecognized controlNo"));
        callback(token, "{\"hello\":1}").andExpect(status().isBadRequest());
        assertThat(db(() -> jdbc.queryForList("select outcome from nhif_callbacks order by id", String.class)))
                .containsExactly("APPLIED", "IGNORED", "APPLIED", "UNMATCHED", "UNMATCHED");
        // credentials made again: the old token stops working
        s.call(post("/api/nhif-setup/callback-credentials"), admin, null).andExpect(status().isOk());
        callback(token, "{\"controlNo\":\"" + controlNo + "\",\"status\":\"Paid\"}").andExpect(status().isUnauthorized());

        // the batch, re-checking own cards with NHIF, the reports, and the calls in the integration log
        JsonNode batches = s.body(s.call(get("/api/health-covers/overview"), registrar, null)).get("batches");
        assertThat(batches.findValuesAsText("kind")).containsExactly("CARD_APPLICATION", "REGISTRATION");
        assertThat(s.body(s.call(get("/api/health-covers/registrations/" + batches.get(0).get("id").asLong() + "/items"), registrar, null))
                .findValuesAsText("registrationNo")).containsExactly(regB);
        assertThat(s.body(s.call(post("/api/health-covers/recheck"), registrar, null)).get("summary").asText()).startsWith("1 card(s) checked");
        JsonNode rep = s.body(s.call(get("/api/reports/REG-08"), registrar, null).andExpect(status().isOk()));
        assertThat(rep.toString()).contains("Covered %");
        JsonNode without = s.body(s.call(get("/api/reports/REG-09"), registrar, null).andExpect(status().isOk()));
        assertThat(without.get("rowCount").asInt()).isEqualTo(0);
        List<String> refs = db(() -> jdbc.queryForList("select reference from integration_calls where system = 'NHIF' order by id", String.class));
        assertThat(refs).contains("card …4561", "1 student(s)").anyMatch(r -> r.startsWith("batch ")).contains(controlNo);
    }

    @Test
    void aStudentChoosesTheInstitutionAndCanStillGiveACardAndTheRehearsalStandsInForNhif() throws Exception {
        List<Map<String, Object>> st = students();
        s.call(put("/api/integration-settings"), admin, SETTINGS.formatted(true, false)).andExpect(status().isOk());
        String student = studentToken((String) st.get(1).get("registration_no"));
        JsonNode chose = s.body(s.call(post("/api/student/health-cover/institution"), student, null).andExpect(status().isOk()));
        assertThat(chose.at("/cover/route").asText()).isEqualTo("INSTITUTION");
        assertThat(chose.at("/cover/status").asText()).isEqualTo("READY");
        assertThat(chose.get("message").asText()).contains("control number");
        // again: nothing changes
        assertThat(s.body(s.call(post("/api/student/health-cover/institution"), student, null)).at("/cover/id").asLong()).isEqualTo(chose.at("/cover/id").asLong());

        // the student finds their card after all, before the institution applied for one
        JsonNode card = s.body(s.call(put("/api/student/health-cover/card"), student, "{\"cardNo\":\"SN20345673\"}").andExpect(status().isOk()));
        assertThat(card.at("/cover/route").asText()).isEqualTo("OWN_CARD");
        assertThat(card.at("/cover/status").asText()).isEqualTo("ACTIVE");
        s.call(post("/api/student/health-cover/institution"), student, null).andExpect(jsonPath("$.code").value("ALREADY_COVERED"));
        // not required here: registration never waits for cover
        Long yearId = card.get("academicYearId").asLong();
        long other = ((Number) st.get(0).get("id")).longValue();
        assertThat(db(() -> health.coveredForRegistration(other, yearId))).isTrue();

        // the other student through the institution, all the way with the rehearsal (simulated NHIF only)
        String regA = (String) st.get(0).get("registration_no");
        db(() -> jdbc.update("update students set nin_encrypted = ? where id = ?", cipher.encrypt("20060304111110000212"), other));
        s.call(post("/api/registry/students/" + other + "/health-cover/institution"), registrar, null).andExpect(status().isOk());
        s.call(post("/api/health-covers/registrations"), registrar, null).andExpect(status().isOk());
        if (regA.endsWith("7")) return;
        s.call(post("/api/health-covers/card-applications"), registrar, null).andExpect(status().isOk());
        JsonNode sim = s.body(s.call(post("/api/health-covers/simulate-nhif"), registrar, null).andExpect(status().isOk()));
        assertThat(sim.get("done").asInt()).isEqualTo(1);
        JsonNode done = s.body(s.call(get("/api/registry/students/" + other + "/health-cover"), registrar, null));
        assertThat(done.at("/cover/status").asText()).isEqualTo("ACTIVE");
        assertThat(done.at("/cover/cardNo").asText()).startsWith("SN");
    }

    ResultActions token(String clientId, String secret) throws Exception {
        return mvc.perform(post("/api/integrations/nhif/token").header("X-Tenant", tenant).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("client_id", clientId).param("client_secret", secret).param("grant_type", "client_credentials"));
    }

    ResultActions callback(String token, String body) throws Exception {
        var req = post("/api/integrations/nhif/callback").header("X-Tenant", tenant).contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) req.header("Authorization", "Bearer " + token);
        return mvc.perform(req);
    }
}
