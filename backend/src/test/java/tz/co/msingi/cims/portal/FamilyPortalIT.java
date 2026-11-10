package tz.co.msingi.cims.portal;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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

import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The family and sponsor portal: sign-in with a code by SMS, what a phone may see, and the student's and registry's controls (FR-STU-05). */
@IntegrationTest
class FamilyPortalIT {

    static final String INSTRUCTIONS = """
            {"reportingDate":"2027-09-27","reportingTime":"08:00","reportingVenue":"Main Hall",
             "documentsToBring":["Original CSEE certificate"],"signatoryName":"Dr. Amina Salum","signatoryTitle":"Principal"}""";
    /** The next of kin AdmissionScenario records for every applicant. */
    static final String PARENT = "0713000111";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    AdmissionScenario s;
    TestData.Institution inst;
    String officer, chair, registrar, admin, bursar;
    long aId, bId;
    String aRegNo;

    @BeforeEach
    void setUp() throws Exception {
        String tenant = "Y" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        inst = data.institution(tenant, BigDecimal.ZERO);
        data.staff(inst.tenant(), "chair", Role.DEPUTY_PRINCIPAL_ACADEMIC);
        data.staff(inst.tenant(), "registrar", Role.REGISTRAR);
        data.staff(inst.tenant(), "admin", Role.INSTITUTION_ADMIN);
        data.staff(inst.tenant(), "bursar", Role.BURSAR);
        s = new AdmissionScenario(mvc, json, tenant, inst);
        officer = s.staffToken("officer");
        chair = s.staffToken("chair");
        registrar = s.staffToken("registrar");
        admin = s.staffToken("admin");
        bursar = s.staffToken("bursar");
        s.call(put("/api/admission/windows/" + inst.windowId() + "/joining-instructions"), officer, INSTRUCTIONS).andExpect(status().isOk());
        Applicant a = s.apply("S0101/0001");
        Applicant b = s.applyEligibleEndingIn(5, 400);
        s.admit(officer, chair, a, b);
        String aPhone = s.body(s.call(get("/api/applicant/profile"), a.token(), null)).get("phone").asString();
        JsonNode handover = s.body(s.call(post("/api/registry/windows/" + inst.windowId() + "/handover"), registrar, "{}").andExpect(status().isOk()));
        for (JsonNode st : handover.get("students")) {
            JsonNode detail = s.body(s.call(get("/api/registry/students/" + st.get("studentId").asLong()), registrar, null));
            if (detail.at("/summary/phone").asString().equals(aPhone)) {
                aId = st.get("studentId").asLong();
                aRegNo = detail.at("/summary/registrationNo").asString();
            } else {
                bId = st.get("studentId").asLong();
            }
        }
        assertThat(aId).isPositive();
        assertThat(bId).isPositive();
    }

    private <T> T db(Supplier<T> work) {
        return TenantContext.callAs(inst.info(), () -> tx.execute(x -> work.get()));
    }

    private String familySignIn(String phone) throws Exception {
        String code = s.body(s.call(post("/api/family-auth/code"), null, "{\"phone\":\"" + phone + "\"}").andExpect(status().isOk()))
                .get("developmentCode").asString();
        return s.body(s.call(post("/api/family-auth/verify"), null, "{\"phone\":\"" + phone + "\",\"code\":\"" + code + "\"}").andExpect(status().isOk()))
                .get("accessToken").asString();
    }

    @Test
    void aParentSignsInWithACodeAndFollowsTheirStudents() throws Exception {
        // a number linked to no one gets the same answer and no code
        JsonNode unknown = s.body(s.call(post("/api/family-auth/code"), null, "{\"phone\":\"0788999000\"}").andExpect(status().isOk()));
        assertThat(unknown.path("developmentCode").isMissingNode() || unknown.path("developmentCode").isNull()).isTrue();
        assertThat(db(() -> jdbc.queryForObject("select count(*) from messages where template_code = 'FAMILY_CODE'", Integer.class))).isZero();

        // the next of kin on both students' records: a code by SMS (masked in the log), a wrong code, then the right one
        String code = s.body(s.call(post("/api/family-auth/code"), null, "{\"phone\":\"" + PARENT + "\"}").andExpect(status().isOk()))
                .get("developmentCode").asString();
        assertThat(code).hasSize(6);
        String body = db(() -> jdbc.queryForObject("select body from messages where template_code = 'FAMILY_CODE'", String.class));
        assertThat(body).doesNotContain(code);
        String wrong = code.equals("000000") ? "111111" : "000000";
        s.call(post("/api/family-auth/verify"), null, "{\"phone\":\"" + PARENT + "\",\"code\":\"" + wrong + "\"}").andExpect(jsonPath("$.code").value("CODE_INVALID"));
        String family = s.body(s.call(post("/api/family-auth/verify"), null, "{\"phone\":\"" + PARENT + "\",\"code\":\"" + code + "\"}")
                .andExpect(status().isOk())).get("accessToken").asString();
        s.call(post("/api/family-auth/verify"), null, "{\"phone\":\"" + PARENT + "\",\"code\":\"" + code + "\"}").andExpect(jsonPath("$.code").value("CODE_EXPIRED"));

        JsonNode students = s.body(s.call(get("/api/family/students"), family, null).andExpect(status().isOk()));
        assertThat(students).hasSize(2);
        assertThat(students.get(0).get("as").asString()).isEqualTo("GUARDIAN");
        s.call(get("/api/family/students/" + aId + "/fees"), family, null).andExpect(status().isOk()).andExpect(jsonPath("$.registrationNo").value(aRegNo));
        s.call(get("/api/family/students/" + aId + "/results"), family, null).andExpect(status().isOk());
        s.call(get("/api/family/students/" + aId + "/attendance"), family, null).andExpect(status().isOk());

        // the family sign-in works only in the family portal, and the family portal takes no other sign-in
        s.call(get("/api/registry/students"), family, null).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("WRONG_REALM"));
        s.call(get("/api/student/dashboard"), family, null).andExpect(status().isForbidden());
        s.call(get("/api/family/students"), registrar, null).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("WRONG_REALM"));
        s.call(get("/api/family/students"), null, null).andExpect(status().isUnauthorized());

        // the student stops sharing results with this number: fees still show, results do not
        String student = s.body(s.call(post("/api/student-auth/login"), null, "{\"login\":\"" + aRegNo + "\",\"password\":\"Kifaru#Mto47\"}")
                .andExpect(status().isOk())).get("accessToken").asString();
        JsonNode mine = s.body(s.call(get("/api/student/family"), student, null).andExpect(status().isOk()));
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).get("phone").asString()).doesNotContain("713000111");   // masked for the student
        assertThat(mine.get(0).get("lastSeenAt").isNull()).isFalse();
        long link = mine.get(0).get("id").asLong();
        s.call(put("/api/student/family/" + link), student, "{\"shareResults\":false,\"shareAttendance\":true}").andExpect(jsonPath("$.shareResults").value(false));
        s.call(get("/api/family/students/" + aId + "/results"), family, null).andExpect(jsonPath("$.code").value("NOT_SHARED"));
        s.call(get("/api/family/students/" + aId + "/fees"), family, null).andExpect(status().isOk());
        s.call(put("/api/student/family/" + link), registrar, "{\"shareResults\":true,\"shareAttendance\":true}").andExpect(status().isForbidden());

        // the registry stops the link for student A: the phone no longer sees A, only B
        s.call(get("/api/registry/students/" + aId + "/family"), bursar, null).andExpect(jsonPath("$[0].phone").value("255713000111"));
        s.call(put("/api/registry/students/" + aId + "/family/" + link), bursar, "{\"active\":false}").andExpect(status().isForbidden());
        s.call(put("/api/registry/students/" + aId + "/family/" + link), registrar, "{\"active\":false}").andExpect(jsonPath("$.active").value(false));
        s.call(get("/api/family/students"), family, null).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].studentId").value(bId));
        s.call(get("/api/family/students/" + aId + "/fees"), family, null).andExpect(status().isNotFound());
    }

    @Test
    void theRegistryAddsAGuardianAndTheAdministratorCanCloseThePortal() throws Exception {
        // the installable student app carries the institution's name
        s.call(get("/api/public/manifest.webmanifest"), null, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.start_url").value("/student")).andExpect(jsonPath("$.name").value(org.hamcrest.Matchers.endsWith("Student App")));
        s.call(post("/api/registry/students/" + bId + "/family"), registrar, "{\"phone\":\"12345\",\"name\":\"Uncle\"}").andExpect(status().is4xxClientError());
        s.call(post("/api/registry/students/" + bId + "/family"), registrar, "{\"phone\":\"0754111222\",\"name\":\"Juma Ally\",\"relation\":\"Uncle\"}")
                .andExpect(jsonPath("$.phone").value("255754111222")).andExpect(jsonPath("$.source").value("ADDED"));
        String uncle = familySignIn("+255 754 111 222");
        s.call(get("/api/family/students"), uncle, null).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].studentId").value(bId))
                .andExpect(jsonPath("$[0].relation").value("Uncle"));
        // another student's record is not reachable from this phone
        s.call(get("/api/family/students/" + aId + "/fees"), uncle, null).andExpect(status().isNotFound());

        s.call(put("/api/family-portal/settings"), registrar, "{\"enabled\":false,\"sponsorsSeeResults\":true}").andExpect(status().isForbidden());
        s.call(put("/api/family-portal/settings"), admin, "{\"enabled\":false,\"sponsorsSeeResults\":true}").andExpect(jsonPath("$.enabled").value(false));
        s.call(post("/api/family-auth/code"), null, "{\"phone\":\"0754111222\"}").andExpect(jsonPath("$.code").value("FAMILY_PORTAL_OFF"));
        s.call(get("/api/family/students"), uncle, null).andExpect(jsonPath("$.code").value("FAMILY_PORTAL_OFF"));
        assertThat(db(() -> jdbc.queryForObject("select count(*) from audit_logs where action = 'FAMILY_SIGN_IN'", Integer.class))).isEqualTo(1);
    }
}
