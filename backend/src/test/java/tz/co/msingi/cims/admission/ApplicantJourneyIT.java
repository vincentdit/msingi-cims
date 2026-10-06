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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** BP-02 end to end: register → verify → NECTA → profile → fee via GePG → choices → submit. */
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
        // register and verify phone
        JsonNode reg = body(call(post("/api/applicant-auth/register"), null, """
                {"firstName":"Neema","surname":"Kweka","phone":"0755 123 456","password":"Secret123","acceptPrivacyNotice":true}""")
                .andExpect(status().isCreated()));
        String otp = reg.get("devOtp").asText();

        call(post("/api/applicant-auth/login"), null, """
                {"login":"0755123456","password":"Secret123"}""")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_VERIFIED"));

        String token = body(call(post("/api/applicant-auth/verify"), null,
                "{\"phone\":\"+255755123456\",\"code\":\"" + otp + "\"}").andExpect(status().isOk()))
                .get("accessToken").asText();

        // weak results first → not eligible
        call(post("/api/applicant/qualifications/necta"), token, """
                {"type":"CSEE","indexNumber":"S0101/0002","year":2024}""").andExpect(status().isCreated());
        JsonNode elig = body(call(get("/api/applicant/eligibility?windowId=" + inst.windowId()), token, null));
        assertThat(elig.get(0).get("eligible").asBoolean()).isFalse();

        // strong results → eligible
        call(post("/api/applicant/qualifications/necta"), token, """
                {"type":"CSEE","indexNumber":"S0101/0001","year":2025}""").andExpect(status().isCreated());
        elig = body(call(get("/api/applicant/eligibility?windowId=" + inst.windowId()), token, null));
        assertThat(elig.get(0).get("eligible").asBoolean()).isTrue();

        call(put("/api/applicant/profile"), token, """
                {"firstName":"Neema","surname":"Kweka","sex":"F","dateOfBirth":"2006-05-01","nationality":"Tanzanian",
                 "region":"Arusha","nextOfKinName":"Mama Kweka","nextOfKinPhone":"0713000111","nextOfKinRelation":"Mother"}""")
                .andExpect(status().isOk());

        // start application; choices are blocked until the fee is paid (BRU-01)
        Long appId = body(call(post("/api/applicant/applications"), token, "{\"windowId\":" + inst.windowId() + "}"))
                .get("id").asLong();
        call(put("/api/applicant/applications/" + appId + "/choices"), token,
                "{\"windowProgrammeIds\":[" + inst.windowProgrammeId() + "]}")
                .andExpect(jsonPath("$.code").value("FEE_NOT_PAID"));

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

        // locked after submission
        call(put("/api/applicant/applications/" + appId + "/choices"), token,
                "{\"windowProgrammeIds\":[" + inst.windowProgrammeId() + "]}")
                .andExpect(jsonPath("$.code").value("APPLICATION_LOCKED"));

        // the Admissions Office sees it
        String staff = body(call(post("/api/auth/login"), null,
                "{\"username\":\"officer\",\"password\":\"" + TestData.STAFF_PASSWORD + "\"}")).get("accessToken").asText();
        call(get("/api/admission/applications?status=SUBMITTED"), staff, null)
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].applicantName").value("Neema Kweka"));
    }

    @Test
    void duplicatePhoneIsRejectedAndWrongOtpCounts() throws Exception {
        String body = """
                {"firstName":"Ali","surname":"Said","phone":"0688 000 111","password":"Secret123","acceptPrivacyNotice":true}""";
        call(post("/api/applicant-auth/register"), null, body).andExpect(status().isCreated());
        call(post("/api/applicant-auth/register"), null, body)
                .andExpect(jsonPath("$.code").value("PHONE_REGISTERED"));
        call(post("/api/applicant-auth/verify"), null, "{\"phone\":\"0688000111\",\"code\":\"000000\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OTP_INVALID"));
    }

    @Test
    void privacyConsentIsRequired() throws Exception {
        call(post("/api/applicant-auth/register"), null, """
                {"firstName":"Ali","surname":"Said","phone":"0688 222 333","password":"Secret123","acceptPrivacyNotice":false}""")
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
