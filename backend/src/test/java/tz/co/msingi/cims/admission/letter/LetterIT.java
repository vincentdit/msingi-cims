package tz.co.msingi.cims.admission.letter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tz.co.msingi.cims.platform.security.Role;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FR-ADM-13 / BP-16: letter issued on acceptance, PDF with QR code, public verification, reissue and revoke. */
@IntegrationTest
class LetterIT {

    static final String INSTRUCTIONS = """
            {"reportingDate":"2027-09-27","reportingTime":"08:00","reportingVenue":"Main Hall",
             "registrationDeadline":"2027-10-08","documentsToBring":["Original CSEE certificate"],
             "itemsToBring":["Laboratory coat"],"feesNote":"Pay only with a GePG control number.",
             "notes":["Orientation runs in the first week."],"signatoryName":"Dr. Amina Salum","signatoryTitle":"Principal"}""";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;

    AdmissionScenario s;
    String officer;
    String chair;

    @BeforeEach
    void setUp() throws Exception {
        String tenant = "L" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TestData.Institution inst = data.institution(tenant, BigDecimal.ZERO);
        data.staff(inst.tenant(), "chair", Role.DEPUTY_PRINCIPAL_ACADEMIC);
        data.staff(inst.tenant(), "clerk", Role.ADMISSIONS_OFFICER);
        s = new AdmissionScenario(mvc, json, tenant, inst);
        officer = s.staffToken("officer");
        chair = s.staffToken("chair");
    }

    @Test
    void letterIsIssuedOnAcceptanceAndVerifiable() throws Exception {
        configure();
        Applicant a = acceptedApplicant("S0101/0001");

        JsonNode letter = app(a).get("letter");
        String holder = s.lookup("S0101/0001").get("fullName").asText();
        assertThat(letter.get("letterNo").asText()).isEqualTo(s.tenant + "/ADM/2027/000001");
        String code = letter.get("verificationCode").asText();
        assertThat(code).matches("[0-9A-Z]{4}(-[0-9A-Z]{4}){3}");

        // the applicant downloads the PDF: letter + joining instructions, QR code links to the verification page
        byte[] pdf = s.call(get("/api/applicant/applications/" + a.applicationId() + "/admission-letter"), a.token(), null)
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn().getResponse().getContentAsByteArray();
        try (PDDocument doc = PDDocument.load(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains(holder, "Basic Technician Certificate in IT (" + s.tenant + ")", code,
                    s.tenant + "/ADM/2027/000001", "Monday, 27 September 2027", "Main Hall", "Dr. Amina Salum", "Joining instructions");
            assertThat(text).containsPattern("NV/\\d{4}/\\d{6}");
            String qr = new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(
                    new BufferedImageLuminanceSource(new PDFRenderer(doc).renderImageWithDPI(0, 200))))).getText();
            assertThat(qr).endsWith("/verify/" + code);
        }

        // public verification: no sign-in, tolerant of how the code is typed
        s.call(get("/api/public/verify/" + code.toLowerCase().replace("-", " ")), null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALID"))
                .andExpect(jsonPath("$.holderName").value(holder))
                .andExpect(jsonPath("$.institution").value(s.tenant + " Technical College"))
                .andExpect(jsonPath("$.reportingDate").value("2027-09-27"))
                .andExpect(jsonPath("$.phone").doesNotExist());
        s.call(get("/api/public/verify/0000-0000-0000-0000"), null, null).andExpect(status().isNotFound());
        s.call(get("/api/public/verify/not-a-code"), null, null).andExpect(status().isNotFound());

        // another institution's site does not confirm this institution's letter
        String other = "M" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        data.institution(other, BigDecimal.ZERO);
        new AdmissionScenario(mvc, json, other, null).call(get("/api/public/verify/" + code), null, null).andExpect(status().isNotFound());

        // a corrected letter: new edition and code, the old code reports superseded
        JsonNode reissued = s.body(s.call(post("/api/admission/applications/" + a.applicationId() + "/admission-letter/reissue"), officer,
                "{\"reason\":\"Middle name added\"}").andExpect(status().isOk()));
        assertThat(reissued.get("edition").asInt()).isEqualTo(2);
        assertThat(reissued.get("letterNo").asText()).isEqualTo(s.tenant + "/ADM/2027/000001");
        String code2 = reissued.get("verificationCode").asText();
        assertThat(code2).isNotEqualTo(code);
        s.call(get("/api/public/verify/" + code), null, null)
                .andExpect(jsonPath("$.status").value("SUPERSEDED")).andExpect(jsonPath("$.currentEdition").value(2));
        assertThat(app(a).at("/letter/verificationCode").asText()).isEqualTo(code2);

        // revoke (senior role only)
        s.call(post("/api/admission/applications/" + a.applicationId() + "/admission-letter/revoke"), s.staffToken("clerk"),
                "{\"reason\":\"x\"}").andExpect(status().isForbidden());
        s.call(post("/api/admission/applications/" + a.applicationId() + "/admission-letter/revoke"), chair,
                "{\"reason\":\"Certificate found to be forged\"}").andExpect(jsonPath("$.status").value("REVOKED"));
        s.call(get("/api/public/verify/" + code2), null, null).andExpect(jsonPath("$.status").value("REVOKED"));
        s.call(get("/api/applicant/applications/" + a.applicationId() + "/admission-letter"), a.token(), null).andExpect(status().isNotFound());
    }

    @Test
    void lettersWaitForJoiningInstructionsThenAreIssuedInBulk() throws Exception {
        Applicant a = acceptedApplicant("S0101/0001");
        assertThat(app(a).get("status").asText()).isEqualTo("ACCEPTED"); // acceptance stands without a letter
        assertThat(app(a).hasNonNull("letter")).isFalse();
        JsonNode ov = s.body(s.call(get("/api/admission/windows/" + s.inst.windowId() + "/verification"), officer, null));
        assertThat(ov.get("joiningInstructionsSet").asBoolean()).isFalse();
        assertThat(ov.get("lettersPending").asInt()).isEqualTo(1);

        s.call(post("/api/admission/windows/" + s.inst.windowId() + "/admission-letters"), officer, null)
                .andExpect(jsonPath("$.code").value("LETTER_NOT_CONFIGURED"));
        configure();
        s.call(post("/api/admission/windows/" + s.inst.windowId() + "/admission-letters"), officer, null)
                .andExpect(jsonPath("$.issued").value(1));
        s.call(post("/api/admission/windows/" + s.inst.windowId() + "/admission-letters"), officer, null)
                .andExpect(jsonPath("$.code").value("NOTHING_TO_ISSUE"));
        assertThat(app(a).hasNonNull("letter")).isTrue();
        ov = s.body(s.call(get("/api/admission/windows/" + s.inst.windowId() + "/verification"), officer, null));
        assertThat(ov.get("lettersPending").asInt()).isZero();
        assertThat(ov.at("/applicants/0/letterNo").asText()).isEqualTo(s.tenant + "/ADM/2027/000001");

        // staff copy
        s.call(get("/api/admission/applications/" + a.applicationId() + "/admission-letter"), officer, null)
                .andExpect(status().isOk()).andExpect(header().string("Content-Type", "application/pdf"));
    }

    @Test
    void joiningInstructionsAreValidatedAndLettersArePrivate() throws Exception {
        s.call(put("/api/admission/windows/" + s.inst.windowId() + "/joining-instructions"), officer,
                "{\"reportingVenue\":\"Hall\",\"signatoryName\":\"X\",\"signatoryTitle\":\"Y\"}").andExpect(status().isBadRequest());
        s.call(put("/api/admission/windows/" + s.inst.windowId() + "/joining-instructions"), officer,
                INSTRUCTIONS.replace("2027-10-08", "2027-09-01")).andExpect(jsonPath("$.code").value("INVALID_DATES"));
        configure();
        s.call(get("/api/admission/windows/" + s.inst.windowId()), officer, null)
                .andExpect(jsonPath("$.joiningInstructions.reportingVenue").value("Main Hall"));

        Applicant a = acceptedApplicant("S0101/0001");
        Applicant b = s.applyEligibleEndingIn(5, 600);
        s.call(get("/api/applicant/applications/" + a.applicationId() + "/admission-letter"), b.token(), null).andExpect(status().isNotFound());
        s.call(get("/api/admission/applications/" + a.applicationId() + "/admission-letter"), a.token(), null).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ helpers

    private void configure() throws Exception {
        s.call(put("/api/admission/windows/" + s.inst.windowId() + "/joining-instructions"), officer, INSTRUCTIONS).andExpect(status().isOk());
    }

    /** Selected, verified by (simulated) NACTVET, and accepted. */
    private Applicant acceptedApplicant(String index) throws Exception {
        Applicant a = s.apply(index);
        s.selectAndApprove(officer, chair);
        long batch = s.body(s.call(post("/api/admission/windows/" + s.inst.windowId() + "/nactvet-submissions"), officer,
                "{\"channel\":\"API\",\"paymentReference\":\"991100223344\"}")).get("id").asLong();
        s.call(post("/api/admission/nactvet-submissions/" + batch + "/fetch-outcomes"), officer, null).andExpect(status().isOk());
        s.call(post("/api/applicant/applications/" + a.applicationId() + "/offer/accept"), a.token(), "{}")
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
        return a;
    }

    private JsonNode app(Applicant a) throws Exception {
        ResultActions r = s.call(get("/api/applicant/applications/" + a.applicationId()), a.token(), null).andExpect(status().isOk());
        return s.body(r);
    }
}
