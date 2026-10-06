package tz.co.msingi.cims.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.ProgrammeRepository;
import tz.co.msingi.cims.academic.domain.Programme;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** NFR-SEC-05: no institution can read or change another institution's data. */
@IntegrationTest
class TenantIsolationIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;
    @Autowired ProgrammeRepository programmes;
    @Autowired TransactionTemplate tx;
    @PersistenceContext EntityManager em;

    @Test
    void repositoriesOnlySeeTheCurrentInstitution() {
        TestData.Institution a = data.institution(code("A"), BigDecimal.TEN);
        TestData.Institution b = data.institution(code("B"), BigDecimal.TEN);

        List<Programme> seenByA = TenantContext.callAs(a.info(), () -> tx.execute(s -> programmes.findAll()));
        assertThat(seenByA).extracting(Programme::getId).containsExactly(a.programmeId());

        // even a direct lookup by id of B's row returns nothing for A
        boolean visible = TenantContext.callAs(a.info(), () -> tx.execute(s -> programmes.findById(b.programmeId()).isPresent()));
        assertThat(visible).isFalse();

        // native SQL is filtered too (row-level security in PostgreSQL)
        Number count = TenantContext.callAs(a.info(), () -> tx.execute(s ->
                (Number) em.createNativeQuery("select count(*) from programmes").getSingleResult()));
        assertThat(count.longValue()).isEqualTo(1);
    }

    @Test
    void writingARowForAnotherInstitutionIsRejectedByTheDatabase() {
        TestData.Institution a = data.institution(code("A"), BigDecimal.TEN);
        TestData.Institution b = data.institution(code("B"), BigDecimal.TEN);
        assertThatThrownBy(() -> TenantContext.runAs(a.info(), () -> tx.executeWithoutResult(s ->
                em.createNativeQuery("insert into departments (tenant_id, code, name) values (:t, 'X', 'Smuggled')")
                        .setParameter("t", b.tenant().getId()).executeUpdate())))
                .hasMessageContaining("row-level security");
    }

    @Test
    void noTenantMeansNoRows() {
        data.institution(code("A"), BigDecimal.TEN);
        Number count = tx.execute(s -> (Number) em.createNativeQuery("select count(*) from user_accounts").getSingleResult());
        assertThat(count.longValue()).isZero();
    }

    @Test
    void tokenFromOneInstitutionIsRejectedByAnother() throws Exception {
        TestData.Institution a = data.institution(code("A"), BigDecimal.TEN);
        TestData.Institution b = data.institution(code("B"), BigDecimal.TEN);
        String tokenA = json.readTree(mvc.perform(post("/api/auth/login").header("X-Tenant", a.tenant().getCode())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"officer\",\"password\":\"" + TestData.STAFF_PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("accessToken").asText();

        mvc.perform(get("/api/academic/programmes").header("X-Tenant", a.tenant().getCode()).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/academic/programmes").header("X-Tenant", b.tenant().getCode()).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownInstitutionGets404() throws Exception {
        mvc.perform(get("/api/public/institution").header("X-Tenant", "NOSUCH"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNKNOWN_INSTITUTION"));
    }

    private static String code(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }
}
