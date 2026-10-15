package tz.co.msingi.cims.graduation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.msingi.cims.platform.links.PortalLinks;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.support.AdmissionScenario;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Alumni contacts and graduate tracer surveys (BR-31 / FR-GRD-05). */
@IntegrationTest
class AlumniIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    AdmissionScenario s;
    TestData.Institution inst;
    String tenant, registrar, admin, lecturer;

    @BeforeEach
    void setUp() throws Exception {
        tenant = "G" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        inst = data.institution(tenant, BigDecimal.ZERO);
        data.staff(inst.tenant(), "registrar", Role.REGISTRAR);
        data.staff(inst.tenant(), "admin", Role.INSTITUTION_ADMIN);
        data.staff(inst.tenant(), "lecturer", Role.LECTURER);
        s = new AdmissionScenario(mvc, json, tenant, inst);
        registrar = s.staffToken("registrar");
        admin = s.staffToken("admin");
        lecturer = s.staffToken("lecturer");
        // three former students brought in by migration; two have graduated
        String file = """
                registration_no,first_name,surname,sex,date_of_birth,phone,programme_code,entry_year,intake_code,year_of_study,status
                OLD/20/0001,Asha,Mwinyi,F,2001-03-14,0712000101,BTCIT,2020/2021,SEPTEMBER,2,GRADUATED
                OLD/20/0002,Baraka,Said,M,2000-07-01,0712000102,BTCIT,2020/2021,SEPTEMBER,2,GRADUATED
                OLD/20/0003,Neema,Kato,F,2001-01-01,0712000103,BTCIT,2020/2021,SEPTEMBER,2,DISCONTINUED
                """;
        var req = multipart("/api/imports/STUDENTS").file(new MockMultipartFile("file", "s.csv", "text/csv", file.getBytes(StandardCharsets.UTF_8)));
        req.header("X-Tenant", tenant).header("Authorization", "Bearer " + registrar);
        long batch = s.body(mvc.perform(req).andExpect(status().isCreated())).get("id").asLong();
        s.call(post("/api/imports/batches/" + batch + "/apply"), admin, null).andExpect(jsonPath("$.status").value("APPLIED"));
    }

    private <T> T db(Supplier<T> work) {
        return TenantContext.callAs(inst.info(), () -> tx.execute(x -> work.get()));
    }

    @Test
    void graduatesAreKeptInTouchWithAndAnswerTheTracerSurvey() throws Exception {
        JsonNode list = s.body(s.call(get("/api/alumni"), registrar, null).andExpect(status().isOk()));
        assertThat(list).hasSize(2);
        s.call(get("/api/alumni"), lecturer, null).andExpect(status().isForbidden());
        long asha = list.get(0).get("studentId").asLong();
        s.call(put("/api/alumni/" + asha), registrar, "{\"phone\":\"0789000111\",\"employmentStatus\":\"EMPLOYED\",\"employer\":\"TANESCO\",\"jobTitle\":\"ICT Technician\","
                + "\"sector\":\"Government / public service\",\"consentContact\":true}").andExpect(jsonPath("$.employer").value("TANESCO"))
                .andExpect(jsonPath("$.phone").value("255789000111"));
        s.call(get("/api/alumni?status=EMPLOYED"), registrar, null).andExpect(jsonPath("$.length()").value(1));
        String csv = s.text(s.call(get("/api/alumni/export"), registrar, null).andExpect(status().isOk()));
        assertThat(csv).contains("OLD/20/0001", "TANESCO");

        // a tracer survey of the programme's graduates: each gets a personal link by SMS (masked in the log)
        long id = s.body(s.call(post("/api/tracer-surveys"), registrar, "{\"title\":\"Tracer study 2026\",\"programmeId\":" + inst.programmeId() + "}")
                .andExpect(status().isCreated())).get("id").asLong();
        s.call(post("/api/tracer-surveys/" + id + "/open"), registrar, null).andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.invited").value(2)).andExpect(jsonPath("$.cohort").value(2));
        List<String> sms = db(() -> jdbc.queryForList("select phone || ' ' || body from messages where template_code = 'TRACER_SURVEY'", String.class));
        assertThat(sms).hasSize(2).allMatch(b -> b.contains("[personal link]")).anyMatch(b -> b.startsWith("255789000111"));

        // the graduate answers through the link (a known token is put in place of the one sent, which only its owner has)
        String token = "known-test-token-0123456789abcdef";
        db(() -> jdbc.update("update tracer_invitations set token_hash = ? where student_id = ?", PortalLinks.hash(token),
                jdbc.queryForObject("select id from students where registration_no = 'OLD/20/0002'", Long.class)));
        JsonNode pub = s.body(s.call(get("/api/public/tracer/" + token), null, null).andExpect(status().isOk()));
        assertThat(pub.get("graduateName").asText()).isEqualTo("Baraka Said");
        assertThat(pub.get("questions").size()).isGreaterThanOrEqualTo(5);
        s.call(post("/api/public/tracer/" + token), null, "{\"relevance\":\"4\"}").andExpect(jsonPath("$.code").value("ANSWER_REQUIRED"));
        s.call(post("/api/public/tracer/" + token), null, "{\"employment\":\"SELF_EMPLOYED\",\"relevance\":\"9\"}").andExpect(jsonPath("$.code").value("INVALID_ANSWER"));
        s.call(post("/api/public/tracer/" + token), null, "{\"employment\":\"SELF_EMPLOYED\",\"employer\":\"Said Computer Services\",\"relevance\":\"5\",\"recommend\":\"Yes\"}")
                .andExpect(jsonPath("$.submitted").value(true));
        s.call(post("/api/public/tracer/" + token), null, "{\"employment\":\"SEEKING\"}").andExpect(jsonPath("$.code").value("ALREADY_ANSWERED"));
        s.call(get("/api/public/tracer/not-a-real-token-at-all-xyz"), null, null).andExpect(status().isNotFound());

        JsonNode survey = s.body(s.call(get("/api/tracer-surveys/" + id), registrar, null));
        assertThat(survey.get("responded").asInt()).isEqualTo(1);
        assertThat(survey.get("employmentRate").asDouble()).isEqualTo(100.0);
        assertThat(survey.at("/distributions/relevance/5").asInt()).isEqualTo(1);
        // the alumni record follows the answer
        s.call(get("/api/alumni?q=said"), registrar, null).andExpect(jsonPath("$[0].employer").value("Said Computer Services"))
                .andExpect(jsonPath("$[0].updatedVia").value("SURVEY"));
        s.call(post("/api/tracer-surveys/" + id + "/close"), registrar, null).andExpect(jsonPath("$.status").value("CLOSED"));
    }
}
