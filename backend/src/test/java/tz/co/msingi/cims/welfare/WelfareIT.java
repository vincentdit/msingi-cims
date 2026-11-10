package tz.co.msingi.cims.welfare;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FR-WEL-01..04: a disciplinary case from report to appeal, a fine on the fees account, and counselling only the Dean of Students reads. */
@IntegrationTest
class WelfareIT {

    static final String INSTRUCTIONS = """
            {"reportingDate":"2027-09-27","reportingVenue":"Main Hall","documentsToBring":["Original CSEE certificate"],
             "signatoryName":"Dr. Amina Salum","signatoryTitle":"Principal"}""";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    AdmissionScenario s;
    TestData.Institution inst;
    String tenant, admin, dpa, registrar, warden, lecturer, dean, principal, student, regNo;

    @BeforeEach
    void setUp() throws Exception {
        tenant = "W" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        inst = data.institution(tenant, BigDecimal.ZERO);
        data.staff(inst.tenant(), "dpa", Role.DEPUTY_PRINCIPAL_ACADEMIC);
        data.staff(inst.tenant(), "registrar", Role.REGISTRAR);
        data.staff(inst.tenant(), "warden", Role.HOSTEL_WARDEN);
        data.staff(inst.tenant(), "lecturer", Role.LECTURER);
        data.staff(inst.tenant(), "dean", Role.DEAN_OF_STUDENTS);
        data.staff(inst.tenant(), "principal", Role.MANAGEMENT_VIEWER);
        s = new AdmissionScenario(mvc, json, tenant, inst);
        admin = s.staffToken("officer");
        dpa = s.staffToken("dpa");
        registrar = s.staffToken("registrar");
        warden = s.staffToken("warden");
        lecturer = s.staffToken("lecturer");
        dean = s.staffToken("dean");
        principal = s.staffToken("principal");
        s.call(put("/api/admission/windows/" + inst.windowId() + "/joining-instructions"), admin, INSTRUCTIONS).andExpect(status().isOk());
        Applicant a = s.apply("S0101/0001");
        s.admit(admin, dpa, a);
        JsonNode handover = s.body(s.call(post("/api/registry/windows/" + inst.windowId() + "/handover"), registrar, "{}"));
        long id = handover.at("/students/0/studentId").asLong();
        s.call(put("/api/registry/students/" + id + "/first-registration"), registrar,
                "{\"documents\":[{\"item\":\"Original CSEE certificate\",\"verified\":true}]}").andExpect(status().isOk());
        s.call(post("/api/registry/students/" + id + "/first-registration/approve"), registrar, null).andExpect(status().isOk());
        regNo = s.body(s.call(get("/api/registry/students/" + id), registrar, null)).at("/summary/registrationNo").asString();
        student = s.body(s.call(post("/api/student-auth/login"), null, "{\"login\":\"" + regNo + "\",\"password\":\"Kifaru#Mto47\"}")).get("accessToken").asString();
    }

    private <T> T db(Supplier<T> work) {
        return TenantContext.callAs(inst.info(), () -> tx.execute(x -> work.get()));
    }

    private int sms(String code) {
        return db(() -> jdbc.queryForObject("select count(*) from messages where template_code = ?", Integer.class, code));
    }

    @Test
    void aCaseIsReportedHeardDecidedAndAppealed() throws Exception {
        // the warden reports; sees only what they reported; the lecturer sees nothing
        String body = "{\"registrationNo\":\"" + regNo + "\",\"category\":\"PROPERTY_DAMAGE\",\"occurredOn\":\"" + LocalDate.now().minusDays(2)
                + "\",\"place\":\"Block A\",\"description\":\"Broke a window in room 12\"}";
        long caseId = s.body(s.call(post("/api/discipline/cases"), warden, body).andExpect(status().isOk())).get("id").asLong();
        s.call(post("/api/discipline/cases"), warden, body.replace(regNo, "NOPE/1")).andExpect(jsonPath("$.code").value("STUDENT_NOT_FOUND"));
        s.call(get("/api/discipline/cases"), warden, null).andExpect(jsonPath("$.length()").value(1));
        s.call(get("/api/discipline/cases"), lecturer, null).andExpect(jsonPath("$.length()").value(0));
        s.call(get("/api/discipline/cases/" + caseId), lecturer, null).andExpect(status().isForbidden());

        // the student is heard before the decision; the hearing is sent by SMS; the student writes a statement
        s.call(post("/api/discipline/cases/" + caseId + "/decision"), dean, "{\"finding\":\"GUILTY\",\"outcome\":\"WARNING\"}")
                .andExpect(jsonPath("$.code").value("HEARING_REQUIRED"));
        s.call(put("/api/discipline/cases/" + caseId + "/hearing"), warden, "{}").andExpect(status().isForbidden());
        s.call(put("/api/discipline/cases/" + caseId + "/hearing"), dean, "{\"hearingAt\":\"" + OffsetDateTime.now().plusDays(3) + "\",\"place\":\"Board room\","
                + "\"committee\":\"Dean, Warden, Student leader\"}").andExpect(jsonPath("$.status").value("HEARING"));
        assertThat(sms("DISCIPLINE_HEARING")).isEqualTo(1);
        long mine = s.body(s.call(get("/api/student/discipline"), student, null)).get(0).get("id").asLong();
        s.call(post("/api/student/discipline/" + mine + "/statement"), student, "{\"text\":\"It was an accident during football.\"}")
                .andExpect(jsonPath("$[0].studentStatement").value("It was an accident during football."));

        // compensation goes on the fees account with a control number
        s.call(post("/api/discipline/cases/" + caseId + "/decision"), dean, "{\"finding\":\"GUILTY\",\"outcome\":\"COMPENSATION\"}")
                .andExpect(jsonPath("$.code").value("AMOUNT_REQUIRED"));
        s.call(post("/api/discipline/cases/" + caseId + "/decision"), dean, "{\"finding\":\"GUILTY\",\"outcome\":\"COMPENSATION\",\"amount\":80000,"
                + "\"details\":\"Replace the window\"}").andExpect(jsonPath("$.status").value("DECIDED")).andExpect(jsonPath("$.invoiceStatus").exists());
        assertThat(db(() -> jdbc.queryForObject("select count(*) from invoices where purpose = 'DISCIPLINE_CHARGES' and total_amount = 80000", Integer.class))).isEqualTo(1);
        assertThat(sms("DISCIPLINE_DECISION")).isEqualTo(1);

        // the student appeals; the Dean (who decided) does not hear it; the Principal varies it
        JsonNode my = s.body(s.call(get("/api/student/discipline"), student, null));
        assertThat(my.get(0).get("canAppeal").asBoolean()).isTrue();
        s.call(post("/api/student/discipline/" + mine + "/appeal"), student, "{\"text\":\"The amount is more than the window costs.\"}")
                .andExpect(jsonPath("$[0].status").value("APPEALED"));
        s.call(post("/api/discipline/cases/" + caseId + "/appeal-decision"), dean, "{\"result\":\"VARIED\"}").andExpect(status().isForbidden());
        s.call(get("/api/approvals"), principal, null).andExpect(jsonPath("$.items[?(@.kind == 'DISCIPLINE_APPEAL')].canAct").value(true));
        s.call(post("/api/discipline/cases/" + caseId + "/appeal-decision"), principal, "{\"result\":\"VARIED\",\"note\":\"Quotation for the glass was TZS 50,000\","
                + "\"outcome\":\"COMPENSATION\",\"amount\":50000}").andExpect(jsonPath("$.status").value("CLOSED")).andExpect(jsonPath("$.amount").value(50000.0));
        assertThat(db(() -> jdbc.queryForObject("select count(*) from invoices where purpose = 'DISCIPLINE_CHARGES' and status = 'CANCELLED'", Integer.class))).isEqualTo(1);
        assertThat(db(() -> jdbc.queryForObject("select count(*) from invoices where purpose = 'DISCIPLINE_CHARGES' and status <> 'CANCELLED' and total_amount = 50000",
                Integer.class))).isEqualTo(1);
        s.call(get("/api/welfare/overview"), principal, null).andExpect(jsonPath("$.outcomesThisYear.COMPENSATION").value(1));
    }

    @Test
    void counsellingIsReadOnlyByTheDean() throws Exception {
        s.call(post("/api/student/welfare"), student, "{\"concern\":\"FINANCIAL\",\"text\":\"I cannot pay for meals this month\",\"contactPhone\":\"0712 345 678\"}")
                .andExpect(jsonPath("$[0].status").value("OPEN"));
        s.call(post("/api/student/welfare"), student, "{\"concern\":\"FAMILY\"}").andExpect(jsonPath("$.code").value("REQUEST_OPEN"));
        s.call(post("/api/welfare/referrals"), lecturer, "{\"registrationNo\":\"" + regNo + "\",\"concern\":\"ACADEMIC\",\"text\":\"Missing classes for two weeks\"}")
                .andExpect(status().isOk());

        // not the administrator, not management: only counts
        s.call(get("/api/welfare/counselling"), admin, null).andExpect(status().isForbidden());
        s.call(get("/api/welfare/overview"), admin, null).andExpect(jsonPath("$.counsellingOpen").value(2)).andExpect(jsonPath("$.counsellingNew").value(2));

        JsonNode list = s.body(s.call(get("/api/welfare/counselling"), dean, null));
        assertThat(list.size()).isEqualTo(2);
        long selfCase = list.findParents("selfReferred").stream().filter(x -> x.get("selfReferred").asBoolean()).findFirst().orElseThrow().get("id").asLong();
        s.call(get("/api/welfare/counselling/" + selfCase), dean, null).andExpect(jsonPath("$.requestText").value("I cannot pay for meals this month"))
                .andExpect(jsonPath("$.contactPhone").value("0712345678"));
        s.call(post("/api/welfare/counselling/" + selfCase + "/notes"), dean, "{\"text\":\"Referred to the bursar for a payment plan.\"}")
                .andExpect(jsonPath("$.notes[0].note").value("Referred to the bursar for a payment plan."));
        // what is written is kept encrypted
        assertThat(db(() -> jdbc.queryForObject("select note_enc from counselling_notes", String.class))).doesNotContain("bursar");
        assertThat(db(() -> jdbc.queryForObject("select request_text_enc from counselling_cases where id = ?", String.class, selfCase))).doesNotContain("meals");

        s.call(put("/api/welfare/counselling/" + selfCase + "/appointment"), dean, "{\"at\":\"" + OffsetDateTime.now().plusDays(1) + "\"}").andExpect(status().isOk());
        assertThat(sms("COUNSELLING_APPOINTMENT")).isEqualTo(1);
        s.call(get("/api/student/welfare"), student, null).andExpect(jsonPath("$[0].nextSessionAt").exists()).andExpect(jsonPath("$[0].requestText").doesNotExist());
        s.call(put("/api/welfare/counselling/" + selfCase + "/open"), dean, "{\"open\":false}").andExpect(jsonPath("$.summary.status").value("CLOSED"));
    }
}
