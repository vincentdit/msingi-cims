package tz.co.msingi.cims.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.support.AdmissionScenario;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The Institution Administrator manages staff accounts and roles, as NOAS user management. */
@IntegrationTest
class StaffUserManagementIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;

    AdmissionScenario s;
    String admin, registrar, auditor;
    long departmentId;

    @BeforeEach
    void setUp() throws Exception {
        String tenant = "U" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TestData.Institution inst = data.institution(tenant, BigDecimal.ZERO);
        data.staff(inst.tenant(), "admin", Role.INSTITUTION_ADMIN);
        data.staff(inst.tenant(), "registrar", Role.REGISTRAR);
        data.staff(inst.tenant(), "auditor", Role.AUDITOR);
        s = new AdmissionScenario(mvc, json, tenant, inst);
        admin = s.staffToken("admin");
        registrar = s.staffToken("registrar");
        auditor = s.staffToken("auditor");
        departmentId = s.body(s.call(get("/api/academic/departments"), admin, null)).get(0).get("id").asLong();
    }

    String user(String username, String roles) {
        return "{\"username\":\"" + username + "\",\"fullName\":\"Asha Mrisho\",\"jobTitle\":\"Head of ICT\",\"email\":\"" + username
                + "@college.ac.tz\",\"phone\":\"0712000111\",\"roles\":" + roles + "}";
    }

    String login(String username, String password) throws Exception {
        return s.body(s.call(post("/api/auth/login"), null, "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")
                .andExpect(status().isOk())).get("accessToken").asText();
    }

    @Test
    void adminCreatesAStaffAccountWhoMustChooseTheirOwnPassword() throws Exception {
        JsonNode issued = s.body(s.call(post("/api/admin/users"), admin,
                user("asha", "[{\"role\":\"HEAD_OF_DEPARTMENT\",\"scope\":\"DEPARTMENT\",\"scopeId\":" + departmentId + "},{\"role\":\"LECTURER\"}]"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.mustChangePassword").value(true))
                .andExpect(jsonPath("$.user.phone").value("255712000111"))
                .andExpect(jsonPath("$.user.roles[0].role").value("HEAD_OF_DEPARTMENT"))
                .andExpect(jsonPath("$.user.roles[0].scopeName").isNotEmpty()));
        String temp = issued.get("temporaryPassword").asText();
        assertThat(temp).hasSize(12).matches(".*[A-Z].*").matches(".*[a-z].*").matches(".*\\d.*").matches(".*[^A-Za-z0-9].*");

        // with the temporary password only the password change is allowed
        JsonNode first = s.body(s.call(post("/api/auth/login"), null, "{\"username\":\"asha\",\"password\":\"" + temp + "\"}"));
        assertThat(first.get("user").get("mustChangePassword").asBoolean()).isTrue();
        String tempToken = first.get("accessToken").asText();
        s.call(get("/api/academic/departments"), tempToken, null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
        s.call(post("/api/auth/password"), tempToken, "{\"currentPassword\":\"" + temp + "\",\"newPassword\":\"weak\"}").andExpect(status().isBadRequest());
        s.call(post("/api/auth/password"), tempToken, "{\"currentPassword\":\"wrong\",\"newPassword\":\"Mpya@2026x\"}")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("WRONG_PASSWORD"));
        JsonNode changed = s.body(s.call(post("/api/auth/password"), tempToken, "{\"currentPassword\":\"" + temp + "\",\"newPassword\":\"Mpya@2026x\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.mustChangePassword").value(false)));
        s.call(get("/api/academic/departments"), changed.get("accessToken").asText(), null).andExpect(status().isOk());
        login("asha", "Mpya@2026x");

        s.call(post("/api/admin/users"), admin, user("ASHA", "[{\"role\":\"LECTURER\"}]"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));
    }

    @Test
    void rolesAreCheckedAndScoped() throws Exception {
        s.call(post("/api/admin/users"), admin, user("xone", "[{\"role\":\"STUDENT\"}]")).andExpect(jsonPath("$.code").value("ROLE_NOT_ASSIGNABLE"));
        s.call(post("/api/admin/users"), admin, user("xtwo", "[{\"role\":\"PLATFORM_ADMIN\"}]")).andExpect(jsonPath("$.code").value("ROLE_NOT_ASSIGNABLE"));
        s.call(post("/api/admin/users"), admin, user("xthree", "[{\"role\":\"BURSAR\",\"scope\":\"DEPARTMENT\",\"scopeId\":" + departmentId + "}]"))
                .andExpect(jsonPath("$.code").value("SCOPE_NOT_ALLOWED"));
        s.call(post("/api/admin/users"), admin, user("xfour", "[{\"role\":\"HEAD_OF_DEPARTMENT\",\"scope\":\"DEPARTMENT\"}]"))
                .andExpect(jsonPath("$.code").value("SCOPE_NOT_FOUND"));
        s.call(post("/api/admin/users"), admin, user("xfive", "[]")).andExpect(status().isBadRequest());
        s.call(get("/api/admin/roles"), admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.role=='HEAD_OF_DEPARTMENT')].scopes[1]").value("DEPARTMENT"))
                .andExpect(jsonPath("$[?(@.role=='STUDENT')]").isEmpty());
    }

    @Test
    void changingRolesResettingOrDisablingEndsTheUsersSessions() throws Exception {
        JsonNode issued = s.body(s.call(post("/api/admin/users"), admin, user("juma", "[{\"role\":\"REGISTRAR\"}]")));
        long id = issued.get("user").get("id").asLong();
        String temp = issued.get("temporaryPassword").asText();
        String t0 = s.body(s.call(post("/api/auth/password"), login("juma", temp), "{\"currentPassword\":\"" + temp + "\",\"newPassword\":\"Juma@2026x\"}"))
                .get("accessToken").asText();
        String token = login("juma", "Juma@2026x");
        s.call(get("/api/registry/students"), token, null).andExpect(status().isOk());

        // new roles: the old session ends, the new one carries the new roles
        s.call(put("/api/admin/users/" + id), admin, user("juma", "[{\"role\":\"BURSAR\"}]")).andExpect(status().isOk());
        s.call(get("/api/registry/students"), token, null).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_ENDED"));
        s.call(get("/api/registry/students"), t0, null).andExpect(status().isUnauthorized());
        String bursar = login("juma", "Juma@2026x");
        s.call(get("/api/registry/students"), bursar, null).andExpect(status().isForbidden());
        s.call(get("/api/finance/fee-items"), bursar, null).andExpect(status().isOk());

        // disabled: signed out and cannot sign in; enabled again: can
        s.call(post("/api/admin/users/" + id + "/disable"), admin, "{\"reason\":\"Left the college\"}").andExpect(jsonPath("$.status").value("DISABLED"));
        s.call(get("/api/finance/fee-items"), bursar, null).andExpect(status().isUnauthorized());
        s.call(post("/api/auth/login"), null, "{\"username\":\"juma\",\"password\":\"Juma@2026x\"}").andExpect(status().isUnauthorized());
        s.call(post("/api/admin/users/" + id + "/enable"), admin, null).andExpect(jsonPath("$.status").value("ACTIVE"));
        login("juma", "Juma@2026x");

        // reset: a new temporary password, the old one no longer works
        String temp2 = s.body(s.call(post("/api/admin/users/" + id + "/reset-password"), admin, null)
                .andExpect(jsonPath("$.user.mustChangePassword").value(true))).get("temporaryPassword").asText();
        s.call(post("/api/auth/login"), null, "{\"username\":\"juma\",\"password\":\"Juma@2026x\"}").andExpect(status().isUnauthorized());
        login("juma", temp2);
    }

    @Test
    void theInstitutionAlwaysKeepsAnAdministrator() throws Exception {
        long self = s.body(s.call(get("/api/admin/users"), admin, null)).findValues("username").stream()
                .map(JsonNode::asText).toList().indexOf("admin");
        long adminId = s.body(s.call(get("/api/admin/users"), admin, null)).get((int) self).get("id").asLong();
        s.call(post("/api/admin/users/" + adminId + "/disable"), admin, null).andExpect(jsonPath("$.code").value("OWN_ACCOUNT"));
        s.call(put("/api/admin/users/" + adminId), admin, "{\"username\":\"admin\",\"fullName\":\"Admin\",\"roles\":[{\"role\":\"REGISTRAR\"}]}")
                .andExpect(jsonPath("$.code").value("OWN_ADMIN_ROLE"));
        s.call(post("/api/admin/users/" + adminId + "/reset-password"), admin, null).andExpect(jsonPath("$.code").value("OWN_PASSWORD"));

        // a second administrator may not take away the only other one's role while that one is the last active admin
        JsonNode second = s.body(s.call(post("/api/admin/users"), admin, user("deputy", "[{\"role\":\"INSTITUTION_ADMIN\"}]")));
        long secondId = second.get("user").get("id").asLong();
        s.call(post("/api/admin/users/" + secondId + "/disable"), admin, null).andExpect(jsonPath("$.status").value("DISABLED"));
        s.call(put("/api/admin/users/" + secondId), admin, user("deputy", "[{\"role\":\"LECTURER\"}]")).andExpect(status().isOk());
    }

    @Test
    void onlyTheAdministratorChangesAccountsAndTheAuditorMayLook() throws Exception {
        s.call(get("/api/admin/users"), auditor, null).andExpect(status().isOk());
        s.call(get("/api/admin/users"), registrar, null).andExpect(status().isForbidden());
        s.call(post("/api/admin/users"), auditor, user("yone", "[{\"role\":\"LECTURER\"}]")).andExpect(status().isForbidden());
        s.call(post("/api/admin/users"), registrar, user("ytwo", "[{\"role\":\"LECTURER\"}]")).andExpect(status().isForbidden());
        int listed = s.body(s.call(get("/api/admin/users"), admin, null)).size();
        s.call(get("/api/admin/users/summary"), admin, null).andExpect(jsonPath("$.total").value(listed)).andExpect(jsonPath("$.active").value(listed));
    }
}
