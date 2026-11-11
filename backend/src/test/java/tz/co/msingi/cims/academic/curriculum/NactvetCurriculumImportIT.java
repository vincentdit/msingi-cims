package tz.co.msingi.cims.academic.curriculum;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.msingi.cims.academic.domain.Programme;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.support.AdmissionScenario;
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

/** FR-ACA-06: a programme's curriculum from NACTVET into its draft curriculum, approved by someone else. */
@IntegrationTest
class NactvetCurriculumImportIT {

    static final String ID = "1b6c88deba258d0f16439fc782e76a5d62a9d6c0";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    AdmissionScenario s;
    TestData.Institution inst;
    String tenant, hod, dpa, lecturer;
    long yearId;

    @BeforeEach
    void setUp() throws Exception {
        tenant = "C" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        inst = data.institution(tenant, BigDecimal.ZERO);
        data.staff(inst.tenant(), "hod", Role.HEAD_OF_DEPARTMENT);
        data.staff(inst.tenant(), "dpa", Role.DEPUTY_PRINCIPAL_ACADEMIC);
        data.staff(inst.tenant(), "lecturer", Role.LECTURER);
        s = new AdmissionScenario(mvc, json, tenant, inst);
        hod = s.staffToken("hod");
        dpa = s.staffToken("dpa");
        lecturer = s.staffToken("lecturer");
        yearId = s.body(s.call(get("/api/admission/windows/" + inst.windowId()), s.staffToken("officer"), null)).get("academicYearId").asLong();
    }

    private <T> T db(Supplier<T> work) {
        return TenantContext.callAs(inst.info(), () -> tx.execute(x -> work.get()));
    }

    @Test
    void importsTheCurriculumIntoADraft() throws Exception {
        String body = "{\"effectiveYearId\":" + yearId + ",\"intake\":\"SEPT\"}";
        s.call(post("/api/academic/programmes/" + inst.programmeId() + "/nactvet-curriculum"), lecturer, body).andExpect(status().isForbidden());
        s.call(get("/api/academic/nactvet-curricula"), hod, null).andExpect(jsonPath("$[0].levels[0]").value("NTA4"))
                .andExpect(jsonPath("$[0].nactvetProgrammeId").doesNotExist());
        s.call(post("/api/academic/programmes/" + inst.programmeId() + "/nactvet-curriculum"), hod, body).andExpect(jsonPath("$.code").value("NACTVET_ID_MISSING"));
        s.call(put("/api/academic/programmes/" + inst.programmeId() + "/nactvet-programme-id"), hod, "{\"nactvetProgrammeId\":\"bad id!\"}")
                .andExpect(jsonPath("$.code").value("NACTVET_ID_INVALID"));
        s.call(put("/api/academic/programmes/" + inst.programmeId() + "/nactvet-programme-id"), hod, "{\"nactvetProgrammeId\":\"" + ID + "\"}")
                .andExpect(jsonPath("$.nactvetProgrammeId").value(ID));
        s.call(post("/api/academic/programmes/" + inst.programmeId() + "/nactvet-curriculum"), hod, "{\"effectiveYearId\":" + yearId + ",\"intake\":\"JUNE\"}")
                .andExpect(jsonPath("$.code").value("INTAKE_INVALID"));

        // two semesters at NTA 4: six modules each (one elective), all new, in draft v1
        JsonNode r = s.body(s.call(post("/api/academic/programmes/" + inst.programmeId() + "/nactvet-curriculum"), hod, body).andExpect(status().isOk()));
        assertThat(r.get("outcome").asString()).isEqualTo("IMPORTED");
        assertThat(r.get("modulesCreated").asInt()).isEqualTo(12);
        assertThat(r.get("modulesPlaced").asInt()).isEqualTo(12);
        assertThat(r.get("curriculumVersion").asInt()).isEqualTo(1);
        assertThat(r.get("slots").size()).isEqualTo(2);
        assertThat(r.at("/slots/1/level").asString()).isEqualTo("NTA4");
        assertThat(r.get("notes").toString()).contains("electives");
        long cid = r.get("curriculumId").asLong();
        JsonNode cur = s.body(s.call(get("/api/academic/curricula/" + cid), hod, null));
        assertThat(cur.get("status").asString()).isEqualTo("DRAFT");
        assertThat(cur.get("notes").asString()).contains("From NACTVET curriculum S1B042025, 2027/2028, SEPT intake");
        assertThat(cur.get("modules").size()).isEqualTo(12);
        assertThat(db(() -> jdbc.queryForObject("select count(*) from modules where nta_level = 4 and credits > 0 and department_id is not null", Integer.class)))
                .isEqualTo(12);
        assertThat(db(() -> jdbc.queryForObject("select nactvet_curriculum_code from curricula where id = ?", String.class, cid))).isEqualTo("S1B042025");
        assertThat(db(() -> jdbc.queryForObject("select count(*) from integration_calls where system = 'NACTVET_CURRICULUM'", Integer.class))).isEqualTo(2);

        // again: the same draft is refreshed, no new modules
        JsonNode again = s.body(s.call(post("/api/academic/programmes/" + inst.programmeId() + "/nactvet-curriculum"), hod, body));
        assertThat(again.get("curriculumId").asLong()).isEqualTo(cid);
        assertThat(again.get("replacedDraft").asBoolean()).isTrue();
        assertThat(again.get("modulesCreated").asInt()).isZero();
        s.call(get("/api/academic/nactvet-curricula"), hod, null).andExpect(jsonPath("$[0].lastImport.outcome").value("IMPORTED"));

        // approval stays with someone else, once the electives are settled
        s.call(post("/api/academic/curricula/" + cid + "/approve"), dpa, null).andExpect(jsonPath("$.code").value("CURRICULUM_INCOMPLETE"));
    }

    @Test
    void ntaLevelsByYear() {
        Programme diploma = programme(6, 6, null);
        assertThat(NactvetCurriculumImporter.levels(diploma)).containsExactly("NTA4", "NTA5", "NTA6");
        assertThat(NactvetCurriculumImporter.levels(programme(5, 2, null))).containsExactly("NTA5");
        assertThat(NactvetCurriculumImporter.levels(programme(6, 2, (short) 6))).containsExactly("NTA6");
        assertThat(NactvetCurriculumImporter.levels(programme(8, 6, null))).containsExactly("NTA7", "NTA7", "NTA8");
    }

    private static Programme programme(int award, int semesters, Short entry) {
        Programme p = new Programme();
        p.setAwardFamily(Programme.AwardFamily.NTA);
        p.setAwardLevel((short) award);
        p.setDurationSemesters((short) semesters);
        p.setEntryLevelSet(entry);
        return p;
    }
}
