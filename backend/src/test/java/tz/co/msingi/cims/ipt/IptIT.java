package tz.co.msingi.cims.ipt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tz.co.msingi.cims.exams.RegulationDefaults;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.support.AdmissionScenario;
import tz.co.msingi.cims.support.AdmissionScenario.Applicant;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
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

/** FR-IPT-01..03, BP-13: organisations, placements, letters, logbooks, supervision, assessment and transfer to the examination record. */
@IntegrationTest
class IptIT {

    static final String INSTRUCTIONS = """
            {"reportingDate":"2027-09-27","reportingVenue":"Main Hall","documentsToBring":["Original CSEE certificate"],
             "signatoryName":"Dr. Amina Salum","signatoryTitle":"Principal"}""";
    static final LocalDate TODAY = LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam"));

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;

    AdmissionScenario s;
    TestData.Institution inst;
    String officer, dpa, hod, registrar, exams, coordinator, lecturer, lecturer2, viewer;
    long yearId;

    @BeforeEach
    void setUp() throws Exception {
        String tenant = "I" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        inst = data.institution(tenant, BigDecimal.ZERO);
        data.staff(inst.tenant(), "dpa", Role.DEPUTY_PRINCIPAL_ACADEMIC);
        data.staff(inst.tenant(), "hod", Role.HEAD_OF_DEPARTMENT);
        data.staff(inst.tenant(), "registrar", Role.REGISTRAR);
        data.staff(inst.tenant(), "exams", Role.EXAMINATIONS_OFFICER);
        data.staff(inst.tenant(), "coordinator", Role.IPT_COORDINATOR);
        data.staff(inst.tenant(), "lecturer", Role.LECTURER);
        data.staff(inst.tenant(), "lecturer2", Role.LECTURER);
        data.staff(inst.tenant(), "viewer", Role.MANAGEMENT_VIEWER);
        s = new AdmissionScenario(mvc, json, tenant, inst);
        officer = s.staffToken("officer");
        dpa = s.staffToken("dpa");
        hod = s.staffToken("hod");
        registrar = s.staffToken("registrar");
        exams = s.staffToken("exams");
        coordinator = s.staffToken("coordinator");
        lecturer = s.staffToken("lecturer");
        lecturer2 = s.staffToken("lecturer2");
        viewer = s.staffToken("viewer");
        s.call(put("/api/admission/windows/" + inst.windowId() + "/joining-instructions"), officer, INSTRUCTIONS).andExpect(status().isOk());
        yearId = s.body(s.call(get("/api/admission/windows/" + inst.windowId()), officer, null)).get("academicYearId").asLong();
        s.call(post("/api/exams/regulations"), exams, json.writeValueAsString(RegulationDefaults.ntaCertificate(yearId))).andExpect(status().isCreated());
    }

    @Test
    void studentsArePlacedKeepLogbooksAreAssessedAndTheirMarksReachTheResults() throws Exception {
        Setup c = setup();

        // organisations
        s.call(post("/api/ipt/organisations"), lecturer, "{\"name\":\"TANESCO\"}").andExpect(status().isForbidden());
        long tpa = s.body(s.call(post("/api/ipt/organisations"), coordinator, "{\"name\":\"Tanzania Ports Authority\",\"region\":\"Dar es Salaam\","
                + "\"contactName\":\"Ms. Halima Omari\",\"contactTitle\":\"HR Manager\",\"contactPhone\":\"0754000111\",\"capacity\":1}")
                .andExpect(status().isCreated())).get("id").asLong();
        long tcra = s.body(s.call(post("/api/ipt/organisations"), coordinator, "{\"name\":\"TCRA\",\"region\":\"Dar es Salaam\"}")).get("id").asLong();
        s.call(post("/api/ipt/organisations"), coordinator, "{\"name\":\"tcra\"}").andExpect(jsonPath("$.code").value("DUPLICATE_ORGANISATION"));

        // the period, for the IPT module
        s.call(post("/api/ipt/periods"), coordinator, period(c, "40", "30", "40")).andExpect(jsonPath("$.code").value("INVALID_WEIGHTS"));
        long pid = s.body(s.call(post("/api/ipt/periods"), coordinator, period(c, "40", "30", "30")).andExpect(status().isCreated())).get("id").asLong();
        assertThat(s.body(s.call(get("/api/ipt/periods/" + pid + "/students"), coordinator, null))).hasSize(3);

        // students ask: a at TPA, b at an organisation not yet registered
        JsonNode mine = s.body(s.call(get("/api/student/ipt"), c.aToken, null));
        assertThat(mine.get("periods")).hasSize(1);
        s.call(post("/api/student/ipt/placements"), c.aToken, "{\"periodId\":" + pid + ",\"organisationId\":" + tpa + ",\"unit\":\"ICT Section\","
                + "\"workplaceSupervisorName\":\"Mr. Peter Mushi\"}").andExpect(jsonPath("$.placement.summary.status").value("REQUESTED"));
        s.call(post("/api/student/ipt/placements"), c.aToken, "{\"periodId\":" + pid + ",\"organisationId\":" + tcra + "}").andExpect(jsonPath("$.code").value("ALREADY_PLACED"));
        s.call(post("/api/student/ipt/placements"), c.bToken, "{\"periodId\":" + pid + ",\"newOrganisationName\":\"Vodacom Tanzania\",\"newOrganisationRegion\":\"Dodoma\"}")
                .andExpect(status().isOk());
        Map<String, JsonNode> pl = byRegNo(s.body(s.call(get("/api/ipt/periods/" + pid + "/placements"), coordinator, null)));
        assertThat(pl.get(c.b).get("organisation").asText()).isEqualTo("Vodacom Tanzania");
        assertThat(s.body(s.call(get("/api/ipt/organisations"), coordinator, null)).findParents("name").stream()
                .filter(x -> x.get("name").asText().equals("Vodacom Tanzania")).findFirst().orElseThrow().get("proposedByStudent").asBoolean()).isTrue();

        // the coordinator confirms a with an institution supervisor, declines b with a reason, places c directly
        long lecturerId = staffId("Staff lecturer");
        long aPl = pl.get(c.a).get("id").asLong(), bPl = pl.get(c.b).get("id").asLong();
        s.call(post("/api/ipt/placements/" + bPl + "/decision"), coordinator, "{\"confirm\":false}").andExpect(jsonPath("$.code").value("NOTE_REQUIRED"));
        s.call(post("/api/ipt/placements/" + bPl + "/decision"), coordinator, "{\"confirm\":false,\"note\":\"Vodacom has no IT attachment this year\"}")
                .andExpect(jsonPath("$.summary.status").value("DECLINED"));
        JsonNode aView = s.body(s.call(post("/api/ipt/placements/" + aPl + "/decision"), coordinator, "{\"confirm\":true,\"institutionSupervisorId\":" + lecturerId + "}")
                .andExpect(jsonPath("$.summary.status").value("CONFIRMED")));
        assertThat(aView.at("/summary/letterNo").asText()).contains("/IPT/");
        assertThat(aView.at("/summary/institutionSupervisorName").asText()).isEqualTo("Staff lecturer");
        s.call(post("/api/ipt/placements"), coordinator, "{\"periodId\":" + pid + ",\"studentId\":" + c.cId + ",\"organisationId\":" + tpa + "}")
                .andExpect(jsonPath("$.code").value("ORGANISATION_FULL"));
        long cPl = s.body(s.call(post("/api/ipt/placements"), coordinator, "{\"periodId\":" + pid + ",\"studentId\":" + c.cId + ",\"organisationId\":" + tcra
                + ",\"institutionSupervisorId\":" + lecturerId + "}").andExpect(jsonPath("$.summary.status").value("CONFIRMED"))).at("/summary/id").asLong();
        byte[] letter = mvc.perform(get("/api/student/ipt/letter").header("Authorization", "Bearer " + c.aToken).header("X-Tenant", s.tenant))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(letter, 0, 4)).isEqualTo("%PDF");
        List<String> sms = s.body(s.call(get("/api/messaging/messages?size=100"), viewer, null)).get("content").findValuesAsText("templateCode");
        assertThat(sms).contains("IPT_PLACEMENT_CONFIRMED", "IPT_PLACEMENT_DECLINED");

        // logbook: draft, submit, returned, corrected, approved
        String week = TODAY.minusDays(7).toString();
        s.call(put("/api/student/ipt/logbook"), c.aToken, logbook(1, TODAY.plusDays(7).toString(), false)).andExpect(jsonPath("$.code").value("FUTURE_WEEK"));
        s.call(put("/api/student/ipt/logbook"), c.aToken, logbook(1, week, false)).andExpect(jsonPath("$.logbook[0].status").value("DRAFT"));
        JsonNode lv = s.body(s.call(put("/api/student/ipt/logbook"), c.aToken, logbook(1, week, true)));
        long entry = lv.at("/logbook/0/id").asLong();
        s.call(put("/api/student/ipt/logbook"), c.aToken, logbook(1, week, false)).andExpect(jsonPath("$.code").value("ENTRY_LOCKED"));
        s.call(post("/api/ipt/logbook/" + entry + "/review"), lecturer2, "{\"approve\":true}").andExpect(status().isForbidden());
        assertThat(s.body(s.call(get("/api/ipt/supervised"), lecturer, null))).hasSize(2);
        s.call(post("/api/ipt/logbook/" + entry + "/review"), lecturer, "{\"approve\":false}").andExpect(jsonPath("$.code").value("COMMENT_REQUIRED"));
        s.call(post("/api/ipt/logbook/" + entry + "/review"), lecturer, "{\"approve\":false,\"comment\":\"Describe the network tasks in more detail\"}")
                .andExpect(jsonPath("$.status").value("RETURNED"));
        s.call(put("/api/student/ipt/logbook"), c.aToken, logbook(1, week, true)).andExpect(status().isOk());
        s.call(post("/api/ipt/logbook/" + entry + "/review"), lecturer, "{\"approve\":true,\"comment\":\"Good\"}").andExpect(jsonPath("$.status").value("APPROVED"));

        // supervision and the three marks
        s.call(post("/api/ipt/placements/" + aPl + "/visits"), lecturer, "{\"visitedOn\":\"" + TODAY + "\",\"mode\":\"VISIT\",\"notes\":\"Student settled well\"}")
                .andExpect(jsonPath("$.visits[0].mode").value("VISIT"));
        s.call(put("/api/ipt/placements/" + aPl + "/assessments"), lecturer2, "{\"kind\":\"WORKPLACE\",\"score\":80}").andExpect(status().isForbidden());
        s.call(put("/api/ipt/placements/" + aPl + "/assessments"), lecturer, "{\"kind\":\"WORKPLACE\",\"score\":80,\"comments\":\"From the workplace form\"}")
                .andExpect(status().isOk());
        s.call(put("/api/ipt/placements/" + aPl + "/assessments"), lecturer, "{\"kind\":\"INSTITUTION\",\"score\":70}").andExpect(status().isOk());
        JsonNode done = s.body(s.call(put("/api/ipt/placements/" + aPl + "/assessments"), lecturer, "{\"kind\":\"LOGBOOK\",\"score\":60}").andExpect(status().isOk()));
        assertThat(done.at("/summary/status").asText()).isEqualTo("COMPLETED");
        assertThat(done.at("/summary/totalMark").decimalValue()).isEqualByComparingTo("71");     // 80×40% + 70×30% + 60×30%
        s.call(put("/api/ipt/placements/" + cPl + "/assessments"), lecturer, "{\"kind\":\"LOGBOOK\",\"score\":60}").andExpect(jsonPath("$.code").value("LOGBOOK_INCOMPLETE"));

        // transfer to the IPT module's marks; c is not ready
        s.call(post("/api/ipt/periods/" + pid + "/transfer"), lecturer, null).andExpect(status().isForbidden());
        JsonNode tr = s.body(s.call(post("/api/ipt/periods/" + pid + "/transfer"), coordinator, null).andExpect(status().isOk()));
        assertThat(tr.get("transferred").asInt()).isEqualTo(1);
        assertThat(tr.get("notReady").get(0).asText()).contains("3 mark(s) missing");
        JsonNode off = s.body(s.call(get("/api/exams/offerings/" + c.iptOffering), exams, null));
        JsonNode row = off.get("rows").findParents("registrationNo").stream().filter(x -> x.get("registrationNo").asText().equals(c.a)).findFirst().orElseThrow();
        assertThat(row.get("total").decimalValue()).isEqualByComparingTo("71");
        assertThat(row.get("grade").asText()).isEqualTo("B");

        // views and reports
        JsonNode myView = s.body(s.call(get("/api/student/ipt"), c.aToken, null));
        assertThat(myView.at("/placement/summary/totalMark").decimalValue()).isEqualByComparingTo("71");
        assertThat(myView.at("/placement/summary/transferredAt").isMissingNode()).isFalse();
        assertThat(s.body(s.call(get("/api/reports/IPT-01?semesterId=" + c.semesterId), coordinator, null)).get("rowCount").asInt()).isEqualTo(2);
        assertThat(s.body(s.call(get("/api/reports/IPT-02?semesterId=" + c.semesterId), coordinator, null)).get("rowCount").asInt()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ scenario

    record Setup(long semesterId, long iptModuleId, long iptOffering, String a, String b, long cId, String aToken, String bToken) {
    }

    private Setup setup() throws Exception {
        long m1 = module("ITU 04101", "Computing"), ipt = module("ITU 04199", "Industrial Practical Training"), m4 = module("ITU 04201", "Networks");
        long cid = s.body(s.call(post("/api/academic/curricula"), hod, "{\"programmeId\":" + inst.programmeId() + ",\"effectiveYearId\":" + yearId
                + ",\"modules\":[" + slot(m1, 1) + "," + slot(ipt, 1) + "," + slot(m4, 2) + "],\"electiveRules\":[]}")).get("id").asLong();
        s.call(post("/api/academic/curricula/" + cid + "/approve"), dpa, null).andExpect(status().isOk());
        long semId = s.body(s.call(post("/api/academic/semesters"), dpa, "{\"academicYearId\":" + yearId + ",\"semesterNo\":1,\"name\":\"Semester 1\","
                + "\"startDate\":\"2027-09-27\",\"endDate\":\"2028-02-10\",\"registrationOpens\":\"" + TODAY.minusDays(1) + "\",\"registrationCloses\":\""
                + TODAY.plusDays(10) + "\",\"marksEntryOpens\":\"" + TODAY.minusDays(1) + "\",\"marksEntryCloses\":\"" + TODAY.plusDays(90) + "\"}")
                .andExpect(status().isCreated())).get("id").asLong();
        Applicant a = s.apply("S0101/0001");
        Applicant b = s.applyEligibleEndingIn(5, 400);
        Applicant c = s.applyEligibleEndingIn(5, 900);
        s.admit(officer, dpa, a, b, c);
        JsonNode handover = s.body(s.call(post("/api/registry/windows/" + inst.windowId() + "/handover"), registrar, "{}"));
        Map<String, String> byPhone = new HashMap<>();
        Map<String, Long> ids = new HashMap<>();
        for (JsonNode st : handover.get("students")) {
            long id = st.get("studentId").asLong();
            JsonNode d = s.body(s.call(get("/api/registry/students/" + id), registrar, null));
            s.call(put("/api/registry/students/" + id + "/first-registration"), registrar,
                    "{\"documents\":[{\"item\":\"Original CSEE certificate\",\"verified\":true}]}").andExpect(status().isOk());
            s.call(post("/api/registry/students/" + id + "/first-registration/approve"), registrar, null).andExpect(status().isOk());
            byPhone.put(d.at("/summary/phone").asText(), d.at("/summary/registrationNo").asText());
            ids.put(d.at("/summary/registrationNo").asText(), id);
        }
        Applicant[] apps = {a, b, c};
        String[] regNos = new String[3];
        String[] tokens = new String[3];
        for (int i = 0; i < 3; i++) {
            String phone = s.body(s.call(get("/api/applicant/profile"), apps[i].token(), null)).get("phone").asText();
            regNos[i] = byPhone.get(phone);
            tokens[i] = s.body(s.call(post("/api/student-auth/login"), null, "{\"login\":\"" + regNos[i] + "\",\"password\":\"Secret123\"}")).get("accessToken").asText();
            s.call(post("/api/student/semester-registration"), tokens[i], "{\"semesterId\":" + semId + ",\"electiveModuleIds\":[],\"detailsConfirmed\":true}")
                    .andExpect(status().isOk());
        }
        s.call(post("/api/registry/semester-registrations/approve-all"), registrar, "{\"semesterId\":" + semId + "}").andExpect(jsonPath("$.approved").value(3));
        s.call(post("/api/exams/offerings/setup"), exams, "{\"semesterId\":" + semId + "}").andExpect(status().isOk());
        long iptOffering = s.body(s.call(get("/api/exams/offerings?semesterId=" + semId), exams, null)).findParents("code").stream()
                .filter(x -> x.get("code").asText().equals("ITU 04199")).findFirst().orElseThrow().get("id").asLong();
        return new Setup(semId, ipt, iptOffering, regNos[0], regNos[1], ids.get(regNos[2]), tokens[0], tokens[1]);
    }

    private long module(String code, String title) throws Exception {
        return s.body(s.call(post("/api/academic/modules"), hod, "{\"code\":\"" + code + "\",\"title\":\"" + title
                + "\",\"credits\":10,\"ntaLevel\":4,\"assessmentType\":\"GRADED\",\"active\":true}").andExpect(status().isCreated())).get("id").asLong();
    }

    private static String slot(long moduleId, int sem) {
        return "{\"moduleId\":" + moduleId + ",\"yearOfStudy\":1,\"semesterNo\":" + sem + ",\"category\":\"CORE\"}";
    }

    private String period(Setup c, String w, String i, String l) {
        return "{\"name\":\"IPT 2028\",\"semesterId\":" + c.semesterId + ",\"moduleId\":" + c.iptModuleId + ",\"startsOn\":\"" + TODAY.minusDays(14)
                + "\",\"endsOn\":\"" + TODAY.plusDays(60) + "\",\"workplaceWeight\":" + w + ",\"institutionWeight\":" + i + ",\"logbookWeight\":" + l
                + ",\"minLogbookWeeks\":1}";
    }

    private static String logbook(int week, String starting, boolean submit) {
        return "{\"weekNo\":" + week + ",\"weekStarting\":\"" + starting + "\",\"activities\":\"Configured switches and VLANs\",\"hours\":40,\"submit\":" + submit + "}";
    }

    private long staffId(String name) throws Exception {
        return s.body(s.call(get("/api/ipt/staff"), coordinator, null)).findParents("fullName").stream()
                .filter(x -> x.get("fullName").asText().equals(name)).findFirst().orElseThrow().get("id").asLong();
    }

    private static Map<String, JsonNode> byRegNo(JsonNode arr) {
        Map<String, JsonNode> m = new HashMap<>();
        arr.forEach(x -> m.put(x.get("registrationNo").asText(), x));
        return m;
    }
}
