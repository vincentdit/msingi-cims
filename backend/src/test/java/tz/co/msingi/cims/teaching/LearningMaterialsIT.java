package tz.co.msingi.cims.teaching;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tz.co.msingi.cims.exams.RegulationDefaults;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.support.AdmissionScenario;
import tz.co.msingi.cims.support.AdmissionScenario.Applicant;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FR-TCH-06: lecturers post notes and assignments for their modules; registered students download them, hand in work and see marks. */
@IntegrationTest
class LearningMaterialsIT {

    static final String INSTRUCTIONS = """
            {"reportingDate":"2027-09-27","reportingVenue":"Main Hall","documentsToBring":["Original CSEE certificate"],
             "signatoryName":"Dr. Amina Salum","signatoryTitle":"Principal"}""";
    static final LocalDate TODAY = LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam"));
    static final byte[] PDF = "%PDF-1.4\n1 0 obj << >> endobj\ntrailer << >>\n%%EOF".getBytes(StandardCharsets.US_ASCII);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;

    AdmissionScenario s;
    TestData.Institution inst;
    String tenant, hod, dpa, officer, registrar, lecturer, lecturer2, exams, viewer;
    long yearId;

    @BeforeEach
    void setUp() throws Exception {
        tenant = "L" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        inst = data.institution(tenant, BigDecimal.ZERO);
        data.staff(inst.tenant(), "dpa", Role.DEPUTY_PRINCIPAL_ACADEMIC);
        data.staff(inst.tenant(), "hod", Role.HEAD_OF_DEPARTMENT);
        data.staff(inst.tenant(), "registrar", Role.REGISTRAR);
        data.staff(inst.tenant(), "lecturer", Role.LECTURER);
        data.staff(inst.tenant(), "lecturer2", Role.LECTURER);
        data.staff(inst.tenant(), "exams", Role.EXAMINATIONS_OFFICER);
        data.staff(inst.tenant(), "viewer", Role.MANAGEMENT_VIEWER);
        s = new AdmissionScenario(mvc, json, tenant, inst);
        dpa = s.staffToken("dpa");
        hod = s.staffToken("hod");
        officer = s.staffToken("officer");
        registrar = s.staffToken("registrar");
        lecturer = s.staffToken("lecturer");
        lecturer2 = s.staffToken("lecturer2");
        exams = s.staffToken("exams");
        viewer = s.staffToken("viewer");
        s.call(put("/api/admission/windows/" + inst.windowId() + "/joining-instructions"), officer, INSTRUCTIONS).andExpect(status().isOk());
        yearId = s.body(s.call(get("/api/admission/windows/" + inst.windowId()), officer, null)).get("academicYearId").asLong();
        s.call(post("/api/exams/regulations"), exams, json.writeValueAsString(RegulationDefaults.ntaCertificate(yearId))).andExpect(status().isCreated());
    }

    @Test
    void lecturersPostMaterialsAndStudentsHandInWork() throws Exception {
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
        s.admit(officer, dpa, a, b);
        JsonNode handover = s.body(s.call(post("/api/registry/windows/" + inst.windowId() + "/handover"), registrar, "{}"));
        Map<String, String> tokens = new HashMap<>();
        for (JsonNode st : handover.get("students")) {
            long id = st.get("studentId").asLong();
            s.call(put("/api/registry/students/" + id + "/first-registration"), registrar,
                    "{\"documents\":[{\"item\":\"Original CSEE certificate\",\"verified\":true}]}").andExpect(status().isOk());
            s.call(post("/api/registry/students/" + id + "/first-registration/approve"), registrar, null).andExpect(status().isOk());
            String regNo = s.body(s.call(get("/api/registry/students/" + id), registrar, null)).at("/summary/registrationNo").asString();
            tokens.put(regNo, s.body(s.call(post("/api/student-auth/login"), null, "{\"login\":\"" + regNo + "\",\"password\":\"Kifaru#Mto47\"}"))
                    .get("accessToken").asString());
        }
        String[] regNos = tokens.keySet().stream().sorted().toArray(String[]::new);
        String st1 = tokens.get(regNos[0]), st2 = tokens.get(regNos[1]);
        // the first student registers for the semester; the second does not
        s.call(post("/api/student/semester-registration"), st1, "{\"semesterId\":" + semId + ",\"electiveModuleIds\":[],\"detailsConfirmed\":true}")
                .andExpect(status().isOk());
        s.call(post("/api/registry/semester-registrations/approve-all"), registrar, "{\"semesterId\":" + semId + "}").andExpect(jsonPath("$.approved").value(1));
        s.call(post("/api/exams/offerings/setup"), exams, "{\"semesterId\":" + semId + "}").andExpect(status().isOk());
        Map<String, Long> off = new HashMap<>();
        s.body(s.call(get("/api/exams/offerings?semesterId=" + semId), exams, null)).forEach(o -> off.put(o.get("code").asString(), o.get("id").asLong()));
        long lecturerId = s.body(s.call(get("/api/exams/staff"), exams, null)).findParents("fullName").stream()
                .filter(x -> x.get("fullName").asString().equals("Staff lecturer")).findFirst().orElseThrow().get("id").asLong();
        long o1 = off.get("ITU 04101");
        s.call(put("/api/exams/offerings/" + o1 + "/staff"), hod, "{\"userIds\":[" + lecturerId + "]}").andExpect(status().isOk());

        // a note with a file; another lecturer may not post; the Head of Department may read
        long note = s.body(perform(multipart("/api/teaching/offerings/" + o1 + "/materials").file(pdf("file", "week1.pdf"))
                .param("kind", "NOTE").param("title", "Week 1 notes").param("body", "Read before class"), lecturer)
                .andExpect(status().isCreated())).get("id").asLong();
        perform(multipart("/api/teaching/offerings/" + o1 + "/materials").param("title", "Mine"), lecturer2).andExpect(status().isForbidden());
        perform(multipart("/api/teaching/offerings/" + o1 + "/materials").param("title", "Empty"), lecturer).andExpect(jsonPath("$.code").value("MATERIAL_EMPTY"));
        perform(multipart("/api/teaching/offerings/" + o1 + "/materials").file(new MockMultipartFile("file", "virus.pdf", "application/pdf", "MZ\u0090binary".getBytes()))
                .param("title", "Bad"), lecturer).andExpect(jsonPath("$.code").value("FILE_TYPE"));
        s.call(get("/api/teaching/offerings/" + o1 + "/materials"), hod, null).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].classSize").value(1));
        s.call(get("/api/teaching/offerings/" + o1 + "/materials"), lecturer2, null).andExpect(status().isForbidden());

        // an assignment, with the class told by SMS; a hidden draft is not shown to students
        OffsetDateTime due = OffsetDateTime.now().plusDays(3);
        long asg = s.body(perform(multipart("/api/teaching/offerings/" + o1 + "/materials").param("kind", "ASSIGNMENT").param("title", "Assignment 1")
                .param("body", "Write a short report").param("dueAt", due.toString()).param("maxScore", "20").param("acceptLate", "false").param("tellClass", "true"),
                lecturer).andExpect(status().isCreated())).get("id").asLong();
        perform(multipart("/api/teaching/offerings/" + o1 + "/materials").param("title", "Draft").param("body", "Not yet").param("published", "false"), lecturer)
                .andExpect(status().isCreated());
        assertThat(db(() -> jdbc.queryForObject("select count(*) from messages where template_code = 'ASSIGNMENT_POSTED'", Integer.class))).isEqualTo(1);

        // students: only the registered one sees the module and its published items
        s.call(get("/api/student/modules"), st1, null).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[?(@.code=='ITU 04101')].notes").value(1)).andExpect(jsonPath("$[?(@.code=='ITU 04101')].openAssignments").value(1));
        s.call(get("/api/student/modules"), st2, null).andExpect(jsonPath("$.length()").value(0));
        s.call(get("/api/student/modules/" + o1 + "/materials"), st1, null).andExpect(jsonPath("$.length()").value(2));
        s.call(get("/api/student/modules/" + o1 + "/materials"), st2, null).andExpect(status().isNotFound());
        byte[] got = s.call(get("/api/student/materials/" + note + "/file"), st1, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(got).isEqualTo(PDF);
        s.call(get("/api/student/materials/" + note + "/file"), st2, null).andExpect(status().isNotFound());

        // hand in, then again (replaces); a student not registered cannot
        perform(multipart("/api/student/materials/" + asg + "/submission").file(pdf("file", "report.pdf")).param("note", "First try"), st1)
                .andExpect(jsonPath("$.mine.timesSubmitted").value(1)).andExpect(jsonPath("$.mine.late").value(false));
        perform(multipart("/api/student/materials/" + asg + "/submission").file(pdf("file", "report-v2.pdf")), st1)
                .andExpect(jsonPath("$.mine.timesSubmitted").value(2)).andExpect(jsonPath("$.mine.fileName").value("report-v2.pdf"));
        perform(multipart("/api/student/materials/" + asg + "/submission").file(pdf("file", "x.pdf")), st2).andExpect(status().isNotFound());

        // the lecturer sees the class list with the work, marks it (not above the maximum)
        JsonNode sheet = s.body(s.call(get("/api/teaching/materials/" + asg + "/submissions"), lecturer, null).andExpect(status().isOk()));
        assertThat(sheet.get("students")).hasSize(1);
        long sub = sheet.get("students").get(0).get("submission").get("id").asLong();
        s.call(get("/api/teaching/submissions/" + sub + "/file"), lecturer, null).andExpect(status().isOk());
        s.call(put("/api/teaching/submissions/" + sub + "/mark"), lecturer, "{\"score\":25}").andExpect(jsonPath("$.code").value("SCORE_INVALID"));
        s.call(put("/api/teaching/submissions/" + sub + "/mark"), hod, "{\"score\":15}").andExpect(status().isForbidden());
        s.call(put("/api/teaching/submissions/" + sub + "/mark"), lecturer, "{\"score\":15.5,\"feedback\":\"Good work\"}")
                .andExpect(jsonPath("$.score").value(15.5)).andExpect(jsonPath("$.markedByName").value("Staff lecturer"));
        s.call(get("/api/student/modules/" + o1 + "/materials"), st1, null).andExpect(jsonPath("$[?(@.id==" + asg + ")].mine.feedback").value("Good work"));
        perform(multipart("/api/student/materials/" + asg + "/submission").file(pdf("file", "late.pdf")), st1).andExpect(jsonPath("$.code").value("ALREADY_MARKED"));
        s.call(delete("/api/teaching/materials/" + asg), lecturer, null).andExpect(jsonPath("$.code").value("HAS_SUBMISSIONS"));

        // after the due date an assignment that takes no late work is closed
        db(() -> jdbc.update("update course_materials set due_at = now() - interval '1 hour' where id = ?", asg));
        db(() -> jdbc.update("update assignment_submissions set marked_at = null where material_id = ?", asg));
        perform(multipart("/api/student/materials/" + asg + "/submission").file(pdf("file", "late.pdf")), st1).andExpect(jsonPath("$.code").value("ASSIGNMENT_CLOSED"));

        s.call(delete("/api/teaching/materials/" + note), lecturer, null).andExpect(status().isNoContent());
        s.call(get("/api/student/materials/" + note + "/file"), st1, null).andExpect(status().isNotFound());
    }

    private org.springframework.test.web.servlet.ResultActions perform(MockMultipartHttpServletRequestBuilder b, String token) throws Exception {
        return mvc.perform(b.header("X-Tenant", tenant).header("Authorization", "Bearer " + token));
    }

    private <T> T db(java.util.function.Supplier<T> work) {
        return tz.co.msingi.cims.platform.tenancy.TenantContext.callAs(inst.info(), () -> tx.execute(x -> work.get()));
    }

    private static MockMultipartFile pdf(String part, String name) {
        return new MockMultipartFile(part, name, "application/pdf", PDF);
    }

    private long module(String code) throws Exception {
        return s.body(s.call(post("/api/academic/modules"), hod, "{\"code\":\"" + code + "\",\"title\":\"Module " + code
                + "\",\"credits\":10,\"ntaLevel\":4,\"assessmentType\":\"GRADED\",\"active\":true}").andExpect(status().isCreated())).get("id").asLong();
    }

    private static String slot(long moduleId, int semester) {
        return "{\"moduleId\":" + moduleId + ",\"yearOfStudy\":1,\"semesterNo\":" + semester + ",\"category\":\"CORE\"}";
    }
}
