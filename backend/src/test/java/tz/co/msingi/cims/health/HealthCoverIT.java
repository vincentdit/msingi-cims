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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Students' health insurance with NHIF, against CIMS's NHIF simulator: own cards checked with NHIF, the contribution billed to
 * students without cover, paid contributions sent to NHIF and the cards that come back, the registration gate and the reports.
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
    void ownCardsAreCheckedWithNhifAndContributionsLeadToNhifCards() throws Exception {
        List<Map<String, Object>> st = students();
        long a = ((Number) st.get(0).get("id")).longValue();
        long b = ((Number) st.get(1).get("id")).longValue();
        String regB = (String) st.get(1).get("registration_no");
        String student = studentToken((String) st.get(0).get("registration_no"));

        // switched off: the student sees it is not in use, and nothing can be recorded
        s.call(get("/api/student/health-cover"), student, null).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        s.call(put("/api/student/health-cover/card"), student, "{\"cardNo\":\"10234561\"}").andExpect(jsonPath("$.code").value("NHIF_OFF"));
        s.call(put("/api/integration-settings"), admin, SETTINGS.formatted(true, true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.modes.NHIF").value("SIMULATED"));

        // own cards, as the simulator answers them: not found, someone else's, then the student's own
        s.call(put("/api/student/health-cover/card"), student, "{\"cardNo\":\"1023-4560\"}")
                .andExpect(jsonPath("$.cover.status").value("INVALID")).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("no member")));
        s.call(put("/api/student/health-cover/card"), student, "{\"cardNo\":\"10234568\"}")
                .andExpect(jsonPath("$.cover.status").value("INVALID")).andExpect(jsonPath("$.cover.reason").value(org.hamcrest.Matchers.containsString("NEEMA JOHN KIMARO")));
        s.call(put("/api/student/health-cover/card"), student, "{\"cardNo\":\"x\"}").andExpect(jsonPath("$.code").value("INVALID_CARD"));
        JsonNode mine = s.body(s.call(put("/api/student/health-cover/card"), student, "{\"cardNo\":\"1023 4561\"}").andExpect(status().isOk()));
        assertThat(mine.at("/cover/status").asText()).isEqualTo("ACTIVE");
        assertThat(mine.at("/cover/cardNo").asText()).isEqualTo("10234561");
        assertThat(mine.get("covered").asBoolean()).isTrue();
        s.call(put("/api/registry/students/" + b + "/health-cover/card"), registrar, "{\"cardNo\":\"10234561\"}").andExpect(jsonPath("$.code").value("CARD_IN_USE"));
        s.call(put("/api/registry/students/" + b + "/health-cover/card"), bursar, "{\"cardNo\":\"10234562\"}").andExpect(status().isForbidden());

        // the bursary bills everyone still without cover: only B
        s.call(post("/api/health-covers/bill"), registrar, null).andExpect(status().isForbidden());
        JsonNode billed = s.body(s.call(post("/api/health-covers/bill"), bursar, null).andExpect(status().isOk()));
        assertThat(billed.get("done").asInt()).isEqualTo(1);
        JsonNode coverB = s.body(s.call(get("/api/registry/students/" + b + "/health-cover"), registrar, null));
        assertThat(coverB.at("/cover/status").asText()).isEqualTo("AWAITING_PAYMENT");
        assertThat(coverB.at("/cover/invoice/amount").decimalValue()).isEqualByComparingTo("50400");
        assertThat(coverB.at("/cover/invoice/controlNo").asText()).isNotBlank();
        Long yearId = coverB.get("academicYearId").asLong();
        assertThat(db(() -> health.coveredForRegistration(b, yearId))).isFalse();   // required and not paid
        assertThat(db(() -> health.coveredForRegistration(a, yearId))).isTrue();
        s.call(post("/api/health-covers/registrations"), registrar, null).andExpect(jsonPath("$.code").value("NOTHING_TO_SEND"));

        JsonNode o = s.body(s.call(get("/api/health-covers/overview"), bursar, null).andExpect(status().isOk()));
        assertThat(o.get("students").asInt()).isEqualTo(2);
        assertThat(o.at("/counts/ACTIVE").asInt()).isEqualTo(1);
        assertThat(o.at("/counts/AWAITING_PAYMENT").asInt()).isEqualTo(1);
        assertThat(s.body(s.call(get("/api/health-covers?status=AWAITING_PAYMENT"), registrar, null)).findValuesAsText("registrationNo")).containsExactly(regB);

        // B pays: ready for NHIF, and registration may go ahead; the Registrar sends B to NHIF and the card comes back
        db(() -> jdbc.update("update invoices set paid_amount = total_amount, status = 'PAID' where purpose = 'NHIF_CONTRIBUTION'"));
        assertThat(db(() -> health.coveredForRegistration(b, yearId))).isTrue();
        String file = s.text(s.call(get("/api/health-covers/registration-file"), registrar, null).andExpect(status().isOk()));
        assertThat(file).contains("Registration no.", regB, "READY");
        JsonNode batch = s.body(s.call(post("/api/health-covers/registrations"), registrar, null).andExpect(status().isOk()));
        assertThat(batch.get("status").asText()).isEqualTo("COMPLETED");
        boolean refused = regB.endsWith("7");   // the simulator refuses registration numbers ending in 7
        assertThat(batch.get("registered").asInt()).isEqualTo(refused ? 0 : 1);
        JsonNode after = s.body(s.call(get("/api/registry/students/" + b + "/health-cover"), registrar, null));
        if (refused) {
            assertThat(after.at("/cover/status").asText()).isEqualTo("REJECTED");
        } else {
            assertThat(after.at("/cover/status").asText()).isEqualTo("ACTIVE");
            assertThat(after.at("/cover/cardNo").asText()).startsWith("SN");
            assertThat(after.at("/cover/scheme").asText()).isEqualTo("Student package");
            assertThat(db(() -> jdbc.queryForObject("select count(*) from messages where template_code = 'NHIF_CARD_ISSUED'", Integer.class))).isEqualTo(1);
        }
        assertThat(s.body(s.call(get("/api/health-covers/registrations/" + batch.get("id").asLong() + "/items"), registrar, null))
                .findValuesAsText("registrationNo")).containsExactly(regB);

        // re-checking own cards with NHIF, the reports, and the calls in the integration log
        assertThat(s.body(s.call(post("/api/health-covers/recheck"), registrar, null)).get("summary").asText()).startsWith("1 card(s) checked");
        JsonNode rep = s.body(s.call(get("/api/reports/REG-08"), registrar, null).andExpect(status().isOk()));
        assertThat(rep.toString()).contains("Covered %");
        JsonNode without = s.body(s.call(get("/api/reports/REG-09"), registrar, null).andExpect(status().isOk()));
        assertThat(without.get("rowCount").asInt()).isEqualTo(refused ? 1 : 0);
        List<String> refs = db(() -> jdbc.queryForList("select reference from integration_calls where system = 'NHIF' order by id", String.class));
        assertThat(refs).contains("card …4561").anyMatch(r -> r.contains("1 students"));
    }

    @Test
    void aStudentWithoutCoverChoosesTheInstitutionAndCanStillGiveACardBeforePaying() throws Exception {
        List<Map<String, Object>> st = students();
        s.call(put("/api/integration-settings"), admin, SETTINGS.formatted(true, false)).andExpect(status().isOk());
        String student = studentToken((String) st.get(1).get("registration_no"));
        JsonNode chose = s.body(s.call(post("/api/student/health-cover/institution"), student, null).andExpect(status().isOk()));
        assertThat(chose.at("/cover/route").asText()).isEqualTo("INSTITUTION");
        assertThat(chose.at("/cover/status").asText()).isEqualTo("AWAITING_PAYMENT");
        long invoice = chose.at("/cover/invoice/id").asLong();
        // again: nothing billed twice
        assertThat(s.body(s.call(post("/api/student/health-cover/institution"), student, null)).at("/cover/invoice/id").asLong()).isEqualTo(invoice);

        // the student finds their card after all: the unpaid contribution is cancelled
        JsonNode card = s.body(s.call(put("/api/student/health-cover/card"), student, "{\"cardNo\":\"20345673\"}").andExpect(status().isOk()));
        assertThat(card.at("/cover/route").asText()).isEqualTo("OWN_CARD");
        assertThat(card.at("/cover/status").asText()).isEqualTo("ACTIVE");
        assertThat(db(() -> jdbc.queryForObject("select status from invoices where id = ?", String.class, invoice))).isEqualTo("CANCELLED");
        s.call(post("/api/student/health-cover/institution"), student, null).andExpect(jsonPath("$.code").value("ALREADY_COVERED"));
        // not required here: registration never waits for cover
        Long yearId = card.get("academicYearId").asLong();
        long other = ((Number) st.get(0).get("id")).longValue();
        assertThat(db(() -> health.coveredForRegistration(other, yearId))).isTrue();
    }
}
