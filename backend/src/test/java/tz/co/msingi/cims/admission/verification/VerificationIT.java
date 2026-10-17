package tz.co.msingi.cims.admission.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.ProgrammeRepository;
import tz.co.msingi.cims.admission.domain.AdmissionRepositories.ApplicationRepository;
import tz.co.msingi.cims.admission.domain.AdmissionRepositories.WindowProgrammeRepository;
import tz.co.msingi.cims.admission.offer.OfferService;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BP-04: submit selected applicants to NACTVET → record outcomes → resolve queries → offers →
 * acceptance (with multiple-admission confirmation), decline and lapse → released places go to the next round.
 */
@IntegrationTest
class VerificationIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;
    @Autowired WindowProgrammeRepository windowProgrammes;
    @Autowired ProgrammeRepository programmes;
    @Autowired ApplicationRepository applications;
    @Autowired OfferService offers;
    @Autowired TransactionTemplate tx;

    AdmissionScenario s;
    String officer;
    String chair;

    @BeforeEach
    void setUp() throws Exception {
        String tenant = "V" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TestData.Institution inst = data.institution(tenant, BigDecimal.ZERO);
        data.staff(inst.tenant(), "chair", Role.DEPUTY_PRINCIPAL_ACADEMIC);
        data.staff(inst.tenant(), "viewer", Role.MANAGEMENT_VIEWER);
        TenantContext.runAs(inst.info(), () -> tx.executeWithoutResult(t ->
                windowProgrammes.findById(inst.windowProgrammeId()).orElseThrow().setCapacity(5)));
        s = new AdmissionScenario(mvc, json, tenant, inst);
        officer = s.staffToken("officer");
        chair = s.staffToken("chair");
    }

    @Test
    void verificationAndOfferWorkflow() throws Exception {
        Applicant single = s.apply("S0101/0001");                 // verified
        Applicant multiple = s.applyEligibleEndingIn(3, 100);      // verified, admitted elsewhere too
        Applicant queried = s.applyEligibleEndingIn(7, 400);       // queried until resubmitted with remarks
        Applicant rejected = s.applyEligibleEndingIn(9, 700);      // rejected
        s.selectAndApprove(officer, chair);

        JsonNode overview = overview();
        assertThat(overview.at("/counts/SELECTED").asInt()).isEqualTo(4);
        assertThat(overview.get("ready")).hasSize(4);
        assertThat(overview.at("/ready/0/issues")).isEmpty();
        assertThat(overview.get("institutionNactvetNo").asText()).isEqualTo("REG/" + s.tenant + "/001");

        // submit by API: NACTVET needs the payment reference of the verification fee with every upload
        submit("API", null).andExpect(jsonPath("$.code").value("PAYMENT_REFERENCE_REQUIRED"));
        JsonNode batch = s.body(submit("API").andExpect(status().isOk()));
        long batchId = batch.get("id").asLong();
        assertThat(batch.get("batchNo").asInt()).isEqualTo(1);
        assertThat(batch.get("pending").asInt()).isEqualTo(4);
        // outcomes come from NACTVET's feedback for the intake's application year and intake (as NOAS pulls them)
        assertThat(batch.get("externalRef").asText()).matches("\\d{4}/(SEPTEMBER|MARCH)");
        assertThat(batch.get("paymentReference").asText()).isEqualTo("991100223344");
        submit("API").andExpect(jsonPath("$.code").value("NOTHING_TO_SUBMIT"));
        assertThat(statusOf(single)).isEqualTo("PENDING_VERIFICATION");

        // outcomes
        JsonNode result = s.body(s.call(post("/api/admission/nactvet-submissions/" + batchId + "/fetch-outcomes"), officer, null)
                .andExpect(status().isOk()));
        assertThat(result.get("verified").asInt()).isEqualTo(2);
        assertThat(result.get("queried").asInt()).isEqualTo(1);
        assertThat(result.get("rejected").asInt()).isEqualTo(1);
        assertThat(result.at("/submission/status").asText()).isEqualTo("COMPLETED");
        s.call(post("/api/admission/nactvet-submissions/" + batchId + "/fetch-outcomes"), officer, null)
                .andExpect(jsonPath("$.code").value("SUBMISSION_COMPLETE"));

        JsonNode singleApp = app(single);
        assertThat(singleApp.get("status").asText()).isEqualTo("OFFERED");
        assertThat(singleApp.at("/offer/verificationNo").asText()).startsWith("NV/");
        assertThat(singleApp.at("/offer/open").asBoolean()).isTrue();
        assertThat(singleApp.at("/offer/multipleAdmission").asBoolean()).isFalse();
        assertThat(app(rejected).get("status").asText()).isEqualTo("NACTVET_REJECTED");
        assertThat(app(rejected).at("/offer/rejectionReason").asText()).contains("minimum entry requirements");
        assertThat(app(queried).get("status").asText()).isEqualTo("QUERIED");
        assertThat(app(queried).hasNonNull("offer")).isFalse(); // queries are handled by the office, not shown to the applicant

        // resolve the query and resubmit: NACTVET verifies the corrected record
        s.call(post("/api/admission/applications/" + queried.applicationId() + "/resolve-query"), officer,
                "{\"remarks\":\"Name corrected to match the NECTA certificate\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SELECTED"));
        long batch2 = s.body(submit("API")).get("id").asLong();
        s.call(post("/api/admission/nactvet-submissions/" + batch2 + "/fetch-outcomes"), officer, null)
                .andExpect(jsonPath("$.verified").value(1));
        assertThat(statusOf(queried)).isEqualTo("OFFERED");

        // single admission: accept without a code
        accept(single, null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));
        accept(single, null).andExpect(jsonPath("$.code").value("NO_OPEN_OFFER"));

        // multiple admission: NACTVET's confirmation code is required
        accept(multiple, null).andExpect(jsonPath("$.code").value("CONFIRMATION_CODE_REQUIRED"));
        accept(multiple, "000000x").andExpect(jsonPath("$.code").value("INVALID_CONFIRMATION_CODE"));
        JsonNode multi = app(multiple);
        assertThat(multi.at("/offer/confirmationAttemptsLeft").asInt()).isEqualTo(4); // wrong attempt was recorded
        String code = multi.at("/offer/devConfirmationCode").asText();
        assertThat(code).hasSize(6);
        accept(multiple, code).andExpect(jsonPath("$.status").value("ACCEPTED"));

        // decline
        s.call(post("/api/applicant/applications/" + queried.applicationId() + "/offer/decline"), queried.token(),
                "{\"reason\":\"Joining another college\"}").andExpect(jsonPath("$.status").value("DECLINED"));

        overview = overview();
        assertThat(overview.at("/counts/ACCEPTED").asInt()).isEqualTo(2);
        assertThat(overview.at("/counts/DECLINED").asInt()).isEqualTo(1);
        assertThat(overview.at("/counts/NACTVET_REJECTED").asInt()).isEqualTo(1);
        assertThat(overview.get("acceptanceRate").asDouble()).isEqualTo(66.7);
        assertThat(overview.get("submissions")).hasSize(2);

        // only the two accepted applicants still hold places: 3 of 5 are available to the next round
        s.applyEligibleEndingIn(1, 900);
        JsonNode round2 = s.body(s.call(post("/api/admission/windows/" + s.inst.windowId() + "/selection-rounds"), officer, null));
        assertThat(round2.at("/programmes/0/availableThisRound").asInt()).isEqualTo(3);
    }

    @Test
    void fileSubmissionAndOutcomeImport() throws Exception {
        Applicant a = s.apply("S0101/0001");
        Applicant b = s.applyEligibleEndingIn(5, 200);
        s.selectAndApprove(officer, chair);
        String refA = app(a).get("referenceNo").asText();
        String refB = app(b).get("referenceNo").asText();

        JsonNode batch = s.body(submit("FILE").andExpect(status().isOk()));
        long id = batch.get("id").asLong();
        assertThat(batch.hasNonNull("externalRef")).isFalse();
        s.call(post("/api/admission/nactvet-submissions/" + id + "/fetch-outcomes"), officer, null)
                .andExpect(jsonPath("$.code").value("NOT_AN_API_SUBMISSION"));

        String file = s.text(s.call(get("/api/admission/nactvet-submissions/" + id + "/file"), officer, null).andExpect(status().isOk()));
        assertThat(file).contains("Application Ref", refA, refB, "NTA4-IT-" + s.tenant, "S0101/0001",
                "payment_reference_number", "impairement", "form_four_indexnumber", "next_kin_relation");
        String template = s.text(s.call(get("/api/admission/nactvet-submissions/" + id + "/file?kind=outcomes"), officer, null));
        assertThat(template).startsWith("﻿reference,index_number,outcome").contains(refA, refB);

        // a file with mistakes is rejected as a whole
        String bad = "reference,outcome,reason,verification_no\n" + refA + ",VERIFIED,,\nAPP/XYZ,REJECTED,Wrong,\n";
        s.call(post("/api/admission/nactvet-submissions/" + id + "/outcomes"), officer, csvBody(bad))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_FILE"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("verification_no is required")))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("APP/XYZ is not awaiting")));
        assertThat(statusOf(a)).isEqualTo("PENDING_VERIFICATION");

        // partial outcome file: B still pending at NACTVET
        String partial = "reference,index_number,outcome,reason,verification_no,multiple_admission\n"
                + refA + ",S0101/0001,VERIFIED,,NV/2026/123456,no\n" + refB + ",,,,,\n";
        s.call(post("/api/admission/nactvet-submissions/" + id + "/outcomes"), officer, csvBody(partial))
                .andExpect(jsonPath("$.applied").value(1))
                .andExpect(jsonPath("$.submission.status").value("SUBMITTED"));
        assertThat(app(a).at("/offer/verificationNo").asText()).isEqualTo("NV/2026/123456");

        s.call(post("/api/admission/nactvet-submissions/" + id + "/outcomes"), officer,
                        csvBody("reference,outcome,reason\n\"" + refB + "\",rejected,\"Age, below minimum\"\n"))
                .andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.submission.status").value("COMPLETED"));
        assertThat(app(b).at("/offer/rejectionReason").asText()).isEqualTo("Age, below minimum");
    }

    @Test
    void unansweredOfferLapsesAndReleasesThePlace() throws Exception {
        Applicant a = s.apply("S0101/0001");
        s.selectAndApprove(officer, chair);
        long id = s.body(submit("API")).get("id").asLong();
        s.call(post("/api/admission/nactvet-submissions/" + id + "/fetch-outcomes"), officer, null);

        TenantContext.runAs(s.inst.info(), () -> tx.executeWithoutResult(t ->
                applications.findById(a.applicationId()).orElseThrow().setOfferExpiresAt(OffsetDateTime.now().minusMinutes(1))));
        accept(a, null).andExpect(jsonPath("$.code").value("OFFER_EXPIRED"));
        assertThat(app(a).at("/offer/open").asBoolean()).isFalse();

        int lapsed = TenantContext.callAs(s.inst.info(), offers::lapseExpired);
        assertThat(lapsed).isEqualTo(1);
        assertThat(statusOf(a)).isEqualTo("OFFER_LAPSED");
        assertThat(overview().at("/counts/OFFER_LAPSED").asInt()).isEqualTo(1);
    }

    @Test
    void wrongConfirmationCodesLockTheOnlineAcceptance() throws Exception {
        Applicant a = s.applyEligibleEndingIn(3, 300);
        s.selectAndApprove(officer, chair);
        long id = s.body(submit("API")).get("id").asLong();
        s.call(post("/api/admission/nactvet-submissions/" + id + "/fetch-outcomes"), officer, null);
        String code = app(a).at("/offer/devConfirmationCode").asText();

        for (int i = 0; i < OfferService.MAX_CONFIRMATION_ATTEMPTS; i++) {
            accept(a, "999999".equals(code) ? "111111" : "999999").andExpect(jsonPath("$.code").value("INVALID_CONFIRMATION_CODE"));
        }
        accept(a, code).andExpect(jsonPath("$.code").value("CONFIRMATION_LOCKED"));
        assertThat(statusOf(a)).isEqualTo("OFFERED");
    }

    @Test
    void preValidationHoldsBackRecordsNactvetWouldQuery() throws Exception {
        s.apply("S0101/0001");
        s.selectAndApprove(officer, chair);
        TenantContext.runAs(s.inst.info(), () -> tx.executeWithoutResult(t ->
                programmes.findById(s.inst.programmeId()).orElseThrow().setNactvetCode(null)));

        assertThat(overview().at("/ready/0/issues/0").asText()).contains("has no NACTVET programme code");
        submit("API").andExpect(jsonPath("$.code").value("NOTHING_TO_SUBMIT"));
    }

    @Test
    void accessIsLimitedByRoleOwnershipAndInstitution() throws Exception {
        Applicant a = s.apply("S0101/0001");
        Applicant other = s.applyEligibleEndingIn(5, 500);
        s.selectAndApprove(officer, chair);
        String viewer = s.staffToken("viewer");

        s.call(get("/api/admission/windows/" + s.inst.windowId() + "/verification"), viewer, null).andExpect(status().isOk());
        s.call(post("/api/admission/windows/" + s.inst.windowId() + "/nactvet-submissions"), viewer, "{\"channel\":\"API\"}")
                .andExpect(status().isForbidden());
        s.call(get("/api/admission/windows/" + s.inst.windowId() + "/verification"), a.token(), null).andExpect(status().isForbidden());

        long id = s.body(submit("API")).get("id").asLong();
        s.call(post("/api/admission/nactvet-submissions/" + id + "/fetch-outcomes"), officer, null);
        // one applicant cannot act on another's offer
        s.call(post("/api/applicant/applications/" + other.applicationId() + "/offer/accept"), a.token(), "{}")
                .andExpect(status().isNotFound());

        // another institution cannot see this window's verification data
        String otherTenant = "W" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        data.institution(otherTenant, BigDecimal.ZERO);
        AdmissionScenario o = new AdmissionScenario(mvc, json, otherTenant, null);
        o.call(get("/api/admission/windows/" + s.inst.windowId() + "/verification"), o.staffToken("officer"), null)
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode overview() throws Exception {
        return s.body(s.call(get("/api/admission/windows/" + s.inst.windowId() + "/verification"), officer, null).andExpect(status().isOk()));
    }

    private org.springframework.test.web.servlet.ResultActions submit(String channel) throws Exception {
        return submit(channel, "API".equals(channel) ? "991100223344" : null);
    }

    private org.springframework.test.web.servlet.ResultActions submit(String channel, String paymentReference) throws Exception {
        return s.call(post("/api/admission/windows/" + s.inst.windowId() + "/nactvet-submissions"), officer, "{\"channel\":\"" + channel
                + "\"" + (paymentReference == null ? "" : ",\"paymentReference\":\"" + paymentReference + "\"") + "}");
    }

    private org.springframework.test.web.servlet.ResultActions accept(Applicant a, String code) throws Exception {
        return s.call(post("/api/applicant/applications/" + a.applicationId() + "/offer/accept"), a.token(),
                code == null ? "{}" : "{\"confirmationCode\":\"" + code + "\"}");
    }

    private JsonNode app(Applicant a) throws Exception {
        return s.body(s.call(get("/api/applicant/applications/" + a.applicationId()), a.token(), null).andExpect(status().isOk()));
    }

    private String statusOf(Applicant a) throws Exception {
        return app(a).get("status").asText();
    }

    private String csvBody(String csv) throws Exception {
        return json.writeValueAsString(java.util.Map.of("csv", csv));
    }
}
