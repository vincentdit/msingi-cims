package tz.co.msingi.cims.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.support.AdmissionScenario;
import tz.co.msingi.cims.support.AdmissionScenario.Applicant;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FR-STU-01/02 and FR-REG-08: student sign-in, dashboard, notices, fees and receipts, and requests to update details. */
@IntegrationTest
class StudentPortalIT {

    static final String INSTRUCTIONS = """
            {"reportingDate":"2027-09-27","reportingVenue":"Main Hall","documentsToBring":["Original CSEE certificate"],
             "signatoryName":"Dr. Amina Salum","signatoryTitle":"Principal"}""";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;

    AdmissionScenario s;
    String officer, chair, registrar, bursar, accountant, viewer;
    Applicant a, b;
    String aPhone, aRegNo, bRegNo;
    long aStudentId;

    @BeforeEach
    void setUp() throws Exception {
        String tenant = "P" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TestData.Institution inst = data.institution(tenant, BigDecimal.ZERO);
        data.staff(inst.tenant(), "chair", Role.DEPUTY_PRINCIPAL_ACADEMIC);
        data.staff(inst.tenant(), "registrar", Role.REGISTRAR);
        data.staff(inst.tenant(), "bursar", Role.BURSAR);
        data.staff(inst.tenant(), "accountant", Role.ACCOUNTANT);
        data.staff(inst.tenant(), "viewer", Role.MANAGEMENT_VIEWER);
        s = new AdmissionScenario(mvc, json, tenant, inst);
        officer = s.staffToken("officer");
        chair = s.staffToken("chair");
        registrar = s.staffToken("registrar");
        bursar = s.staffToken("bursar");
        accountant = s.staffToken("accountant");
        viewer = s.staffToken("viewer");
        s.call(put("/api/admission/windows/" + inst.windowId() + "/joining-instructions"), officer, INSTRUCTIONS).andExpect(status().isOk());
        long yearId = s.body(s.call(get("/api/admission/windows/" + inst.windowId()), officer, null)).get("academicYearId").asLong();
        approvedStructure(yearId);

        a = s.apply("S0101/0001");
        b = s.applyEligibleEndingIn(5, 400);
        s.admit(officer, chair, a, b);
        aPhone = s.body(s.call(get("/api/applicant/profile"), a.token(), null)).get("phone").asText();

        // an applicant who has not been handed over is not a student
        s.call(post("/api/student-auth/login"), null, login(aPhone, "Secret123")).andExpect(jsonPath("$.code").value("NOT_A_STUDENT"));

        JsonNode handover = s.body(s.call(post("/api/registry/windows/" + inst.windowId() + "/handover"), registrar, "{}").andExpect(status().isOk()));
        for (JsonNode st : handover.get("students")) {
            JsonNode detail = s.body(s.call(get("/api/registry/students/" + st.get("studentId").asLong()), registrar, null));
            if (detail.at("/summary/phone").asText().equals(aPhone)) {
                aStudentId = st.get("studentId").asLong();
                aRegNo = detail.at("/summary/registrationNo").asText();
            } else {
                bRegNo = detail.at("/summary/registrationNo").asText();
            }
        }
        assertThat(aRegNo).isNotNull();
    }

    @Test
    void studentsSignInWithTheirRegistrationNumberAndSeeTheirDashboard() throws Exception {
        s.call(post("/api/student-auth/login"), null, login(aRegNo, "wrong")).andExpect(status().isUnauthorized());
        String token = s.body(s.call(post("/api/student-auth/login"), null, login(aRegNo.toLowerCase(), "Secret123")).andExpect(status().isOk()))
                .get("accessToken").asText();
        s.call(post("/api/student-auth/login"), null, login("0" + aPhone.substring(3), "Secret123")).andExpect(status().isOk()); // local phone format

        JsonNode d = s.body(s.call(get("/api/student/dashboard"), token, null).andExpect(status().isOk()));
        assertThat(d.get("registrationNo").asText()).isEqualTo(aRegNo);
        assertThat(d.get("status").asText()).isEqualTo("ADMITTED");
        assertThat(d.get("firstRegistrationStatus").asText()).isEqualTo("PENDING");
        assertThat(d.get("balance").decimalValue()).isEqualByComparingTo("620000");
        assertThat(d.at("/nextPayment/controlNo").asText()).startsWith("99");
        assertThat(d.get("clearance").findValuesAsText("gate")).containsExactly("REGISTRATION", "EXAMINATION");
        assertThat(d.get("unreadNotices").asInt()).isZero();

        // the token issued before hand-over has no student role; staff are not students
        s.call(get("/api/student/dashboard"), a.token(), null).andExpect(status().isForbidden());
        s.call(get("/api/student/dashboard"), registrar, null).andExpect(status().isForbidden());
    }

    @Test
    void noticesReachTheirAudienceAndTrackReads() throws Exception {
        String token = studentToken(aRegNo);
        String bToken = studentToken(bRegNo);
        s.call(post("/api/notices"), viewer, notice("x", null, null)).andExpect(status().isForbidden());

        long forAll = s.body(s.call(post("/api/notices"), registrar, notice("Orientation week", null, null)).andExpect(status().isOk())).get("id").asLong();
        long year2 = s.body(s.call(post("/api/notices"), registrar, notice("Year 2 field trip", null, 2))).get("id").asLong();
        long later = s.body(s.call(post("/api/notices"), bursar, notice("Fees deadline", null, null))).get("id").asLong();
        long draft = s.body(s.call(post("/api/notices"), registrar, notice("Not yet", null, null))).get("id").asLong();

        JsonNode published = s.body(s.call(post("/api/notices/" + forAll + "/publish"), registrar, "{\"sendSms\":true}").andExpect(status().isOk()));
        assertThat(published.get("live").asBoolean()).isTrue();
        assertThat(published.get("smsSentCount").asInt()).isEqualTo(2);
        assertThat(published.get("audienceCount").asInt()).isEqualTo(2);
        s.call(post("/api/notices/" + year2 + "/publish"), registrar, "{}").andExpect(jsonPath("$.audienceCount").value(0));
        String tomorrow = OffsetDateTime.now().plusDays(1).toString();
        s.call(post("/api/notices/" + later + "/publish"), bursar, "{\"publishAt\":\"" + tomorrow + "\",\"sendSms\":true}")
                .andExpect(jsonPath("$.code").value("SMS_NEEDS_IMMEDIATE"));
        s.call(post("/api/notices/" + later + "/publish"), bursar, "{\"publishAt\":\"" + tomorrow + "\"}")
                .andExpect(jsonPath("$.status").value("PUBLISHED")).andExpect(jsonPath("$.live").value(false));
        s.call(post("/api/notices/" + forAll + "/publish"), registrar, "{}").andExpect(jsonPath("$.code").value("NOT_A_DRAFT"));

        // only the live notice for this student's year
        JsonNode mine = s.body(s.call(get("/api/student/notices"), token, null));
        assertThat(mine.findValuesAsText("title")).containsExactly("Orientation week");
        assertThat(mine.get(0).get("read").asBoolean()).isFalse();
        assertThat(s.body(s.call(get("/api/student/dashboard"), token, null)).get("unreadNotices").asInt()).isEqualTo(1);
        s.call(post("/api/student/notices/" + draft + "/read"), token, null).andExpect(status().isNotFound());
        s.call(post("/api/student/notices/" + forAll + "/read"), token, null).andExpect(jsonPath("$.read").value(true));
        s.call(post("/api/student/notices/" + forAll + "/read"), token, null).andExpect(status().isOk()); // again: no duplicate
        assertThat(s.body(s.call(get("/api/student/dashboard"), token, null)).get("unreadNotices").asInt()).isZero();
        assertThat(s.body(s.call(get("/api/student/notices"), bToken, null)).get(0).get("read").asBoolean()).isFalse(); // reads are per student

        JsonNode staffList = s.body(s.call(get("/api/notices"), viewer, null).andExpect(status().isOk()));
        assertThat(byId(staffList, forAll).get("readCount").asInt()).isEqualTo(1);
        assertThat(byId(staffList, year2).get("audience").asText()).isEqualTo("All students, year 2");

        s.call(post("/api/notices/" + forAll + "/withdraw"), registrar, null).andExpect(jsonPath("$.status").value("WITHDRAWN"));
        assertThat(s.body(s.call(get("/api/student/notices"), token, null))).isEmpty();
        s.call(put("/api/notices/" + forAll), registrar, notice("Again", null, null)).andExpect(jsonPath("$.code").value("NOTICE_WITHDRAWN"));
    }

    @Test
    void registryDecidesRequestsToUpdateDetails() throws Exception {
        String token = studentToken(aRegNo);
        String url = "/api/student/profile/change-requests";
        s.call(post(url), token, change("\"registrationNo\":\"X\"")).andExpect(jsonPath("$.code").value("FIELD_NOT_EDITABLE"));
        s.call(post(url), token, change("\"phone\":\"12345\"")).andExpect(jsonPath("$.code").value("INVALID_VALUE"));
        s.call(post(url), token, change("\"surname\":\"Applicant\"")).andExpect(jsonPath("$.code").value("NO_CHANGES"));
        s.call(post(url), token, "{\"changes\":{\"region\":\"Arusha\"},\"reason\":\" \"}").andExpect(status().isBadRequest());

        JsonNode r = s.body(s.call(post(url), token, change("\"surname\":\"Mushi\",\"phone\":\"0713 000 111\",\"region\":\"Arusha\"")).andExpect(status().isOk()));
        assertThat(r.get("status").asText()).isEqualTo("PENDING");
        assertThat(r.get("needsDocuments").asBoolean()).isTrue();
        assertThat(r.get("changes").findValuesAsText("to")).containsExactly("Mushi", "255713000111", "Arusha");
        s.call(post(url), token, change("\"region\":\"Mwanza\"")).andExpect(jsonPath("$.code").value("PENDING_REQUEST_EXISTS"));
        assertThat(s.body(s.call(get("/api/student/dashboard"), token, null)).at("/pendingRequest/id").asLong()).isEqualTo(r.get("id").asLong());

        long id = r.get("id").asLong();
        assertThat(s.body(s.call(get("/api/registry/change-requests"), viewer, null)).findValuesAsText("registrationNo")).containsExactly(aRegNo);
        assertThat(s.body(s.call(get("/api/registry/change-requests/count"), registrar, null)).get("pending").asInt()).isEqualTo(1);
        s.call(post("/api/registry/change-requests/" + id + "/approve"), viewer, "{}").andExpect(status().isForbidden());
        s.call(post("/api/registry/change-requests/" + id + "/reject"), registrar, "{\"note\":\"\"}").andExpect(status().isBadRequest());
        s.call(post("/api/registry/change-requests/" + id + "/approve"), registrar, "{\"note\":\"Birth certificate seen\"}")
                .andExpect(jsonPath("$.status").value("APPROVED")).andExpect(jsonPath("$.decidedByName").value("Staff registrar"));
        s.call(post("/api/registry/change-requests/" + id + "/approve"), registrar, "{}").andExpect(jsonPath("$.code").value("NOT_PENDING"));

        JsonNode profile = s.body(s.call(get("/api/student/profile"), token, null));
        assertThat(profile.get("surname").asText()).isEqualTo("Mushi");
        assertThat(profile.get("phone").asText()).isEqualTo("255713000111");
        assertThat(profile.get("requests").get(0).get("decisionNote").asText()).isEqualTo("Birth certificate seen");
        s.call(post("/api/student-auth/login"), null, login("0713000111", "Secret123")).andExpect(status().isOk()); // sign-in follows the new number
        assertThat(s.body(s.call(get("/api/registry/students/" + aStudentId), registrar, null)).at("/summary/fullName").asText()).endsWith("Mushi");

        // a request can be withdrawn by the student while pending, or turned down with a reason
        long second = s.body(s.call(post(url), token, change("\"district\":\"Meru\""))).get("id").asLong();
        s.call(post(url + "/" + second + "/cancel"), studentToken(bRegNo), null).andExpect(status().isNotFound());
        s.call(post(url + "/" + second + "/cancel"), token, null).andExpect(jsonPath("$.status").value("CANCELLED"));
        long third = s.body(s.call(post(url), token, change("\"district\":\"Meru\""))).get("id").asLong();
        s.call(post("/api/registry/change-requests/" + third + "/reject"), registrar, "{\"note\":\"Please bring a letter from the ward office\"}")
                .andExpect(jsonPath("$.status").value("REJECTED"));
        assertThat(s.body(s.call(get("/api/registry/change-requests?status=DECIDED"), registrar, null)).findValuesAsText("status"))
                .containsExactly("REJECTED", "APPROVED");
    }

    @Test
    void studentsPayAndDownloadTheirOwnReceipts() throws Exception {
        String token = studentToken(aRegNo);
        JsonNode account = s.body(s.call(get("/api/student/account"), token, null).andExpect(status().isOk()));
        long invoiceId = account.at("/invoices/0/id").asLong();
        account = s.body(s.call(post("/api/student/invoices/" + invoiceId + "/simulate-payment"), token, "{\"amount\":120500}").andExpect(status().isOk()));
        String receiptNo = account.at("/payments/0/receiptNo").asText();

        byte[] pdf = s.call(get("/api/student/receipt.pdf?no=" + receiptNo), token, null)
                .andExpect(status().isOk()).andExpect(content().contentType("application/pdf"))
                .andReturn().getResponse().getContentAsByteArray();
        try (PDDocument doc = PDDocument.load(pdf)) {
            String text = new PDFTextStripper().getText(doc).replaceAll("\\s+", " ");
            assertThat(text).contains("RECEIPT").contains(receiptNo).contains(aRegNo).contains("TZS 120,500")
                    .contains("Tanzanian shillings one hundred twenty thousand five hundred only").contains("Registration fee");
        }
        s.call(get("/api/applicant/receipt.pdf?no=" + receiptNo), a.token(), null).andExpect(status().isOk()); // the same account
        s.call(get("/api/student/receipt.pdf?no=" + receiptNo), studentToken(bRegNo), null).andExpect(status().isNotFound());
        s.call(get("/api/student/receipt.pdf?no=" + receiptNo), b.token(), null).andExpect(status().isForbidden());
        s.call(get("/api/student/account"), studentToken(bRegNo), null)
                .andExpect(jsonPath("$.payments").isEmpty()).andExpect(jsonPath("$.registrationNo").value(bRegNo));
        s.call(post("/api/student/invoices/" + invoiceId + "/control-number"), studentToken(bRegNo), null).andExpect(status().isNotFound());
        assertThat(s.body(s.call(get("/api/student/dashboard"), token, null)).get("paid").decimalValue()).isEqualByComparingTo("120500");
    }

    // ------------------------------------------------------------------ helpers

    private void approvedStructure(long yearId) throws Exception {
        long reg = item("REG", "Registration fee", "REGISTRATION", 10);
        long tuition = item("TUITION", "Tuition fee", "TUITION", 50);
        String structure = "{\"academicYearId\":" + yearId + ",\"programmeId\":" + s.inst.programmeId() + ",\"yearOfStudy\":1,\"lines\":["
                + "{\"feeItemId\":" + reg + ",\"amount\":20000,\"instalment\":1},{\"feeItemId\":" + tuition + ",\"amount\":600000,\"instalment\":1}]}";
        long id = s.body(s.call(post("/api/finance/fee-structures"), accountant, structure)).get("id").asLong();
        s.call(post("/api/finance/fee-structures/" + id + "/approve"), bursar, null).andExpect(status().isOk());
    }

    private long item(String code, String name, String category, int priority) throws Exception {
        return s.body(s.call(post("/api/finance/fee-items"), accountant,
                "{\"code\":\"" + code + "\",\"name\":\"" + name + "\",\"category\":\"" + category + "\",\"allocationPriority\":" + priority + ",\"active\":true}"))
                .get("id").asLong();
    }

    private String studentToken(String regNo) throws Exception {
        return s.body(s.call(post("/api/student-auth/login"), null, login(regNo, "Secret123")).andExpect(status().isOk())).get("accessToken").asText();
    }

    private static String login(String login, String password) {
        return "{\"login\":\"" + login + "\",\"password\":\"" + password + "\"}";
    }

    private String notice(String title, Long programmeId, Integer year) {
        return "{\"title\":\"" + title + "\",\"body\":\"Details of " + title + ".\",\"category\":\"GENERAL\",\"programmeId\":" + programmeId
                + ",\"yearOfStudy\":" + year + ",\"pinned\":false}";
    }

    private static String change(String fields) {
        return "{\"changes\":{" + fields + "},\"reason\":\"My details were entered wrongly\"}";
    }

    private static JsonNode byId(JsonNode list, long id) {
        for (JsonNode n : list) if (n.get("id").asLong() == id) return n;
        throw new AssertionError("no " + id);
    }
}
