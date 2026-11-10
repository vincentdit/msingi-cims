package tz.co.msingi.cims.admission;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tz.co.msingi.cims.support.AdmissionScenario;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Additional and other qualifications, as in NOAS: NTA awards from NACTVET and other declared awards. */
@IntegrationTest
class OtherQualificationsIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;

    AdmissionScenario s;
    String token;

    @BeforeEach
    void setUp() throws Exception {
        String tenant = "Q" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        s = new AdmissionScenario(mvc, json, tenant, data.institution(tenant, BigDecimal.ZERO));
        token = s.applicantToken();
    }

    @Test
    void ntaAwardsComeFromNactvet() throws Exception {
        JsonNode nta = s.body(s.call(post("/api/applicant/qualifications/nta"), token,
                "{\"registrationNumber\":\"ns6123/0129/2019\",\"graduationYear\":2022}").andExpect(status().isCreated()));
        assertThat(nta.get("type").asString()).isEqualTo("NTA");
        assertThat(nta.get("indexNumber").asString()).isEqualTo("NS6123/0129/2019");
        assertThat(nta.get("awardLevel").asInt()).isEqualTo(6);
        assertThat(nta.get("awardName").asString()).isEqualTo("Ordinary Diploma in Information Technology");
        assertThat(nta.get("institution").asString()).isEqualTo("Simulated Technical Institute");
        assertThat(nta.get("gpa").isNumber()).isTrue();
        assertThat(nta.get("classAward").asString()).isNotBlank();
        assertThat(nta.get("verified").asBoolean()).isTrue();
        assertThat(nta.get("source").asString()).isEqualTo("NACTVET");
        assertThat(nta.get("subjects").size()).isEqualTo(3);

        s.call(post("/api/applicant/qualifications/nta"), token, "{\"registrationNumber\":\"NS6123/0129/2019\",\"graduationYear\":2022}")
                .andExpect(jsonPath("$.code").value("QUALIFICATION_EXISTS"));
        s.call(post("/api/applicant/qualifications/nta"), token, "{\"registrationNumber\":\"NS5123/0000/2019\",\"graduationYear\":2022}")
                .andExpect(jsonPath("$.code").value("NTA_AWARD_NOT_FOUND"));
        s.call(post("/api/applicant/qualifications/nta"), token, "{\"registrationNumber\":\"NS5123/9999/2019\",\"graduationYear\":2022}")
                .andExpect(jsonPath("$.code").value("NTA_AWARD_NAME_MISMATCH"))
                // the award holder's names (someone else's) are not shown to the applicant
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsStringIgnoringCase("SOMEONE"))))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("The names on this NTA award do not match your names from NECTA (")));
        s.call(post("/api/applicant/qualifications/nta"), token, "{\"registrationNumber\":\"NS5123/0001/2019\"}").andExpect(status().isBadRequest());

        // a verified NTA award counts for programmes that accept prior awards; it can be removed again
        s.call(delete("/api/applicant/qualifications/" + nta.get("id").asLong()), token, null).andExpect(status().isNoContent());
        assertThat(s.body(s.call(get("/api/applicant/qualifications"), token, null)).findValuesAsString("type")).containsExactly("CSEE");
    }

    @Test
    void otherAwardsAreDeclaredForTheOfficeToCheck() throws Exception {
        JsonNode other = s.body(s.call(post("/api/applicant/qualifications/other"), token, """
                {"awardName":"Certificate in Computer Applications","institution":"VETA Dodoma","year":2021,"reference":"cca/2021/77","level":"CERTIFICATE"}""")
                .andExpect(status().isCreated()));
        assertThat(other.get("type").asString()).isEqualTo("OTHER");
        assertThat(other.get("indexNumber").asString()).isEqualTo("CCA/2021/77");
        assertThat(other.get("otherLevel").asString()).isEqualTo("CERTIFICATE");
        assertThat(other.get("verified").asBoolean()).isFalse();
        assertThat(other.get("source").asString()).isEqualTo("MANUAL");

        // without a reference one is made up; the same reference cannot be added twice
        JsonNode noRef = s.body(s.call(post("/api/applicant/qualifications/other"), token, """
                {"awardName":"Short course in First Aid","institution":"Red Cross","year":2020,"level":"OTHER"}""").andExpect(status().isCreated()));
        assertThat(noRef.get("indexNumber").asString()).startsWith("OTH-");
        s.call(post("/api/applicant/qualifications/other"), token, """
                {"awardName":"Again","institution":"VETA Dodoma","year":2021,"reference":"CCA/2021/77","level":"CERTIFICATE"}""")
                .andExpect(jsonPath("$.code").value("QUALIFICATION_EXISTS"));
        s.call(post("/api/applicant/qualifications/other"), token, """
                {"awardName":"","institution":"X","year":2021,"level":"CERTIFICATE"}""").andExpect(status().isBadRequest());
        s.call(post("/api/applicant/qualifications/other"), token, """
                {"awardName":"X","institution":"X","year":2021,"level":"PHD"}""").andExpect(status().isBadRequest());
        assertThat(s.body(s.call(get("/api/applicant/qualifications"), token, null)).findValuesAsString("type")).containsExactly("CSEE", "OTHER", "OTHER");
    }
}
