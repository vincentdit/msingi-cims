package tz.co.msingi.cims.admission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The applicant's address chosen from Tanzania's areas (region → district → ward → village/street), impairment and next-of-kin details. */
@IntegrationTest
class ApplicantProfileIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;

    AdmissionScenario s;

    @BeforeEach
    void setUp() {
        String tenant = "P" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        s = new AdmissionScenario(mvc, json, tenant, data.institution(tenant, BigDecimal.ZERO));
    }

    String profile(String extra) {
        return """
                {"firstName":"x","surname":"y","sex":"F","dateOfBirth":"2006-05-01","nationality":"Tanzanian",
                 "nextOfKinName":"Mama","nextOfKinPhone":"0713000111",""" + extra + "}";
    }

    @Test
    void areasAreListedForThePickers() throws Exception {
        JsonNode regions = s.body(s.call(get("/api/public/geo/regions"), null, null).andExpect(status().isOk()));
        assertThat(regions.size()).isEqualTo(31);
        assertThat(json.convertValue(regions, String[].class)).contains("Arusha", "Dar es Salaam", "Mjini Magharibi");
        assertThat(json.convertValue(s.body(s.call(get("/api/public/geo/districts?region=dar es salaam"), null, null)), String[].class))
                .contains("Ilala", "Kinondoni", "Temeke", "Ubungo", "Kigamboni");
        assertThat(json.convertValue(s.body(s.call(get("/api/public/geo/wards?region=Arusha&district=Arumeru"), null, null)), String[].class))
                .contains("Akheri");
        assertThat(json.convertValue(s.body(s.call(get("/api/public/geo/streets?region=Arusha&district=Arumeru&ward=Akheri"), null, null)), String[].class))
                .contains("Duluti");
        assertThat(s.body(s.call(get("/api/public/geo/districts?region=Atlantis"), null, null)).size()).isZero();
        JsonNode options = s.body(s.call(get("/api/public/profile-options"), null, null));
        assertThat(json.convertValue(options.get("disabilities"), String[].class)).contains("Visual impairment", "Albinism");
        assertThat(json.convertValue(options.get("relationships"), String[].class)).contains("Mother", "Guardian");
    }

    @Test
    void addressImpairmentAndNextOfKinAreCheckedAndKept() throws Exception {
        String token = s.applicantToken();
        s.call(put("/api/applicant/profile"), token, profile("""
                "region":"arusha","district":"ARUMERU","ward":"akheri","street":"duluti","postalAddress":"P.O. Box 1, Arusha",
                "disability":"albinism","nextOfKinRelation":"guardian","nextOfKinEmail":"mama@example.com","nextOfKinAddress":"Plot 12, Mbezi",
                "nextOfKinRegion":"Dar es Salaam","nextOfKinDistrict":"Kinondoni\"""")).andExpect(status().isOk())
                .andExpect(jsonPath("$.region").value("Arusha"))
                .andExpect(jsonPath("$.district").value("Arumeru"))
                .andExpect(jsonPath("$.ward").value("Akheri"))
                .andExpect(jsonPath("$.street").value("Duluti"))
                .andExpect(jsonPath("$.disability").value("Albinism"))
                .andExpect(jsonPath("$.nextOfKinRelation").value("Guardian"))
                .andExpect(jsonPath("$.nextOfKinEmail").value("mama@example.com"))
                .andExpect(jsonPath("$.nextOfKinAddress").value("Plot 12, Mbezi"))
                .andExpect(jsonPath("$.nextOfKinRegion").value("Dar es Salaam"))
                .andExpect(jsonPath("$.nextOfKinDistrict").value("Kinondoni"));

        // a district of another region, a ward without a district, an impairment or relationship not on the list
        s.call(put("/api/applicant/profile"), token, profile("\"region\":\"Arusha\",\"district\":\"Ilala\""))
                .andExpect(jsonPath("$.code").value("INVALID_ADDRESS")).andExpect(jsonPath("$.detail").value("Ilala is not a district in Arusha"));
        s.call(put("/api/applicant/profile"), token, profile("\"region\":\"Arusha\",\"ward\":\"Akheri\""))
                .andExpect(jsonPath("$.code").value("INVALID_ADDRESS"));
        s.call(put("/api/applicant/profile"), token, profile("\"region\":\"Atlantis\"")).andExpect(jsonPath("$.code").value("INVALID_ADDRESS"));
        s.call(put("/api/applicant/profile"), token, profile("\"region\":\"Arusha\",\"nextOfKinRegion\":\"Arusha\",\"nextOfKinDistrict\":\"Temeke\""))
                .andExpect(jsonPath("$.detail").value("Next of kin: Temeke is not a district in Arusha"));
        s.call(put("/api/applicant/profile"), token, profile("\"region\":\"Arusha\",\"disability\":\"Tall\""))
                .andExpect(jsonPath("$.code").value("INVALID_DISABILITY"));
        s.call(put("/api/applicant/profile"), token, profile("\"region\":\"Arusha\",\"nextOfKinRelation\":\"Neighbour\""))
                .andExpect(jsonPath("$.code").value("INVALID_RELATIONSHIP"));
        s.call(put("/api/applicant/profile"), token, profile("\"region\":\"Arusha\",\"nextOfKinEmail\":\"not-an-email\""))
                .andExpect(status().isBadRequest());

        // clearing optional choices is allowed
        s.call(put("/api/applicant/profile"), token, profile("\"region\":\"Arusha\",\"disability\":\"\""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.district").doesNotExist()).andExpect(jsonPath("$.disability").doesNotExist());
    }

    @Test
    void educationHistoryUsesThePrimarySchoolListAndNectaSchools() throws Exception {
        JsonNode found = s.body(s.call(get("/api/public/schools/primary?q=bangata"), null, null).andExpect(status().isOk()));
        assertThat(found.findValuesAsText("name")).contains("BANGATA PRIMARY SCHOOL");
        assertThat(found.get(0).get("code").asText()).isEqualTo("PS0101001");
        assertThat(found.get(0).get("region").asText()).isEqualTo("ARUSHA");
        assertThat(s.body(s.call(get("/api/public/schools/primary?q=ps0101001"), null, null)).get(0).get("name").asText()).isEqualTo("BANGATA PRIMARY SCHOOL");
        assertThat(s.body(s.call(get("/api/public/schools/primary?q=b"), null, null)).size()).isZero();
        assertThat(s.body(s.call(get("/api/public/schools/primary?q=primary"), null, null)).size()).isEqualTo(25);

        // the O-level school comes with the Form Four results the applicant registered with
        String index = "S0909/0001";
        String school = s.lookup(index).get("centreName").asText();
        String token = s.applicantToken();
        s.call(get("/api/applicant/profile"), token, null)
                .andExpect(jsonPath("$.olevelSchool").value(school)).andExpect(jsonPath("$.olevelFromNecta").value(true))
                .andExpect(jsonPath("$.alevelFromNecta").value(false));

        // primary school from the list (by code) or typed; NECTA's O-level school cannot be overwritten; A-level typed
        s.call(put("/api/applicant/profile"), token, profile("""
                "region":"Arusha","primarySchool":"Bangata Primary School","primarySchoolCode":"ps0101001",
                "olevelSchool":"Somewhere Else","alevelSchool":"Ilboru secondary school\""""))
                .andExpect(jsonPath("$.primarySchool").value("BANGATA PRIMARY SCHOOL"))
                .andExpect(jsonPath("$.primarySchoolCode").value("PS0101001"))
                .andExpect(jsonPath("$.olevelSchool").value(school))
                .andExpect(jsonPath("$.alevelSchool").value("ILBORU SECONDARY SCHOOL"));
        s.call(put("/api/applicant/profile"), token, profile("\"region\":\"Arusha\",\"primarySchool\":\"Old Mission School\",\"primarySchoolCode\":\"PS0101001\""))
                .andExpect(jsonPath("$.primarySchool").value("Old Mission School"))
                .andExpect(jsonPath("$.primarySchoolCode").doesNotExist());

        // Form Six results from NECTA bring the A-level school; removing them lets the applicant type it again
        JsonNode acsee = s.body(s.call(post("/api/applicant/qualifications/necta"), token, "{\"type\":\"ACSEE\",\"indexNumber\":\"S0909/0101\",\"year\":2025}")
                .andExpect(status().isCreated()));
        s.call(get("/api/applicant/profile"), token, null)
                .andExpect(jsonPath("$.alevelSchool").value(acsee.get("centreName").asText())).andExpect(jsonPath("$.alevelFromNecta").value(true));
        s.call(delete("/api/applicant/qualifications/" + acsee.get("id").asLong()), token, null).andExpect(status().isNoContent());
        s.call(get("/api/applicant/profile"), token, null)
                .andExpect(jsonPath("$.alevelSchool").doesNotExist()).andExpect(jsonPath("$.alevelFromNecta").value(false));
    }
}
