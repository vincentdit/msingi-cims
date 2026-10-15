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

/** Marks entry windows per assessment item (FR-EXM-02), approval stages set by the institution (FR-EXM-05 / FR-ADM-S03)
 * and results compared across years (FR-EXM-14). */
@IntegrationTest
class ExamConfigurationIT {

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
    String admin, dpa, hod, officer, registrar, lecturer, exams;
    long yearId;

    @BeforeEach
    void setUp() throws Exception {
        String tenant = "X" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        inst = data.institution(tenant, BigDecimal.ZERO);
        data.staff(inst.tenant(), "admin", Role.INSTITUTION_ADMIN);
        data.staff(inst.tenant(), "dpa", Role.DEPUTY_PRINCIPAL_ACADEMIC);
        data.staff(inst.tenant(), "hod", Role.HEAD_OF_DEPARTMENT);
        data.staff(inst.tenant(), "registrar", Role.REGISTRAR);
        data.staff(inst.tenant(), "lecturer", Role.LECTURER);
        data.staff(inst.tenant(), "exams", Role.EXAMINATIONS_OFFICER);
        s = new AdmissionScenario(mvc, json, tenant, inst);
        admin = s.staffToken("admin");
        dpa = s.staffToken("dpa");
        hod = s.staffToken("hod");
        officer = s.staffToken("officer");
        registrar = s.staffToken("registrar");
        lecturer = s.staffToken("lecturer");
        exams = s.staffToken("exams");
        s.call(put("/api/admission/windows/" + inst.windowId() + "/joining-instructions"), officer, INSTRUCTIONS).andExpect(status().isOk());
        yearId = s.body(s.call(get("/api/admission/windows/" + inst.windowId()), officer, null)).get("academicYearId").asLong();
    }

    @Test
    void itemWindowsStagesAndTrends() throws Exception {
        s.call(post("/api/exams/regulations"), exams, json.writeValueAsString(RegulationDefaults.ntaCertificate(yearId))).andExpect(status().is2xxSuccessful());
        Class c = classOfThree();
        s.call(post("/api/exams/offerings/setup"), exams, "{\"semesterId\":" + c.semesterId + "}").andExpect(status().isOk());
        Map<String, Long> off = new HashMap<>();
        s.body(s.call(get("/api/exams/offerings?semesterId=" + c.semesterId), exams, null)).forEach(o -> off.put(o.get("code").asText(), o.get("id").asLong()));
        long lecturerId = s.body(s.call(get("/api/exams/staff"), exams, null)).findParents("fullName").stream()
                .filter(x -> x.get("fullName").asText().equals("Staff lecturer")).findFirst().orElseThrow().get("id").asLong();
        for (Long id : off.values()) s.call(put("/api/exams/offerings/" + id + "/staff"), hod, "{\"userIds\":[" + lecturerId + "]}").andExpect(status().isOk());

        // marks entry per assessment item: coursework of one module closed, the examination of every module opening later
        long m1 = off.get("ITU 04101");
        JsonNode v = s.body(s.call(get("/api/exams/offerings/" + m1), lecturer, null));
        long ca = v.get("components").get(0).get("id").asLong(), se = v.get("components").get(1).get("id").asLong();
        String seName = v.get("components").get(1).get("name").asText();
        long reg = v.get("rows").get(0).get("moduleRegistrationId").asLong();
        s.call(put("/api/exams/semesters/" + c.semesterId + "/component-windows"), lecturer, "{\"offeringId\":" + m1 + ",\"componentId\":" + ca + "}")
                .andExpect(status().isForbidden());
        s.call(put("/api/exams/semesters/" + c.semesterId + "/component-windows"), exams,
                "{\"offeringId\":" + m1 + ",\"componentId\":" + ca + ",\"opens\":\"" + TODAY.minusDays(10) + "\",\"closes\":\"" + TODAY.minusDays(1) + "\"}")
                .andExpect(jsonPath("$.items").value(1));
        s.call(put("/api/exams/semesters/" + c.semesterId + "/component-windows"), exams,
                "{\"name\":\"" + seName.toUpperCase() + "\",\"opens\":\"" + TODAY.plusDays(3) + "\"}").andExpect(jsonPath("$.items").value(3));
        s.call(put("/api/exams/offerings/" + m1 + "/scores"), lecturer, scores(reg, ca, "30")).andExpect(jsonPath("$.code").value("MARKS_ENTRY_CLOSED"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("closed on " + TODAY.minusDays(1))));
        s.call(put("/api/exams/offerings/" + m1 + "/scores"), lecturer, scores(reg, se, "60"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("open on " + TODAY.plusDays(3))));
        v = s.body(s.call(get("/api/exams/offerings/" + m1), lecturer, null));
        assertThat(v.get("components").get(0).get("entryCloses").asText()).isEqualTo(TODAY.minusDays(1).toString());
        // back to the semester window
        s.call(put("/api/exams/semesters/" + c.semesterId + "/component-windows"), exams, "{\"offeringId\":" + m1 + ",\"componentId\":" + ca + "}").andExpect(status().isOk());
        s.call(put("/api/exams/semesters/" + c.semesterId + "/component-windows"), exams, "{\"name\":\"" + seName + "\"}").andExpect(status().isOk());

        // the institution's approval stages for module results
        s.call(put("/api/exams/approval-stages/MODULE_RESULTS"), exams, "[{\"name\":\"Moderation\",\"role\":\"LECTURER\"}]")
                .andExpect(jsonPath("$.code").value("ROLE_NOT_ALLOWED"));
        s.call(put("/api/exams/approval-stages/MODULE_RESULTS"), exams,
                "[{\"name\":\"Department Board\",\"role\":\"HEAD_OF_DEPARTMENT\"},{\"name\":\"External examiner\",\"role\":\"EXAMINATIONS_OFFICER\"}]")
                .andExpect(jsonPath("$.moduleResults.length()").value(2));
        s.call(put("/api/exams/approval-stages/RESULT_SHEET"), exams,
                "[{\"name\":\"Examinations Committee\",\"role\":\"EXAMINATIONS_OFFICER\"},{\"name\":\"Department review\",\"role\":\"HEAD_OF_DEPARTMENT\"}]")
                .andExpect(jsonPath("$.resultSheet.length()").value(2));

        int k = 0;
        for (Long id : off.values()) {
            JsonNode o = s.body(s.call(get("/api/exams/offerings/" + id), lecturer, null));
            List<String> entries = new ArrayList<>();
            for (JsonNode row : o.get("rows")) {
                entries.add(entry(row.get("moduleRegistrationId").asLong(), o.get("components").get(0).get("id").asLong(), "30"));
                entries.add(entry(row.get("moduleRegistrationId").asLong(), o.get("components").get(1).get("id").asLong(), "45"));
            }
            s.call(put("/api/exams/offerings/" + id + "/scores"), lecturer, "{\"entries\":[" + String.join(",", entries) + "]}").andExpect(status().isOk());
            s.call(post("/api/exams/offerings/" + id + "/submit"), lecturer, null).andExpect(status().isOk());
            s.call(post("/api/exams/offerings/" + id + "/hod-approve"), hod, null).andExpect(jsonPath("$.status").value("HOD_APPROVED"))
                    .andExpect(jsonPath("$.waitingAtStage").value("Department Board"));
            if (k++ == 0) {
                // stages cannot change while results are part-way through them
                s.call(put("/api/exams/approval-stages/MODULE_RESULTS"), exams, "[]").andExpect(jsonPath("$.code").value("RESULTS_IN_PROGRESS"));
                s.call(post("/api/exams/offerings/" + id + "/approve-stage"), exams, null).andExpect(status().isForbidden());
            }
            s.call(post("/api/exams/offerings/" + id + "/approve-stage"), hod, "{\"note\":\"Board meeting 2/2028\"}")
                    .andExpect(jsonPath("$.status").value("HOD_APPROVED")).andExpect(jsonPath("$.waitingAtStage").value("External examiner"));
            s.call(post("/api/exams/offerings/" + id + "/approve-stage"), exams, null).andExpect(jsonPath("$.status").value("BOARD_APPROVED"))
                    .andExpect(jsonPath("$.stageApprovals.length()").value(2)).andExpect(jsonPath("$.stageApprovals[0].note").value("Board meeting 2/2028"));
        }

        // the class result sheet passes the review stages before the Academic Committee; a stage can send it back
        String compute = "{\"semesterId\":" + c.semesterId + ",\"programmeId\":" + inst.programmeId() + ",\"yearOfStudy\":1}";
        long sheet = s.body(s.call(post("/api/exams/sheets/compute"), exams, compute).andExpect(jsonPath("$.waitingAtStage").value("Examinations Committee"))).get("id").asLong();
        s.call(post("/api/exams/sheets/" + sheet + "/recommend"), dpa, null).andExpect(jsonPath("$.code").value("STAGES_PENDING"));
        s.call(post("/api/exams/sheets/" + sheet + "/approve-stage"), exams, null).andExpect(jsonPath("$.waitingAtStage").value("Department review"));
        s.call(post("/api/exams/sheets/" + sheet + "/return"), hod, "{\"note\":\"Recheck the third student\"}")
                .andExpect(jsonPath("$.waitingAtStage").value("Examinations Committee")).andExpect(jsonPath("$.returnedNote").value("Recheck the third student"));
        s.call(post("/api/exams/sheets/" + sheet + "/approve-stage"), exams, null).andExpect(status().isOk());
        s.call(post("/api/exams/sheets/" + sheet + "/approve-stage"), hod, null).andExpect(jsonPath("$.can.recommend").value(false));
        s.call(post("/api/exams/sheets/" + sheet + "/recommend"), dpa, "{\"note\":\"AC 4/2028\"}").andExpect(jsonPath("$.status").value("RECOMMENDED"));
        s.call(post("/api/exams/sheets/" + sheet + "/confirm"), admin, null).andExpect(jsonPath("$.status").value("CONFIRMED"));

        // results compared across years: this year's class
        JsonNode t = s.body(s.call(get("/api/exams/analytics/trends?programmeId=" + inst.programmeId()), exams, null).andExpect(status().isOk()));
        assertThat(t.get("years").get(0).asText()).isEqualTo("2027/2028");
        assertThat(t.at("/rows/0/students").asInt()).isEqualTo(3);
        assertThat(t.at("/rows/0/grades").size()).isPositive();
        assertThat(t.get("modules")).hasSize(3);
        assertThat(t.get("modules").get(0).get("passRateByYear").has("2027/2028")).isTrue();
        s.call(get("/api/exams/analytics/trends"), lecturer, null).andExpect(status().isForbidden());
    }

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
            tokens[i] = s.body(s.call(post("/api/student-auth/login"), null, "{\"login\":\"" + regNos[i] + "\",\"password\":\"Kifaru#Mto47\"}")).get("accessToken").asText();
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

}
