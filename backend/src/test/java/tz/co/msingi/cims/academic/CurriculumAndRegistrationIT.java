package tz.co.msingi.cims.academic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.support.AdmissionScenario;
import tz.co.msingi.cims.support.AdmissionScenario.Applicant;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FR-ACA-02/03/04 and FR-REG-03: modules, versioned curricula, the calendar and semester registration. */
@IntegrationTest
class CurriculumAndRegistrationIT {

    static final String INSTRUCTIONS = """
            {"reportingDate":"2027-09-27","reportingVenue":"Main Hall","documentsToBring":["Original CSEE certificate"],
             "signatoryName":"Dr. Amina Salum","signatoryTitle":"Principal"}""";
    static final LocalDate TODAY = LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam"));

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;

    AdmissionScenario s;
    String admin, dpa, hod, officer, registrar, viewer, accountant, bursar;
    long yearId;

    @BeforeEach
    void setUp() throws Exception {
        String tenant = "C" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TestData.Institution inst = data.institution(tenant, BigDecimal.ZERO);
        data.staff(inst.tenant(), "admin", Role.INSTITUTION_ADMIN);
        data.staff(inst.tenant(), "dpa", Role.DEPUTY_PRINCIPAL_ACADEMIC);
        data.staff(inst.tenant(), "hod", Role.HEAD_OF_DEPARTMENT);
        data.staff(inst.tenant(), "registrar", Role.REGISTRAR);
        data.staff(inst.tenant(), "viewer", Role.MANAGEMENT_VIEWER);
        data.staff(inst.tenant(), "accountant", Role.ACCOUNTANT);
        data.staff(inst.tenant(), "bursar", Role.BURSAR);
        s = new AdmissionScenario(mvc, json, tenant, inst);
        admin = s.staffToken("admin");
        dpa = s.staffToken("dpa");
        hod = s.staffToken("hod");
        officer = s.staffToken("officer");
        registrar = s.staffToken("registrar");
        viewer = s.staffToken("viewer");
        accountant = s.staffToken("accountant");
        bursar = s.staffToken("bursar");
        s.call(put("/api/admission/windows/" + inst.windowId() + "/joining-instructions"), officer, INSTRUCTIONS).andExpect(status().isOk());
        yearId = s.body(s.call(get("/api/admission/windows/" + inst.windowId()), officer, null)).get("academicYearId").asLong();
    }

    @Test
    void curriculaAreVersionedCheckedAndApprovedBySomeoneElse() throws Exception {
        long m1 = module(hod, "ITU 04101", 12), m2 = module(hod, "ITU 04102", 10), e1 = module(hod, "ITU 04103", 8), e2 = module(hod, "ITU 04104", 8);
        s.call(post("/api/academic/modules"), hod, moduleJson("itu 04101", 5)).andExpect(jsonPath("$.code").value("DUPLICATE_CODE"));
        s.call(post("/api/academic/modules"), viewer, moduleJson("X 1", 5)).andExpect(status().isForbidden());

        // year 1 semester 2 is empty and the electives have no rule
        JsonNode c = s.body(s.call(post("/api/academic/curricula"), hod, curriculum(List.of(slot(m1, 1, 1, "CORE"), slot(e1, 1, 1, "ELECTIVE"),
                slot(e2, 1, 1, "ELECTIVE")), "")).andExpect(status().isCreated()));
        long id = c.get("id").asLong();
        assertThat(c.get("curriculumVersion").asInt()).isEqualTo(1);
        assertThat(c.get("problems").findValuesAsText("")).isEmpty();
        assertThat(texts(c.get("problems"))).containsExactly("Year 1 semester 1: say how many of the 2 electives a student takes", "Year 1 semester 2: no modules");
        s.call(post("/api/academic/curricula"), hod, curriculum(List.of(slot(m1, 3, 1, "CORE")), ""))
                .andExpect(jsonPath("$.code").value("DRAFT_EXISTS"));
        s.call(put("/api/academic/curricula/" + id), hod, curriculum(List.of(slot(m1, 2, 1, "CORE")), ""))
                .andExpect(jsonPath("$.code").value("OUTSIDE_PROGRAMME"));
        s.call(post("/api/academic/curricula/" + id + "/approve"), dpa, null).andExpect(jsonPath("$.code").value("CURRICULUM_INCOMPLETE"));

        s.call(put("/api/academic/curricula/" + id), dpa, curriculum(List.of(slot(m1, 1, 1, "CORE"), slot(e1, 1, 1, "ELECTIVE"), slot(e2, 1, 1, "ELECTIVE"),
                slot(m2, 1, 2, "CORE")), "{\"yearOfStudy\":1,\"semesterNo\":1,\"minElectives\":1,\"maxElectives\":1}")).andExpect(status().isOk());
        s.call(post("/api/academic/curricula/" + id + "/approve"), hod, null).andExpect(status().isForbidden());
        // the HOD prepared it: the Deputy Principal approves (the admin could too, but not whoever created it)
        JsonNode approved = s.body(s.call(post("/api/academic/curricula/" + id + "/approve"), dpa, null).andExpect(status().isOk()));
        assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.get("approvedByName").asText()).isEqualTo("Staff dpa");

        s.call(put("/api/academic/curricula/" + id), hod, curriculum(List.of(slot(m1, 1, 1, "CORE")), "")).andExpect(jsonPath("$.code").value("NOT_A_DRAFT"));
        s.call(put("/api/academic/modules/" + m1), hod, moduleJson("ITU 04101", 15)).andExpect(jsonPath("$.code").value("MODULE_IN_USE"));
        s.call(put("/api/academic/modules/" + m1), hod, moduleJson("ITU 04101", 12).replace("Module ITU 04101", "Renamed")).andExpect(status().isOk());

        JsonNode v2 = s.body(s.call(post("/api/academic/curricula/" + id + "/revise"), hod, null).andExpect(status().isOk()));
        assertThat(v2.get("curriculumVersion").asInt()).isEqualTo(2);
        assertThat(v2.get("modules").size()).isEqualTo(4);
        s.call(post("/api/academic/curricula/" + v2.get("id").asLong() + "/approve"), dpa, null).andExpect(status().isOk()); // HOD prepared v2
        JsonNode list = s.body(s.call(get("/api/academic/curricula?programmeId=" + s.inst.programmeId()), viewer, null));
        assertThat(list.findValuesAsText("status")).containsExactly("APPROVED", "APPROVED");
        assertThat(s.body(s.call(get("/api/academic/modules"), viewer, null)).findValuesAsText("usedInCurricula")).contains("2");

        // another institution sees none of it
        String other = "D" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TestData.Institution o = data.institution(other, BigDecimal.ZERO);
        data.staff(o.tenant(), "admin", Role.INSTITUTION_ADMIN);
        AdmissionScenario os = new AdmissionScenario(mvc, json, other, o);
        String oAdmin = os.staffToken("admin");
        os.call(get("/api/academic/curricula/" + id), oAdmin, null).andExpect(status().isNotFound());
        assertThat(os.body(os.call(get("/api/academic/modules"), oAdmin, null))).isEmpty();
    }

    @Test
    void theCalendarHoldsSemestersAndKeyDates() throws Exception {
        s.call(post("/api/academic/semesters"), dpa, semester(1, "2027-08-01", "2028-02-10", TODAY.minusDays(1), TODAY.plusDays(10), null))
                .andExpect(jsonPath("$.code").value("INVALID_DATES"));
        s.call(post("/api/academic/semesters"), registrar, semester(1, "2027-09-27", "2028-02-10", TODAY, TODAY.plusDays(10), null)).andExpect(status().isForbidden());
        JsonNode sem = s.body(s.call(post("/api/academic/semesters"), dpa, semester(1, "2027-09-27", "2028-02-10", TODAY.minusDays(1), TODAY.plusDays(10),
                TODAY.plusDays(20))).andExpect(status().isCreated()));
        assertThat(sem.get("window").asText()).isEqualTo("OPEN");
        s.call(post("/api/academic/semesters"), dpa, semester(1, "2027-09-27", "2028-02-10", TODAY, TODAY.plusDays(10), null))
                .andExpect(jsonPath("$.code").value("DUPLICATE_SEMESTER"));
        s.call(post("/api/academic/calendar-events"), dpa, "{\"academicYearId\":" + yearId
                + ",\"kind\":\"EXAMINATIONS\",\"title\":\"Too early\",\"startDate\":\"2027-01-24\",\"endDate\":\"2027-02-04\"}")
                .andExpect(jsonPath("$.code").value("INVALID_DATES"));
        s.call(post("/api/academic/calendar-events"), dpa, "{\"academicYearId\":" + yearId + ",\"semesterId\":" + sem.get("id").asLong()
                + ",\"kind\":\"EXAMINATIONS\",\"title\":\"Semester 1 examinations\",\"startDate\":\"2028-01-24\",\"endDate\":\"2028-02-04\"}").andExpect(status().isCreated());
        JsonNode cal = s.body(s.call(get("/api/academic/calendar?academicYearId=" + yearId), viewer, null).andExpect(status().isOk()));
        assertThat(cal.at("/semesters/0/name").asText()).isEqualTo("Semester 1");
        assertThat(cal.at("/events/0/semesterName").asText()).isEqualTo("Semester 1");
    }

    @Test
    void studentsRegisterForTheSemesterAndRegistryApproves() throws Exception {
        long m1 = module(hod, "ITU 04101", 12), m2 = module(hod, "ITU 04102", 10), e1 = module(hod, "ITU 04103", 8), e2 = module(hod, "ITU 04104", 8);
        long m3 = module(hod, "ITU 04201", 10);
        long cid = s.body(s.call(post("/api/academic/curricula"), hod, curriculum(List.of(slot(m1, 1, 1, "CORE"), slot(m2, 1, 1, "CORE"),
                slot(e1, 1, 1, "ELECTIVE"), slot(e2, 1, 1, "ELECTIVE"), slot(m3, 1, 2, "CORE")),
                "{\"yearOfStudy\":1,\"semesterNo\":1,\"minElectives\":1,\"maxElectives\":1}"))).get("id").asLong();
        s.call(post("/api/academic/curricula/" + cid + "/approve"), dpa, null).andExpect(status().isOk());
        long semId = s.body(s.call(post("/api/academic/semesters"), dpa, semester(1, "2027-09-27", "2028-02-10", TODAY.minusDays(1), TODAY.plusDays(10),
                TODAY.plusDays(20)))).get("id").asLong();

        Applicant a = s.apply("S0101/0001");
        Applicant b = s.applyEligibleEndingIn(5, 400);
        s.admit(officer, s.staffToken("dpa"), a, b);
        JsonNode handover = s.body(s.call(post("/api/registry/windows/" + s.inst.windowId() + "/handover"), registrar, "{}"));
        List<Long> ids = new ArrayList<>();
        handover.get("students").forEach(x -> ids.add(x.get("studentId").asLong()));
        String aPhone = s.body(s.call(get("/api/applicant/profile"), a.token(), null)).get("phone").asText();
        long aId = -1, bId = -1;
        String aReg = null, bReg = null;
        for (long id : ids) {
            JsonNode d = s.body(s.call(get("/api/registry/students/" + id), registrar, null));
            if (d.at("/summary/phone").asText().equals(aPhone)) { aId = id; aReg = d.at("/summary/registrationNo").asText(); }
            else { bId = id; bReg = d.at("/summary/registrationNo").asText(); }
        }
        String aTok = studentToken(aReg), bTok = studentToken(bReg);

        // not yet through first registration
        assertThat(s.body(s.call(get("/api/student/semester-registration"), aTok, null).andExpect(status().isOk())).get("state").asText()).isEqualTo("NOT_ACTIVE");
        firstRegistration(aId);
        firstRegistration(bId);

        JsonNode page = s.body(s.call(get("/api/student/semester-registration"), aTok, null));
        assertThat(page.get("state").asText()).isEqualTo("OPEN");
        assertThat(page.get("core").findValuesAsText("code")).containsExactly("ITU 04101", "ITU 04102");
        assertThat(page.get("electives").findValuesAsText("code")).containsExactly("ITU 04103", "ITU 04104");
        assertThat(page.get("minElectives").asInt()).isEqualTo(1);
        assertThat(page.at("/clearance/cleared").asBoolean()).isTrue();

        String url = "/api/student/semester-registration";
        s.call(post(url), aTok, submit(semId, List.of(), true)).andExpect(jsonPath("$.code").value("ELECTIVE_COUNT"));
        s.call(post(url), aTok, submit(semId, List.of(m3), true)).andExpect(jsonPath("$.code").value("NOT_AN_ELECTIVE"));
        s.call(post(url), aTok, submit(semId, List.of(e1), false)).andExpect(jsonPath("$.code").value("DETAILS_NOT_CONFIRMED"));
        JsonNode mine = s.body(s.call(post(url), aTok, submit(semId, List.of(e2), true)).andExpect(status().isOk()));
        assertThat(mine.at("/registration/status").asText()).isEqualTo("SUBMITTED");
        assertThat(mine.at("/registration/totalCredits").decimalValue()).isEqualByComparingTo("30");
        assertThat(mine.at("/registration/modules").findValuesAsText("code")).containsExactly("ITU 04101", "ITU 04102", "ITU 04104");
        assertThat(mine.at("/registration/late").asBoolean()).isFalse();
        // a change of mind before approval replaces the elective
        mine = s.body(s.call(post(url), aTok, submit(semId, List.of(e1), true)));
        assertThat(mine.at("/registration/modules").findValuesAsText("code")).containsExactly("ITU 04101", "ITU 04102", "ITU 04103");

        // the second student registers after the Bursar requires the first instalment: blocked
        requireFirstInstalment();
        s.call(post(url), bTok, submit(semId, List.of(e1), true)).andExpect(jsonPath("$.code").value("FINANCIAL_CLEARANCE_REQUIRED"));

        // Registry approves; approval re-checks fees, so the first student must now pay as well
        long regId = mine.at("/registration/id").asLong();
        JsonNode list = s.body(s.call(get("/api/registry/semester-registrations?semesterId=" + semId), viewer, null).andExpect(status().isOk()));
        assertThat(list.findValuesAsText("registrationNo")).containsExactly(aReg);
        s.call(post("/api/registry/semester-registrations/" + regId + "/approve"), viewer, null).andExpect(status().isForbidden());
        s.call(post("/api/registry/semester-registrations/" + regId + "/approve"), registrar, null).andExpect(jsonPath("$.code").value("FINANCIAL_CLEARANCE_REQUIRED"));
        JsonNode all = s.body(s.call(post("/api/registry/semester-registrations/approve-all"), registrar, "{\"semesterId\":" + semId + "}"));
        assertThat(all.get("approved").asInt()).isZero();
        assertThat(all.get("skipped").get(0).asText()).startsWith(aReg + ": TZS ");
        payAnnualFees(a);
        s.call(post("/api/registry/semester-registrations/" + regId + "/approve"), registrar, null)
                .andExpect(jsonPath("$.status").value("APPROVED")).andExpect(jsonPath("$.decidedByName").value("Staff registrar"));
        s.call(post(url), aTok, submit(semId, List.of(e2), true)).andExpect(jsonPath("$.code").value("ALREADY_APPROVED"));

        // the second pays, registers, is returned for correction and registers again
        payAnnualFees(b);
        long bReg2 = s.body(s.call(post(url), bTok, submit(semId, List.of(e2), true))).at("/registration/id").asLong();
        s.call(post("/api/registry/semester-registrations/" + bReg2 + "/reject"), registrar, "{\"note\":\"\"}").andExpect(status().isBadRequest());
        s.call(post("/api/registry/semester-registrations/" + bReg2 + "/reject"), registrar, "{\"note\":\"Take ITU 04103 as advised by your HOD\"}")
                .andExpect(jsonPath("$.status").value("REJECTED"));
        assertThat(s.body(s.call(get(url), bTok, null)).at("/registration/decisionNote").asText()).isEqualTo("Take ITU 04103 as advised by your HOD");
        s.call(post(url), bTok, submit(semId, List.of(e1), true)).andExpect(jsonPath("$.registration.status").value("SUBMITTED"));
        assertThat(s.body(s.call(post("/api/registry/semester-registrations/approve-all"), registrar, "{\"semesterId\":" + semId + "}")).get("approved").asInt()).isEqualTo(1);

        JsonNode summary = s.body(s.call(get("/api/registry/semester-registrations/summary?semesterId=" + semId), registrar, null));
        assertThat(summary.at("/byStatus/APPROVED").asInt()).isEqualTo(2);
        assertThat(summary.get("notRegistered").asInt()).isZero();

        // reports: registration status and the class list of the elective
        JsonNode st = s.body(s.call(get("/api/reports/REG-06?semesterId=" + semId), registrar, null).andExpect(status().isOk()));
        assertThat(st.at("/rows/0/2").asInt()).isEqualTo(2);
        assertThat(st.at("/rows/0/4").asInt()).isEqualTo(2);
        JsonNode cl = s.body(s.call(get("/api/reports/REG-07?semesterId=" + semId + "&moduleId=" + e1), registrar, null));
        assertThat(cl.get("rowCount").asInt()).isEqualTo(2);
        assertThat(cl.at("/rows/0/6").asText()).isEqualTo("Elective");
        assertThat(s.body(s.call(get("/api/reports/REG-07?semesterId=" + semId + "&moduleId=" + e2), registrar, null)).get("rowCount").asInt()).isZero();
    }

    @Test
    void registrationFollowsTheSemesterWindow() throws Exception {
        long m1 = module(hod, "ITU 04101", 12), m2 = module(hod, "ITU 04201", 12);
        long cid = s.body(s.call(post("/api/academic/curricula"), hod, curriculum(List.of(slot(m1, 1, 1, "CORE"), slot(m2, 1, 2, "CORE")), ""))).get("id").asLong();
        s.call(post("/api/academic/curricula/" + cid + "/approve"), dpa, null).andExpect(status().isOk());
        long closed = s.body(s.call(post("/api/academic/semesters"), dpa, semester(1, "2027-09-27", "2028-02-10", TODAY.minusDays(30), TODAY.minusDays(10),
                TODAY.minusDays(1)))).get("id").asLong();
        long future = s.body(s.call(post("/api/academic/semesters"), dpa, semester(2, "2028-03-06", "2028-07-28", TODAY.plusDays(30), TODAY.plusDays(40), null)))
                .get("id").asLong();
        Applicant a = s.apply("S0101/0001");
        s.admit(officer, dpa, a);
        long id = s.body(s.call(post("/api/registry/windows/" + s.inst.windowId() + "/handover"), registrar, "{}")).at("/students/0/studentId").asLong();
        String reg = s.body(s.call(get("/api/registry/students/" + id), registrar, null)).at("/summary/registrationNo").asText();
        firstRegistration(id);
        String tok = studentToken(reg);
        JsonNode page = s.body(s.call(get("/api/student/semester-registration"), tok, null));
        assertThat(page.get("state").asText()).isEqualTo("NOT_OPEN"); // the next semester to open is shown
        assertThat(page.at("/semester/id").asLong()).isEqualTo(future);
        s.call(post("/api/student/semester-registration"), tok, submit(future, List.of(), true)).andExpect(jsonPath("$.code").value("REGISTRATION_NOT_OPEN"));
        s.call(post("/api/student/semester-registration"), tok, submit(closed, List.of(), true)).andExpect(jsonPath("$.code").value("REGISTRATION_CLOSED"));
    }

    // ------------------------------------------------------------------ helpers

    private long module(String token, String code, int credits) throws Exception {
        return s.body(s.call(post("/api/academic/modules"), token, moduleJson(code, credits)).andExpect(status().isCreated())).get("id").asLong();
    }

    private static String moduleJson(String code, int credits) {
        return "{\"code\":\"" + code + "\",\"title\":\"Module " + code + "\",\"credits\":" + credits + ",\"ntaLevel\":4,\"assessmentType\":\"GRADED\",\"active\":true}";
    }

    private static String slot(long moduleId, int year, int sem, String category) {
        return "{\"moduleId\":" + moduleId + ",\"yearOfStudy\":" + year + ",\"semesterNo\":" + sem + ",\"category\":\"" + category + "\"}";
    }

    private String curriculum(List<String> modules, String rules) {
        return "{\"programmeId\":" + s.inst.programmeId() + ",\"effectiveYearId\":" + yearId + ",\"modules\":[" + String.join(",", modules)
                + "],\"electiveRules\":[" + rules + "]}";
    }

    private String semester(int no, String start, String end, LocalDate opens, LocalDate closes, LocalDate late) {
        return "{\"academicYearId\":" + yearId + ",\"semesterNo\":" + no + ",\"name\":\"Semester " + no + "\",\"startDate\":\"" + start + "\",\"endDate\":\""
                + end + "\",\"registrationOpens\":\"" + opens + "\",\"registrationCloses\":\"" + closes + "\",\"lateRegistrationCloses\":"
                + (late == null ? "null" : "\"" + late + "\"") + "}";
    }

    private static String submit(long semesterId, List<Long> electives, boolean confirmed) {
        return "{\"semesterId\":" + semesterId + ",\"electiveModuleIds\":" + electives + ",\"detailsConfirmed\":" + confirmed + "}";
    }

    private void firstRegistration(long studentId) throws Exception {
        s.call(put("/api/registry/students/" + studentId + "/first-registration"), registrar,
                "{\"documents\":[{\"item\":\"Original CSEE certificate\",\"verified\":true}]}").andExpect(status().isOk());
        s.call(post("/api/registry/students/" + studentId + "/first-registration/approve"), registrar, null).andExpect(status().isOk());
    }

    private String studentToken(String regNo) throws Exception {
        return s.body(s.call(post("/api/student-auth/login"), null, "{\"login\":\"" + regNo + "\",\"password\":\"Secret@123\"}").andExpect(status().isOk()))
                .get("accessToken").asText();
    }

    private void requireFirstInstalment() throws Exception {
        long reg = s.body(s.call(post("/api/finance/fee-items"), accountant,
                "{\"code\":\"REG\",\"name\":\"Registration fee\",\"category\":\"REGISTRATION\",\"allocationPriority\":10,\"active\":true}")).get("id").asLong();
        long st = s.body(s.call(post("/api/finance/fee-structures"), accountant, "{\"academicYearId\":" + yearId + ",\"programmeId\":" + s.inst.programmeId()
                + ",\"yearOfStudy\":1,\"lines\":[{\"feeItemId\":" + reg + ",\"amount\":50000,\"instalment\":1}]}")).get("id").asLong();
        s.call(post("/api/finance/fee-structures/" + st + "/approve"), bursar, null).andExpect(status().isOk());
        s.call(post("/api/finance/billing-runs"), accountant, "{\"academicYearId\":" + yearId + "}").andExpect(status().isOk());
        s.call(put("/api/finance/clearance-gates/REGISTRATION"), bursar, "{\"enabled\":true,\"minPercentPaid\":0,\"firstInstalmentRequired\":true}")
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
