package tz.co.msingi.cims.timetable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FR-TCH-05 the class timetable and FR-EXM-15 the examination timetable: venues, clash rules, publishing and personal timetables. */
@IntegrationTest
class TimetableIT {

    static final String INSTRUCTIONS = """
            {"reportingDate":"2027-09-27","reportingVenue":"Main Hall","documentsToBring":["Original CSEE certificate"],
             "signatoryName":"Dr. Amina Salum","signatoryTitle":"Principal"}""";
    static final LocalDate TODAY = LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam"));

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;
    @Autowired JdbcTemplate jdbc;
    @Autowired tz.co.msingi.cims.platform.tenancy.TenantWork tenantWork;

    AdmissionScenario s;
    TestData.Institution inst;
    String admin, dpa, hod, otherHod, officer, registrar, viewer, lecturer, lecturer2, exams;
    long yearId, semId, sem2Id, lecturerId, lecturer2Id, lh1, cr1;
    Map<String, Long> off = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        String tenant = "W" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
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
        s.call(put("/api/admission/windows/" + inst.windowId() + "/joining-instructions"), officer, INSTRUCTIONS).andExpect(status().isOk());
        yearId = s.body(s.call(get("/api/admission/windows/" + inst.windowId()), officer, null)).get("academicYearId").asLong();
        s.call(post("/api/exams/regulations"), exams, json.writeValueAsString(RegulationDefaults.ntaCertificate(yearId))).andExpect(status().isCreated());

        long m1 = module("ITU 04101"), m2 = module("ITU 04102"), m3 = module("ITU 04103"), m4 = module("ITU 04201");
        long cid = s.body(s.call(post("/api/academic/curricula"), hod, "{\"programmeId\":" + inst.programmeId() + ",\"effectiveYearId\":" + yearId
                + ",\"modules\":[" + slot(m1, 1) + "," + slot(m2, 1) + "," + slot(m3, 1) + "," + slot(m4, 2) + "],\"electiveRules\":[]}")).get("id").asLong();
        s.call(post("/api/academic/curricula/" + cid + "/approve"), dpa, null).andExpect(status().isOk());
        semId = semester(1, "2027-09-27", "2028-02-10", TODAY.minusDays(1), TODAY.plusDays(10));
        sem2Id = semester(2, "2028-03-06", "2028-07-28", TODAY.plusDays(100), TODAY.plusDays(110));

        // the timetable is planned before students register: offerings come from the approved curricula
        s.call(post("/api/exams/offerings/from-curricula"), lecturer, "{\"semesterId\":" + semId + "}").andExpect(status().isForbidden());
        s.call(post("/api/exams/offerings/from-curricula"), exams, "{\"semesterId\":" + semId + "}").andExpect(jsonPath("$.created").value(3));
        s.call(post("/api/exams/offerings/from-curricula"), exams, "{\"semesterId\":" + semId + "}").andExpect(jsonPath("$.created").value(0));
        s.call(post("/api/exams/offerings/from-curricula"), exams, "{\"semesterId\":" + sem2Id + "}").andExpect(jsonPath("$.created").value(1));
        s.body(s.call(get("/api/exams/offerings?semesterId=" + semId), exams, null)).forEach(o -> off.put(o.get("code").asText(), o.get("id").asLong()));
        JsonNode staff = s.body(s.call(get("/api/exams/staff"), exams, null));
        lecturerId = staffId(staff, "Staff lecturer");
        lecturer2Id = staffId(staff, "Staff lecturer2");
        s.call(put("/api/exams/offerings/" + off.get("ITU 04101") + "/staff"), hod, "{\"userIds\":[" + lecturerId + "," + lecturer2Id + "]}").andExpect(status().isOk());
        s.call(put("/api/exams/offerings/" + off.get("ITU 04102") + "/staff"), hod, "{\"userIds\":[" + lecturerId + "]}").andExpect(status().isOk());
        s.call(put("/api/exams/offerings/" + off.get("ITU 04103") + "/staff"), hod, "{\"userIds\":[" + lecturer2Id + "]}").andExpect(status().isOk());

        // venues: kept by academic management and the Examinations Office
        s.call(post("/api/timetable/venues"), lecturer, venue("LH1", 2, 2)).andExpect(status().isForbidden());
        lh1 = s.body(s.call(post("/api/timetable/venues"), dpa, venue("lh1", 2, 2)).andExpect(status().isCreated())).get("id").asLong();
        cr1 = s.body(s.call(post("/api/timetable/venues"), exams, venue("CR1", 50, null)).andExpect(status().isCreated())).get("id").asLong();
        s.call(post("/api/timetable/venues"), dpa, venue("LH1", 10, null)).andExpect(jsonPath("$.code").value("VENUE_CODE_TAKEN"));
        JsonNode vs = s.body(s.call(get("/api/timetable/venues"), lecturer, null));
        assertThat(vs.findValuesAsText("code")).containsExactly("CR1", "LH1");
        assertThat(vs.get(0).get("examSeats").asInt()).isEqualTo(50);
    }

    @Test
    void classesAreCheckedForClashesThenPublishedToStudents() throws Exception {
        String url = "/api/timetable/semesters/" + semId + "/slots";
        long a = id(s.call(post(url), hod, slotReq("ITU 04101", "LECTURE", 1, "08:00", "10:00", lh1, lecturerId, null)).andExpect(status().isCreated()));
        // refused: the room, the lecturer, or the module twice at once
        s.call(post(url), dpa, slotReq("ITU 04103", "LECTURE", 1, "09:00", "11:00", lh1, null, null)).andExpect(jsonPath("$.code").value("VENUE_CLASH"));
        s.call(post(url), dpa, slotReq("ITU 04102", "LECTURE", 1, "09:00", "11:00", cr1, null, null)).andExpect(jsonPath("$.code").value("LECTURER_CLASH"));
        s.call(post(url), dpa, slotReq("ITU 04102", "LECTURE", 1, "10:00", "12:00", lh1, null, null)).andExpect(status().isCreated());   // back to back is fine
        long c = id(s.call(post(url), dpa, slotReq("ITU 04103", "TUTORIAL", 1, "09:00", "11:00", cr1, null, null)).andExpect(status().isCreated()));
        s.call(post(url), dpa, slotReq("ITU 04102", "LECTURE", 3, "08:00", "10:00", cr1, lecturer2Id, null)).andExpect(jsonPath("$.code").value("LECTURER_NOT_ALLOCATED"));
        s.call(post(url), dpa, slotReq("ITU 04102", "LECTURE", 3, "18:00", "17:00", cr1, null, null)).andExpect(jsonPath("$.code").value("INVALID_TIMES"));
        // two practical groups of one module at once, each with its own lecturer and room; not without groups
        s.call(post(url), dpa, slotReq("ITU 04101", "PRACTICAL", 2, "14:00", "16:00", lh1, lecturerId, "Group A")).andExpect(status().isCreated());
        s.call(post(url), dpa, slotReq("ITU 04101", "PRACTICAL", 2, "14:00", "16:00", cr1, lecturer2Id, "Group B")).andExpect(status().isCreated());
        s.call(post(url), dpa, slotReq("ITU 04101", "PRACTICAL", 2, "15:00", "17:00", cr1, lecturer2Id, null)).andExpect(jsonPath("$.code").value("VENUE_CLASH"));
        // who may change classes
        s.call(post(url), lecturer, slotReq("ITU 04102", "LECTURE", 4, "08:00", "10:00", cr1, null, null)).andExpect(status().isForbidden());
        s.call(post(url), otherHod, slotReq("ITU 04102", "LECTURE", 4, "08:00", "10:00", cr1, null, null)).andExpect(status().isForbidden());
        s.call(post("/api/timetable/semesters/" + sem2Id + "/slots"), dpa, slotReq("ITU 04102", "LECTURE", 4, "08:00", "10:00", cr1, null, null))
                .andExpect(jsonPath("$.code").value("OFFERING_NOT_IN_SEMESTER"));

        // before registration: a planned-class clash (both core for the programme's year 1)
        JsonNode t = s.body(s.call(get("/api/timetable/semesters/" + semId), viewer, null));
        assertThat(t.get("slots")).hasSize(5);
        assertThat(t.get("canEdit").asBoolean()).isFalse();
        assertThat(kinds(t)).contains("COHORT_CLASH").doesNotContain("STUDENT_CLASH", "VENUE_CLASH");
        assertThat(row(t, "ITU 04101").get("minutesPerWeek").asInt()).isEqualTo(360);
        assertThat(row(t, "ITU 04101").at("/cohorts/0/yearOfStudy").asInt()).isEqualTo(1);
        assertThat(s.body(s.call(get("/api/timetable/semesters/" + semId), hod, null)).get("canPublish").asBoolean()).isFalse();

        // students register: the clash is now counted in registered students, and 3 students do not fit in a room for 2
        String[] students = registerThree();
        t = s.body(s.call(get("/api/timetable/semesters/" + semId), dpa, null));
        assertThat(kinds(t)).contains("STUDENT_CLASH", "OVER_CAPACITY").doesNotContain("COHORT_CLASH");
        assertThat(t.get("warnings").findValuesAsText("message")).anyMatch(m -> m.startsWith("3 registered students take ITU 04101 and ITU 04103"));

        // nothing for students until it is published
        JsonNode mine = s.body(s.call(get("/api/student/timetable"), students[0], null));
        assertThat(mine.get("semesterId").asLong()).isEqualTo(semId);
        assertThat(mine.get("classPublished").asBoolean()).isFalse();
        assertThat(mine.get("slots")).isEmpty();
        s.call(post("/api/timetable/semesters/" + semId + "/publish"), hod, "{\"kind\":\"CLASS\",\"sendSms\":true}").andExpect(status().isForbidden());
        s.call(post("/api/timetable/semesters/" + semId + "/publish"), dpa, "{\"kind\":\"CLASS\",\"sendSms\":true}").andExpect(jsonPath("$.notified").value(3));
        JsonNode sms = s.body(s.call(get("/api/messaging/messages?size=100"), viewer, null)).get("content");
        assertThat(sms.findValuesAsText("templateCode").stream().filter("TIMETABLE_PUBLISHED"::equals).count()).isEqualTo(3);
        mine = s.body(s.call(get("/api/student/timetable"), students[0], null));
        assertThat(mine.get("classPublished").asBoolean()).isTrue();
        assertThat(mine.get("slots")).hasSize(5);
        assertThat(mine.get("slots").findValuesAsText("changedSincePublished")).containsOnly("false");

        // a change after publishing is marked for students
        s.call(put("/api/timetable/slots/" + c), dpa, slotReq("ITU 04103", "TUTORIAL", 4, "09:00", "11:00", cr1, null, null)).andExpect(status().isOk());
        mine = s.body(s.call(get("/api/student/timetable"), students[0], null));
        JsonNode moved = find(mine.get("slots"), c);
        assertThat(moved.get("dayOfWeek").asInt()).isEqualTo(4);
        assertThat(moved.get("changedSincePublished").asBoolean()).isTrue();

        // lecturers see their own classes: lecturer2 takes ITU 04103 and the Group B practical, not the classes named for the other lecturer
        JsonNode l2 = s.body(s.call(get("/api/timetable/mine"), lecturer2, null));
        assertThat(l2.get("slots").findValuesAsText("moduleCode")).containsExactlyInAnyOrder("ITU 04101", "ITU 04103");
        assertThat(l2.get("slots").findValuesAsText("groupLabel")).contains("Group B").doesNotContain("Group A");
        assertThat(l2.get("classPublished").asBoolean()).isTrue();

        s.call(get("/api/timetable/semesters/" + semId + "/export?kind=CLASS"), viewer, null).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("class-timetable-semester-1")));
        s.call(delete("/api/timetable/slots/" + a), lecturer, null).andExpect(status().isForbidden());
        s.call(delete("/api/timetable/slots/" + a), dpa, null).andExpect(status().isNoContent());

        // copying: semester 2 does not offer these modules
        s.call(post("/api/timetable/semesters/" + sem2Id + "/copy"), dpa, "{\"fromSemesterId\":" + sem2Id + "}").andExpect(jsonPath("$.code").value("SAME_SEMESTER"));
        JsonNode copy = s.body(s.call(post("/api/timetable/semesters/" + sem2Id + "/copy"), dpa, "{\"fromSemesterId\":" + semId + "}"));
        assertThat(copy.get("copied").asInt()).isZero();
        assertThat(copy.get("skipped").asInt()).isEqualTo(4);

        s.call(post("/api/timetable/semesters/" + semId + "/withdraw"), dpa, "{\"kind\":\"CLASS\"}").andExpect(status().isOk());
        assertThat(s.body(s.call(get("/api/student/timetable"), students[0], null)).get("slots")).isEmpty();
    }

    @Test
    void examinationPapersNeverClashForAStudentAndArePublished() throws Exception {
        String[] students = registerThree();
        String url = "/api/timetable/semesters/" + semId + "/exams";
        s.call(post(url), lecturer, paper("ITU 04101", null, "2028-02-01", "09:00", "12:00", List.of(lh1), List.of())).andExpect(status().isForbidden());
        long p1 = id(s.call(post(url), exams, paper("ITU 04101", null, "2028-02-01", "09:00", "12:00", List.of(lh1), List.of(lecturerId))).andExpect(status().isCreated()));
        s.call(post(url), exams, paper("ITU 04101", "paper 1", "2028-02-03", "09:00", "12:00", List.of(cr1), List.of())).andExpect(jsonPath("$.code").value("PAPER_EXISTS"));
        s.call(post(url), exams, paper("ITU 04102", null, "2028-02-01", "11:00", "14:00", List.of(cr1), List.of())).andExpect(jsonPath("$.code").value("STUDENT_EXAM_CLASH"));
        s.call(post(url), exams, paper("ITU 04102", null, "2028-03-01", "09:00", "12:00", List.of(cr1), List.of())).andExpect(jsonPath("$.code").value("SITTING_OUTSIDE_SEMESTER"));
        long p2 = id(s.call(post(url), exams, paper("ITU 04102", null, "2028-02-01", "14:00", "17:00", List.of(cr1), List.of(lecturerId))).andExpect(status().isCreated()));
        s.call(post(url), exams, paper("ITU 04103", null, "2028-02-02", "08:00", "11:00", List.of(cr1), List.of()));
        // an invigilator cannot be in two rooms; a second paper of the same module is fine on another day
        JsonNode t = s.body(s.call(get(url), viewer, null));
        assertThat(t.get("sittings")).hasSize(3);
        assertThat(t.get("canEdit").asBoolean()).isFalse();
        assertThat(kinds(t)).contains("OVER_CAPACITY", "SAME_DAY").doesNotContain("STUDENT_EXAM_CLASH");
        assertThat(t.get("sittings").get(0).get("candidates").asInt()).isEqualTo(3);
        assertThat(t.get("sittings").get(0).get("seats").asInt()).isEqualTo(2);
        long p3 = t.get("sittings").get(2).get("id").asLong();
        s.call(put("/api/timetable/exams/" + p3), exams, paper("ITU 04103", null, "2028-02-01", "15:00", "17:00", List.of(lh1), List.of()))
                .andExpect(jsonPath("$.code").value("STUDENT_EXAM_CLASH"));
        s.call(put("/api/timetable/exams/" + p3), exams, paper("ITU 04103", null, "2028-02-02", "08:00", "11:00", List.of(lh1, cr1), List.of(lecturer2Id)))
                .andExpect(jsonPath("$.seats").value(52));
        s.call(post(url), exams, paper("ITU 04101", "Practical", "2028-02-02", "09:00", "10:00", List.of(cr1), List.of(lecturer2Id)))
                .andExpect(jsonPath("$.code").value("INVIGILATOR_CLASH"));

        // students see their papers once published, without the invigilators; a lecturer sees what they invigilate
        assertThat(s.body(s.call(get("/api/student/timetable"), students[1], null)).get("exams")).isEmpty();
        s.call(post("/api/timetable/semesters/" + semId + "/publish"), dpa, "{\"kind\":\"EXAM\",\"sendSms\":false}").andExpect(status().isForbidden());
        s.call(post("/api/timetable/semesters/" + semId + "/publish"), exams, "{\"kind\":\"EXAM\",\"sendSms\":false}").andExpect(jsonPath("$.notified").value(0));
        JsonNode mine = s.body(s.call(get("/api/student/timetable"), students[1], null));
        assertThat(mine.get("examPublished").asBoolean()).isTrue();
        assertThat(mine.get("exams").findValuesAsText("moduleCode")).containsExactly("ITU 04101", "ITU 04102", "ITU 04103");
        assertThat(mine.get("exams").get(2).get("venues")).hasSize(2);
        assertThat(mine.get("exams").get(0).get("invigilators")).isEmpty();
        JsonNode lec = s.body(s.call(get("/api/timetable/mine"), lecturer, null));
        assertThat(lec.get("exams")).hasSize(2);   // the papers of their modules (04101, 04102), invigilating both
        assertThat(lec.get("exams").findValuesAsText("invigilating").stream().filter("true"::equals).count()).isEqualTo(2);

        // a clash that crept in (e.g. a late registration) stops the timetable being published again
        tenantWork.run(inst.tenant(), () -> jdbc.update("""
                update exam_sittings x set start_time = p.start_time + interval '1 hour', end_time = p.end_time + interval '1 hour'
                from exam_sittings p where p.id = ? and x.id = ?""", p1, p2));
        s.call(post("/api/timetable/semesters/" + semId + "/publish"), exams, "{\"kind\":\"EXAM\",\"sendSms\":false}")
                .andExpect(jsonPath("$.code").value("STUDENT_EXAM_CLASH"));
        assertThat(kinds(s.body(s.call(get(url), exams, null)))).contains("STUDENT_EXAM_CLASH");
        s.call(get("/api/timetable/semesters/" + semId + "/export?kind=EXAM"), exams, null).andExpect(status().isOk());
        s.call(delete("/api/timetable/exams/" + p1), exams, null).andExpect(status().isNoContent());
        s.call(post("/api/timetable/semesters/" + semId + "/withdraw"), exams, "{\"kind\":\"EXAM\"}").andExpect(status().isOk());
        assertThat(s.body(s.call(get("/api/student/timetable"), students[1], null)).get("examPublished").asBoolean()).isFalse();
    }

    // ------------------------------------------------------------------ helpers

    /** Three students admitted, registered for semester 1 (all three modules) and approved; their portal tokens. */
    private String[] registerThree() throws Exception {
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
        String[] tokens = new String[3];
        Applicant[] apps = {a, b, c};
        for (int i = 0; i < 3; i++) {
            String phone = s.body(s.call(get("/api/applicant/profile"), apps[i].token(), null)).get("phone").asText();
            tokens[i] = s.body(s.call(post("/api/student-auth/login"), null, "{\"login\":\"" + byPhone.get(phone) + "\",\"password\":\"Kifaru#Mto47\"}"))
                    .get("accessToken").asText();
            s.call(post("/api/student/semester-registration"), tokens[i], "{\"semesterId\":" + semId + ",\"electiveModuleIds\":[],\"detailsConfirmed\":true}")
                    .andExpect(status().isOk());
        }
        s.call(post("/api/registry/semester-registrations/approve-all"), registrar, "{\"semesterId\":" + semId + "}").andExpect(jsonPath("$.approved").value(3));
        return tokens;
    }

    private long semester(int no, String start, String end, LocalDate opens, LocalDate closes) throws Exception {
        return s.body(s.call(post("/api/academic/semesters"), dpa, "{\"academicYearId\":" + yearId + ",\"semesterNo\":" + no + ",\"name\":\"Semester " + no
                + "\",\"startDate\":\"" + start + "\",\"endDate\":\"" + end + "\",\"registrationOpens\":\"" + opens + "\",\"registrationCloses\":\"" + closes
                + "\"}").andExpect(status().isCreated())).get("id").asLong();
    }

    private long module(String code) throws Exception {
        return s.body(s.call(post("/api/academic/modules"), hod, "{\"code\":\"" + code + "\",\"title\":\"Module " + code
                + "\",\"credits\":10,\"ntaLevel\":4,\"assessmentType\":\"GRADED\",\"active\":true}").andExpect(status().isCreated())).get("id").asLong();
    }

    private static String slot(long moduleId, int sem) {
        return "{\"moduleId\":" + moduleId + ",\"yearOfStudy\":1,\"semesterNo\":" + sem + ",\"category\":\"CORE\"}";
    }

    private static String venue(String code, int capacity, Integer exam) {
        return "{\"code\":\"" + code + "\",\"name\":\"Room " + code + "\",\"kind\":\"CLASSROOM\",\"capacity\":" + capacity
                + (exam == null ? "" : ",\"examCapacity\":" + exam) + "}";
    }

    private String slotReq(String module, String kind, int day, String start, String end, long venue, Long lecturerId, String group) {
        return "{\"offeringId\":" + off.get(module) + ",\"kind\":\"" + kind + "\",\"dayOfWeek\":" + day + ",\"startTime\":\"" + start + "\",\"endTime\":\""
                + end + "\",\"venueId\":" + venue + (lecturerId == null ? "" : ",\"lecturerId\":" + lecturerId)
                + (group == null ? "" : ",\"groupLabel\":\"" + group + "\"") + "}";
    }

    private String paper(String module, String name, String date, String start, String end, List<Long> venues, List<Long> invigilators) {
        return "{\"offeringId\":" + off.get(module) + (name == null ? "" : ",\"paper\":\"" + name + "\"") + ",\"date\":\"" + date + "\",\"startTime\":\""
                + start + "\",\"endTime\":\"" + end + "\",\"venueIds\":" + venues + ",\"invigilatorIds\":" + invigilators + "}";
    }

    private long id(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return s.body(r).get("id").asLong();
    }

    private static long staffId(JsonNode staff, String name) {
        return staff.findParents("fullName").stream().filter(x -> x.get("fullName").asText().equals(name)).findFirst().orElseThrow().get("id").asLong();
    }

    private static List<String> kinds(JsonNode t) {
        List<String> out = new ArrayList<>();
        t.get("warnings").forEach(w -> out.add(w.get("kind").asText()));
        return out;
    }

    private static JsonNode row(JsonNode t, String code) {
        for (JsonNode o : t.get("offerings")) if (o.get("code").asText().equals(code)) return o;
        throw new AssertionError(code);
    }

    private static JsonNode find(JsonNode arr, long id) {
        for (JsonNode x : arr) if (x.get("id").asLong() == id) return x;
        throw new AssertionError(id);
    }
}
