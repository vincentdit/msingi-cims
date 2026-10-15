package tz.co.msingi.cims.teaching;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FR-TCH-02/03/04 (BP-07): sessions and attendance; FR-EXM-04 and BRU-08: examination eligibility and exemptions. */
@IntegrationTest
class AttendanceAndEligibilityIT {

    static final String INSTRUCTIONS = """
            {"reportingDate":"2027-09-27","reportingVenue":"Main Hall","documentsToBring":["Original CSEE certificate"],
             "signatoryName":"Dr. Amina Salum","signatoryTitle":"Principal"}""";
    static final LocalDate TODAY = LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam"));

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;
    @Autowired tz.co.msingi.cims.platform.tenancy.TenantWork tenantWork;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired CheckInService checkIns;

    AdmissionScenario s;
    TestData.Institution inst;
    String admin, dpa, hod, otherHod, officer, registrar, viewer, lecturer, lecturer2, exams, accountant, bursar;
    long yearId;

    @BeforeEach
    void setUp() throws Exception {
        String tenant = "T" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
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
        s.call(post("/api/exams/regulations"), exams, json.writeValueAsString(RegulationDefaults.ntaCertificate(yearId))).andExpect(status().isCreated());
    }

    @Test
    void attendanceIsRecordedOnceAndLowAttendanceIsFlagged() throws Exception {
        Setup c = classWithOfferings();
        long m1 = c.offerings.get("ITU 04101");
        s.call(put("/api/exams/settings"), dpa, "{\"attendanceAlertAfterSessions\":2}")
                .andExpect(jsonPath("$.useDepartmentBoard").value(true)).andExpect(jsonPath("$.minAttendancePercent").value(75))
                .andExpect(jsonPath("$.attendanceAlertAfterSessions").value(2));
        s.call(put("/api/exams/settings"), lecturer, "{\"minAttendancePercent\":50}").andExpect(status().isForbidden());

        JsonNode register = s.body(s.call(get("/api/teaching/offerings/" + m1 + "/register"), lecturer, null));
        assertThat(register).hasSize(3);
        assertThat(register.findValuesAsText("status")).containsOnly("PRESENT");
        Map<String, Long> reg = new HashMap<>();
        register.forEach(x -> reg.put(x.get("registrationNo").asText(), x.get("moduleRegistrationId").asLong()));

        // a: always there; b: absent twice of four (50%); c: late, excused, present, absent (2 of 3 counted = 66.7%)
        String[][] plan = {{"PRESENT", "ABSENT", "LATE"}, {"PRESENT", "ABSENT", "EXCUSED"}, {"PRESENT", "PRESENT", "PRESENT"}, {"PRESENT", "PRESENT", "ABSENT"}};
        UUID first = UUID.randomUUID();
        s.call(post("/api/teaching/offerings/" + m1 + "/sessions"), lecturer2, session(first, TODAY.minusDays(10), "08:00", reg, c, plan[0]))
                .andExpect(status().isForbidden());
        long s1 = s.body(s.call(post("/api/teaching/offerings/" + m1 + "/sessions"), lecturer, session(first, TODAY.minusDays(10), "08:00", reg, c, plan[0]))
                .andExpect(status().isOk())).get("id").asLong();
        // the device saves the same session again (e.g. after a dropped connection): no duplicate
        assertThat(s.body(s.call(post("/api/teaching/offerings/" + m1 + "/sessions"), lecturer, session(first, TODAY.minusDays(10), "08:00", reg, c, plan[0])))
                .get("id").asLong()).isEqualTo(s1);
        s.call(post("/api/teaching/offerings/" + m1 + "/sessions"), lecturer, session(UUID.randomUUID(), TODAY.minusDays(10), "08:00", reg, c, plan[0]))
                .andExpect(jsonPath("$.code").value("SESSION_EXISTS"));
        s.call(post("/api/teaching/offerings/" + m1 + "/sessions"), lecturer, session(UUID.randomUUID(), TODAY.plusDays(1), "08:00", reg, c, plan[0]))
                .andExpect(jsonPath("$.code").value("FUTURE_SESSION"));
        for (int i = 1; i < 4; i++) {
            s.call(post("/api/teaching/offerings/" + m1 + "/sessions"), lecturer, session(UUID.randomUUID(), TODAY.minusDays(10 - i), "08:00", reg, c, plan[i]))
                    .andExpect(status().isOk());
        }

        JsonNode v = s.body(s.call(get("/api/teaching/offerings/" + m1 + "/attendance"), lecturer, null));
        assertThat(v.get("sessions").asInt()).isEqualTo(4);
        assertThat(v.get("minutesTaught").asInt()).isEqualTo(4 * 120);
        assertThat(v.get("below").asInt()).isEqualTo(2);
        assertThat(v.get("canRecord").asBoolean()).isTrue();
        Map<String, JsonNode> st = byRegNo(v.get("students"));
        assertThat(st.get(c.a).get("percent").decimalValue()).isEqualByComparingTo("100");
        assertThat(st.get(c.b).get("percent").decimalValue()).isEqualByComparingTo("50");
        assertThat(st.get(c.c).get("percent").decimalValue()).isEqualByComparingTo("66.7");
        assertThat(st.get(c.c).get("excused").asInt()).isEqualTo(1);
        assertThat(missing(st.get(c.b), "alertedAt")).isFalse();
        assertThat(missing(st.get(c.a), "alertedAt")).isTrue();
        assertThat(v.get("sessionList").get(0).get("absent").asInt()).isEqualTo(1);
        JsonNode sms = s.body(s.call(get("/api/messaging/messages?size=100"), viewer, null)).get("content");
        assertThat(sms.findValuesAsText("templateCode").stream().filter("ATTENDANCE_LOW"::equals).count()).isEqualTo(2);

        // a correction: b was there at the first session after all (3 of 4 = 75%, no longer below); warnings are not repeated
        JsonNode one = s.body(s.call(get("/api/teaching/sessions/" + s1), lecturer, null));
        assertThat(one.get("register").findValuesAsText("status")).containsExactlyInAnyOrder("PRESENT", "ABSENT", "LATE");
        s.call(put("/api/teaching/sessions/" + s1), lecturer, session(first, TODAY.minusDays(10), "08:00", reg, c, new String[]{"PRESENT", "PRESENT", "LATE"}))
                .andExpect(status().isOk());
        st = byRegNo(s.body(s.call(get("/api/teaching/offerings/" + m1 + "/attendance"), hod, null)).get("students"));
        assertThat(st.get(c.b).get("percent").decimalValue()).isEqualByComparingTo("75");
        assertThat(st.get(c.b).get("below").asBoolean()).isFalse();
        s.call(get("/api/teaching/offerings/" + m1 + "/attendance"), lecturer2, null).andExpect(status().isForbidden());
        s.call(get("/api/teaching/offerings/" + m1 + "/attendance"), otherHod, null).andExpect(status().isForbidden());

        // summaries: the lecturer's own modules, every module for the Head of Department, none for another department's head
        JsonNode mine = s.body(s.call(get("/api/teaching/attendance?semesterId=" + c.semesterId + "&mine=true"), lecturer, null));
        assertThat(mine).hasSize(3);
        JsonNode m1Summary = mine.findParents("code").stream().filter(x -> x.get("code").asText().equals("ITU 04101")).findFirst().orElseThrow();
        assertThat(m1Summary.get("sessions").asInt()).isEqualTo(4);
        assertThat(m1Summary.get("below").asInt()).isEqualTo(1);
        assertThat(s.body(s.call(get("/api/teaching/attendance?semesterId=" + c.semesterId), hod, null))).hasSize(3);
        assertThat(s.body(s.call(get("/api/teaching/attendance?semesterId=" + c.semesterId), otherHod, null))).isEmpty();
        assertThat(s.body(s.call(get("/api/teaching/attendance?semesterId=" + c.semesterId), lecturer2, null))).isEmpty();

        // workload
        s.call(get("/api/teaching/workload?semesterId=" + c.semesterId), lecturer, null).andExpect(status().isForbidden());
        JsonNode w = s.body(s.call(get("/api/teaching/workload?semesterId=" + c.semesterId), hod, null));
        assertThat(w.get("staff")).hasSize(1);
        assertThat(w.at("/staff/0/fullName").asText()).isEqualTo("Staff lecturer");
        assertThat(w.at("/staff/0/modules").asInt()).isEqualTo(3);
        assertThat(w.at("/staff/0/credits").decimalValue()).isEqualByComparingTo("30");
        assertThat(w.at("/staff/0/hoursTaught").decimalValue()).isEqualByComparingTo("8");
        assertThat(w.get("unallocated")).isEmpty();

        // the student sees their own attendance
        JsonNode my = s.body(s.call(get("/api/student/attendance"), c.cToken, null));
        assertThat(my.get("threshold").decimalValue()).isEqualByComparingTo("75");
        JsonNode mod = my.get("modules").findParents("code").stream().filter(x -> x.get("code").asText().equals("ITU 04101")).findFirst().orElseThrow();
        assertThat(mod.get("percent").decimalValue()).isEqualByComparingTo("66.7");
        assertThat(mod.get("below").asBoolean()).isTrue();
        assertThat(missing(mod, "eligible")).isTrue();

        // a session can be removed while results are open; once submitted, attendance is frozen
        long extra = s.body(s.call(post("/api/teaching/offerings/" + m1 + "/sessions"), lecturer,
                session(UUID.randomUUID(), TODAY.minusDays(2), "14:00", reg, c, plan[2]))).get("id").asLong();
        s.call(delete("/api/teaching/sessions/" + extra), lecturer2, null).andExpect(status().isForbidden());
        s.call(delete("/api/teaching/sessions/" + extra), lecturer, null).andExpect(status().isNoContent());
        assertThat(s.body(s.call(get("/api/teaching/offerings/" + m1 + "/attendance"), lecturer, null)).get("sessions").asInt()).isEqualTo(4);
    }

    @Test
    void eligibilityCombinesAttendanceCaAndClearanceWithExemptionsDecidedBySomeoneElse() throws Exception {
        Setup c = classWithOfferings();
        long m1 = c.offerings.get("ITU 04101");
        // examination clearance: all annual fees; only a pays
        long item = s.body(s.call(post("/api/finance/fee-items"), accountant,
                "{\"code\":\"TUI\",\"name\":\"Tuition fee\",\"category\":\"TUITION\",\"allocationPriority\":10,\"active\":true}")).get("id").asLong();
        long fs = s.body(s.call(post("/api/finance/fee-structures"), accountant, "{\"academicYearId\":" + yearId + ",\"programmeId\":" + inst.programmeId()
                + ",\"yearOfStudy\":1,\"lines\":[{\"feeItemId\":" + item + ",\"amount\":100000,\"instalment\":1}]}")).get("id").asLong();
        s.call(post("/api/finance/fee-structures/" + fs + "/approve"), bursar, null).andExpect(status().isOk());
        s.call(post("/api/finance/billing-runs"), accountant, "{\"academicYearId\":" + yearId + "}").andExpect(status().isOk());
        s.call(put("/api/finance/clearance-gates/EXAMINATION"), bursar, "{\"enabled\":true,\"minPercentPaid\":100,\"firstInstalmentRequired\":false}")
                .andExpect(status().isOk());
        payAnnualFees(c.aApplicant);

        // CA marks: c has none; attendance: b attends one session of two
        JsonNode v = s.body(s.call(get("/api/exams/offerings/" + m1), lecturer, null));
        long ca = v.get("components").get(0).get("id").asLong(), se = v.get("components").get(1).get("id").asLong();
        Map<String, Long> reg = new HashMap<>();
        v.get("rows").forEach(x -> reg.put(x.get("registrationNo").asText(), x.get("moduleRegistrationId").asLong()));
        s.call(put("/api/exams/offerings/" + m1 + "/scores"), lecturer, "{\"entries\":[" + entry(reg.get(c.a), ca, "80") + "," + entry(reg.get(c.b), ca, "60") + "]}")
                .andExpect(status().isOk());
        s.call(post("/api/teaching/offerings/" + m1 + "/sessions"), lecturer, session(UUID.randomUUID(), TODAY.minusDays(3), "10:00", reg, c,
                new String[]{"PRESENT", "PRESENT", "PRESENT"})).andExpect(status().isOk());
        s.call(post("/api/teaching/offerings/" + m1 + "/sessions"), lecturer, session(UUID.randomUUID(), TODAY.minusDays(2), "10:00", reg, c,
                new String[]{"PRESENT", "ABSENT", "PRESENT"})).andExpect(status().isOk());

        s.call(post("/api/exams/semesters/" + c.semesterId + "/eligibility"), lecturer, null).andExpect(status().isForbidden());
        s.call(post("/api/exams/semesters/" + c.semesterId + "/eligibility"), hod, null).andExpect(status().isForbidden());
        JsonNode gen = s.body(s.call(post("/api/exams/semesters/" + c.semesterId + "/eligibility"), exams, null).andExpect(status().isOk()));
        assertThat(gen.get("offerings").asInt()).isEqualTo(3);
        assertThat(gen.get("students").asInt()).isEqualTo(9);

        JsonNode list = s.body(s.call(get("/api/exams/offerings/" + m1 + "/eligibility"), exams, null));
        Map<String, JsonNode> e = byRegNo(list.get("students"));
        assertThat(e.get(c.a).get("eligible").asBoolean()).isTrue();
        assertThat(e.get(c.b).get("eligible").asBoolean()).isFalse();
        assertThat(e.get(c.b).get("attendanceMet").asBoolean()).isFalse();
        assertThat(e.get(c.b).get("caMet").asBoolean()).isTrue();
        assertThat(texts(e.get(c.b).get("reasons"))).anyMatch(r -> r.startsWith("Attendance 50%")).anyMatch(r -> r.contains("still to pay"));
        assertThat(e.get(c.c).get("caMet").asBoolean()).isFalse();
        assertThat(texts(e.get(c.c).get("reasons"))).anyMatch(r -> r.equals("Continuous assessment incomplete: Coursework"));
        JsonNode overview = s.body(s.call(get("/api/exams/eligibility?semesterId=" + c.semesterId), hod, null));
        assertThat(overview.get("canGenerate").asBoolean()).isFalse();
        JsonNode o1 = overview.get("offerings").findParents("code").stream().filter(x -> x.get("code").asText().equals("ITU 04101")).findFirst().orElseThrow();
        assertThat(o1.get("eligible").asInt()).isEqualTo(1);
        assertThat(o1.get("notEligible").asInt()).isEqualTo(2);
        JsonNode sms = s.body(s.call(get("/api/messaging/messages?size=100"), viewer, null)).get("content");
        assertThat(sms.findValuesAsText("templateCode").stream().filter("EXAM_NOT_ELIGIBLE"::equals).count()).isEqualTo(8);   // a (no CA yet in two modules), b and c in all three

        // marks: an ineligible student gets no examination mark (absent is fine)
        s.call(put("/api/exams/offerings/" + m1 + "/scores"), lecturer, scores(reg.get(c.b), se, "70")).andExpect(jsonPath("$.code").value("NOT_ELIGIBLE_FOR_EXAM"));
        JsonNode rows = s.body(s.call(put("/api/exams/offerings/" + m1 + "/scores"), lecturer, scores(reg.get(c.a), se, "70")).andExpect(status().isOk()));
        assertThat(byRegNo(rows.get("rows")).get(c.b).get("examEligible").asBoolean()).isFalse();
        assertThat(byRegNo(rows.get("rows")).get(c.b).get("failReason").asText()).isEqualTo("Not eligible to sit the examination");

        // exemptions: asked for with a reason by the Head of Department; decided by someone else
        s.call(post("/api/exams/offerings/" + m1 + "/exemptions"), lecturer, exemption(reg.get(c.b), "Hospitalised for two weeks")).andExpect(status().isForbidden());
        s.call(post("/api/exams/offerings/" + m1 + "/exemptions"), hod, exemption(reg.get(c.a), "Already fine but asking anyway"))
                .andExpect(jsonPath("$.code").value("ALREADY_ELIGIBLE"));
        long xb = s.body(s.call(post("/api/exams/offerings/" + m1 + "/exemptions"), hod, exemption(reg.get(c.b), "Hospitalised for two weeks (letter on file)"))
                .andExpect(jsonPath("$.status").value("PENDING"))).get("id").asLong();
        s.call(post("/api/exams/offerings/" + m1 + "/exemptions"), exams, exemption(reg.get(c.b), "Another request for the same"))
                .andExpect(jsonPath("$.code").value("EXEMPTION_EXISTS"));
        long xc = s.body(s.call(post("/api/exams/offerings/" + m1 + "/exemptions"), dpa, exemption(reg.get(c.c), "Coursework lost by the department"))).get("id").asLong();
        s.call(post("/api/exams/exemptions/" + xc + "/approve"), dpa, "{}").andExpect(jsonPath("$.code").value("SEGREGATION_OF_DUTIES"));
        s.call(post("/api/exams/exemptions/" + xc + "/approve"), exams, "{}").andExpect(status().isForbidden());
        JsonNode pending = s.body(s.call(get("/api/exams/exemptions"), dpa, null));
        assertThat(pending).hasSize(2);
        assertThat(pending.findParents("id").stream().filter(x -> x.get("id").asLong() == xb).findFirst().orElseThrow().get("canDecide").asBoolean()).isTrue();
        assertThat(pending.findParents("id").stream().filter(x -> x.get("id").asLong() == xc).findFirst().orElseThrow().get("canDecide").asBoolean()).isFalse();
        s.call(post("/api/exams/exemptions/" + xc + "/reject"), admin, "{}").andExpect(jsonPath("$.code").value("NOTE_REQUIRED"));
        s.call(post("/api/exams/exemptions/" + xc + "/reject"), admin, "{\"note\":\"The lecturer has the coursework\"}").andExpect(jsonPath("$.status").value("REJECTED"));
        s.call(post("/api/exams/exemptions/" + xb + "/approve"), dpa, "{\"note\":\"Medical letter seen\"}").andExpect(jsonPath("$.status").value("APPROVED"));
        s.call(post("/api/exams/exemptions/" + xb + "/approve"), admin, "{}").andExpect(jsonPath("$.code").value("ALREADY_DECIDED"));

        s.call(put("/api/exams/offerings/" + m1 + "/scores"), lecturer, scores(reg.get(c.b), se, "70")).andExpect(status().isOk());
        // regenerating keeps the exemption; c (rejected) is still not eligible
        s.call(post("/api/exams/semesters/" + c.semesterId + "/eligibility"), exams, null).andExpect(status().isOk());
        e = byRegNo(s.body(s.call(get("/api/exams/offerings/" + m1 + "/eligibility"), lecturer, null)).get("students"));
        assertThat(e.get(c.b).get("eligible").asBoolean()).isTrue();
        assertThat(e.get(c.b).get("exempted").asBoolean()).isTrue();
        assertThat(e.get(c.b).at("/exemption/decidedByName").asText()).isEqualTo("Staff dpa");
        assertThat(e.get(c.c).get("eligible").asBoolean()).isFalse();
        assertThat(e.get(c.c).at("/exemption/status").asText()).isEqualTo("REJECTED");

        // reports
        JsonNode rep = s.body(s.call(get("/api/reports/EXM-04?semesterId=" + c.semesterId + "&moduleId=" + c.moduleIds.get("ITU 04101")), exams, null)
                .andExpect(status().isOk()));
        assertThat(rep.get("rowCount").asInt()).isEqualTo(3);
        JsonNode att = s.body(s.call(get("/api/reports/TCH-01?semesterId=" + c.semesterId), viewer, null).andExpect(status().isOk()));
        assertThat(att.get("rowCount").asInt()).isEqualTo(3);

        // the students see it
        JsonNode myB = s.body(s.call(get("/api/student/attendance"), c.bToken, null));
        assertThat(myB.get("listPublished").asBoolean()).isTrue();
        JsonNode b1 = myB.get("modules").findParents("code").stream().filter(x -> x.get("code").asText().equals("ITU 04101")).findFirst().orElseThrow();
        assertThat(b1.get("eligible").asBoolean()).isTrue();
        assertThat(b1.get("exempted").asBoolean()).isTrue();
        JsonNode cc = s.body(s.call(get("/api/student/attendance"), c.cToken, null)).get("modules").findParents("code").stream()
                .filter(x -> x.get("code").asText().equals("ITU 04101")).findFirst().orElseThrow();
        assertThat(cc.get("eligible").asBoolean()).isFalse();
        assertThat(texts(cc.get("reasons"))).isNotEmpty();
    }

    // ------------------------------------------------------------------ scenario

    record Setup(long semesterId, String a, String b, String c, String aToken, String bToken, String cToken, Applicant aApplicant,
                 Map<String, Long> offerings, Map<String, Long> moduleIds) {
    }

    @Test
    void studentsCheckThemselvesInWithTheRotatingCodeAndAbsenteesAreRecordedOnClose() throws Exception {
        Setup c = classWithOfferings();
        long m1 = c.offerings.get("ITU 04101");
        String open = "{\"kind\":\"LECTURE\",\"topic\":\"Networks\",\"durationMinutes\":60,\"openMinutes\":10,\"lateAfterMinutes\":10}";
        s.call(post("/api/teaching/offerings/" + m1 + "/check-in"), lecturer2, open).andExpect(status().isForbidden());
        JsonNode cs = s.body(s.call(post("/api/teaching/offerings/" + m1 + "/check-in"), lecturer, open).andExpect(status().isOk()));
        long sid = cs.get("id").asLong();
        assertThat(cs.at("/checkIn/open").asBoolean()).isTrue();
        assertThat(cs.at("/checkIn/classSize").asInt()).isEqualTo(3);
        assertThat(cs.get("editable").asBoolean()).isFalse();
        assertThat(cs.get("register").findValuesAsText("registrationNo")).hasSize(3);
        assertThat(cs.get("register").findValues("status")).isEmpty();
        s.call(post("/api/teaching/offerings/" + m1 + "/check-in"), lecturer, open).andExpect(jsonPath("$.code").value("CHECKIN_ALREADY_OPEN"));

        // the lecturer's screen: the code and a QR code of the check-in link on the institution's address
        s.call(get("/api/teaching/sessions/" + sid + "/check-in"), lecturer2, null).andExpect(status().isForbidden());
        s.call(get("/api/teaching/sessions/" + sid + "/check-in"), hod, null).andExpect(status().isForbidden());
        JsonNode screen = s.body(s.call(get("/api/teaching/sessions/" + sid + "/check-in?base=http://college.localhost:5173"), lecturer, null));
        String code = screen.get("code").asText();
        assertThat(code).matches("\\d{6}");
        assertThat(screen.get("checkInUrl").asText()).isEqualTo("http://college.localhost:5173/student/check-in?code=" + code);
        assertThat(screen.get("qrPng").asText()).startsWith("data:image/png;base64,");
        assertThat(s.body(s.call(get("/api/teaching/sessions/" + sid + "/check-in?base=javascript:alert(1)"), lecturer, null)).has("qrPng")).isFalse();

        // a and b check in from the same phone; a twice
        JsonNode done = s.body(s.call(post("/api/student/check-in"), c.aToken, "{\"code\":\"" + code + "\",\"deviceId\":\"phone-1\"}").andExpect(status().isOk()));
        assertThat(done.get("moduleCode").asText()).isEqualTo("ITU 04101");
        assertThat(done.get("status").asText()).isEqualTo("PRESENT");
        assertThat(done.get("already").asBoolean()).isFalse();
        assertThat(s.body(s.call(post("/api/student/check-in"), c.aToken, "{\"code\":\"" + code + "\",\"deviceId\":\"phone-1\"}")).get("already").asBoolean()).isTrue();
        s.call(post("/api/student/check-in"), c.bToken, "{\"code\":\"" + code + "\",\"deviceId\":\"phone-1\"}").andExpect(jsonPath("$.status").value("PRESENT"));
        s.call(post("/api/student/check-in"), lecturer, "{\"code\":\"" + code + "\"}").andExpect(status().isForbidden());

        // c types wrong codes: refused, then limited even with the right code
        String wrong = String.format("%06d", (Integer.parseInt(code) + 500_000) % 1_000_000);
        for (int i = 0; i < CheckInService.MAX_FAILED; i++) {
            s.call(post("/api/student/check-in"), c.cToken, "{\"code\":\"" + wrong + "\"}").andExpect(jsonPath("$.code").value("CHECKIN_CODE_INVALID"));
        }
        s.call(post("/api/student/check-in"), c.cToken, "{\"code\":\"" + code + "\"}").andExpect(status().isTooManyRequests());

        screen = s.body(s.call(get("/api/teaching/sessions/" + sid + "/check-in"), lecturer, null));
        assertThat(screen.at("/state/checkedIn").asInt()).isEqualTo(2);
        assertThat(screen.at("/state/sharedDevices").asInt()).isEqualTo(2);
        assertThat(screen.get("recent")).hasSize(2);

        // the register cannot be edited while check-in is open; closing records c absent
        Map<String, Long> reg = new HashMap<>();
        cs.get("register").forEach(x -> reg.put(x.get("registrationNo").asText(), x.get("moduleRegistrationId").asLong()));
        String time = cs.get("startTime").asText().substring(0, 5);
        s.call(put("/api/teaching/sessions/" + sid), lecturer, session(UUID.randomUUID(), TODAY, time, reg, c, new String[]{"PRESENT", "ABSENT", "ABSENT"}))
                .andExpect(jsonPath("$.code").value("CHECKIN_OPEN"));
        s.call(post("/api/teaching/sessions/" + sid + "/check-in/close"), lecturer2, null).andExpect(status().isForbidden());
        JsonNode closed = s.body(s.call(post("/api/teaching/sessions/" + sid + "/check-in/close"), lecturer, null).andExpect(status().isOk()));
        assertThat(closed.at("/checkIn/open").asBoolean()).isFalse();
        assertThat(closed.get("editable").asBoolean()).isTrue();
        Map<String, JsonNode> lines = byRegNo(closed.get("register"));
        assertThat(lines.get(c.a).get("source").asText()).isEqualTo("SELF");
        assertThat(lines.get(c.a).get("sharedDevice").asBoolean()).isTrue();
        assertThat(lines.get(c.c).get("status").asText()).isEqualTo("ABSENT");
        assertThat(lines.get(c.c).get("source").asText()).isEqualTo("REGISTER");
        s.call(post("/api/student/check-in"), c.aToken, "{\"code\":\"" + code + "\"}").andExpect(jsonPath("$.code").value("CHECKIN_CODE_INVALID"));

        // the lecturer corrects b (the shared phone): b becomes absent and the line no longer counts as a self check-in
        JsonNode fixed = s.body(s.call(put("/api/teaching/sessions/" + sid), lecturer,
                session(UUID.randomUUID(), TODAY, time, reg, c, new String[]{"PRESENT", "ABSENT", "ABSENT"})).andExpect(status().isOk()));
        assertThat(byRegNo(fixed.get("register")).get(c.b).get("source").asText()).isEqualTo("REGISTER");
        JsonNode view = s.body(s.call(get("/api/teaching/offerings/" + m1 + "/attendance"), lecturer, null));
        assertThat(view.at("/sessionList/0/selfCheckIns").asInt()).isEqualTo(2);
        assertThat(view.at("/sessionList/0/present").asInt()).isEqualTo(1);

        // check-in left open closes on time, with absentees
        long m2 = c.offerings.get("ITU 04102");
        long sid2 = s.body(s.call(post("/api/teaching/offerings/" + m2 + "/check-in"), lecturer,
                "{\"kind\":\"TUTORIAL\",\"durationMinutes\":60,\"openMinutes\":5,\"lateAfterMinutes\":0}")).get("id").asLong();
        tenantWork.run(inst.tenant(), () -> jdbc.update("update class_sessions set checkin_closes_at = now() - interval '1 minute' where id = ?", sid2));
        assertThat(tenantWork.call(inst.tenant(), () -> checkIns.closeExpired())).isEqualTo(1);
        JsonNode s2 = s.body(s.call(get("/api/teaching/sessions/" + sid2), lecturer, null));
        assertThat(s2.at("/checkIn/open").asBoolean()).isFalse();
        assertThat(s2.get("register").findValuesAsText("status")).containsOnly("ABSENT");
    }

    @Test
    void theLecturerSeesTheirModulesAndPrintsClassLists() throws Exception {
        Setup c = classWithOfferings();
        long m1 = c.offerings.get("ITU 04101");
        JsonNode none = s.body(s.call(get("/api/teaching/mine"), lecturer2, null));
        assertThat(none.get("modules")).isEmpty();
        assertThat(none.at("/next/0/text").asText()).contains("No modules are allocated to you");

        JsonNode b = s.body(s.call(get("/api/teaching/mine"), lecturer, null).andExpect(status().isOk()));
        assertThat(b.at("/semester/id").asLong()).isEqualTo(c.semesterId);
        assertThat(b.get("marksWindow").asText()).isEqualTo("OPEN");
        assertThat(b.get("daysToClose").asInt()).isEqualTo(30);
        assertThat(b.get("modules")).hasSize(3);
        assertThat(b.get("students").asInt()).isEqualTo(9);
        JsonNode mod = b.get("modules").findParents("code").stream().filter(x -> x.get("code").asText().equals("ITU 04101")).findFirst().orElseThrow();
        assertThat(mod.get("students").asInt()).isEqualTo(3);
        assertThat(mod.get("planSet").asBoolean()).isTrue();
        assertThat(mod.get("caPercent").decimalValue()).isEqualByComparingTo("0");
        assertThat(mod.get("status").asText()).isEqualTo("DRAFT");
        assertThat(mod.get("canRecord").asBoolean()).isTrue();

        // a session with b absent, a CA mark for a, then the board shows them
        Map<String, Long> reg = new HashMap<>();
        s.body(s.call(get("/api/teaching/offerings/" + m1 + "/register"), lecturer, null)).forEach(x -> reg.put(x.get("registrationNo").asText(),
                x.get("moduleRegistrationId").asLong()));
        s.call(post("/api/teaching/offerings/" + m1 + "/sessions"), lecturer, session(UUID.randomUUID(), TODAY.minusDays(1), "08:00", reg, c,
                new String[]{"PRESENT", "ABSENT", "PRESENT"})).andExpect(status().isOk());
        JsonNode o = s.body(s.call(get("/api/exams/offerings/" + m1), lecturer, null));
        long ca = o.get("components").findParents("category").stream().filter(x -> x.get("category").asText().equals("CA")).findFirst().orElseThrow()
                .get("id").asLong();
        s.call(put("/api/exams/offerings/" + m1 + "/scores"), lecturer, scores(reg.get(c.a), ca, "12")).andExpect(status().isOk());
        b = s.body(s.call(get("/api/teaching/mine?semesterId=" + c.semesterId), lecturer, null));
        mod = b.get("modules").findParents("code").stream().filter(x -> x.get("code").asText().equals("ITU 04101")).findFirst().orElseThrow();
        assertThat(mod.get("sessions").asInt()).isEqualTo(1);
        assertThat(mod.get("below").asInt()).isEqualTo(1);
        assertThat(mod.get("caPercent").decimalValue()).isPositive();
        assertThat(b.get("atRisk")).hasSize(1);
        assertThat(b.at("/atRisk/0/registrationNo").asText()).isEqualTo(c.b);
        assertThat(b.get("next").findValuesAsText("text")).anyMatch(t -> t.contains("below the attendance threshold in ITU 04101"));

        // the class list: PDF register and Excel, for the module's lecturers and those who oversee it
        var pdf = s.call(get("/api/teaching/offerings/" + m1 + "/class-list"), lecturer, null).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(pdf.getContentType()).isEqualTo("application/pdf");
        assertThat(new String(pdf.getContentAsByteArray(), 0, 4)).isEqualTo("%PDF");
        var xlsx = s.call(get("/api/teaching/offerings/" + m1 + "/class-list?format=xlsx"), hod, null).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(new String(xlsx.getContentAsByteArray(), 0, 2)).isEqualTo("PK");
        assertThat(xlsx.getHeader("Content-Disposition")).contains("class-list-ITU-04101");
        s.call(get("/api/teaching/offerings/" + m1 + "/class-list"), lecturer2, null).andExpect(status().isForbidden());
        s.call(get("/api/teaching/offerings/" + m1 + "/class-list"), otherHod, null).andExpect(status().isForbidden());
    }

    /** Three registered students in three first-semester modules, offerings set up and allocated to one lecturer. */
    private Setup classWithOfferings() throws Exception {
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
        s.call(post("/api/exams/offerings/setup"), exams, "{\"semesterId\":" + semId + "}").andExpect(status().isOk());
        Map<String, Long> off = new HashMap<>();
        s.body(s.call(get("/api/exams/offerings?semesterId=" + semId), exams, null)).forEach(o -> off.put(o.get("code").asText(), o.get("id").asLong()));
        long lecturerId = s.body(s.call(get("/api/exams/staff"), exams, null)).findParents("fullName").stream()
                .filter(x -> x.get("fullName").asText().equals("Staff lecturer")).findFirst().orElseThrow().get("id").asLong();
        for (Long id : off.values()) s.call(put("/api/exams/offerings/" + id + "/staff"), hod, "{\"userIds\":[" + lecturerId + "]}").andExpect(status().isOk());
        return new Setup(semId, regNos[0], regNos[1], regNos[2], tokens[0], tokens[1], tokens[2], a, off, Map.of("ITU 04101", m1, "ITU 04102", m2, "ITU 04103", m3));
    }

    private long module(String code) throws Exception {
        return s.body(s.call(post("/api/academic/modules"), hod, "{\"code\":\"" + code + "\",\"title\":\"Module " + code
                + "\",\"credits\":10,\"ntaLevel\":4,\"assessmentType\":\"GRADED\",\"active\":true}").andExpect(status().isCreated())).get("id").asLong();
    }

    private static String slot(long moduleId, int sem) {
        return "{\"moduleId\":" + moduleId + ",\"yearOfStudy\":1,\"semesterNo\":" + sem + ",\"category\":\"CORE\"}";
    }

    /** A two-hour lecture; statuses for a, b and c in that order. */
    private static String session(UUID ref, LocalDate date, String time, Map<String, Long> reg, Setup c, String[] statuses) {
        String[] who = {c.a, c.b, c.c};
        List<String> marks = new ArrayList<>();
        for (int i = 0; i < 3; i++) marks.add("{\"moduleRegistrationId\":" + reg.get(who[i]) + ",\"status\":\"" + statuses[i] + "\"}");
        return "{\"clientRef\":\"" + ref + "\",\"sessionDate\":\"" + date + "\",\"startTime\":\"" + time + "\",\"durationMinutes\":120,\"kind\":\"LECTURE\","
                + "\"topic\":\"Week topic\",\"marks\":[" + String.join(",", marks) + "]}";
    }

    private static String entry(long reg, long comp, String score) {
        return "{\"moduleRegistrationId\":" + reg + ",\"componentId\":" + comp + ",\"score\":" + score + "}";
    }

    private static String scores(long reg, long comp, String score) {
        return "{\"entries\":[" + entry(reg, comp, score) + "]}";
    }

    private static String exemption(long reg, String reason) {
        return "{\"moduleRegistrationId\":" + reg + ",\"reason\":\"" + reason + "\"}";
    }

    private static Map<String, JsonNode> byRegNo(JsonNode arr) {
        Map<String, JsonNode> m = new HashMap<>();
        arr.forEach(x -> m.put(x.get("registrationNo").asText(), x));
        return m;
    }

    private static boolean missing(JsonNode n, String field) {
        return n.path(field).isMissingNode() || n.path(field).isNull();
    }

    private static List<String> texts(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(x -> out.add(x.asText()));
        return out;
    }

    private void payAnnualFees(Applicant a) throws Exception {
        for (JsonNode inv : s.body(s.call(get("/api/applicant/account"), a.token(), null)).get("invoices")) {
            if (inv.get("purpose").asText().equals("ANNUAL_FEES") && inv.get("balance").decimalValue().signum() > 0) {
                s.call(post("/api/applicant/invoices/" + inv.get("id").asLong() + "/simulate-payment"), a.token(), "{}").andExpect(status().isOk());
            }
        }
    }
}
