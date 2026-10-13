package tz.co.msingi.cims.platform;

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

/** A Head of Department sees their department, a Programme Coordinator their programme (Document 03, data scope). */
@IntegrationTest
class DataScopeIT {

    static final String INSTRUCTIONS = """
            {"reportingDate":"2027-09-27","reportingTime":"08:00","reportingVenue":"Main Hall",
             "documentsToBring":["Original CSEE certificate"],"signatoryName":"Dr. Amina Salum","signatoryTitle":"Principal"}""";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    AdmissionScenario s;
    TestData.Institution inst;
    String registrar, hodIct, hodBus, coordBus, hodAll;
    long ictDept, busDept, btcit, btcac, ictStudent, busStudent;

    @BeforeEach
    void setUp() throws Exception {
        String tenant = "D" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        inst = data.institution(tenant, BigDecimal.ZERO);
        data.staff(inst.tenant(), "admin", Role.INSTITUTION_ADMIN);
        data.staff(inst.tenant(), "chair", Role.DEPUTY_PRINCIPAL_ACADEMIC);
        data.staff(inst.tenant(), "registrar", Role.REGISTRAR);
        data.staff(inst.tenant(), "hodall", Role.HEAD_OF_DEPARTMENT);
        s = new AdmissionScenario(mvc, json, tenant, inst);
        String admin = s.staffToken("admin"), officer = s.staffToken("officer");
        registrar = s.staffToken("registrar");
        hodAll = s.staffToken("hodall");

        btcit = inst.programmeId();
        ictDept = s.body(s.call(get("/api/academic/programmes/" + btcit), admin, null)).get("departmentId").asLong();
        busDept = s.body(s.call(post("/api/academic/departments"), admin, "{\"code\":\"BUS\",\"name\":\"Business Studies\",\"active\":true}")
                .andExpect(status().isCreated())).get("id").asLong();
        btcac = s.body(s.call(post("/api/academic/programmes"), admin, "{\"departmentId\":" + busDept + ",\"code\":\"BTCAC\",\"name\":\"Basic Technician Certificate in Accountancy\","
                + "\"awardFamily\":\"NTA\",\"awardLevel\":4,\"durationSemesters\":2,\"accreditedCapacity\":50,\"active\":true}")
                .andExpect(status().isCreated())).get("id").asLong();

        data.staffScoped(inst.tenant(), "hodict", Role.HEAD_OF_DEPARTMENT, "DEPARTMENT", ictDept);
        data.staffScoped(inst.tenant(), "hodbus", Role.HEAD_OF_DEPARTMENT, "DEPARTMENT", busDept);
        data.staffScoped(inst.tenant(), "coordbus", Role.PROGRAMME_COORDINATOR, "PROGRAMME", btcac);
        hodIct = s.staffToken("hodict");
        hodBus = s.staffToken("hodbus");
        coordBus = s.staffToken("coordbus");

        // two students handed over to Registry; one of them then studies Accountancy (Business Studies)
        s.call(put("/api/admission/windows/" + inst.windowId() + "/joining-instructions"), officer, INSTRUCTIONS).andExpect(status().isOk());
        Applicant a = s.apply("S0101/0001");
        Applicant b = s.applyEligibleEndingIn(5, 300);
        s.admit(officer, s.staffToken("chair"), a, b);
        s.call(post("/api/registry/windows/" + inst.windowId() + "/handover"), registrar, "{}").andExpect(status().isOk());
        JsonNode list = s.body(s.call(get("/api/registry/students"), registrar, null)).get("content");
        ictStudent = list.get(0).get("id").asLong();
        busStudent = list.get(1).get("id").asLong();
        TenantContext.runAs(inst.info(), () -> tx.executeWithoutResult(st ->
                jdbc.update("update students set programme_id = ? where id = ?", btcac, busStudent)));
    }

    long students(String token) throws Exception {
        return s.body(s.call(get("/api/registry/students"), token, null).andExpect(status().isOk())).get("totalElements").asLong();
    }

    @Test
    void headsOfDepartmentAndCoordinatorsSeeTheirOwnProgrammesAndStudents() throws Exception {
        assertThat(s.body(s.call(get("/api/academic/programmes"), hodBus, null)).findValuesAsText("code")).containsExactly("BTCAC");
        assertThat(s.body(s.call(get("/api/academic/programmes"), coordBus, null)).findValuesAsText("code")).containsExactly("BTCAC");
        assertThat(s.body(s.call(get("/api/academic/programmes"), hodIct, null)).findValuesAsText("code")).containsExactly("BTCIT");
        assertThat(s.body(s.call(get("/api/academic/programmes"), registrar, null)).findValuesAsText("code")).contains("BTCIT", "BTCAC");
        s.call(get("/api/academic/programmes/" + btcit), hodBus, null).andExpect(status().isForbidden());

        assertThat(students(registrar)).isEqualTo(2);
        assertThat(students(hodAll)).isEqualTo(2);
        assertThat(students(hodIct)).isEqualTo(1);
        assertThat(students(hodBus)).isEqualTo(1);
        assertThat(s.body(s.call(get("/api/registry/students"), hodBus, null)).get("content").get(0).get("id").asLong()).isEqualTo(busStudent);
        s.call(get("/api/registry/students/" + ictStudent), hodBus, null).andExpect(status().isForbidden());
        s.call(get("/api/registry/students/" + busStudent), hodBus, null).andExpect(status().isOk());
        s.call(get("/api/registry/students?programmeId=" + btcit), hodBus, null).andExpect(status().isForbidden());
        s.call(get("/api/registry/programmes/" + btcit + "/class-list"), hodBus, null).andExpect(status().isForbidden());

        long counted = 0;
        for (JsonNode v : s.body(s.call(get("/api/registry/students/stats"), hodBus, null))) counted += v.asLong();
        assertThat(counted).isEqualTo(1);
        s.call(get("/api/dashboard"), hodBus, null).andExpect(jsonPath("$.registry.current").value(1));
        s.call(get("/api/dashboard"), registrar, null).andExpect(jsonPath("$.registry.current").value(2));

        s.call(get("/api/auth/scope"), hodBus, null).andExpect(jsonPath("$.all").value(false))
                .andExpect(jsonPath("$.departments[0]").value("Business Studies"))
                .andExpect(jsonPath("$.programmes[0]").value("BTCAC · Basic Technician Certificate in Accountancy"));
        s.call(get("/api/auth/scope"), registrar, null).andExpect(jsonPath("$.all").value(true));
    }

    @Test
    void theyChangeOnlyTheirOwnDepartmentsModulesAndCurricula() throws Exception {
        String module = "{\"code\":\"%s\",\"title\":\"Principles of Accounting\",\"credits\":10,\"ntaLevel\":4,\"assessmentType\":\"GRADED\",\"departmentId\":%d,\"active\":true}";
        s.call(post("/api/academic/modules"), hodBus, module.formatted("ACC101", ictDept)).andExpect(status().isForbidden());
        s.call(post("/api/academic/modules"), hodBus, module.formatted("ACC101", busDept)).andExpect(status().isCreated());
        s.call(post("/api/academic/modules"), coordBus, module.formatted("ACC102", busDept)).andExpect(status().isCreated());
        long yearId = s.body(s.call(get("/api/admission/windows/" + inst.windowId()), s.staffToken("officer"), null)).get("academicYearId").asLong();
        String curriculum = "{\"programmeId\":%d,\"effectiveYearId\":" + yearId + ",\"modules\":[],\"electiveRules\":[]}";
        s.call(post("/api/academic/curricula"), hodBus, curriculum.formatted(btcit)).andExpect(status().isForbidden());
        s.call(post("/api/academic/curricula"), coordBus, curriculum.formatted(btcac)).andExpect(status().isCreated());
        assertThat(s.body(s.call(get("/api/academic/curricula"), hodIct, null)).findValuesAsText("programmeCode")).isEmpty();
        assertThat(s.body(s.call(get("/api/academic/curricula"), hodBus, null)).findValuesAsText("programmeCode")).containsExactly("BTCAC");
    }

    @Test
    void reportsAreForTheirOwnProgrammes() throws Exception {
        JsonNode catalogue = s.body(s.call(get("/api/reports"), hodBus, null));
        for (JsonNode r : catalogue) assertThat(r.get("filters").toString()).contains("programme");
        assertThat(s.body(s.call(get("/api/reports"), registrar, null)).size()).isGreaterThan(catalogue.size());
        // with one programme it is chosen for them; another department's programme is refused
        s.call(get("/api/reports/REG-02"), hodBus, null).andExpect(status().isOk()).andExpect(jsonPath("$.rowCount").value(1));
        s.call(get("/api/reports/REG-02?programmeId=" + btcit), hodBus, null).andExpect(status().isForbidden());
    }
}
