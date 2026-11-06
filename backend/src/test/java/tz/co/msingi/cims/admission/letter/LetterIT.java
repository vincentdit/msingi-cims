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

    @Test
    void lettersAreSignedByThePrincipalAndKeepTheSignatureTheyWereIssuedWith() throws Exception {
        String admin = officer;                           // the test institution's officer is also its administrator
        String clerk = s.staffToken("clerk");
        String base = "/api/admin/institution/principal";
        s.call(get(base), clerk, null).andExpect(status().isForbidden());
        s.call(get(base), admin, null).andExpect(status().isNoContent());
        s.call(mark("signature", scan()), admin, null).andExpect(jsonPath("$.code").value("PRINCIPAL_FIRST"));

        s.call(put(base), admin, "{\"name\":\"Dr. Neema Mushi\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Principal")).andExpect(jsonPath("$.signature").value(false));
        s.call(mark("signature", scan()), admin, null).andExpect(jsonPath("$.signature").value(true));
        s.call(mark("stamp", scan(300)), admin, null).andExpect(jsonPath("$.stamp").value(true))
                .andExpect(jsonPath("$.changeNote").value("Stamp uploaded"));
        // the signature is shown to the administrator only, never on the public photo address
        s.call(get(base + "/signature"), admin, null).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        s.call(get(base + "/signature"), clerk, null).andExpect(status().isForbidden());
        s.call(get("/api/public/institution/photos/signature"), null, null).andExpect(status().isNotFound());

        // the window's joining instructions still name someone else: the letter is signed by the Principal
        configure();
        Applicant a = acceptedApplicant("S0101/0001");
        String path = "/api/applicant/applications/" + a.applicationId() + "/admission-letter";
        byte[] first = s.call(get(path), a.token(), null).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (PDDocument doc = PDDocument.load(first)) {
            assertThat(new PDFTextStripper().getText(doc)).contains("Dr. Neema Mushi", "Principal").doesNotContain("Dr. Amina Salum");
            int images = 0;
            for (var n : doc.getPage(0).getResources().getXObjectNames()) if (doc.getPage(0).getResources().isImageXObject(n)) images++;
            assertThat(images).isGreaterThanOrEqualTo(3);   // signature, stamp, QR code
        }

        // a new Principal takes office: the signature is not carried over, the stamp is; the issued letter keeps its signatory
        s.call(put(base), admin, "{\"name\":\"Mr. Juma Hassan\",\"title\":\"Principal\"}")
                .andExpect(jsonPath("$.signature").value(false)).andExpect(jsonPath("$.stamp").value(true))
                .andExpect(jsonPath("$.changes").value(4));
        assertThat(s.body(s.call(get(base + "/history"), admin, null)).size()).isEqualTo(4);
        byte[] again = s.call(get(path), a.token(), null).andReturn().getResponse().getContentAsByteArray();
        try (PDDocument doc = PDDocument.load(again)) {
            assertThat(new PDFTextStripper().getText(doc)).contains("Dr. Neema Mushi").doesNotContain("Mr. Juma Hassan");
        }
        // a corrected letter is a new issue, signed by the Principal in office
        s.call(post("/api/admission/applications/" + a.applicationId() + "/admission-letter/reissue"), officer, "{\"reason\":\"Name corrected\"}")
                .andExpect(status().isOk());
        try (PDDocument doc = PDDocument.load(s.call(get(path), a.token(), null).andReturn().getResponse().getContentAsByteArray())) {
            assertThat(new PDFTextStripper().getText(doc)).contains("Mr. Juma Hassan").doesNotContain("Dr. Neema Mushi");
        }
        try (PDDocument doc = PDDocument.load(s.call(get(base + "/sample"), admin, null).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray())) {
            assertThat(new PDFTextStripper().getText(doc)).contains("Mr. Juma Hassan", "SAMPLE/ADM/2027/000001");
        }
        s.call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(base + "/stamp"), admin, null)
                .andExpect(jsonPath("$.stamp").value(false)).andExpect(jsonPath("$.changeNote").value("Stamp removed"));
    }

    // ------------------------------------------------------------------ helpers

    static byte[] scan() throws Exception {
        return scan(600);
    }

    static byte[] scan(int width) throws Exception {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(width, 240, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, width, 240);
        g.setColor(new java.awt.Color(20, 30, 90));
        g.fillOval(width / 6, 60, width * 2 / 3, 120);
        g.dispose();
        var out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    static org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder mark(String kind, byte[] bytes) {
        var b = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/admin/institution/principal/" + kind);
        b.file(new org.springframework.mock.web.MockMultipartFile("file", kind + ".jpg", "image/jpeg", bytes));
        b.with(r -> { r.setMethod("PUT"); return r; });
        return b;
    }

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
