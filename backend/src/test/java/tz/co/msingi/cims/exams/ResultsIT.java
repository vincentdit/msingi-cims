package tz.co.msingi.cims.exams;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FR-ACA-05 and FR-EXM-01/02/03/05/06/07/10 (BP-08): from regulations and marks to published, locked results. */
@IntegrationTest
class ResultsIT {

    static final String INSTRUCTIONS = """
            {"reportingDate":"2027-09-27","reportingVenue":"Main Hall","documentsToBring":["Original CSEE certificate"],
             "signatoryName":"Dr. Amina Salum","signatoryTitle":"Principal"}""";
    static final LocalDate TODAY = LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam"));

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    AdmissionScenario s;
    TestData.Institution inst;
    String admin, dpa, hod, otherHod, officer, registrar, viewer, lecturer, lecturer2, exams, accountant, bursar;
    long yearId, lecturerId;

    @BeforeEach
    void setUp() throws Exception {
        String tenant = "R" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        inst = data.institution(tenant, BigDecimal.ZERO);
        data.staff(inst.tenant(), "admin", Role.INSTITUTION_ADMIN);
        data.staff(inst.tenant(), "dpa", Role.DEPUTY_PRINCIPAL_ACADEMIC);
        data.staff(inst.tenant(), "hod", Role.HEAD_OF_DEPARTMENT);
        data.staffScoped(inst.tenant(), "otherhod", Role.HEAD_OF_DEPARTMENT, "DEPARTMENT", 999_999L);
        data.staff(inst.tenant(), "registrar", Role.REGISTRAR);
        data.staff(inst.tenant(), "viewer", Role.MANAGEMENT_VIEWER);
        data.staff(inst.tenant(), "lecturer", Role.LECTURER);
        data.staff(inst.tenant(), "lecturer2", Role.LECTURER);
        data.staff(inst.tenant(), "exams", Role.EXAMINATIONS_OFFICER);
        data.staff(inst.tenant(), "accountant", Role.ACCOUNTANT);
        data.staff(inst.tenant(), "bursar", Role.BURSAR);
        s = new AdmissionScenario(mvc, json, tenant, inst);
        admin = s.staffToken("admin");
        dpa = s.staffToken("dpa");
        hod = s.staffToken("hod");
        otherHod = s.staffToken("otherhod");
        officer = s.staffToken("officer");
        registrar = s.staffToken("registrar");
        viewer = s.staffToken("viewer");
        lecturer = s.staffToken("lecturer");
        lecturer2 = s.staffToken("lecturer2");
        exams = s.staffToken("exams");
        accountant = s.staffToken("accountant");
        bursar = s.staffToken("bursar");
        s.call(put("/api/admission/windows/" + inst.windowId() + "/joining-instructions"), officer, INSTRUCTIONS).andExpect(status().isOk());
        yearId = s.body(s.call(get("/api/admission/windows/" + inst.windowId()), officer, null)).get("academicYearId").asLong();
    }

    @Test
    void regulationsAreCheckedAndCannotChangeOnceUsed() throws Exception {
        String cert = json.writeValueAsString(RegulationDefaults.ntaCertificate(yearId));
        s.call(post("/api/exams/regulations"), lecturer, cert).andExpect(status().isForbidden());
        JsonNode r = s.body(s.call(post("/api/exams/regulations"), exams, cert).andExpect(status().isCreated()));
        assertThat(r.get("caPassMark").decimalValue()).isEqualByComparingTo("20");
        assertThat(r.get("sePassMark").decimalValue()).isEqualByComparingTo("30");
        assertThat(r.get("gradeBands").findValuesAsText("grade")).containsExactly("A", "B", "C", "D", "F");
        assertThat(r.get("inUse").asBoolean()).isFalse();
        s.call(post("/api/exams/regulations"), exams, cert).andExpect(jsonPath("$.code").value("OVERLAPPING_REGULATIONS"));
        s.call(post("/api/exams/regulations"), exams, cert.replace("\"levelFrom\":4,\"levelTo\":5", "\"levelFrom\":6,\"levelTo\":6")
                .replace("\"maxMark\":64", "\"maxMark\":63")).andExpect(jsonPath("$.code").value("INVALID_SCALE"));
        s.call(post("/api/exams/regulations"), exams, json.writeValueAsString(RegulationDefaults.ntaDiploma(yearId))
                .replace("\"seWeight\":60", "\"seWeight\":50")).andExpect(jsonPath("$.code").value("INVALID_WEIGHTS"));
        s.call(put("/api/exams/regulations/" + r.get("id").asLong()), dpa, cert.replace("Levels 4–5 (certificates)", "Levels 4–5"))
                .andExpect(jsonPath("$.name").value("NTA Levels 4–5"));
        assertThat(s.body(s.call(get("/api/exams/regulations"), lecturer, null))).hasSize(1);
    }

    @Test
    void marksBecomeApprovedLockedAndPublishedResults() throws Exception {
        long regsId = s.body(s.call(post("/api/exams/regulations"), exams, json.writeValueAsString(RegulationDefaults.ntaCertificate(yearId)))).get("id").asLong();
        Class c = classOfThree();
        String sem = "{\"semesterId\":" + c.semesterId + "}";

        // set-up: one offering per module with the default 40/60 plan; lecturers allocated
        s.call(post("/api/exams/offerings/setup"), lecturer, sem).andExpect(status().isForbidden());
        assertThat(s.body(s.call(post("/api/exams/offerings/setup"), exams, sem)).get("created").asInt()).isEqualTo(3);
        assertThat(s.body(s.call(post("/api/exams/offerings/setup"), exams, sem)).get("created").asInt()).isZero();
        Map<String, Long> off = new HashMap<>();
        s.body(s.call(get("/api/exams/offerings?semesterId=" + c.semesterId), exams, null)).forEach(o -> off.put(o.get("code").asText(), o.get("id").asLong()));
        lecturerId = s.body(s.call(get("/api/exams/staff"), exams, null)).findParents("fullName").stream()
                .filter(x -> x.get("fullName").asText().equals("Staff lecturer")).findFirst().orElseThrow().get("id").asLong();
        for (Long id : off.values()) {
            s.call(put("/api/exams/offerings/" + id + "/staff"), hod, "{\"userIds\":[" + lecturerId + "]}").andExpect(status().isOk());
        }
        assertThat(s.body(s.call(get("/api/exams/offerings?semesterId=" + c.semesterId + "&mine=true"), lecturer, null))).hasSize(3);
        assertThat(s.body(s.call(get("/api/exams/offerings?semesterId=" + c.semesterId + "&mine=true"), lecturer2, null))).isEmpty();
        s.call(get("/api/exams/offerings/" + off.get("ITU 04101")), lecturer2, null).andExpect(status().isForbidden());

        // M1: CA split into a test and an assignment
        long m1 = off.get("ITU 04101");
        JsonNode v = s.body(s.call(get("/api/exams/offerings/" + m1), lecturer, null));
        long seId = v.get("components").get(1).get("id").asLong();
        s.call(put("/api/exams/offerings/" + m1 + "/plan"), lecturer, plan(null, "Test 1", "CA", 15, 30, null, "Assignment", "CA", 20, 50, seId, "Final", "SE", 60, 100))
                .andExpect(jsonPath("$.code").value("PLAN_WEIGHTS"));
        v = s.body(s.call(put("/api/exams/offerings/" + m1 + "/plan"), lecturer,
                plan(null, "Test 1", "CA", 15, 30, null, "Assignment", "CA", 25, 50, seId, "Semester examination", "SE", 60, 100)).andExpect(status().isOk()));
        List<Long> comp = new ArrayList<>();
        v.get("components").forEach(x -> comp.add(x.get("id").asLong()));
        Map<String, Long> reg = new HashMap<>();
        v.get("rows").forEach(x -> reg.put(x.get("registrationNo").asText(), x.get("moduleRegistrationId").asLong()));
        s.call(put("/api/exams/offerings/" + m1 + "/scores"), lecturer2, scores(reg.get(c.a), comp.get(0), "27")).andExpect(status().isForbidden());
        s.call(put("/api/exams/offerings/" + m1 + "/scores"), lecturer, scores(reg.get(c.a), comp.get(0), "31")).andExpect(jsonPath("$.code").value("INVALID_SCORE"));
        s.call(put("/api/exams/offerings/" + m1 + "/scores"), lecturer, "{\"entries\":["
                + entry(reg.get(c.a), comp.get(0), "27") + "," + entry(reg.get(c.a), comp.get(1), "45") + "," + entry(reg.get(c.a), comp.get(2), "80") + ","
                + entry(reg.get(c.b), comp.get(0), "15") + "," + entry(reg.get(c.b), comp.get(1), "30") + "," + entry(reg.get(c.b), comp.get(2), "55") + ","
                + entry(reg.get(c.c), comp.get(0), "20") + "," + entry(reg.get(c.c), comp.get(1), "40") + "]}").andExpect(status().isOk());
        s.call(post("/api/exams/offerings/" + m1 + "/submit"), lecturer, null).andExpect(jsonPath("$.code").value("MARKS_INCOMPLETE"));
        v = s.body(s.call(put("/api/exams/offerings/" + m1 + "/scores"), lecturer,
                "{\"entries\":[{\"moduleRegistrationId\":" + reg.get(c.c) + ",\"componentId\":" + comp.get(2) + ",\"absent\":true}]}"));
        Map<String, JsonNode> rows = rows(v);
        assertThat(rows.get(c.a).get("total").decimalValue()).isEqualByComparingTo("84");
        assertThat(rows.get(c.a).get("grade").asText()).isEqualTo("A");
        assertThat(rows.get(c.b).get("total").decimalValue()).isEqualByComparingTo("56");
        assertThat(rows.get(c.b).get("grade").asText()).isEqualTo("C");
        assertThat(rows.get(c.c).get("remark").asText()).isEqualTo("ABSENT");
        assertThat(v.at("/stats/flags").get(0).asText()).isEqualTo("1 absent from the semester examination (special examination)");
        s.call(post("/api/exams/offerings/" + m1 + "/submit"), lecturer, null).andExpect(jsonPath("$.status").value("SUBMITTED"));
        s.call(put("/api/exams/offerings/" + m1 + "/scores"), lecturer, scores(reg.get(c.a), comp.get(0), "28")).andExpect(jsonPath("$.code").value("RESULTS_SUBMITTED"));

        // M2 by Excel: a file with errors changes nothing; the corrected file is applied
        long m2 = off.get("ITU 04102");
        byte[] template = mvc.perform(get("/api/exams/offerings/" + m2 + "/marks-template").header("Authorization", "Bearer " + lecturer)
                .header("X-Tenant", s.tenant)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        Map<String, String[]> bad = Map.of(c.a, new String[]{"80", "70"}, c.b, new String[]{"60", "120"}, "XX/0000", new String[]{"50", "50"});
        JsonNode up = s.body(mvc.perform(post("/api/exams/offerings/" + m2 + "/marks-upload").header("Authorization", "Bearer " + lecturer)
                .header("X-Tenant", s.tenant).contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet").content(fill(template, bad))));
        assertThat(up.get("updated").asInt()).isZero();
        assertThat(up.get("errors").findValuesAsText("message")).anyMatch(m -> m.contains("120 is outside 0–100")).anyMatch(m -> m.contains("XX/0000 is not registered"));
        Map<String, String[]> good = Map.of(c.a, new String[]{"80", "70"}, c.b, new String[]{"60", "30"}, c.c, new String[]{"70", "60"});
        up = s.body(mvc.perform(post("/api/exams/offerings/" + m2 + "/marks-upload").header("Authorization", "Bearer " + lecturer)
                .header("X-Tenant", s.tenant).contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet").content(fill(template, good))));
        assertThat(up.get("updated").asInt()).isEqualTo(6);
        rows = rows(s.body(s.call(get("/api/exams/offerings/" + m2), lecturer, null)));
        assertThat(rows.get(c.a).get("grade").asText()).isEqualTo("B");      // 32 + 42 = 74
        assertThat(rows.get(c.b).get("grade").asText()).isEqualTo("D");      // 24 + 18 = 42
        assertThat(rows.get(c.b).get("remark").asText()).isEqualTo("FAIL");
        assertThat(rows.get(c.c).get("grade").asText()).isEqualTo("C");      // 28 + 36 = 64
        s.call(post("/api/exams/offerings/" + m2 + "/submit"), lecturer, null).andExpect(status().isOk());

        // M3 on screen; the Head of Department returns it once
        long m3 = off.get("ITU 04103");
        JsonNode v3 = s.body(s.call(get("/api/exams/offerings/" + m3), lecturer, null));
        long ca3 = v3.get("components").get(0).get("id").asLong(), se3 = v3.get("components").get(1).get("id").asLong();
        Map<String, JsonNode> r3 = rows(v3);
        long a3 = r3.get(c.a).get("moduleRegistrationId").asLong(), b3 = r3.get(c.b).get("moduleRegistrationId").asLong(),
                c3 = r3.get(c.c).get("moduleRegistrationId").asLong();
        s.call(put("/api/exams/offerings/" + m3 + "/scores"), lecturer, "{\"entries\":[" + entry(a3, ca3, "90") + "," + entry(a3, se3, "90") + ","
                + entry(b3, ca3, "60") + "," + entry(b3, se3, "70") + "," + entry(c3, ca3, "30") + "," + entry(c3, se3, "80") + "]}").andExpect(status().isOk());
        s.call(post("/api/exams/offerings/" + m3 + "/submit"), lecturer, null).andExpect(status().isOk());
        s.call(post("/api/exams/offerings/" + m3 + "/hod-approve"), otherHod, null).andExpect(status().isForbidden());
        s.call(post("/api/exams/offerings/" + m3 + "/return"), hod, "{\"note\":\"Check the coursework of the third student\"}")
                .andExpect(jsonPath("$.status").value("DRAFT")).andExpect(jsonPath("$.returnedNote").value("Check the coursework of the third student"));
        s.call(post("/api/exams/offerings/" + m3 + "/submit"), lecturer, null).andExpect(status().isOk());

        for (long id : List.of(m1, m2, m3)) s.call(post("/api/exams/offerings/" + id + "/hod-approve"), hod, null).andExpect(jsonPath("$.status").value("HOD_APPROVED"));
        s.call(post("/api/exams/offerings/" + m1 + "/board-approve"), lecturer, null).andExpect(status().isForbidden());
        for (long id : List.of(m1, m2)) s.call(post("/api/exams/offerings/" + id + "/board-approve"), exams, null).andExpect(jsonPath("$.status").value("BOARD_APPROVED"));

        // class result sheet: computed once every module has passed the department
        String compute = "{\"semesterId\":" + c.semesterId + ",\"programmeId\":" + inst.programmeId() + ",\"yearOfStudy\":1}";
        JsonNode list = s.body(s.call(get("/api/exams/sheets?semesterId=" + c.semesterId), exams, null));
        assertThat(list.get(0).get("status").asText()).isEqualTo("NOT_COMPUTED");
        assertThat(list.get(0).get("modulesPending").findValuesAsText("")).isEmpty();
        assertThat(texts(list.get(0).get("modulesPending"))).containsExactly("ITU 04103");
        s.call(post("/api/exams/sheets/compute"), exams, compute).andExpect(jsonPath("$.code").value("MODULES_NOT_APPROVED"));
        s.call(post("/api/exams/offerings/" + m3 + "/board-approve"), hod, null).andExpect(status().isOk());
        s.call(get("/api/exams/sheets?semesterId=" + c.semesterId), lecturer, null).andExpect(status().isForbidden());
        JsonNode sheet = s.body(s.call(post("/api/exams/sheets/compute"), exams, compute).andExpect(status().isOk()));
        long sheetId = sheet.get("id").asLong();
        Map<String, JsonNode> sr = new HashMap<>();
        sheet.get("rows").forEach(x -> sr.put(x.get("registrationNo").asText(), x));
        assertThat(sr.get(c.a).get("gpa").decimalValue()).isEqualByComparingTo("3.7");            // (4+3+4) x 10 / 30
        assertThat(sr.get(c.a).get("decision").asText()).isEqualTo("PASS");
        assertThat(sr.get(c.a).get("classification").asText()).isEqualTo("First Class");
        assertThat(sr.get(c.b).get("gpa").decimalValue()).isEqualByComparingTo("2.0");            // C, D, B
        assertThat(sr.get(c.b).get("decision").asText()).isEqualTo("SUPPLEMENTARY");
        assertThat(sr.get(c.c).get("decision").asText()).isEqualTo("SPECIAL_EXAM");
        assertThat(sr.get(c.c).at("/cells/ITU 04103/remark").asText()).isEqualTo("FAIL");         // C grade but coursework 12 < 20
        assertThat(sheet.get("decisions").get("PASS").asInt()).isEqualTo(1);

        // Academic Committee: a changed decision needs a reason; then recommend; the Board confirms (someone else)
        String studentC = "/api/exams/sheets/" + sheetId + "/students/" + sr.get(c.c).get("studentId").asLong() + "/decision";
        s.call(put(studentC), dpa, "{\"decision\":\"INCOMPLETE\"}").andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
        s.call(put(studentC), exams, "{\"decision\":\"INCOMPLETE\",\"reason\":\"x\"}").andExpect(status().isForbidden());
        s.call(put(studentC), dpa, "{\"decision\":\"INCOMPLETE\",\"reason\":\"Absence without permission: the committee will rule after hearing the student\"}")
                .andExpect(status().isOk());
        s.call(post("/api/exams/sheets/" + sheetId + "/confirm"), admin, null).andExpect(jsonPath("$.code").value("WRONG_STAGE"));
        s.call(post("/api/exams/sheets/" + sheetId + "/recommend"), admin, "{\"note\":\"Recommended by AC meeting 3/2028\"}").andExpect(status().isOk());
        s.call(post("/api/exams/sheets/" + sheetId + "/confirm"), admin, null).andExpect(jsonPath("$.code").value("SEGREGATION_OF_DUTIES"));
        s.call(post("/api/exams/sheets/" + sheetId + "/return"), admin, "{\"note\":\"Minute reference to be corrected\"}").andExpect(jsonPath("$.status").value("COMPUTED"));
        // recomputing keeps the committee's change
        sheet = s.body(s.call(post("/api/exams/sheets/compute"), exams, compute));
        assertThat(sheet.get("rows").findValuesAsText("decision")).contains("INCOMPLETE");
        s.call(post("/api/exams/sheets/" + sheetId + "/recommend"), dpa, "{\"note\":\"AC 3/2028\"}").andExpect(jsonPath("$.status").value("RECOMMENDED"));
        s.call(post("/api/exams/sheets/" + sheetId + "/confirm"), admin, null).andExpect(jsonPath("$.status").value("CONFIRMED"));

        // locked: no return to the lecturer, and the database refuses direct changes
        s.call(post("/api/exams/offerings/" + m1 + "/return"), exams, "{\"note\":\"change\"}").andExpect(jsonPath("$.code").value("RESULTS_LOCKED"));
        assertThatThrownBy(() -> TenantContext.runAs(inst.info(), () -> tx.executeWithoutResult(t ->
                jdbc.update("update module_results set grade = 'A' where offering_id = ?", m2)))).hasMessageContaining("Confirmed results are locked");
        s.call(put("/api/exams/regulations/" + regsId), exams, json.writeValueAsString(RegulationDefaults.ntaCertificate(yearId)))
                .andExpect(jsonPath("$.code").value("REGULATIONS_IN_USE"));

        // publication: results withheld for a student without results clearance
        assertThat(s.body(s.call(get("/api/student/results"), c.aToken, null)).get("semesters")).isEmpty();
        requireResultsClearance();
        payAnnualFees(c.aApplicant);
        s.call(post("/api/exams/sheets/" + sheetId + "/publish"), exams, null).andExpect(jsonPath("$.status").value("PUBLISHED"));
        JsonNode mine = s.body(s.call(get("/api/student/results"), c.aToken, null)).get("semesters").get(0);
        assertThat(mine.get("withheld").asBoolean()).isFalse();
        assertThat(mine.get("gpa").decimalValue()).isEqualByComparingTo("3.7");
        assertThat(mine.get("cgpa").decimalValue()).isEqualByComparingTo("3.7");
        assertThat(mine.get("decision").asText()).isEqualTo("PASS");
        assertThat(mine.get("modules").findValuesAsText("grade")).containsExactly("A", "B", "A");
        JsonNode bView = s.body(s.call(get("/api/student/results"), c.bToken, null)).get("semesters").get(0);
        assertThat(bView.get("withheld").asBoolean()).isTrue();
        assertThat(bView.get("modules")).isEmpty();
        assertThat(bView.has("gpa")).isFalse();
        List<String> sms = s.body(s.call(get("/api/messaging/messages?size=50"), admin, null)).get("content").findValuesAsText("templateCode");
        assertThat(sms).contains("RESULTS_PUBLISHED", "RESULTS_WITHHELD");

        // reports
        JsonNode perf = s.body(s.call(get("/api/reports/EXM-01?semesterId=" + c.semesterId), viewer, null).andExpect(status().isOk()));
        assertThat(perf.get("rowCount").asInt()).isEqualTo(3);
        JsonNode cls = s.body(s.call(get("/api/reports/EXM-02?semesterId=" + c.semesterId + "&programmeId=" + inst.programmeId() + "&yearOfStudy=1"), exams, null));
        assertThat(cls.get("rowCount").asInt()).isEqualTo(3);
        assertThat(cls.get("columns").findValuesAsText("label")).contains("ITU 04101", "ITU 04102", "ITU 04103", "GPA", "Decision");
        s.call(get("/api/reports/EXM-02?semesterId=" + c.semesterId), viewer, null).andExpect(status().isForbidden());

        // another institution sees none of it
        String other = "Q" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TestData.Institution o = data.institution(other, BigDecimal.ZERO);
        data.staff(o.tenant(), "exams", Role.EXAMINATIONS_OFFICER);
        AdmissionScenario os = new AdmissionScenario(mvc, json, other, o);
        String oExams = os.staffToken("exams");
        os.call(get("/api/exams/offerings/" + m1), oExams, null).andExpect(status().isNotFound());
        os.call(get("/api/exams/sheets/" + sheetId), oExams, null).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ a class of three registered students

    record Class(long semesterId, String a, String b, String c, String aToken, String bToken, Applicant aApplicant) {
    }

    private Class classOfThree() throws Exception {
        long m1 = module("ITU 04101"), m2 = module("ITU 04102"), m3 = module("ITU 04103"), m4 = module("ITU 04201");
        long cid = s.body(s.call(post("/api/academic/curricula"), hod, "{\"programmeId\":" + inst.programmeId() + ",\"effectiveYearId\":" + yearId
                + ",\"modules\":[" + slot(m1, 1) + "," + slot(m2, 1) + "," + slot(m3, 1) + "," + slot(m4, 2) + "],\"electiveRules\":[]}")).get("id").asLong();
        s.call(post("/api/academic/curricula/" + cid + "/approve"), dpa, null).andExpect(status().isOk());
        long semId = s.body(s.call(post("/api/academic/semesters"), dpa, "{\"academicYearId\":" + yearId + ",\"semesterNo\":1,\"name\":\"Semester 1\","
                + "\"startDate\":\"2027-09-27\",\"endDate\":\"2028-02-10\",\"registrationOpens\":\"" + TODAY.minusDays(1) + "\",\"registrationCloses\":\""
                + TODAY.plusDays(10) + "\",\"marksEntryOpens\":\"" + TODAY.minusDays(1) + "\",\"marksEntryCloses\":\"" + TODAY.plusDays(30) + "\"}")
                .andExpect(status().isCreated())).get("id").asLong();
        Applicant a = s.apply("S0101/0001");
        Applicant b = s.applyEligibleEndingIn(5, 400);
        Applicant c = s.applyEligibleEndingIn(5, 900);
        s.admit(officer, dpa, a, b, c);
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
            tokens[i] = s.body(s.call(post("/api/student-auth/login"), null, "{\"login\":\"" + regNos[i] + "\",\"password\":\"Secret@123\"}")).get("accessToken").asText();
            s.call(post("/api/student/semester-registration"), tokens[i], "{\"semesterId\":" + semId + ",\"electiveModuleIds\":[],\"detailsConfirmed\":true}")
                    .andExpect(status().isOk());
        }
        s.call(post("/api/registry/semester-registrations/approve-all"), registrar, "{\"semesterId\":" + semId + "}").andExpect(jsonPath("$.approved").value(3));
        return new Class(semId, regNos[0], regNos[1], regNos[2], tokens[0], tokens[1], a);
    }

    private long module(String code) throws Exception {
        return s.body(s.call(post("/api/academic/modules"), hod, "{\"code\":\"" + code + "\",\"title\":\"Module " + code
                + "\",\"credits\":10,\"ntaLevel\":4,\"assessmentType\":\"GRADED\",\"active\":true}").andExpect(status().isCreated())).get("id").asLong();
    }

    private static String slot(long moduleId, int sem) {
        return "{\"moduleId\":" + moduleId + ",\"yearOfStudy\":1,\"semesterNo\":" + sem + ",\"category\":\"CORE\"}";
    }

    private static String plan(Object... p) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < p.length; i += 5) {
            parts.add("{" + (p[i] == null ? "" : "\"id\":" + p[i] + ",") + "\"name\":\"" + p[i + 1] + "\",\"category\":\"" + p[i + 2] + "\",\"weight\":" + p[i + 3]
                    + ",\"maxScore\":" + p[i + 4] + "}");
        }
        return "{\"components\":[" + String.join(",", parts) + "]}";
    }

    private static String entry(long reg, long comp, String score) {
        return "{\"moduleRegistrationId\":" + reg + ",\"componentId\":" + comp + ",\"score\":" + score + "}";
    }

    private static String scores(long reg, long comp, String score) {
        return "{\"entries\":[" + entry(reg, comp, score) + "]}";
    }

    private static Map<String, JsonNode> rows(JsonNode offering) {
        Map<String, JsonNode> m = new HashMap<>();
        offering.get("rows").forEach(x -> m.put(x.get("registrationNo").asText(), x));
        return m;
    }

    /** Fills the downloaded template: registration number → CA and SE marks (rows not in the class are appended). */
    private static byte[] fill(byte[] template, Map<String, String[]> marks) throws Exception {
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(template)); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sh = wb.getSheetAt(0);
            Map<String, String[]> left = new HashMap<>(marks);
            for (int r = 1; r <= sh.getLastRowNum(); r++) {
                Row row = sh.getRow(r);
                if (row == null || row.getCell(0) == null) continue;
                String[] m = left.remove(row.getCell(0).getStringCellValue());
                if (m == null) continue;
                for (int i = 0; i < m.length; i++) row.createCell(3 + i).setCellValue(Double.parseDouble(m[i]));
            }
            int r = sh.getLastRowNum() + 1;
            for (Map.Entry<String, String[]> e : left.entrySet()) {
                Row row = sh.createRow(r++);
                row.createCell(0).setCellValue(e.getKey());
                for (int i = 0; i < e.getValue().length; i++) row.createCell(3 + i).setCellValue(Double.parseDouble(e.getValue()[i]));
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private void requireResultsClearance() throws Exception {
        long item = s.body(s.call(post("/api/finance/fee-items"), accountant,
                "{\"code\":\"TUI\",\"name\":\"Tuition fee\",\"category\":\"TUITION\",\"allocationPriority\":10,\"active\":true}")).get("id").asLong();
        long st = s.body(s.call(post("/api/finance/fee-structures"), accountant, "{\"academicYearId\":" + yearId + ",\"programmeId\":" + inst.programmeId()
                + ",\"yearOfStudy\":1,\"lines\":[{\"feeItemId\":" + item + ",\"amount\":100000,\"instalment\":1}]}")).get("id").asLong();
        s.call(post("/api/finance/fee-structures/" + st + "/approve"), bursar, null).andExpect(status().isOk());
        s.call(post("/api/finance/billing-runs"), accountant, "{\"academicYearId\":" + yearId + "}").andExpect(status().isOk());
        s.call(put("/api/finance/clearance-gates/RESULTS"), bursar, "{\"enabled\":true,\"minPercentPaid\":100,\"firstInstalmentRequired\":false}")
                .andExpect(status().isOk());
    }

    private void payAnnualFees(Applicant a) throws Exception {
        for (JsonNode inv : s.body(s.call(get("/api/applicant/account"), a.token(), null)).get("invoices")) {
            if (inv.get("purpose").asText().equals("ANNUAL_FEES") && inv.get("balance").decimalValue().signum() > 0) {
                s.call(post("/api/applicant/invoices/" + inv.get("id").asLong() + "/simulate-payment"), a.token(), "{}").andExpect(status().isOk());
            }
        }
    }

    private static List<String> texts(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(x -> out.add(x.asText()));
        return out;
    }
}
