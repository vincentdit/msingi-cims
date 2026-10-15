package tz.co.msingi.cims.exams;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.ProgrammeRepository;
import tz.co.msingi.cims.academic.domain.Programme;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.tenancy.TenantWork;
import tz.co.msingi.cims.support.AdmissionScenario;
import tz.co.msingi.cims.support.AdmissionScenario.Applicant;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** BP-09, FR-EXM-09: NVA competence assessment from tasks to award eligibility. */
@IntegrationTest
class CompetenceIT {

    static final String INSTRUCTIONS = """
            {"reportingDate":"2027-09-27","reportingVenue":"Main Hall","documentsToBring":["Original CSEE certificate"],
             "signatoryName":"Dr. Amina Salum","signatoryTitle":"Principal"}""";
    static final LocalDate TODAY = LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam"));

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;
    @Autowired TenantWork tenantWork;
    @Autowired ProgrammeRepository programmes;

    AdmissionScenario s;
    TestData.Institution inst;
    String admin, dpa, hod, officer, registrar, viewer, assessor, assessor2, exams;
    long yearId;

    @BeforeEach
    void setUp() throws Exception {
        String tenant = "T" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        inst = data.institution(tenant, BigDecimal.ZERO);
        data.staff(inst.tenant(), "admin", Role.INSTITUTION_ADMIN);
        data.staff(inst.tenant(), "dpa", Role.DEPUTY_PRINCIPAL_ACADEMIC);
        data.staff(inst.tenant(), "hod", Role.HEAD_OF_DEPARTMENT);
        data.staff(inst.tenant(), "registrar", Role.REGISTRAR);
        data.staff(inst.tenant(), "viewer", Role.MANAGEMENT_VIEWER);
        data.staff(inst.tenant(), "assessor", Role.ASSESSOR);
        data.staff(inst.tenant(), "assessor2", Role.ASSESSOR);
        data.staff(inst.tenant(), "exams", Role.EXAMINATIONS_OFFICER);
        s = new AdmissionScenario(mvc, json, tenant, inst);
        admin = s.staffToken("admin");
        dpa = s.staffToken("dpa");
        hod = s.staffToken("hod");
        officer = s.staffToken("officer");
        registrar = s.staffToken("registrar");
        viewer = s.staffToken("viewer");
        assessor = s.staffToken("assessor");
        assessor2 = s.staffToken("assessor2");
        exams = s.staffToken("exams");
        s.call(put("/api/admission/windows/" + inst.windowId() + "/joining-instructions"), officer, INSTRUCTIONS).andExpect(status().isOk());
        yearId = s.body(s.call(get("/api/admission/windows/" + inst.windowId()), officer, null)).get("academicYearId").asLong();
    }

    @Test
    void competenceIsRecordedWithEvidenceVerifiedReassessedAndConfirmedForTheAward() throws Exception {
        Setup c = nvaClass();
        // only NVA modules become units; graded offerings are not created for them
        JsonNode setup = s.body(s.call(post("/api/competence/semesters/" + c.semesterId + "/setup"), exams, null).andExpect(status().isOk()));
        assertThat(setup.get("created").asInt()).isEqualTo(3);
        assertThat(s.body(s.call(post("/api/competence/semesters/" + c.semesterId + "/setup"), exams, null)).get("created").asInt()).isZero();
        s.call(post("/api/competence/semesters/" + c.semesterId + "/setup"), assessor, null).andExpect(status().isForbidden());
        Map<String, Long> unit = new HashMap<>();
        s.body(s.call(get("/api/competence/units?semesterId=" + c.semesterId), exams, null)).forEach(u -> unit.put(u.get("code").asText(), u.get("id").asLong()));
        long u1 = unit.get("ELE 01101");
        s.call(get("/api/competence/units/" + u1), assessor, null).andExpect(status().isForbidden());

        // the Head of Department sets the tasks and allocates the assessor
        JsonNode v = s.body(s.call(put("/api/competence/units/" + u1 + "/tasks"), hod, """
                {"tasks":[{"name":"Wire a lighting circuit","kind":"PRACTICAL","evidenceRequired":"Signed observation checklist","required":true},
                          {"name":"Safety and regulations test","kind":"THEORY","evidenceRequired":"Marked test script","required":true},
                          {"name":"Workplace logbook","kind":"WORKPLACE","required":false}]}""").andExpect(status().isOk()));
        assertThat(v.get("tasks")).hasSize(3);
        long practical = v.at("/tasks/0/id").asLong(), theory = v.at("/tasks/1/id").asLong();
        s.call(put("/api/competence/units/" + u1 + "/tasks"), assessor, "{\"tasks\":[{\"name\":\"x\",\"kind\":\"THEORY\"}]}").andExpect(status().isForbidden());
        s.call(put("/api/competence/units/" + u1 + "/assessors"), hod, "{\"userIds\":[" + staffId("Staff assessor") + "]}").andExpect(status().isOk());
        v = s.body(s.call(get("/api/competence/units/" + u1), assessor, null).andExpect(status().isOk()));
        assertThat(v.at("/can/record").asBoolean()).isTrue();
        assertThat(v.at("/can/submit").asBoolean()).isFalse();
        Map<String, Long> reg = new HashMap<>();
        v.get("rows").forEach(r -> reg.put(r.get("registrationNo").asText(), r.get("moduleRegistrationId").asLong()));

        // outcomes with evidence: a competent in both, b not yet competent in the practical, c assessed on the practical only
        s.call(put("/api/competence/units/" + u1 + "/assessments"), assessor2, entries(entry(reg.get(c.a), practical, "COMPETENT", TODAY, false)))
                .andExpect(status().isForbidden());
        s.call(put("/api/competence/units/" + u1 + "/assessments"), assessor, entries(entry(reg.get(c.a), practical, "COMPETENT", TODAY.plusDays(1), false)))
                .andExpect(jsonPath("$.code").value("FUTURE_DATE"));
        s.call(put("/api/competence/units/" + u1 + "/assessments"), assessor, "{\"entries\":[{\"moduleRegistrationId\":" + reg.get(c.a) + ",\"taskId\":"
                + practical + ",\"outcome\":\"COMPETENT\",\"assessedOn\":\"" + TODAY + "\",\"evidenceRef\":\"\"}]}").andExpect(status().isBadRequest());
        v = s.body(s.call(put("/api/competence/units/" + u1 + "/assessments"), assessor, entries(
                entry(reg.get(c.a), practical, "COMPETENT", TODAY, false), entry(reg.get(c.a), theory, "COMPETENT", TODAY, false),
                entry(reg.get(c.b), practical, "NOT_YET_COMPETENT", TODAY, false), entry(reg.get(c.b), theory, "COMPETENT", TODAY, false),
                entry(reg.get(c.c), practical, "COMPETENT", TODAY, false))).andExpect(status().isOk()));
        Map<String, JsonNode> rows = byRegNo(v.get("rows"));
        assertThat(rows.get(c.a).get("outcome").asText()).isEqualTo("COMPETENT");
        assertThat(rows.get(c.b).get("outcome").asText()).isEqualTo("NOT_YET_COMPETENT");
        assertThat(rows.get(c.c).get("outcome").asText()).isEqualTo("INCOMPLETE");
        assertThat(rows.get(c.a).at("/tasks/" + practical + "/evidenceRef").asText()).isEqualTo("Checklist 7");
        assertThat(rows.get(c.a).at("/tasks/" + practical + "/assessorName").asText()).isEqualTo("Staff assessor");
        s.call(post("/api/competence/units/" + u1 + "/submit"), assessor, null).andExpect(jsonPath("$.code").value("INCOMPLETE"));

        // c's theory, and a correction to b in place (still the first attempt)
        v = s.body(s.call(put("/api/competence/units/" + u1 + "/assessments"), assessor, entries(entry(reg.get(c.c), theory, "COMPETENT", TODAY, false),
                entry(reg.get(c.b), practical, "NOT_YET_COMPETENT", TODAY.minusDays(1), false))));
        assertThat(byRegNo(v.get("rows")).get(c.b).at("/tasks/" + practical + "/attempt").asInt()).isEqualTo(1);
        assertThat(v.at("/can/submit").asBoolean()).isTrue();
        s.call(post("/api/competence/units/" + u1 + "/submit"), assessor, null).andExpect(jsonPath("$.status").value("SUBMITTED"));
        s.call(put("/api/competence/units/" + u1 + "/assessments"), assessor, entries(entry(reg.get(c.b), practical, "COMPETENT", TODAY, false)))
                .andExpect(jsonPath("$.code").value("UNIT_SUBMITTED"));

        // the Head of Department sees it in Approvals, returns it once, then verifies it
        JsonNode inbox = s.body(s.call(get("/api/approvals"), hod, null));
        assertThat(inbox.get("items").findValuesAsText("kind")).contains("COMPETENCE_VERIFY");
        s.call(post("/api/competence/units/" + u1 + "/verify"), assessor, null).andExpect(status().isForbidden());
        s.call(post("/api/competence/units/" + u1 + "/return"), hod, "{\"note\":\"Attach the checklist numbers for every trainee\"}")
                .andExpect(jsonPath("$.status").value("DRAFT")).andExpect(jsonPath("$.returnedNote").value("Attach the checklist numbers for every trainee"));
        s.call(post("/api/competence/units/" + u1 + "/submit"), assessor, null).andExpect(status().isOk());
        v = s.body(s.call(post("/api/competence/units/" + u1 + "/verify"), hod, null).andExpect(status().isOk()));
        assertThat(v.get("status").asText()).isEqualTo("VERIFIED");
        assertThat(v.get("competent").asInt()).isEqualTo(2);
        assertThat(v.get("notYetCompetent").asInt()).isEqualTo(1);
        assertThat(v.has("returnedNote")).isFalse();
        JsonNode sms = s.body(s.call(get("/api/messaging/messages?size=100"), viewer, null)).get("content");
        assertThat(sms.findValuesAsText("templateCode").stream().filter("COMPETENCE_NYC"::equals).count()).isEqualTo(1);

        // the award cannot be confirmed while other units are not verified
        JsonNode classes = s.body(s.call(get("/api/competence/awards?semesterId=" + c.semesterId), exams, null));
        assertThat(classes).hasSize(1);
        assertThat(classes.at("/0/unitsVerified").asInt()).isEqualTo(1);
        assertThat(classes.at("/0/unitsTotal").asInt()).isEqualTo(3);
        String confirm = "{\"semesterId\":" + c.semesterId + ",\"programmeId\":" + inst.programmeId() + ",\"yearOfStudy\":1}";
        s.call(post("/api/competence/awards/confirm"), exams, confirm).andExpect(jsonPath("$.code").value("UNITS_NOT_VERIFIED"));

        // re-assessment: the Examinations Officer reopens; b's practical becomes a second attempt; a's verified competence stays
        s.call(post("/api/competence/units/" + u1 + "/reopen"), assessor, null).andExpect(status().isForbidden());
        s.call(post("/api/competence/units/" + u1 + "/reopen"), exams, null).andExpect(jsonPath("$.status").value("DRAFT"));
        s.call(put("/api/exams/settings"), dpa, "{\"nvaMaxAttempts\":1}").andExpect(jsonPath("$.nvaMaxAttempts").value(1));
        s.call(put("/api/competence/units/" + u1 + "/assessments"), assessor, entries(entry(reg.get(c.b), practical, "COMPETENT", TODAY, false)))
                .andExpect(jsonPath("$.code").value("NO_ATTEMPTS_LEFT"));
        s.call(put("/api/exams/settings"), dpa, "{\"nvaMaxAttempts\":3}").andExpect(status().isOk());
        s.call(put("/api/competence/units/" + u1 + "/assessments"), assessor, entries(entry(reg.get(c.a), practical, "NOT_YET_COMPETENT", TODAY, false)))
                .andExpect(jsonPath("$.code").value("ALREADY_COMPETENT"));
        v = s.body(s.call(put("/api/competence/units/" + u1 + "/assessments"), assessor, entries(entry(reg.get(c.b), practical, "COMPETENT", TODAY, false))));
        JsonNode bCell = byRegNo(v.get("rows")).get(c.b).at("/tasks/" + practical);
        assertThat(bCell.get("attempt").asInt()).isEqualTo(2);
        assertThat(bCell.get("earlier")).hasSize(1);
        assertThat(bCell.get("attemptsLeft").asInt()).isEqualTo(1);
        s.call(post("/api/competence/units/" + u1 + "/submit"), assessor, null).andExpect(status().isOk());
        s.call(post("/api/competence/units/" + u1 + "/verify"), hod, null).andExpect(jsonPath("$.competent").value(3));

        // the other two units, with their default tasks, all competent
        for (String code : List.of("ELE 01102", "ELE 01103")) completeUnit(unit.get(code), c, reg);

        JsonNode list = s.body(s.call(get("/api/competence/awards/list?semesterId=" + c.semesterId + "&programmeId=" + inst.programmeId() + "&yearOfStudy=1"),
                exams, null));
        assertThat(list.get("canConfirm").asBoolean()).isTrue();
        assertThat(list.get("eligible").asInt()).isEqualTo(3);
        assertThat(list.at("/lines/0/unitsRequired").asInt()).isEqualTo(3);
        s.call(post("/api/competence/awards/confirm"), hod, confirm).andExpect(status().isForbidden());
        list = s.body(s.call(post("/api/competence/awards/confirm"), exams, confirm).andExpect(status().isOk()));
        assertThat(list.has("listId")).isTrue();
        assertThat(list.get("confirmedByName").asText()).isEqualTo("Staff exams");
        var xlsx = s.call(get("/api/competence/awards/list.xlsx?semesterId=" + c.semesterId + "&programmeId=" + inst.programmeId() + "&yearOfStudy=1"), exams, null)
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(new String(xlsx.getContentAsByteArray(), 0, 2)).isEqualTo("PK");

        // the trainee sees verified outcomes, task by task
        JsonNode mine = s.body(s.call(get("/api/student/competence"), c.bToken, null));
        assertThat(mine).hasSize(3);
        JsonNode m1 = mine.findParents("code").stream().filter(x -> x.get("code").asText().equals("ELE 01101")).findFirst().orElseThrow();
        assertThat(m1.get("outcome").asText()).isEqualTo("COMPETENT");
        assertThat(m1.at("/tasks/0/attempt").asInt()).isEqualTo(2);

        // the assessor's My teaching lists their units
        JsonNode my = s.body(s.call(get("/api/teaching/mine"), assessor, null));
        assertThat(my.get("competence")).hasSize(3);
    }

    private void completeUnit(long unitId, Setup c, Map<String, Long> reg) throws Exception {
        s.call(put("/api/competence/units/" + unitId + "/assessors"), exams, "{\"userIds\":[" + staffId("Staff assessor") + "]}").andExpect(status().isOk());
        JsonNode v = s.body(s.call(get("/api/competence/units/" + unitId), assessor, null));
        List<String> list = new ArrayList<>();
        for (JsonNode r : v.get("rows")) {
            for (JsonNode t : v.get("tasks")) list.add(entry(r.get("moduleRegistrationId").asLong(), t.get("id").asLong(), "COMPETENT", TODAY, false));
        }
        s.call(put("/api/competence/units/" + unitId + "/assessments"), assessor, "{\"entries\":[" + String.join(",", list) + "]}").andExpect(status().isOk());
        s.call(post("/api/competence/units/" + unitId + "/submit"), assessor, null).andExpect(status().isOk());
        s.call(post("/api/competence/units/" + unitId + "/verify"), hod, null).andExpect(jsonPath("$.status").value("VERIFIED"));
    }

    private static String entry(long reg, long task, String outcome, LocalDate on, boolean reassess) {
        return "{\"moduleRegistrationId\":" + reg + ",\"taskId\":" + task + ",\"outcome\":\"" + outcome + "\",\"practicalScore\":70,\"assessedOn\":\"" + on
                + "\",\"evidenceRef\":\"Checklist 7\",\"reassess\":" + reassess + "}";
    }

    private static String entries(String... e) {
        return "{\"entries\":[" + String.join(",", e) + "]}";
    }

    private long staffId(String name) throws Exception {
        return s.body(s.call(get("/api/exams/staff"), exams, null)).findParents("fullName").stream()
                .filter(x -> x.get("fullName").asText().equals(name)).findFirst().orElseThrow().get("id").asLong();
    }

    record Setup(long semesterId, String a, String b, String c, String aToken, String bToken, String cToken) {
    }

    /** Three trainees of an NVA programme registered for three first-semester units. */
    private Setup nvaClass() throws Exception {
        long m1 = module("ELE 01101"), m2 = module("ELE 01102"), m3 = module("ELE 01103"), m4 = module("ELE 01201");
        long cid = s.body(s.call(post("/api/academic/curricula"), hod, "{\"programmeId\":" + inst.programmeId() + ",\"effectiveYearId\":" + yearId
                + ",\"modules\":[" + slot(m1, 1) + "," + slot(m2, 1) + "," + slot(m3, 1) + "," + slot(m4, 2) + "],\"electiveRules\":[]}")).get("id").asLong();
        s.call(post("/api/academic/curricula/" + cid + "/approve"), dpa, null).andExpect(status().isOk());
        long semId = s.body(s.call(post("/api/academic/semesters"), dpa, "{\"academicYearId\":" + yearId + ",\"semesterNo\":1,\"name\":\"Semester 1\","
                + "\"startDate\":\"2027-09-27\",\"endDate\":\"2028-02-10\",\"registrationOpens\":\"" + TODAY.minusDays(1) + "\",\"registrationCloses\":\""
                + TODAY.plusDays(10) + "\"}").andExpect(status().isCreated())).get("id").asLong();
        Applicant a = s.apply("S0101/0001");
        Applicant b = s.applyEligibleEndingIn(5, 400);
        Applicant c = s.applyEligibleEndingIn(5, 900);
        s.admit(officer, dpa, a, b, c);
        // the programme is an NVA (Level III) programme from here on
        tenantWork.run(inst.tenant(), () -> {
            Programme p = programmes.findById(inst.programmeId()).orElseThrow();
            p.setAwardFamily(Programme.AwardFamily.NVA);
            p.setAwardLevel((short) 3);
            programmes.save(p);
        });
        JsonNode handover = s.body(s.call(post("/api/registry/windows/" + inst.windowId() + "/handover"), registrar, "{}"));
        Map<String, String> byPhone = new HashMap<>();
        for (JsonNode st : handover.get("students")) {
            long id = st.get("studentId").asLong();
            JsonNode d = s.body(s.call(get("/api/registry/students/" + id), registrar, null));
            s.call(put("/api/registry/students/" + id + "/first-registration"), registrar,
                    "{\"documents\":[{\"item\":\"Original CSEE certificate\",\"verified\":true}]}").andExpect(status().isOk());
            s.call(post("/api/registry/students/" + id + "/first-registration/approve"), registrar, null).andExpect(status().isOk());
            byPhone.put(d.at("/summary/phone").asText(), d.at("/summary/registrationNo").asText());
        }
        String[] regNos = new String[3];
        String[] tokens = new String[3];
        Applicant[] apps = {a, b, c};
        for (int i = 0; i < 3; i++) {
            String phone = s.body(s.call(get("/api/applicant/profile"), apps[i].token(), null)).get("phone").asText();
            regNos[i] = byPhone.get(phone);
            tokens[i] = s.body(s.call(post("/api/student-auth/login"), null, "{\"login\":\"" + regNos[i] + "\",\"password\":\"Kifaru#Mto47\"}")).get("accessToken").asText();
            s.call(post("/api/student/semester-registration"), tokens[i], "{\"semesterId\":" + semId + ",\"electiveModuleIds\":[],\"detailsConfirmed\":true}")
                    .andExpect(status().isOk());
        }
        s.call(post("/api/registry/semester-registrations/approve-all"), registrar, "{\"semesterId\":" + semId + "}").andExpect(jsonPath("$.approved").value(3));
        return new Setup(semId, regNos[0], regNos[1], regNos[2], tokens[0], tokens[1], tokens[2]);
    }

    private long module(String code) throws Exception {
        return s.body(s.call(post("/api/academic/modules"), hod, "{\"code\":\"" + code + "\",\"title\":\"Unit " + code
                + "\",\"credits\":10,\"ntaLevel\":4,\"assessmentType\":\"COMPETENCE\",\"active\":true}").andExpect(status().isCreated())).get("id").asLong();
    }

    private static String slot(long moduleId, int sem) {
        return "{\"moduleId\":" + moduleId + ",\"yearOfStudy\":1,\"semesterNo\":" + sem + ",\"category\":\"CORE\"}";
    }

    private static Map<String, JsonNode> byRegNo(JsonNode arr) {
        Map<String, JsonNode> m = new HashMap<>();
        arr.forEach(x -> m.put(x.get("registrationNo").asText(), x));
        return m;
    }
}
