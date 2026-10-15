package tz.co.msingi.cims.admission.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.msingi.cims.common.document.LetterWording;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.support.AdmissionScenario;
import tz.co.msingi.cims.support.AdmissionScenario.Applicant;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Documents applicants upload (FR-ADM-07) and the institution's letter wording (FR-ADM-S05). */
@IntegrationTest
class DocumentsAndLettersIT {

    static final String INSTRUCTIONS = """
            {"reportingDate":"2027-09-27","reportingTime":"08:00","reportingVenue":"Main Hall",
             "documentsToBring":["Original CSEE certificate"],"signatoryName":"Dr. Amina Salum","signatoryTitle":"Principal"}""";
    static final byte[] PDF = "%PDF-1.4\n% test document\n%%EOF".getBytes(StandardCharsets.US_ASCII);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    AdmissionScenario s;
    TestData.Institution inst;
    String officer, chair, admin;

    @BeforeEach
    void setUp() throws Exception {
        String tenant = "D" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        inst = data.institution(tenant, BigDecimal.ZERO);
        data.staff(inst.tenant(), "chair", Role.DEPUTY_PRINCIPAL_ACADEMIC);
        data.staff(inst.tenant(), "admin", Role.INSTITUTION_ADMIN);
        s = new AdmissionScenario(mvc, json, tenant, inst);
        officer = s.staffToken("officer");
        chair = s.staffToken("chair");
        admin = s.staffToken("admin");
    }

    private ResultActions upload(String token, long app, long type, String name, byte[] content) throws Exception {
        var req = multipart("/api/applicant/applications/" + app + "/documents/" + type).file(new MockMultipartFile("file", name, "application/octet-stream", content));
        req.with(r -> { r.setMethod("PUT"); return r; });
        req.header("X-Tenant", s.tenant).header("Authorization", "Bearer " + token);
        return mvc.perform(req);
    }

    @Test
    void requiredDocumentsAreUploadedCheckedAndReplacedWhenNotAccepted() throws Exception {
        long birth = s.body(s.call(post("/api/admission/windows/" + inst.windowId() + "/document-types"), officer,
                "{\"code\":\"BIRTH_CERT\",\"name\":\"Birth certificate\",\"required\":true,\"maxKb\":100,\"sortOrder\":1}")
                .andExpect(status().isCreated())).get("id").asLong();
        s.call(post("/api/admission/windows/" + inst.windowId() + "/document-types"), officer,
                "{\"code\":\"PHOTO\",\"name\":\"Passport photo\",\"required\":false,\"maxKb\":500,\"sortOrder\":2}").andExpect(status().isCreated());
        s.call(post("/api/admission/windows/" + inst.windowId() + "/document-types"), officer,
                "{\"code\":\"PHOTO\",\"name\":\"Again\",\"required\":false,\"maxKb\":500,\"sortOrder\":3}").andExpect(jsonPath("$.code").value("DUPLICATE_CODE"));

        String token = s.applicantToken("S0101/0001");
        s.call(put("/api/applicant/profile"), token, """
                {"firstName":"Test","surname":"Applicant","sex":"F","dateOfBirth":"2006-01-01","nationality":"Tanzanian",
                 "region":"Dodoma","nextOfKinName":"Parent","nextOfKinPhone":"0713000111"}""").andExpect(status().isOk());
        long app = s.body(s.call(post("/api/applicant/applications"), token, "{\"windowId\":" + inst.windowId() + "}")).get("id").asLong();
        s.call(put("/api/applicant/applications/" + app + "/choices"), token, "{\"windowProgrammeIds\":[" + inst.windowProgrammeId() + "]}").andExpect(status().isOk());
        s.call(post("/api/applicant/applications/" + app + "/submit"), token, null)
                .andExpect(jsonPath("$.code").value("NOT_READY")).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Upload Birth certificate")));

        // the file's own bytes decide its type, and the size limit holds
        upload(token, app, birth, "cert.pdf", "hello, not a pdf".getBytes()).andExpect(jsonPath("$.code").value("FILE_TYPE"));
        byte[] big = Arrays.copyOf(PDF, 101 * 1024);
        upload(token, app, birth, "cert.pdf", big).andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
        upload(token, app, birth, "../../My Birth <cert>.PDF", PDF).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UPLOADED")).andExpect(jsonPath("$.fileName").value("My Birth cert.pdf"))
                .andExpect(jsonPath("$.contentType").value("application/pdf"));
        s.call(post("/api/applicant/applications/" + app + "/submit"), token, null).andExpect(jsonPath("$.status").value("SUBMITTED"));

        // the office opens it and does not accept it; the applicant is told and uploads again
        JsonNode docs = s.body(s.call(get("/api/admission/applications/" + app + "/documents"), officer, null).andExpect(status().isOk()));
        long docId = docs.get(0).get("documentId").asLong();
        byte[] got = s.call(get("/api/admission/documents/" + docId + "/file"), officer, null).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf")).andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(got).isEqualTo(PDF);
        s.call(get("/api/admission/documents/" + docId + "/file"), token, null).andExpect(status().isForbidden());
        s.call(post("/api/admission/documents/" + docId + "/review"), officer, "{\"accept\":false}").andExpect(jsonPath("$.code").value("NOTE_REQUIRED"));
        s.call(post("/api/admission/documents/" + docId + "/review"), officer, "{\"accept\":false,\"note\":\"The scan is not readable\"}")
                .andExpect(jsonPath("$.status").value("REJECTED"));
        Map<String, Object> sms = TenantContext.callAs(inst.info(), () -> tx.execute(x -> jdbc.queryForMap(
                "select body from messages where template_code = 'DOCUMENT_NOT_ACCEPTED'")));
        assertThat((String) sms.get("body")).contains("Birth certificate", "The scan is not readable");
        s.call(get("/api/applicant/applications/" + app + "/documents"), token, null)
                .andExpect(jsonPath("$[0].status").value("REJECTED")).andExpect(jsonPath("$[0].canUpload").value(true));
        upload(token, app, birth, "cert2.pdf", PDF).andExpect(jsonPath("$.status").value("UPLOADED"));

        s.call(post("/api/admission/documents/" + docId + "/review"), officer, "{\"accept\":true}").andExpect(jsonPath("$.status").value("ACCEPTED"));
        s.call(get("/api/applicant/applications/" + app + "/documents"), token, null).andExpect(jsonPath("$[0].canUpload").value(false));
        upload(token, app, birth, "cert3.pdf", PDF).andExpect(jsonPath("$.code").value("DOCUMENTS_LOCKED"));
        s.call(delete("/api/admission/document-types/" + birth), officer, null).andExpect(jsonPath("$.code").value("IN_USE"));
        assertThat(s.body(s.call(get("/api/admission/applications/" + app), officer, null)).get("history").toString())
                .contains("Document uploaded", "Document not accepted", "Document accepted");
    }

    @Test
    void theInstitutionWritesItsOwnLetterWording() throws Exception {
        assertThat(LetterWording.html("Dear <b>{name}</b>\n\n- one **two**\n- three", Map.of("name", "A & B")))
                .isEqualTo("<p>Dear &#60;b&#62;A &#38; B&#60;/b&#62;</p><ul><li>one <strong>two</strong></li><li>three</li></ul>");
        JsonNode list = s.body(s.call(get("/api/letter-templates"), admin, null).andExpect(status().isOk()));
        assertThat(list).hasSize(5);
        assertThat(list.get(0).get("customised").asBoolean()).isFalse();
        // the payment notice follows how this institution collects fees (the test institution is public: GePG)
        assertThat(list.get(2).get("body").asText()).contains("GePG");

        s.call(put("/api/letter-templates/ADMISSION_LETTER_INTRO"), admin, "{\"body\":\"Hello {studentName}\"}")
                .andExpect(jsonPath("$.code").value("UNKNOWN_PLACEHOLDER"));
        String wording = "{\"body\":\"Karibu sana, {applicantName}! You join **{programmeName}** this {intake}.\"}";
        byte[] preview = s.call(post("/api/letter-templates/ADMISSION_LETTER_INTRO/preview"), admin, wording).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(preview, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        s.call(put("/api/letter-templates/ADMISSION_LETTER_INTRO"), admin, wording).andExpect(jsonPath("$.customised").value(true))
                .andExpect(jsonPath("$.revision").value(1));
        s.call(put("/api/letter-templates/IPT_LETTER_INTRO"), chair, wording).andExpect(status().isForbidden());

        // a letter issued now carries the new wording, and keeps it after the wording changes again
        s.call(put("/api/admission/windows/" + inst.windowId() + "/joining-instructions"), officer, INSTRUCTIONS).andExpect(status().isOk());
        Applicant a = s.apply("S0101/0001");
        Applicant b = s.applyEligibleEndingIn(5, 300);
        s.admit(officer, chair, a, b);
        s.call(delete("/api/letter-templates/ADMISSION_LETTER_INTRO"), admin, null).andExpect(jsonPath("$.customised").value(false));
        byte[] pdf = s.call(get("/api/applicant/applications/" + a.applicationId() + "/admission-letter"), a.token(), null)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (PDDocument doc = PDDocument.load(pdf)) {
            String text = new PDFTextStripper().getText(doc).replaceAll("\\s+", " ");
            assertThat(text).contains("Karibu sana", "this September").doesNotContain("We are pleased to inform you");
            assertThat(text).contains("Fees are paid only through a GePG control number");
        }
    }
}
