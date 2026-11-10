package tz.co.msingi.cims.quality;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.msingi.cims.exams.RegulationDefaults;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.support.AdmissionScenario;
import tz.co.msingi.cims.support.AdmissionScenario.Applicant;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
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

/** FR-QA-01..03: students evaluate their modules anonymously; results only above the minimum; lecturers read theirs after the close. */
@IntegrationTest
class QualityIT {

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
    String tenant, officer, dpa, hod, registrar, lecturer, exams, qa;
    long yearId;

    @BeforeEach
    void setUp() throws Exception {
        tenant = "Q" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        inst = data.institution(tenant, BigDecimal.ZERO);
        data.staff(inst.tenant(), "dpa", Role.DEPUTY_PRINCIPAL_ACADEMIC);
        data.staff(inst.tenant(), "hod", Role.HEAD_OF_DEPARTMENT);
        data.staff(inst.tenant(), "registrar", Role.REGISTRAR);
        data.staff(inst.tenant(), "lecturer", Role.LECTURER);
        data.staff(inst.tenant(), "exams", Role.EXAMINATIONS_OFFICER);
        data.staff(inst.tenant(), "qa", Role.QUALITY_ASSURANCE_OFFICER);
        s = new AdmissionScenario(mvc, json, tenant, inst);
        officer = s.staffToken("officer");
        dpa = s.staffToken("dpa");
        hod = s.staffToken("hod");
        registrar = s.staffToken("registrar");
        lecturer = s.staffToken("lecturer");
        exams = s.staffToken("exams");
        qa = s.staffToken("qa");
        s.call(put("/api/admission/windows/" + inst.windowId() + "/joining-instructions"), officer, INSTRUCTIONS).andExpect(status().isOk());
        yearId = s.body(s.call(get("/api/admission/windows/" + inst.windowId()), officer, null)).get("academicYearId").asLong();
        s.call(post("/api/exams/regulations"), exams, json.writeValueAsString(RegulationDefaults.ntaCertificate(yearId))).andExpect(status().isCreated());
    }

    private <T> T db(Supplier<T> work) {
        return TenantContext.callAs(inst.info(), () -> tx.execute(x -> work.get()));
    }

    private long module(String code) throws Exception {
        return s.body(s.call(post("/api/academic/modules"), hod, "{\"code\":\"" + code + "\",\"title\":\"Module " + code
                + "\",\"credits\":10,\"ntaLevel\":4,\"assessmentType\":\"GRADED\",\"active\":true}").andExpect(status().isCreated())).get("id").asLong();
    }

    private static String slot(long moduleId, int semester) {
        return "{\"moduleId\":" + moduleId + ",\"yearOfStudy\":1,\"semesterNo\":" + semester + ",\"category\":\"CORE\"}";
    }

    @Test
    void studentsEvaluateAnonymouslyAndResultsAreShownWithCare() throws Exception {
        long m1 = module("ITU 04101"), m2 = module("ITU 04102");
        long cid = s.body(s.call(post("/api/academic/curricula"), hod, "{\"programmeId\":" + inst.programmeId() + ",\"effectiveYearId\":" + yearId
                + ",\"modules\":[" + slot(m1, 1) + "," + slot(m2, 2) + "],\"electiveRules\":[]}")).get("id").asLong();
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
        List<String> students = new ArrayList<>();
        for (JsonNode st : handover.get("students")) {
            long id = st.get("studentId").asLong();
            s.call(put("/api/registry/students/" + id + "/first-registration"), registrar,
                    "{\"documents\":[{\"item\":\"Original CSEE certificate\",\"verified\":true}]}").andExpect(status().isOk());
            s.call(post("/api/registry/students/" + id + "/first-registration/approve"), registrar, null).andExpect(status().isOk());
            String regNo = s.body(s.call(get("/api/registry/students/" + id), registrar, null)).at("/summary/registrationNo").asString();
            String token = s.body(s.call(post("/api/student-auth/login"), null, "{\"login\":\"" + regNo + "\",\"password\":\"Kifaru#Mto47\"}"))
                    .get("accessToken").asString();
            s.call(post("/api/student/semester-registration"), token, "{\"semesterId\":" + semId + ",\"electiveModuleIds\":[],\"detailsConfirmed\":true}")
                    .andExpect(status().isOk());
            students.add(token);
        }
        s.call(post("/api/registry/semester-registrations/approve-all"), registrar, "{\"semesterId\":" + semId + "}").andExpect(jsonPath("$.approved").value(3));
        s.call(post("/api/exams/offerings/setup"), exams, "{\"semesterId\":" + semId + "}").andExpect(status().isOk());
        Map<String, Long> off = new HashMap<>();
        s.body(s.call(get("/api/exams/offerings?semesterId=" + semId), exams, null)).forEach(o -> off.put(o.get("code").asString(), o.get("id").asLong()));
        long o1 = off.get("ITU 04101");
        long lecturerId = s.body(s.call(get("/api/exams/staff"), exams, null)).findParents("fullName").stream()
                .filter(x -> x.get("fullName").asString().equals("Staff lecturer")).findFirst().orElseThrow().get("id").asLong();
        s.call(put("/api/exams/offerings/" + o1 + "/staff"), hod, "{\"userIds\":[" + lecturerId + "]}").andExpect(status().isOk());

        // the questionnaire starts from the standard questions; the evaluation opens and the students are told
        JsonNode questions = s.body(s.call(get("/api/qa/questions"), qa, null));
        assertThat(questions.size()).isEqualTo(11);
        s.call(post("/api/qa/rounds"), lecturer, "{}").andExpect(status().isForbidden());
        s.call(post("/api/qa/rounds"), qa, "{\"semesterId\":" + semId + ",\"opensOn\":\"" + TODAY + "\",\"closesOn\":\"" + TODAY.plusDays(7) + "\",\"minResponses\":2}")
                .andExpect(jsonPath("$.code").value("MINIMUM_TOO_LOW"));
        long roundId = s.body(s.call(post("/api/qa/rounds"), qa, "{\"semesterId\":" + semId + ",\"opensOn\":\"" + TODAY + "\",\"closesOn\":\"" + TODAY.plusDays(7)
                + "\",\"minResponses\":3,\"tellStudents\":true}").andExpect(status().isOk())).at("/round/id").asLong();
        assertThat(db(() -> jdbc.queryForObject("select count(*) from messages where template_code = 'EVALUATION_OPEN'", Integer.class))).isEqualTo(3);

        // each student sees the semester's module and answers once
        JsonNode mine = s.body(s.call(get("/api/student/evaluations"), students.get(0), null));
        assertThat(mine.at("/0/modules/0/offeringId").asLong()).isEqualTo(o1);
        assertThat(mine.at("/0/modules/0/lecturers").asString()).isEqualTo("Staff lecturer");
        List<Long> rating = new ArrayList<>(), text = new ArrayList<>();
        questions.forEach(q -> ("RATING".equals(q.get("kind").asString()) ? rating : text).add(q.get("id").asLong()));
        int[] scores = {5, 4, 3};
        for (int i = 0; i < 3; i++) {
            StringBuilder r = new StringBuilder("{\"ratings\":{");
            for (int k = 0; k < rating.size(); k++) r.append(k == 0 ? "" : ",").append('"').append(rating.get(k)).append("\":").append(scores[i]);
            r.append("},\"comments\":{\"").append(text.get(1)).append("\":\"More practical sessions please ").append(i).append("\"}}");
            if (i == 0) {
                s.call(post("/api/student/evaluations/" + roundId + "/offerings/" + o1), students.get(i), "{\"ratings\":{}}")
                        .andExpect(jsonPath("$.code").value("ANSWER_ALL"));
            }
            s.call(post("/api/student/evaluations/" + roundId + "/offerings/" + o1), students.get(i), r.toString())
                    .andExpect(jsonPath("$[0].modules[0].done").value(true));
            if (i == 0) {
                s.call(post("/api/student/evaluations/" + roundId + "/offerings/" + o1), students.get(i), r.toString())
                        .andExpect(jsonPath("$.code").value("ALREADY_ANSWERED"));
                // with one answer the results are not shown
                s.call(get("/api/qa/rounds/" + roundId + "/offerings/" + o1), qa, null).andExpect(jsonPath("$.questions.length()").value(0));
            }
        }
        // the answers carry no student
        assertThat(db(() -> jdbc.queryForObject("select count(*) from information_schema.columns where table_name = 'evaluation_responses' and column_name like '%student%'",
                Integer.class))).isZero();

        // three answers: averages; comments wait for the close; the lecturer waits for the close
        s.call(get("/api/qa/rounds/" + roundId), qa, null).andExpect(jsonPath("$.modules[?(@.code == 'ITU 04101')].average").value(4.0))
                .andExpect(jsonPath("$.modules[?(@.code == 'ITU 04101')].responseRate").value(100));
        JsonNode report = s.body(s.call(get("/api/qa/rounds/" + roundId + "/offerings/" + o1), qa, null));
        assertThat(report.at("/questions/0/average").asDouble()).isEqualTo(4.0);
        assertThat(report.at("/questions/0/counts").toString()).isEqualTo("[0,0,1,1,1]");
        assertThat(report.toString()).doesNotContain("More practical sessions");
        s.call(get("/api/qa/rounds/" + roundId + "/offerings/" + o1), lecturer, null).andExpect(status().isForbidden());
        s.call(get("/api/qa/my-modules"), lecturer, null).andExpect(jsonPath("$[0].modules[0].average").doesNotExist());

        // closed: the lecturer reads it, comments included
        s.call(put("/api/qa/rounds/" + roundId), qa, "{\"closesOn\":\"" + TODAY.minusDays(1) + "\"}").andExpect(jsonPath("$.code").value("DATES_INVALID"));
        db(() -> jdbc.update("update evaluation_rounds set opens_on = ?, closes_on = ? where id = ?", TODAY.minusDays(8), TODAY.minusDays(1), roundId));
        JsonNode closed = s.body(s.call(get("/api/qa/rounds/" + roundId + "/offerings/" + o1), lecturer, null).andExpect(status().isOk()));
        assertThat(closed.toString()).contains("More practical sessions please 0").contains("More practical sessions please 2");
        s.call(get("/api/qa/my-modules"), lecturer, null).andExpect(jsonPath("$[0].modules[0].average").value(4.0));
        s.call(post("/api/student/evaluations/" + roundId + "/offerings/" + o1), students.get(1), "{}").andExpect(jsonPath("$.code").value("EVALUATION_CLOSED"));

        byte[] xlsx = s.call(get("/api/qa/rounds/" + roundId + "/workbook"), qa, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertThat(wb.getSheet("Modules").getRow(1).getCell(7).getNumericCellValue()).isEqualTo(4.0);
        }
    }
}
