package tz.co.msingi.cims.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Campuses, faculties, programme and campus transfers (FR-ACA-01, FR-REG-05) and fees by intake and category (FR-FIN-01). */
@IntegrationTest
class CampusesAndTransfersIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    AdmissionScenario s;
    TestData.Institution inst;
    String admin, registrar, chair, hodBus, bursar, accountant, officer;
    long mainCampus, arusha, busProgramme, busDept, studentA, studentB, yearId;
    Applicant a;

    @BeforeEach
    void setUp() throws Exception {
        String tenant = "T" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        inst = data.institution(tenant, BigDecimal.ZERO);
        data.staff(inst.tenant(), "admin", Role.INSTITUTION_ADMIN);
        data.staff(inst.tenant(), "chair", Role.DEPUTY_PRINCIPAL_ACADEMIC);
        data.staff(inst.tenant(), "registrar", Role.REGISTRAR);
        data.staff(inst.tenant(), "bursar", Role.BURSAR);
        data.staff(inst.tenant(), "accountant", Role.ACCOUNTANT);
        s = new AdmissionScenario(mvc, json, tenant, inst);
        admin = s.staffToken("admin");
        officer = s.staffToken("officer");
        registrar = s.staffToken("registrar");
        chair = s.staffToken("chair");
        bursar = s.staffToken("bursar");
        accountant = s.staffToken("accountant");

        // campuses: the first is the main one; a faculty groups a new department
        mainCampus = s.body(s.call(post("/api/academic/campuses"), admin,
                "{\"code\":\"MAIN\",\"name\":\"Main Campus\",\"region\":\"Dar es Salaam\",\"main\":false,\"active\":true}").andExpect(status().isOk())).get("id").asLong();
        arusha = s.body(s.call(post("/api/academic/campuses"), admin,
                "{\"code\":\"ARU\",\"name\":\"Arusha Campus\",\"region\":\"Arusha\",\"main\":false,\"active\":true}").andExpect(status().isOk())).get("id").asLong();
        JsonNode campuses = s.body(s.call(get("/api/academic/campuses"), registrar, null));
        assertThat(campuses.get(0).get("main").asBoolean()).isTrue();
        assertThat(campuses.get(0).get("id").asLong()).isEqualTo(mainCampus);
        s.call(put("/api/academic/campuses/" + mainCampus), admin,
                "{\"code\":\"MAIN\",\"name\":\"Main Campus\",\"main\":false,\"active\":true}").andExpect(jsonPath("$.code").value("MAIN_REQUIRED"));
        s.call(post("/api/academic/campuses"), registrar, "{\"code\":\"X1\",\"name\":\"X\",\"main\":false,\"active\":true}").andExpect(status().isForbidden());
        long faculty = s.body(s.call(post("/api/academic/faculties"), admin, "{\"code\":\"FBM\",\"name\":\"Faculty of Business\",\"active\":true}")
                .andExpect(status().isOk())).get("id").asLong();
        busDept = s.body(s.call(post("/api/academic/departments"), admin,
                "{\"code\":\"BUS\",\"name\":\"Business Studies\",\"active\":true,\"facultyId\":" + faculty + "}").andExpect(status().isCreated())).get("id").asLong();
        busProgramme = s.body(s.call(post("/api/academic/programmes"), admin, "{\"departmentId\":" + busDept + ",\"code\":\"ODBA\","
                + "\"name\":\"Ordinary Diploma in Business Administration\",\"awardFamily\":\"NTA\",\"awardLevel\":6,\"awardName\":\"Ordinary Diploma\","
                + "\"durationSemesters\":4,\"active\":true}").andExpect(status().isCreated())).get("id").asLong();
        // business is taught only in Arusha
        s.call(put("/api/academic/programmes/" + busProgramme + "/campuses"), admin, "{\"campusIds\":[" + arusha + "]}").andExpect(status().isOk());
        data.staffScoped(inst.tenant(), "hodbus", Role.HEAD_OF_DEPARTMENT, "DEPARTMENT", busDept);
        hodBus = s.staffToken("hodbus");

        s.call(put("/api/admission/windows/" + inst.windowId() + "/joining-instructions"), officer, RegistrarIT.INSTRUCTIONS).andExpect(status().isOk());
        yearId = s.body(s.call(get("/api/admission/windows/" + inst.windowId()), officer, null)).get("academicYearId").asLong();
        a = s.apply("S0101/0001");
        Applicant b = s.applyEligibleEndingIn(5, 300);
        s.admit(officer, chair, a, b);
        s.call(post("/api/registry/windows/" + inst.windowId() + "/handover"), registrar, "{}").andExpect(status().isOk());
        JsonNode list = s.body(s.call(get("/api/registry/students"), registrar, null)).get("content");
        studentA = list.get(0).get("id").asLong();
        studentB = list.get(1).get("id").asLong();
        TenantContext.runAs(inst.info(), () -> tx.executeWithoutResult(st ->
                jdbc.update("update students set status = 'ACTIVE' where id in (?, ?)", studentA, studentB)));
    }

    @Test
    void aProgrammeTransferIsRecommendedApprovedAndKeptInHistory() throws Exception {
        JsonNode before = s.body(s.call(get("/api/registry/students/" + studentA), registrar, null));
        assertThat(before.get("campus").asText()).isEqualTo("Main Campus");   // the old programme has no campus listed: the main one
        String regNo = before.at("/summary/registrationNo").asText();

        // business is not taught at the main campus
        s.call(post("/api/registry/students/" + studentA + "/transfers"), registrar,
                "{\"toProgrammeId\":" + busProgramme + ",\"toCampusId\":" + mainCampus + ",\"reason\":\"Change of career\"}")
                .andExpect(jsonPath("$.code").value("NOT_TAUGHT_AT_CAMPUS"));
        s.call(post("/api/registry/students/" + studentA + "/transfers"), registrar, "{\"reason\":\"Nothing\"}")
                .andExpect(jsonPath("$.code").value("NOTHING_CHANGES"));
        s.call(post("/api/registry/students/" + studentA + "/transfers"), hodBus, "{\"toProgrammeId\":" + busProgramme + ",\"reason\":\"x\"}")
                .andExpect(status().isForbidden());
        JsonNode t = s.body(s.call(post("/api/registry/students/" + studentA + "/transfers"), registrar,
                "{\"toProgrammeId\":" + busProgramme + ",\"reason\":\"Change of career\"}").andExpect(status().isOk()));
        assertThat(t.get("kind").asText()).isEqualTo("BOTH");          // new programme, and its campus
        assertThat(t.get("toCampus").asText()).isEqualTo("Arusha Campus");
        assertThat(t.get("toYear").asInt()).isEqualTo(1);
        long id = t.get("id").asLong();
        s.call(post("/api/registry/students/" + studentA + "/transfers"), registrar,
                "{\"toCampusId\":" + arusha + ",\"reason\":\"Again\"}").andExpect(jsonPath("$.code").value("OPEN_REQUEST"));

        // the Head of the receiving department recommends; it shows in their inbox; the Registrar cannot decide
        JsonNode inbox = s.body(s.call(get("/api/approvals"), hodBus, null));
        assertThat(inbox.get("items").findValuesAsText("kind")).contains("TRANSFER_RECOMMEND");
        s.call(post("/api/registry/transfers/" + id + "/recommend"), hodBus, "{\"note\":\"Welcome\"}").andExpect(jsonPath("$.state").value("RECOMMENDED"));
        s.call(post("/api/registry/transfers/" + id + "/decide"), registrar, "{\"approve\":true}").andExpect(status().isForbidden());
        s.call(post("/api/registry/transfers/" + id + "/decide"), chair, "{\"approve\":false}").andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
        s.call(post("/api/registry/transfers/" + id + "/decide"), chair, "{\"approve\":true,\"note\":\"Board minute 4/27\"}")
                .andExpect(jsonPath("$.state").value("APPROVED"));

        JsonNode after = s.body(s.call(get("/api/registry/students/" + studentA), registrar, null));
        assertThat(after.at("/summary/registrationNo").asText()).isEqualTo(regNo);
        assertThat(after.at("/summary/programmeCode").asText()).isEqualTo("ODBA");
        assertThat(after.get("campus").asText()).isEqualTo("Arusha Campus");
        JsonNode history = s.body(s.call(get("/api/registry/students/" + studentA + "/transfers"), registrar, null));
        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("decidedByName").asText()).isEqualTo("Staff chair");
        Integer audits = TenantContext.callAs(inst.info(), () -> tx.execute(x ->
                jdbc.queryForObject("select count(*) from audit_logs where action = 'STUDENT_TRANSFERRED' and entity_id = ?", Integer.class, studentA)));
        assertThat(audits).isEqualTo(1);
    }

    @Test
    void aStudentAsksForACampusTransferFromThePortal() throws Exception {
        JsonNode d = s.body(s.call(get("/api/registry/students/" + studentA), registrar, null));
        String regNo = d.at("/summary/registrationNo").asText();
        String token = s.body(s.call(post("/api/student-auth/login"), null, "{\"login\":\"" + regNo + "\",\"password\":\"Kifaru#Mto47\"}")
                .andExpect(status().isOk())).get("accessToken").asText();
        // the old programme is taught at both campuses now
        s.call(put("/api/academic/programmes/" + inst.programmeId() + "/campuses"), admin, "{\"campusIds\":[" + mainCampus + "," + arusha + "]}").andExpect(status().isOk());
        JsonNode options = s.body(s.call(get("/api/student/transfer-options"), token, null).andExpect(status().isOk()));
        assertThat(options.get("campuses")).hasSize(2);
        s.call(post("/api/student/transfers"), token, "{\"toCampusId\":" + arusha + ",\"toYearOfStudy\":2,\"reason\":\"Family moved\"}")
                .andExpect(jsonPath("$.code").value("YEAR_SET_BY_REGISTRY"));
        JsonNode t = s.body(s.call(post("/api/student/transfers"), token, "{\"toCampusId\":" + arusha + ",\"reason\":\"Family moved to Arusha\"}")
                .andExpect(status().isOk()));
        assertThat(t.get("kind").asText()).isEqualTo("CAMPUS");
        assertThat(t.get("requestedByStudent").asBoolean()).isTrue();
        // a campus transfer needs no Head of Department; the Deputy Principal decides it
        s.call(post("/api/registry/transfers/" + t.get("id").asLong() + "/recommend"), hodBus, "{}").andExpect(status().isNotFound()); // outside their department
        s.call(post("/api/registry/transfers/" + t.get("id").asLong() + "/decide"), chair, "{\"approve\":true}").andExpect(jsonPath("$.state").value("APPROVED"));
        assertThat(s.body(s.call(get("/api/student/transfers"), token, null)).get(0).get("state").asText()).isEqualTo("APPROVED");
        assertThat(s.body(s.call(get("/api/registry/students/" + studentA), registrar, null)).get("campus").asText()).isEqualTo("Arusha Campus");
        // the student can cancel only their own request while it waits
        JsonNode back = s.body(s.call(post("/api/student/transfers"), token, "{\"toCampusId\":" + mainCampus + ",\"reason\":\"Changed my mind\"}"));
        s.call(post("/api/student/transfers/" + back.get("id").asLong() + "/cancel"), token, null).andExpect(jsonPath("$.state").value("CANCELLED"));
    }

    @Test
    void feesFollowTheStudentsCategoryAndIntake() throws Exception {
        long evening = s.body(s.call(post("/api/academic/student-categories"), bursar, "{\"code\":\"EVENING\",\"name\":\"Evening\",\"active\":true}")
                .andExpect(status().isOk())).get("id").asLong();
        long tuition = s.body(s.call(post("/api/finance/fee-items"), accountant,
                "{\"code\":\"TUITION\",\"name\":\"Tuition\",\"category\":\"TUITION\",\"allocationPriority\":50,\"active\":true}")).get("id").asLong();
        long general = structure(tuition, 500000, null);
        long eve = structure(tuition, 350000, evening);
        s.call(post("/api/finance/fee-structures"), accountant, structureBody(tuition, 1, evening)).andExpect(jsonPath("$.code").value("DRAFT_EXISTS"));
        for (long id : new long[]{general, eve}) {
            s.call(post("/api/finance/fee-structures/" + id + "/approve"), bursar, null).andExpect(jsonPath("$.status").value("APPROVED"));
        }
        JsonNode list = s.body(s.call(get("/api/finance/fee-structures?academicYearId=" + yearId), accountant, null));
        assertThat(list.findValuesAsText("studentCategoryName")).contains("Evening");

        s.call(put("/api/registry/students/" + studentB + "/category"), registrar, "{\"studentCategoryId\":" + evening + "}")
                .andExpect(jsonPath("$.studentCategory").value("Evening"));
        s.call(post("/api/finance/billing-runs"), accountant, "{\"academicYearId\":" + yearId + "}").andExpect(status().isOk());
        BigDecimal totalA = TenantContext.callAs(inst.info(), () -> tx.execute(x -> jdbc.queryForObject(
                "select total_amount from invoices where payer_type = 'STUDENT' and payer_id = ? and purpose = 'ANNUAL_FEES'", BigDecimal.class, studentA)));
        BigDecimal totalB = TenantContext.callAs(inst.info(), () -> tx.execute(x -> jdbc.queryForObject(
                "select total_amount from invoices where payer_type = 'STUDENT' and payer_id = ? and purpose = 'ANNUAL_FEES'", BigDecimal.class, studentB)));
        assertThat(totalA).isEqualByComparingTo("500000");
        assertThat(totalB).isEqualByComparingTo("350000");
    }

    private long structure(long tuition, int amount, Long category) throws Exception {
        return s.body(s.call(post("/api/finance/fee-structures"), accountant, structureBody(tuition, amount, category)).andExpect(status().isOk())).get("id").asLong();
    }

    private String structureBody(long tuition, int amount, Long category) {
        return "{\"academicYearId\":" + yearId + ",\"programmeId\":" + inst.programmeId() + ",\"yearOfStudy\":1,"
                + (category == null ? "" : "\"studentCategoryId\":" + category + ",")
                + "\"lines\":[{\"feeItemId\":" + tuition + ",\"amount\":" + amount + ",\"instalment\":1}]}";
    }
}
