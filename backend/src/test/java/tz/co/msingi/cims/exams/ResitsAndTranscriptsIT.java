package tz.co.msingi.cims.exams;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tz.co.msingi.cims.platform.security.Role;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FR-EXM-08 (supplementary and special examinations, BP-10), amendments to confirmed results, FR-EXM-13 (statements of results with
 * verification) and FR-EXM-11 (NACTVET results file and submission record).
 */
@IntegrationTest
class ResitsAndTranscriptsIT {

    static final String INSTRUCTIONS = """
            {"reportingDate":"2027-09-27","reportingVenue":"Main Hall","documentsToBring":["Original CSEE certificate"],
             "signatoryName":"Dr. Amina Salum","signatoryTitle":"Principal"}""";
    static final LocalDate TODAY = LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam"));

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;

    AdmissionScenario s;
    TestData.Institution inst;
    String admin, dpa, hod, officer, registrar, lecturer, exams;
    long yearId, semId;
    Map<String, Long> off = new HashMap<>();
    String[] reg = new String[3];
    String[] tok = new String[3];
    long[] studentId = new long[3];
    long mainSheet;

    @BeforeEach
    void setUp() throws Exception {
        String tenant = "T" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
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
        s.call(post("/api/exams/regulations"), exams, json.writeValueAsString(RegulationDefaults.ntaCertificate(yearId))).andExpect(status().isCreated());
        publishedMainResults();
    }

    @Test
    void supplementaryAndSpecialExaminationsLeadToFinalResults() throws Exception {
        String session = "{\"supplementaryFee\":20000,\"specialFee\":0,\"registrationCloses\":\"" + TODAY.plusDays(7) + "\",\"examPeriod\":\"Week of 10 July\"}";
        s.call(put("/api/exams/semesters/" + semId + "/resit-session"), lecturer, session).andExpect(status().isForbidden());
        JsonNode sv = s.body(s.call(put("/api/exams/semesters/" + semId + "/resit-session"), exams, session).andExpect(status().isOk()));
        assertThat(sv.get("eligibleStudents").asInt()).isEqualTo(2);
        assertThat(sv.get("eligibleModules").asInt()).isEqualTo(3);

        // who may register for what
        assertThat(s.body(s.call(get("/api/student/resits"), tok[0], null)).get("modules")).isEmpty();
        JsonNode b = s.body(s.call(get("/api/student/resits"), tok[1], null));
        assertThat(b.get("modules").findValuesAsText("code")).containsExactly("ITU 04102");
        assertThat(b.at("/modules/0/kind").asText()).isEqualTo("SUPPLEMENTARY");
        JsonNode c = s.body(s.call(get("/api/student/resits"), tok[2], null));
        assertThat(c.get("modules").findValuesAsText("kind")).containsExactly("SPECIAL", "SUPPLEMENTARY");
        long sessionId = b.get("sessionId").asLong();
        long bM2 = b.at("/modules/0/moduleResultId").asLong();
        long cM1 = c.at("/modules/0/moduleResultId").asLong(), cM3 = c.at("/modules/1/moduleResultId").asLong();
        s.call(post("/api/student/resits"), tok[1], "{\"sessionId\":" + sessionId + ",\"moduleResultIds\":[" + cM1 + "]}")
                .andExpect(jsonPath("$.code").value("NOT_ELIGIBLE"));
        b = s.body(s.call(post("/api/student/resits"), tok[1], "{\"sessionId\":" + sessionId + ",\"moduleResultIds\":[" + bM2 + "]}").andExpect(status().isOk()));
        assertThat(b.get("amountDue").decimalValue()).isEqualByComparingTo("20000");
        assertThat(b.get("controlNo").asText()).isNotBlank();
        s.call(post("/api/student/resits"), tok[1], "{\"sessionId\":" + sessionId + ",\"moduleResultIds\":[" + bM2 + "]}")
                .andExpect(jsonPath("$.code").value("ALREADY_REGISTERED"));
        c = s.body(s.call(post("/api/student/resits"), tok[2], "{\"sessionId\":" + sessionId + ",\"moduleResultIds\":[" + cM1 + "," + cM3 + "]}"));
        assertThat(c.get("amountDue").decimalValue()).isEqualByComparingTo("20000"); // the special examination is free

        // resit marks: only once paid; the supplementary grade is capped at C
        JsonNode m2 = s.body(s.call(get("/api/exams/offerings/" + off.get("ITU 04102") + "/resits"), lecturer, null));
        long bResit = m2.at("/rows/0/resitId").asLong();
        assertThat(m2.at("/rows/0/paid").asBoolean()).isFalse();
        s.call(put("/api/exams/offerings/" + off.get("ITU 04102") + "/resits"), lecturer, resit(bResit, "85")).andExpect(jsonPath("$.code").value("FEE_NOT_PAID"));
        s.call(post("/api/student/invoices/" + b.get("invoiceId").asLong() + "/simulate-payment"), tok[1], "{}").andExpect(status().isOk());
        m2 = s.body(s.call(put("/api/exams/offerings/" + off.get("ITU 04102") + "/resits"), lecturer, resit(bResit, "85")).andExpect(status().isOk()));
        assertThat(m2.at("/rows/0/total").decimalValue()).isEqualByComparingTo("75");   // CA 24 + 85% of 60 = 51
        assertThat(m2.at("/rows/0/grade").asText()).isEqualTo("C");                     // a B, capped
        assertThat(m2.at("/rows/0/capped").asBoolean()).isTrue();
        resitsApproved("ITU 04102");

        JsonNode m1 = s.body(s.call(get("/api/exams/offerings/" + off.get("ITU 04101") + "/resits"), lecturer, null));
        assertThat(m1.at("/rows/0/kind").asText()).isEqualTo("SPECIAL");
        m1 = s.body(s.call(put("/api/exams/offerings/" + off.get("ITU 04101") + "/resits"), lecturer, resit(m1.at("/rows/0/resitId").asLong(), "70")));
        assertThat(m1.at("/rows/0/grade").asText()).isEqualTo("B");                     // 28 + 42 = 70, not capped
        assertThat(m1.at("/rows/0/capped").asBoolean()).isFalse();
        resitsApproved("ITU 04101");

        s.call(post("/api/student/invoices/" + c.get("invoiceId").asLong() + "/simulate-payment"), tok[2], "{}").andExpect(status().isOk());
        JsonNode m3 = s.body(s.call(get("/api/exams/offerings/" + off.get("ITU 04103") + "/resits"), lecturer, null));
        m3 = s.body(s.call(put("/api/exams/offerings/" + off.get("ITU 04103") + "/resits"), lecturer, resit(m3.at("/rows/0/resitId").asLong(), "90")));
        assertThat(m3.at("/rows/0/remark").asText()).isEqualTo("FAIL");                 // coursework 12 cannot be fixed by an examination
        s.call(post("/api/exams/offerings/" + off.get("ITU 04103") + "/resits/submit"), lecturer, null).andExpect(status().isOk());

        String compute = "{\"semesterId\":" + semId + ",\"programmeId\":" + inst.programmeId() + ",\"yearOfStudy\":1,\"round\":\"RESIT\"}";
        s.call(post("/api/exams/sheets/compute"), exams, compute).andExpect(jsonPath("$.code").value("RESITS_NOT_APPROVED"));
        s.call(post("/api/exams/offerings/" + off.get("ITU 04103") + "/resits/approve"), hod, null).andExpect(jsonPath("$.status").value("APPROVED"));

        // the RESIT sheet: only the students who sat resits; carry over for a module still failed
        JsonNode sheet = s.body(s.call(post("/api/exams/sheets/compute"), exams, compute).andExpect(status().isOk()));
        assertThat(sheet.get("round").asText()).isEqualTo("RESIT");
        Map<String, JsonNode> rows = rows(sheet);
        assertThat(rows).containsOnlyKeys(reg[1], reg[2]);
        assertThat(rows.get(reg[1]).get("gpa").decimalValue()).isEqualByComparingTo("2.3");   // C, C (capped), B
        assertThat(rows.get(reg[1]).get("decision").asText()).isEqualTo("PASS");
        assertThat(rows.get(reg[1]).at("/cells/ITU 04102/attempt").asText()).isEqualTo("SUPPLEMENTARY");
        assertThat(rows.get(reg[2]).get("decision").asText()).isEqualTo("CARRY_OVER");
        long resitSheet = sheet.get("id").asLong();
        s.call(post("/api/exams/sheets/" + resitSheet + "/recommend"), dpa, "{}").andExpect(status().isOk());
        s.call(post("/api/exams/sheets/" + resitSheet + "/confirm"), admin, null).andExpect(status().isOk());
        s.call(post("/api/exams/sheets/" + resitSheet + "/publish"), exams, null).andExpect(jsonPath("$.status").value("PUBLISHED"));

        JsonNode mine = s.body(s.call(get("/api/student/results"), tok[1], null)).get("semesters");
        assertThat(mine.findValuesAsText("round")).containsExactly("RESIT", "MAIN");
        assertThat(mine.get(0).get("decision").asText()).isEqualTo("PASS");
        JsonNode m2line = null;
        for (JsonNode m : mine.get(0).get("modules")) if (m.get("code").asText().equals("ITU 04102")) m2line = m;
        assertThat(m2line.get("grade").asText()).isEqualTo("C");
        assertThat(m2line.get("attempt").asText()).isEqualTo("SUPPLEMENTARY");
        s.call(post("/api/exams/offerings/" + off.get("ITU 04102") + "/resits/return"), exams, "{\"note\":\"x\"}").andExpect(jsonPath("$.code").value("RESULTS_LOCKED"));

        // the statement of results shows the final (after resits) semester, and can be verified
        byte[] pdf = mvc.perform(get("/api/student/transcript").header("Authorization", "Bearer " + tok[1]).header("X-Tenant", s.tenant))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        String text;
        try (PDDocument doc = PDDocument.load(pdf)) {
            text = new PDFTextStripper().getText(doc);
        }
        assertThat(text).contains("PROVISIONAL TRANSCRIPT", reg[1], "ITU 04102", "after supplementary", "Pass");
        JsonNode docs = s.body(s.call(get("/api/exams/students/" + studentId[1] + "/transcripts"), registrar, null));
        assertThat(docs).hasSize(1);
        String code = docs.get(0).get("verificationCode").asText();
        mvc.perform(get("/api/student/transcript").header("Authorization", "Bearer " + tok[1]).header("X-Tenant", s.tenant)).andExpect(status().isOk());
        assertThat(s.body(s.call(get("/api/exams/students/" + studentId[1] + "/transcripts"), registrar, null))).hasSize(1); // unchanged: same document
        JsonNode v = s.body(s.call(get("/api/public/verify/" + code), null, null).andExpect(status().isOk()));
        assertThat(v.get("documentType").asText()).isEqualTo("STATEMENT_OF_RESULTS");
        assertThat(v.get("status").asText()).isEqualTo("VALID");
        assertThat(v.get("registrationNo").asText()).isEqualTo(reg[1]);
        assertThat(v.get("cgpa").decimalValue()).isEqualByComparingTo("2.3");
    }

    @Test
    void confirmedResultsChangeOnlyByApprovedAmendment() throws Exception {
        // a statement issued before the amendment
        mvc.perform(post("/api/exams/students/" + studentId[0] + "/transcript").header("Authorization", "Bearer " + exams).header("X-Tenant", s.tenant))
                .andExpect(status().isOk());
        String oldCode = s.body(s.call(get("/api/exams/students/" + studentId[0] + "/transcripts"), exams, null)).get(0).get("verificationCode").asText();

        JsonNode records = s.body(s.call(get("/api/exams/students/" + studentId[0] + "/module-results"), exams, null));
        long m3 = 0;
        for (JsonNode r : records) if (r.get("code").asText().equals("ITU 04103")) m3 = r.get("moduleResultId").asLong();
        assertThat(records.findValuesAsText("locked")).containsOnly("true");
        String body = "{\"moduleResultId\":" + m3 + ",\"caTotal\":36,\"seTotal\":54,\"reason\":\"Marks of the second script were not added\",\"evidenceRef\":\"Script 23\"}";
        s.call(post("/api/exams/amendments"), lecturer, body).andExpect(status().isForbidden());
        s.call(post("/api/exams/amendments"), exams, body.replace("\"caTotal\":36", "\"caTotal\":45")).andExpect(jsonPath("$.code").value("INVALID_SCORE"));
        JsonNode a = s.body(s.call(post("/api/exams/amendments"), exams, body).andExpect(status().isCreated()));
        assertThat(a.get("oldGrade").asText()).isEqualTo("B");
        assertThat(a.get("newGrade").asText()).isEqualTo("A");
        s.call(post("/api/exams/amendments"), exams, body).andExpect(jsonPath("$.code").value("AMENDMENT_PENDING"));
        s.call(post("/api/exams/amendments/" + a.get("id").asLong() + "/approve"), exams, "{}").andExpect(status().isForbidden());
        JsonNode adminReq = s.body(s.call(get("/api/exams/amendments"), admin, null));
        assertThat(adminReq.get(0).get("canDecide").asBoolean()).isTrue();
        s.call(post("/api/exams/amendments/" + a.get("id").asLong() + "/approve"), admin, "{\"note\":\"Checked against the script\"}")
                .andExpect(jsonPath("$.status").value("APPROVED"));

        JsonNode sheet = s.body(s.call(get("/api/exams/sheets/" + mainSheet), exams, null));
        JsonNode row = rows(sheet).get(reg[0]);
        assertThat(row.at("/cells/ITU 04103/grade").asText()).isEqualTo("A");
        assertThat(row.get("gpa").decimalValue()).isEqualByComparingTo("3.7");          // A, B, A → (4+3+4) x 10 / 30
        assertThat(s.body(s.call(get("/api/messaging/messages?size=50"), admin, null)).get("content").findValuesAsText("templateCode")).contains("RESULT_AMENDED");

        // the earlier statement is superseded by a new one
        mvc.perform(post("/api/exams/students/" + studentId[0] + "/transcript").header("Authorization", "Bearer " + exams).header("X-Tenant", s.tenant))
                .andExpect(status().isOk());
        JsonNode docs = s.body(s.call(get("/api/exams/students/" + studentId[0] + "/transcripts"), exams, null));
        assertThat(docs.findValuesAsText("status")).containsExactly("VALID", "SUPERSEDED");
        assertThat(s.body(s.call(get("/api/public/verify/" + oldCode), null, null)).get("status").asText()).isEqualTo("SUPERSEDED");

        // NACTVET: the results file of the confirmed sheet, its submission and NACTVET's response
        byte[] xlsx = mvc.perform(get("/api/exams/sheets/" + mainSheet + "/nactvet-file").header("Authorization", "Bearer " + exams).header("X-Tenant", s.tenant))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            var sh = wb.getSheetAt(0);
            List<String> header = new ArrayList<>();
            sh.getRow(11).forEach(cell -> header.add(cell.getStringCellValue()));
            assertThat(header).contains("NACTVET verification no.", "Registration no.", "ITU 04101", "ITU 04102", "ITU 04103", "GPA", "Remark");
            assertThat(sh.getLastRowNum()).isEqualTo(14);
        }
        JsonNode sub = s.body(s.call(post("/api/exams/sheets/" + mainSheet + "/nactvet-submissions"), exams,
                "{\"reference\":\"NACTVET/RES/2028/0042\",\"note\":\"Uploaded on the NACTVET portal\"}").andExpect(status().isOk()));
        s.call(put("/api/exams/nactvet-submissions/" + sub.get("id").asLong()), exams, "{\"status\":\"ACCEPTED\"}").andExpect(jsonPath("$.status").value("ACCEPTED"));
        assertThat(s.body(s.call(get("/api/exams/sheets/" + mainSheet), exams, null)).at("/nactvetSubmissions/0/status").asText()).isEqualTo("ACCEPTED");
    }

    // ------------------------------------------------------------------ a published class: A passes, B supplementary, C special exam

    private void publishedMainResults() throws Exception {
        long m1 = module("ITU 04101"), m2 = module("ITU 04102"), m3 = module("ITU 04103"), m4 = module("ITU 04201");
        long cid = s.body(s.call(post("/api/academic/curricula"), hod, "{\"programmeId\":" + inst.programmeId() + ",\"effectiveYearId\":" + yearId
                + ",\"modules\":[" + slot(m1, 1) + "," + slot(m2, 1) + "," + slot(m3, 1) + "," + slot(m4, 2) + "],\"electiveRules\":[]}")).get("id").asLong();
        s.call(post("/api/academic/curricula/" + cid + "/approve"), dpa, null).andExpect(status().isOk());
        semId = s.body(s.call(post("/api/academic/semesters"), dpa, "{\"academicYearId\":" + yearId + ",\"semesterNo\":1,\"name\":\"Semester 1\","
                + "\"startDate\":\"2027-09-27\",\"endDate\":\"2028-02-10\",\"registrationOpens\":\"" + TODAY.minusDays(1) + "\",\"registrationCloses\":\""
                + TODAY.plusDays(10) + "\",\"marksEntryOpens\":\"" + TODAY.minusDays(1) + "\",\"marksEntryCloses\":\"" + TODAY.plusDays(30) + "\"}")
                .andExpect(status().isCreated())).get("id").asLong();
        Applicant a = s.apply("S0101/0001");
        Applicant b = s.applyEligibleEndingIn(5, 400);
        Applicant c = s.applyEligibleEndingIn(5, 900);
        s.admit(officer, dpa, a, b, c);
        JsonNode handover = s.body(s.call(post("/api/registry/windows/" + inst.windowId() + "/handover"), registrar, "{}"));
        Map<String, String> byPhone = new HashMap<>();
        Map<String, Long> idByReg = new HashMap<>();
        for (JsonNode st : handover.get("students")) {
            long id = st.get("studentId").asLong();
            JsonNode d = s.body(s.call(get("/api/registry/students/" + id), registrar, null));
            s.call(put("/api/registry/students/" + id + "/first-registration"), registrar,
                    "{\"documents\":[{\"item\":\"Original CSEE certificate\",\"verified\":true}]}").andExpect(status().isOk());
            s.call(post("/api/registry/students/" + id + "/first-registration/approve"), registrar, null).andExpect(status().isOk());
            byPhone.put(d.at("/summary/phone").asText(), d.at("/summary/registrationNo").asText());
            idByReg.put(d.at("/summary/registrationNo").asText(), id);
        }
        Applicant[] apps = {a, b, c};
        for (int i = 0; i < 3; i++) {
            String phone = s.body(s.call(get("/api/applicant/profile"), apps[i].token(), null)).get("phone").asText();
            reg[i] = byPhone.get(phone);
            studentId[i] = idByReg.get(reg[i]);
            tok[i] = s.body(s.call(post("/api/student-auth/login"), null, "{\"login\":\"" + reg[i] + "\",\"password\":\"Secret@123\"}")).get("accessToken").asText();
            s.call(post("/api/student/semester-registration"), tok[i], "{\"semesterId\":" + semId + ",\"electiveModuleIds\":[],\"detailsConfirmed\":true}")
                    .andExpect(status().isOk());
        }
        s.call(post("/api/registry/semester-registrations/approve-all"), registrar, "{\"semesterId\":" + semId + "}").andExpect(status().isOk());
        s.call(post("/api/exams/offerings/setup"), exams, "{\"semesterId\":" + semId + "}").andExpect(status().isOk());
        s.body(s.call(get("/api/exams/offerings?semesterId=" + semId), exams, null)).forEach(o -> off.put(o.get("code").asText(), o.get("id").asLong()));
        long lecturerId = s.body(s.call(get("/api/exams/staff"), exams, null)).findParents("fullName").stream()
                .filter(x -> x.get("fullName").asText().equals("Staff lecturer")).findFirst().orElseThrow().get("id").asLong();
        // CA (of 100, weight 40) and SE (of 100, weight 60) per student: A, B, C
        Map<String, String[][]> marks = Map.of(
                "ITU 04101", new String[][]{{"80", "80"}, {"60", "60"}, {"70", "ABS"}},
                "ITU 04102", new String[][]{{"70", "70"}, {"60", "30"}, {"70", "60"}},
                "ITU 04103", new String[][]{{"70", "70"}, {"70", "70"}, {"30", "80"}});
        for (Map.Entry<String, String[][]> e : marks.entrySet()) {
            long id = off.get(e.getKey());
            s.call(put("/api/exams/offerings/" + id + "/staff"), exams, "{\"userIds\":[" + lecturerId + "]}").andExpect(status().isOk());
            JsonNode v = s.body(s.call(get("/api/exams/offerings/" + id), lecturer, null));
            long ca = v.at("/components/0/id").asLong(), se = v.at("/components/1/id").asLong();
            Map<String, Long> regIds = new HashMap<>();
            v.get("rows").forEach(r -> regIds.put(r.get("registrationNo").asText(), r.get("moduleRegistrationId").asLong()));
            List<String> entries = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                entries.add(entry(regIds.get(reg[i]), ca, e.getValue()[i][0]));
                entries.add(entry(regIds.get(reg[i]), se, e.getValue()[i][1]));
            }
            s.call(put("/api/exams/offerings/" + id + "/scores"), lecturer, "{\"entries\":[" + String.join(",", entries) + "]}").andExpect(status().isOk());
            s.call(post("/api/exams/offerings/" + id + "/submit"), lecturer, null).andExpect(status().isOk());
            s.call(post("/api/exams/offerings/" + id + "/hod-approve"), hod, null).andExpect(status().isOk());
            s.call(post("/api/exams/offerings/" + id + "/board-approve"), exams, null).andExpect(status().isOk());
        }
        s.call(put("/api/exams/semesters/" + semId + "/resit-session"), exams,
                "{\"supplementaryFee\":0,\"specialFee\":0,\"registrationCloses\":\"" + TODAY.plusDays(7) + "\"}").andExpect(jsonPath("$.code").value("NO_PUBLISHED_RESULTS"));
        JsonNode sheet = s.body(s.call(post("/api/exams/sheets/compute"), exams,
                "{\"semesterId\":" + semId + ",\"programmeId\":" + inst.programmeId() + ",\"yearOfStudy\":1}").andExpect(status().isOk()));
        mainSheet = sheet.get("id").asLong();
        Map<String, JsonNode> rows = rows(sheet);
        assertThat(rows.get(reg[0]).get("decision").asText()).isEqualTo("PASS");
        assertThat(rows.get(reg[1]).get("decision").asText()).isEqualTo("SUPPLEMENTARY");
        assertThat(rows.get(reg[2]).get("decision").asText()).isEqualTo("SPECIAL_EXAM");
        s.call(post("/api/exams/sheets/" + mainSheet + "/recommend"), dpa, "{}").andExpect(status().isOk());
        s.call(post("/api/exams/sheets/" + mainSheet + "/confirm"), admin, null).andExpect(status().isOk());
        s.call(post("/api/exams/sheets/" + mainSheet + "/publish"), exams, null).andExpect(status().isOk());
    }

    private void resitsApproved(String code) throws Exception {
        s.call(post("/api/exams/offerings/" + off.get(code) + "/resits/submit"), lecturer, null).andExpect(jsonPath("$.status").value("SUBMITTED"));
        s.call(post("/api/exams/offerings/" + off.get(code) + "/resits/approve"), hod, null).andExpect(jsonPath("$.status").value("APPROVED"));
    }

    private long module(String code) throws Exception {
        return s.body(s.call(post("/api/academic/modules"), hod, "{\"code\":\"" + code + "\",\"title\":\"Module " + code
                + "\",\"credits\":10,\"ntaLevel\":4,\"assessmentType\":\"GRADED\",\"active\":true}").andExpect(status().isCreated())).get("id").asLong();
    }

    private static String slot(long moduleId, int sem) {
        return "{\"moduleId\":" + moduleId + ",\"yearOfStudy\":1,\"semesterNo\":" + sem + ",\"category\":\"CORE\"}";
    }

    private static String entry(long reg, long comp, String score) {
        return score.equals("ABS") ? "{\"moduleRegistrationId\":" + reg + ",\"componentId\":" + comp + ",\"absent\":true}"
                : "{\"moduleRegistrationId\":" + reg + ",\"componentId\":" + comp + ",\"score\":" + score + "}";
    }

    private static String resit(long id, String score) {
        return "{\"entries\":[{\"resitId\":" + id + ",\"score\":" + score + "}]}";
    }

    private static Map<String, JsonNode> rows(JsonNode sheet) {
        Map<String, JsonNode> m = new HashMap<>();
        sheet.get("rows").forEach(x -> m.put(x.get("registrationNo").asText(), x));
        return m;
    }
}
