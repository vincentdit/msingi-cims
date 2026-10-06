package tz.co.msingi.cims.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.ProgrammeRepository;
import tz.co.msingi.cims.admission.domain.AdmissionRepositories.AdmissionWindowRepository;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.security.UserAccountRepository;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.registry.RegistryRepositories.StudentRepository;
import tz.co.msingi.cims.support.AdmissionScenario;
import tz.co.msingi.cims.support.AdmissionScenario.Applicant;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** BP-05: hand-over of accepted applicants to Registry, registration numbers, first registration at reporting. */
@IntegrationTest
class RegistryIT {

    static final String INSTRUCTIONS = """
            {"reportingDate":"2027-09-27","reportingTime":"08:00","reportingVenue":"Main Hall",
             "documentsToBring":["Original CSEE certificate","Birth certificate"],
             "signatoryName":"Dr. Amina Salum","signatoryTitle":"Principal"}""";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager em;
    @Autowired StudentRepository students;
    @Autowired ProgrammeRepository programmes;
    @Autowired AdmissionWindowRepository windows;
    @Autowired UserAccountRepository users;

    AdmissionScenario s;
    String officer, chair, registrar;

    @BeforeEach
    void setUp() throws Exception {
        String tenant = "R" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TestData.Institution inst = data.institution(tenant, BigDecimal.ZERO);
        data.staff(inst.tenant(), "chair", Role.DEPUTY_PRINCIPAL_ACADEMIC);
        data.staff(inst.tenant(), "registrar", Role.REGISTRAR);
        data.staff(inst.tenant(), "viewer", Role.MANAGEMENT_VIEWER);
        s = new AdmissionScenario(mvc, json, tenant, inst);
        officer = s.staffToken("officer");
        chair = s.staffToken("chair");
        registrar = s.staffToken("registrar");
        s.call(put("/api/admission/windows/" + inst.windowId() + "/joining-instructions"), officer, INSTRUCTIONS).andExpect(status().isOk());
    }

    @Test
    void acceptedApplicantsBecomeStudentsAndCompleteFirstRegistration() throws Exception {
        Applicant a = s.apply("S0101/0001");
        Applicant b = s.applyEligibleEndingIn(5, 300);
        Applicant c = s.applyEligibleEndingIn(1, 600);          // verified but does not accept (yet)
        s.admit(officer, chair, a, b);

        JsonNode ov = overview();
        assertThat(ov.get("accepted").asInt()).isEqualTo(2);
        assertThat(ov.get("pending").asInt()).isEqualTo(2);
        assertThat(ov.get("registrationNoPattern").asText()).isEqualTo(RegistrationNumberPattern.DEFAULT);
        assertThat(ov.get("rows").findValuesAsText("applicationId")).doesNotContain(String.valueOf(c.applicationId()));

        s.call(post(handoverUrl()), s.staffToken("viewer"), "{}").andExpect(status().isForbidden());
        s.call(get(handoverUrl()), a.token(), null).andExpect(status().isForbidden());

        JsonNode result = s.body(s.call(post(handoverUrl()), registrar, "{}").andExpect(status().isOk()));
        assertThat(result.get("created").asInt()).isEqualTo(2);
        assertThat(result.get("students").findValuesAsText("registrationNo"))
                .containsExactlyInAnyOrder(s.tenant + "/NTA4/27/0001", s.tenant + "/NTA4/27/0002");
        s.call(post(handoverUrl()), registrar, "{}").andExpect(jsonPath("$.code").value("NOTHING_TO_HAND_OVER"));
        s.call(post(handoverUrl()), registrar, "{\"applicationIds\":[" + c.applicationId() + "]}").andExpect(jsonPath("$.code").value("NOT_ACCEPTED"));

        // the applicant sees the registration number; the applicant account becomes the student's sign-in
        JsonNode app = s.body(s.call(get("/api/applicant/applications/" + a.applicationId()), a.token(), null));
        String regNo = app.at("/student/registrationNo").asText();
        assertThat(regNo).startsWith(s.tenant + "/NTA4/27/");
        assertThat(app.at("/student/status").asText()).isEqualTo("ADMITTED");
        Boolean isStudent = TenantContext.callAs(s.inst.info(), () -> tx.execute(t -> students.findAll().stream()
                .filter(st -> st.getRegistrationNo().equals(regNo)).findFirst()
                .flatMap(st -> users.findById(st.getUserId())).orElseThrow().hasRole(Role.STUDENT)));
        assertThat(isStudent).isTrue();

        // student record copied from the application, with the checklist from the joining instructions
        long studentId = s.body(s.call(get("/api/registry/students?q=" + regNo), registrar, null)).at("/content/0/id").asLong();
        JsonNode st = student(studentId);
        assertThat(st.get("dateOfBirth").asText()).isEqualTo("2006-01-01");
        assertThat(st.get("nationality").asText()).isEqualTo("Tanzanian");
        assertThat(st.get("nactvetVerificationNo").asText()).startsWith("NV/");
        assertThat(st.get("admissionLetterNo").asText()).isEqualTo(s.tenant + "/ADM/2027/000001");
        assertThat(st.at("/firstRegistration/status").asText()).isEqualTo("PENDING");
        assertThat(st.at("/firstRegistration/documents").findValuesAsText("item")).containsExactly("Original CSEE certificate", "Birth certificate");

        // first registration: originals checked, then approved
        s.call(post("/api/registry/students/" + studentId + "/first-registration/approve"), registrar, null)
                .andExpect(jsonPath("$.code").value("DOCUMENTS_OUTSTANDING"));
        s.call(put("/api/registry/students/" + studentId + "/first-registration"), registrar,
                "{\"documents\":[{\"item\":\"Passport\",\"verified\":true}]}").andExpect(jsonPath("$.code").value("UNKNOWN_DOCUMENT"));
        st = s.body(s.call(put("/api/registry/students/" + studentId + "/first-registration"), registrar,
                "{\"documents\":[{\"item\":\"Original CSEE certificate\",\"verified\":true}],\"remarks\":\"Birth certificate tomorrow\"}"));
        assertThat(st.at("/firstRegistration/reportedAt").isMissingNode()).isFalse();
        s.call(post("/api/registry/students/" + studentId + "/first-registration/approve"), registrar, null)
                .andExpect(jsonPath("$.detail").value("Originals not yet verified: Birth certificate"));
        s.call(put("/api/registry/students/" + studentId + "/first-registration"), registrar,
                "{\"documents\":[{\"item\":\"Birth certificate\",\"verified\":true}]}").andExpect(status().isOk());
        s.call(post("/api/registry/students/" + studentId + "/first-registration/approve"), registrar, null)
                .andExpect(jsonPath("$.summary.status").value("ACTIVE"))
                .andExpect(jsonPath("$.firstRegistration.approvedByName").value("Staff registrar"));
        s.call(post("/api/registry/students/" + studentId + "/first-registration/approve"), registrar, null)
                .andExpect(jsonPath("$.code").value("ALREADY_REGISTERED"));

        // lists, statistics and class list
        s.call(get("/api/registry/students?status=ACTIVE"), registrar, null).andExpect(jsonPath("$.totalElements").value(1));
        s.call(get("/api/registry/students/stats"), registrar, null)
                .andExpect(jsonPath("$.ACTIVE").value(1)).andExpect(jsonPath("$.ADMITTED").value(1));
        String csv = s.text(s.call(get("/api/registry/programmes/" + s.inst.programmeId() + "/class-list"), registrar, null).andExpect(status().isOk()));
        assertThat(csv).contains(regNo).contains("Registration No");
        assertThat(csv.lines().count()).isEqualTo(2); // header + the one registered student
    }

    @Test
    void registrationNumbersFollowThePatternAndAreNeverReusedOrChanged() throws Exception {
        s.call(post("/api/registry/settings/preview"), registrar, "{\"pattern\":\"{INST}-{FOO}-{SEQ}\"}")
                .andExpect(jsonPath("$.code").value("INVALID_PATTERN"));
        s.call(post("/api/registry/settings/preview"), registrar, "{\"pattern\":\"{PROG}/{YEAR}/{SEQ:3}\"}")
                .andExpect(jsonPath("$.example").value("ODIT/2027/001"));
        s.call(put("/api/registry/settings"), s.staffToken("viewer"), "{\"pattern\":\"{INST}{YY}{SEQ:4}\"}")
                .andExpect(status().isForbidden());
        s.call(put("/api/registry/settings"), registrar, "{\"pattern\":\"{INST}{YY}{SEQ:4}\"}")
                .andExpect(jsonPath("$.usingDefault").value(false));

        // a number already taken (e.g. a migrated legacy student) is skipped, never reused
        String taken = s.tenant + "270001";
        Long legacyId = TenantContext.callAs(s.inst.info(), () -> tx.execute(t -> {
            var w = windows.findById(s.inst.windowId()).orElseThrow();
            Student legacy = new Student();
            legacy.setRegistrationNo(taken);
            legacy.setFirstName("Legacy");
            legacy.setSurname("Student");
            legacy.setSex("M");
            legacy.setDateOfBirth(LocalDate.of(2000, 1, 1));
            legacy.setNationality("Tanzanian");
            legacy.setPhone("255700000001");
            legacy.setProgramme(programmes.findById(s.inst.programmeId()).orElseThrow());
            legacy.setEntryAcademicYear(w.getAcademicYear());
            legacy.setIntake(w.getIntake());
            legacy.setStatus(Student.Status.ACTIVE);
            legacy.setAdmittedAt(OffsetDateTime.now());
            legacy.setAdmittedByName("Data migration");
            return students.save(legacy).getId();
        }));
        Applicant a = s.apply("S0101/0001");
        s.admit(officer, chair, a);
        JsonNode result = s.body(s.call(post(handoverUrl()), registrar, "{\"applicationIds\":[" + a.applicationId() + "]}"));
        assertThat(result.at("/students/0/registrationNo").asText()).isEqualTo(s.tenant + "270002");

        // the database refuses to change a registration number or delete a student (BRU-06)
        assertThatThrownBy(() -> TenantContext.runAs(s.inst.info(), () -> tx.executeWithoutResult(t ->
                em.createNativeQuery("UPDATE students SET registration_no = 'CHANGED' WHERE id = :id").setParameter("id", legacyId).executeUpdate())))
                .hasStackTraceContaining("a registration number never changes");
        assertThatThrownBy(() -> TenantContext.runAs(s.inst.info(), () -> tx.executeWithoutResult(t ->
                em.createNativeQuery("DELETE FROM students WHERE id = :id").setParameter("id", legacyId).executeUpdate())))
                .hasStackTraceContaining("students are never deleted");
    }

    @Test
    void anotherInstitutionCannotSeeStudents() throws Exception {
        Applicant a = s.apply("S0101/0001");
        s.admit(officer, chair, a);
        long id = s.body(s.call(post(handoverUrl()), registrar, "{}")).at("/students/0/studentId").asLong();
        String other = "Q" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TestData.Institution o = data.institution(other, BigDecimal.ZERO);
        data.staff(o.tenant(), "registrar", Role.REGISTRAR);
        AdmissionScenario os = new AdmissionScenario(mvc, json, other, o);
        String token = os.staffToken("registrar");
        os.call(get("/api/registry/students/" + id), token, null).andExpect(status().isNotFound());
        os.call(get("/api/registry/students"), token, null).andExpect(jsonPath("$.totalElements").value(0));
    }

    private String handoverUrl() {
        return "/api/registry/windows/" + s.inst.windowId() + "/handover";
    }

    private JsonNode overview() throws Exception {
        return s.body(s.call(get(handoverUrl()), registrar, null).andExpect(status().isOk()));
    }

    private JsonNode student(long id) throws Exception {
        return s.body(s.call(get("/api/registry/students/" + id), registrar, null).andExpect(status().isOk()));
    }
}
