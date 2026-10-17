package tz.co.msingi.cims.admission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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

/** BP-02 end to end: NECTA look-up → register with the index number → verify → profile → fee via GePG → choices → submit. */
@IntegrationTest
class ApplicantJourneyIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;

    TestData.Institution inst;
    String tenant;

    @BeforeEach
    void setUp() {
        tenant = "J" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        inst = data.institution(tenant, new BigDecimal("10000"));
    }

    @Test
    void applicantCompletesAndSubmitsApplication() throws Exception {
        // the applicant types the Form Four index number and year; NECTA supplies the names to confirm
        JsonNode candidate = body(call(post("/api/applicant-auth/necta-lookup"), null, """
                {"indexNumber":"s0101-0002","examYear":2025}""").andExpect(status().isOk()));
        assertThat(candidate.get("indexNumber").asText()).isEqualTo("S0101/0002");
        assertThat(candidate.get("firstName").asText()).isNotBlank();
        assertThat(candidate.get("surname").asText()).isNotBlank();
        assertThat(candidate.get("sex").asText()).isIn("M", "F");
        assertThat(candidate.get("subjects").size()).isEqualTo(9);
        assertThat(candidate.get("alreadyRegistered").asBoolean()).isFalse();
        String fullName = candidate.get("fullName").asText();

        // names typed by the browser are not accepted: they come from NECTA again on registration
        JsonNode reg = body(call(post("/api/applicant-auth/register"), null, """
                {"indexNumber":"S0101/0002/2025","firstName":"Someone","surname":"Else","phone":"0755 123 456","password":"Kifaru#Mto47","acceptPrivacyNotice":true}""")
                .andExpect(status().isCreated()));
        assertThat(reg.get("fullName").asText()).isEqualTo(fullName);
        String otp = reg.get("devOtp").asText();

        call(post("/api/applicant-auth/login"), null, """
                {"login":"0755123456","password":"Kifaru#Mto47"}""")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_VERIFIED"));

        String token = body(call(post("/api/applicant-auth/verify"), null,
                "{\"phone\":\"+255755123456\",\"code\":\"" + otp + "\"}").andExpect(status().isOk()))
                .get("accessToken").asText();

        // the profile carries NECTA's names and sex, and the CSEE results are already there
        JsonNode profile = body(call(get("/api/applicant/profile"), token, null));
        assertThat(profile.get("fullName").asText()).isEqualTo(fullName);
        assertThat(profile.get("sex").asText()).isEqualTo(candidate.get("sex").asText());
        assertThat(profile.get("nectaIndexNumber").asText()).isEqualTo("S0101/0002");
        assertThat(profile.get("namesFromNecta").asBoolean()).isTrue();
        assertThat(profile.get("premNumber").asText()).matches("\\d{12}").isEqualTo(candidate.get("premNumber").asText());   // from NECTA
        JsonNode quals = body(call(get("/api/applicant/qualifications"), token, null));
        assertThat(quals.size()).isEqualTo(1);
        assertThat(quals.get(0).get("registration").asBoolean()).isTrue();
        assertThat(quals.get(0).get("verified").asBoolean()).isTrue();
        assertThat(quals.get(0).get("candidateName").asText()).isEqualTo(fullName);
        call(delete("/api/applicant/qualifications/" + quals.get(0).get("id").asLong()), token, null)
                .andExpect(jsonPath("$.code").value("QUALIFICATION_LOCKED"));

        // the applicant can also sign in with the index number
        call(post("/api/applicant-auth/login"), null, """
                {"login":"S0101/0002/2025","password":"Kifaru#Mto47"}""").andExpect(status().isOk());
        call(post("/api/applicant-auth/login"), null, """
                {"login":"s0101/0002","password":"Kifaru#Mto47"}""").andExpect(status().isOk());

        // weak results → not eligible
        JsonNode elig = body(call(get("/api/applicant/eligibility?windowId=" + inst.windowId()), token, null));
        assertThat(elig.get(0).get("eligible").asBoolean()).isFalse();

        // another sitting with strong results → eligible
        call(post("/api/applicant/qualifications/necta"), token, """
                {"type":"CSEE","indexNumber":"S0101/0001","year":2025}""").andExpect(status().isCreated());
        elig = body(call(get("/api/applicant/eligibility?windowId=" + inst.windowId()), token, null));
        assertThat(elig.get(0).get("eligible").asBoolean()).isTrue();

        // names stay as NECTA has them; the rest of the profile is the applicant's
        call(put("/api/applicant/profile"), token, """
                {"firstName":"Neema","surname":"Kweka","sex":"F","dateOfBirth":"2006-05-01","nationality":"Tanzanian",
                 "region":"Arusha","nextOfKinName":"Mama Kweka","nextOfKinPhone":"0713000111","nextOfKinRelation":"Mother"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value(fullName))
                .andExpect(jsonPath("$.region").value("Arusha"));

        // start application; choices are blocked until the fee is paid (BRU-01)
        Long appId = body(call(post("/api/applicant/applications"), token, "{\"windowId\":" + inst.windowId() + "}"))
                .get("id").asLong();
        call(put("/api/applicant/applications/" + appId + "/choices"), token,
                "{\"windowProgrammeIds\":[" + inst.windowProgrammeId() + "]}")
                .andExpect(jsonPath("$.code").value("FEE_NOT_PAID"));
        // what is still to do comes as codes the portal words in the applicant's language, and as plain English
        JsonNode started = body(call(get("/api/applicant/applications"), token, null)).get(0);
        assertThat(started.get("todo").findValuesAsText("code")).containsExactly("FEE", "CHOICE");
        assertThat(started.get("blockers").get(0).asText()).isEqualTo("Pay the application fee");

        JsonNode fee = body(call(post("/api/applicant/applications/" + appId + "/fee"), token, null));
        assertThat(fee.get("controlNo").asText()).startsWith("99").hasSize(12);
        assertThat(fee.get("feePaid").asBoolean()).isFalse();

        fee = body(call(post("/api/applicant/applications/" + appId + "/fee/simulate-payment"), token, null));
        assertThat(fee.get("feePaid").asBoolean()).isTrue();
        assertThat(fee.get("invoiceStatus").asText()).isEqualTo("PAID");

        call(put("/api/applicant/applications/" + appId + "/choices"), token,
                "{\"windowProgrammeIds\":[" + inst.windowProgrammeId() + "]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choices[0].eligible").value(true));

        JsonNode submitted = body(call(post("/api/applicant/applications/" + appId + "/submit"), token, null)
                .andExpect(status().isOk()));
        assertThat(submitted.get("status").asText()).isEqualTo("SUBMITTED");
        assertThat(submitted.get("referenceNo").asText()).isEqualTo(tenant + "/APP/2027/000001");

        // still open to changes while the window is open (see AmendAfterSubmissionIT)
        assertThat(submitted.get("amendable").asBoolean()).isTrue();

        // the Admissions Office sees it under NECTA's names
        String staff = body(call(post("/api/auth/login"), null,
                "{\"username\":\"officer\",\"password\":\"" + TestData.STAFF_PASSWORD + "\"}")).get("accessToken").asText();
        call(get("/api/admission/applications?status=SUBMITTED"), staff, null)
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].applicantName").value(fullName));

        // the index number now has an account
        call(post("/api/applicant-auth/necta-lookup"), null, """
                {"indexNumber":"S0101/0002","examYear":2025}""").andExpect(jsonPath("$.alreadyRegistered").value(true));
        call(post("/api/applicant-auth/register"), null, """
                {"indexNumber":"S0101/0002","examYear":2025,"phone":"0755 999 888","password":"Kifaru#Mto47","acceptPrivacyNotice":true}""")
                .andExpect(jsonPath("$.code").value("INDEX_REGISTERED"));
    }

    @Test
    void indexNumbersAreCheckedAtNecta() throws Exception {
        call(post("/api/applicant-auth/necta-lookup"), null, """
                {"indexNumber":"12345","examYear":2025}""").andExpect(jsonPath("$.code").value("INVALID_INDEX_NUMBER"));
        call(post("/api/applicant-auth/necta-lookup"), null, """
                {"indexNumber":"S0101/0001"}""").andExpect(jsonPath("$.code").value("EXAM_YEAR_REQUIRED"));
        call(post("/api/applicant-auth/necta-lookup"), null, """
                {"indexNumber":"S0101/0001","examYear":1980}""").andExpect(jsonPath("$.code").value("INVALID_EXAM_YEAR"));
        call(post("/api/applicant-auth/necta-lookup"), null, """
                {"indexNumber":"S0101/0000","examYear":2025}""").andExpect(jsonPath("$.code").value("NECTA_NOT_FOUND"));
        call(post("/api/applicant-auth/register"), null, """
                {"indexNumber":"S0101/0000","examYear":2025,"phone":"0688 444 555","password":"Kifaru#Mto47","acceptPrivacyNotice":true}""")
                .andExpect(jsonPath("$.code").value("NECTA_NOT_FOUND"));
    }

    @Test
    void anUnconfirmedRegistrationCanBeStartedAgain() throws Exception {
        // registered with a phone the candidate never confirmed (e.g. a typing mistake)
        call(post("/api/applicant-auth/register"), null, """
                {"indexNumber":"S0101/0007","examYear":2025,"phone":"0688 000 999","password":"Kifaru#Mto47","acceptPrivacyNotice":true}""")
                .andExpect(status().isCreated());
        JsonNode again = body(call(post("/api/applicant-auth/register"), null, """
                {"indexNumber":"S0101/0007","examYear":2025,"phone":"0688 000 998","password":"Kifaru#Mto47","acceptPrivacyNotice":true}""")
                .andExpect(status().isCreated()));
        String token = body(call(post("/api/applicant-auth/verify"), null,
                "{\"phone\":\"0688000998\",\"code\":\"" + again.get("devOtp").asText() + "\"}").andExpect(status().isOk())).get("accessToken").asText();
        assertThat(body(call(get("/api/applicant/qualifications"), token, null)).size()).isEqualTo(1);
        // the first number is free again
        call(post("/api/applicant-auth/login"), null, """
                {"login":"0688000999","password":"Kifaru#Mto47"}""").andExpect(status().isUnauthorized());
    }

    @Test
    void duplicatePhoneIsRejectedAndWrongOtpCounts() throws Exception {
        call(post("/api/applicant-auth/register"), null, """
                {"indexNumber":"S0101/0003","examYear":2025,"phone":"0688 000 111","password":"Kifaru#Mto47","acceptPrivacyNotice":true}""")
                .andExpect(status().isCreated());
        call(post("/api/applicant-auth/register"), null, """
                {"indexNumber":"S0101/0004","examYear":2025,"phone":"0688 000 111","password":"Kifaru#Mto47","acceptPrivacyNotice":true}""")
                .andExpect(jsonPath("$.code").value("PHONE_REGISTERED"));
        call(post("/api/applicant-auth/verify"), null, "{\"phone\":\"0688000111\",\"code\":\"000000\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OTP_INVALID"));
    }

    @Test
    void passwordsNeedUpperLowerDigitAndSpecialCharacter() throws Exception {
        for (String weak : new String[]{"Secret123", "Kifaru#7a", "kifaru#mto47", "KIFARU#MTO47", "Secret@abc", "Se@1"}) {
            call(post("/api/applicant-auth/register"), null, """
                    {"indexNumber":"S0101/0006","examYear":2025,"phone":"0688 222 444","password":"%s","acceptPrivacyNotice":true}""".formatted(weak))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.password").exists());
        }
        call(post("/api/applicant-auth/register"), null, """
                {"indexNumber":"S0101/0006","examYear":2025,"phone":"0688 222 444","password":"Kifaru#Mto47","acceptPrivacyNotice":true}""")
                .andExpect(status().isCreated());
    }

    @Test
    void privacyConsentIsRequired() throws Exception {
        call(post("/api/applicant-auth/register"), null, """
                {"indexNumber":"S0101/0005","examYear":2025,"phone":"0688 222 333","password":"Kifaru#Mto47","acceptPrivacyNotice":false}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.acceptPrivacyNotice").exists());
    }

    private ResultActions call(MockHttpServletRequestBuilder req, String token, String body) throws Exception {
        req.header("X-Tenant", tenant);
        if (token != null) req.header("Authorization", "Bearer " + token);
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(req);
    }

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }
}
