package tz.co.msingi.cims.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.registry.status.StatusChangeService;
import tz.co.msingi.cims.support.AdmissionScenario;
import tz.co.msingi.cims.support.AdmissionScenario.Applicant;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The Registrar: student status changes (FR-REG-04, BP-14) and ID cards with photo and QR verification (FR-REG-06). */
@IntegrationTest
class RegistrarIT {

    static final String INSTRUCTIONS = """
            {"reportingDate":"2027-09-27","reportingTime":"08:00","reportingVenue":"Main Hall",
             "documentsToBring":["Original CSEE certificate"],"signatoryName":"Dr. Amina Salum","signatoryTitle":"Principal"}""";
    static final LocalDate TODAY = LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam"));

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired StatusChangeService statusChanges;

    AdmissionScenario s;
    TestData.Institution inst;
    String registrar, chair, hodIct, hodOther, viewer;
    long studentA, studentB;

    @BeforeEach
    void setUp() throws Exception {
        String tenant = "R" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        inst = data.institution(tenant, BigDecimal.ZERO);
        data.staff(inst.tenant(), "admin", Role.INSTITUTION_ADMIN);
        data.staff(inst.tenant(), "chair", Role.DEPUTY_PRINCIPAL_ACADEMIC);
        data.staff(inst.tenant(), "registrar", Role.REGISTRAR);
        data.staff(inst.tenant(), "viewer", Role.MANAGEMENT_VIEWER);
        s = new AdmissionScenario(mvc, json, tenant, inst);
        String admin = s.staffToken("admin"), officer = s.staffToken("officer");
        registrar = s.staffToken("registrar");
        chair = s.staffToken("chair");
        viewer = s.staffToken("viewer");
        long ictDept = s.body(s.call(get("/api/academic/programmes/" + inst.programmeId()), admin, null)).get("departmentId").asLong();
        long otherDept = s.body(s.call(post("/api/academic/departments"), admin, "{\"code\":\"BUS\",\"name\":\"Business Studies\",\"active\":true}")
                .andExpect(status().isCreated())).get("id").asLong();
        data.staffScoped(inst.tenant(), "hodict", Role.HEAD_OF_DEPARTMENT, "DEPARTMENT", ictDept);
        data.staffScoped(inst.tenant(), "hodbus", Role.HEAD_OF_DEPARTMENT, "DEPARTMENT", otherDept);
        hodIct = s.staffToken("hodict");
        hodOther = s.staffToken("hodbus");

        s.call(put("/api/admission/windows/" + inst.windowId() + "/joining-instructions"), officer, INSTRUCTIONS).andExpect(status().isOk());
        Applicant a = s.apply("S0101/0001");
        Applicant b = s.applyEligibleEndingIn(5, 300);
        s.admit(officer, chair, a, b);
        s.call(post("/api/registry/windows/" + inst.windowId() + "/handover"), registrar, "{}").andExpect(status().isOk());
        JsonNode list = s.body(s.call(get("/api/registry/students"), registrar, null)).get("content");
        studentA = list.get(0).get("id").asLong();
        studentB = list.get(1).get("id").asLong();
        TenantContext.runAs(inst.info(), () -> tx.executeWithoutResult(st ->
                jdbc.update("update students set status = 'ACTIVE' where id in (?, ?)", studentA, studentB)));
    }

    String studentStatus(long id) throws Exception {
        return s.body(s.call(get("/api/registry/students/" + id), registrar, null)).at("/summary/status").asText();
    }

    long sms(String code) {
        return TenantContext.callAs(inst.info(), () -> tx.execute(st ->
                jdbc.queryForObject("select count(*) from messages where template_code = ?", Long.class, code)));
    }

    @Test
    void aSuspensionIsRequestedRecommendedApprovedAndApplied() throws Exception {
        s.call(get("/api/registry/students/" + studentA + "/status-options"), registrar, null)
                .andExpect(jsonPath("$.currentStatus").value("ACTIVE")).andExpect(jsonPath("$.openRequest").value(false));

        // only Registry requests; the change must fit the student's status and dates
        String suspend = "{\"type\":\"SUSPENSION\",\"reason\":\"Disciplinary committee decision\",\"reference\":\"DC/12/2026\",\"effectiveDate\":\"" + TODAY + "\"}";
        s.call(post("/api/registry/students/" + studentA + "/status-changes"), hodIct, suspend).andExpect(status().isForbidden());
        s.call(post("/api/registry/students/" + studentA + "/status-changes"), registrar,
                "{\"type\":\"RESUMPTION\",\"reason\":\"x\",\"effectiveDate\":\"" + TODAY + "\"}").andExpect(jsonPath("$.code").value("NOT_ALLOWED_FROM_STATUS"));
        s.call(post("/api/registry/students/" + studentA + "/status-changes"), registrar,
                "{\"type\":\"POSTPONEMENT\",\"reason\":\"Medical\",\"effectiveDate\":\"" + TODAY + "\"}").andExpect(jsonPath("$.code").value("UNTIL_DATE_REQUIRED"));
        s.call(post("/api/registry/students/" + studentA + "/status-changes"), registrar,
                "{\"type\":\"WITHDRAWAL\",\"reason\":\"x\",\"effectiveDate\":\"" + TODAY.minusDays(200) + "\"}").andExpect(jsonPath("$.code").value("EFFECTIVE_DATE_TOO_EARLY"));
        JsonNode req = s.body(s.call(post("/api/registry/students/" + studentA + "/status-changes"), registrar, suspend).andExpect(status().isOk()));
        long id = req.get("id").asLong();
        assertThat(req.get("state").asText()).isEqualTo("REQUESTED");
        assertThat(req.get("toStatus").asText()).isEqualTo("SUSPENDED");
        s.call(post("/api/registry/students/" + studentA + "/status-changes"), registrar, suspend).andExpect(jsonPath("$.code").value("OPEN_REQUEST"));

        // the Head of the student's department recommends; another department's Head cannot see it
        JsonNode inbox = s.body(s.call(get("/api/approvals"), hodIct, null));
        assertThat(inbox.findValuesAsText("kind")).contains("STATUS_RECOMMEND");
        assertThat(s.body(s.call(get("/api/approvals"), hodOther, null)).findValuesAsText("kind")).doesNotContain("STATUS_RECOMMEND");
        s.call(post("/api/registry/status-changes/" + id + "/recommend"), hodOther, "{}").andExpect(status().isForbidden());
        assertThat(s.body(s.call(get("/api/registry/status-changes"), hodOther, null)).size()).isZero();
        s.call(post("/api/registry/status-changes/" + id + "/recommend"), hodIct, "{\"note\":\"Supported\"}")
                .andExpect(jsonPath("$.state").value("RECOMMENDED")).andExpect(jsonPath("$.recommendedByName").value("Staff hodict"));

        // the Deputy Principal decides; a rejection needs a reason; approval with today's date applies it now
        JsonNode decision = null;
        for (JsonNode i : s.body(s.call(get("/api/approvals"), chair, null)).get("items")) if (i.get("kind").asText().equals("STATUS_DECISION")) decision = i;
        assertThat(decision).isNotNull();
        assertThat(decision.get("canAct").asBoolean()).isTrue();
        s.call(post("/api/registry/status-changes/" + id + "/decision"), registrar, "{\"approve\":true}").andExpect(status().isForbidden());
        s.call(post("/api/registry/status-changes/" + id + "/decision"), chair, "{\"approve\":false}").andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
        long before = sms("STUDENT_STATUS_CHANGED");
        s.call(post("/api/registry/status-changes/" + id + "/decision"), chair, "{\"approve\":true,\"note\":\"Approved per DC minutes\"}")
                .andExpect(jsonPath("$.state").value("APPLIED")).andExpect(jsonPath("$.currentStatus").value("SUSPENDED"));
        assertThat(studentStatus(studentA)).isEqualTo("SUSPENDED");
        assertThat(sms("STUDENT_STATUS_CHANGED")).isEqualTo(before + 1);
        s.call(post("/api/registry/status-changes/" + id + "/cancel"), registrar, "{}").andExpect(jsonPath("$.code").value("INVALID_STATE"));

        // history is visible to those who follow the student; the audit trail has the change
        s.call(get("/api/registry/students/" + studentA + "/status-changes"), viewer, null).andExpect(jsonPath("$[0].state").value("APPLIED"));
        s.call(get("/api/audit/logs?action=STUDENT_STATUS_CHANGED"), s.staffToken("admin"), null).andExpect(status().isOk());

        // resumption from suspension is the way back
        s.call(get("/api/registry/students/" + studentA + "/status-options"), registrar, null).andExpect(jsonPath("$.allowed[0]").value("RESUMPTION"));
    }

    @Test
    void aFutureWithdrawalIsAppliedOnItsDateAndRevokesTheCard() throws Exception {
        s.call(photo(studentB, png(300, 400)), registrar, null).andExpect(status().isOk());
        s.call(post("/api/registry/students/" + studentB + "/id-card"), registrar, null).andExpect(jsonPath("$.current.status").value("VALID"));

        String withdraw = "{\"type\":\"WITHDRAWAL\",\"reason\":\"Student letter of withdrawal\",\"effectiveDate\":\"" + TODAY.plusDays(3) + "\"}";
        long id = s.body(s.call(post("/api/registry/students/" + studentB + "/status-changes"), registrar, withdraw)).get("id").asLong();
        // whoever requested cannot decide (an administrator who is also Registry)
        String admin = s.staffToken("admin");
        long cancelled = s.body(s.call(post("/api/registry/status-changes/" + id + "/cancel"), admin, "{\"note\":\"Wrong date\"}")
                .andExpect(jsonPath("$.state").value("CANCELLED"))).get("id").asLong();
        assertThat(cancelled).isEqualTo(id);
        id = s.body(s.call(post("/api/registry/students/" + studentB + "/status-changes"), admin, withdraw)).get("id").asLong();
        s.call(post("/api/registry/status-changes/" + id + "/decision"), admin, "{\"approve\":true}").andExpect(jsonPath("$.code").value("OWN_REQUEST"));

        // approved straight from the request (the Deputy Principal need not wait for the Head); applied on the date
        s.call(post("/api/registry/status-changes/" + id + "/decision"), chair, "{\"approve\":true}").andExpect(jsonPath("$.state").value("APPROVED"));
        assertThat(studentStatus(studentB)).isEqualTo("ACTIVE");
        assertThat(TenantContext.callAs(inst.info(), statusChanges::applyDue)).isZero();
        long changeId = id;
        TenantContext.runAs(inst.info(), () -> tx.executeWithoutResult(st ->
                jdbc.update("update student_status_changes set effective_date = ? where id = ?", TODAY, changeId)));
        assertThat(TenantContext.callAs(inst.info(), statusChanges::applyDue)).isEqualTo(1);
        assertThat(studentStatus(studentB)).isEqualTo("WITHDRAWN");

        JsonNode card = s.body(s.call(get("/api/registry/students/" + studentB + "/id-card"), registrar, null));
        assertThat(card.has("current") && !card.get("current").isNull()).isFalse();
        assertThat(card.at("/history/0/status").asText()).isEqualTo("REVOKED");
        assertThat(card.at("/history/0/endedReason").asText()).contains("Withdrawal");
        s.call(get("/api/public/verify/" + card.at("/history/0/verificationCode").asText()), null, null)
                .andExpect(jsonPath("$.documentType").value("STUDENT_ID_CARD")).andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.studentStatus").value("WITHDRAWN"));
    }

    @Test
    void idCardsNeedAPhotoAreReissuedWithAReasonAndVerifyPublicly() throws Exception {
        s.call(get("/api/registry/students/" + studentA + "/id-card"), registrar, null)
                .andExpect(jsonPath("$.canIssue").value(false)).andExpect(jsonPath("$.issueBlocker").value("Upload the student's photo first"));
        s.call(post("/api/registry/students/" + studentA + "/id-card"), registrar, null).andExpect(jsonPath("$.code").value("PHOTO_REQUIRED"));

        // photos: JPEG/PNG, upright, big enough; only Registry uploads
        s.call(photo(studentA, png(300, 400)), hodIct, null).andExpect(status().isForbidden());
        s.call(photo(studentA, png(120, 160)), registrar, null).andExpect(jsonPath("$.code").value("PHOTO_TOO_SMALL"));
        s.call(photo(studentA, png(600, 300)), registrar, null).andExpect(jsonPath("$.code").value("PHOTO_LANDSCAPE"));
        s.call(photo(studentA, "not an image".getBytes()), registrar, null).andExpect(jsonPath("$.code").value("PHOTO_TYPE"));
        s.call(photo(studentA, png(300, 400)), registrar, null).andExpect(jsonPath("$.hasPhoto").value(true)).andExpect(jsonPath("$.canIssue").value(true));
        s.call(get("/api/registry/students/" + studentA + "/photo"), hodIct, null).andExpect(header().string("Content-Type", "image/png"));

        // issue, print, then reissue (reason required): the old card is superseded
        JsonNode first = s.body(s.call(post("/api/registry/students/" + studentA + "/id-card"), registrar, null)).get("current");
        assertThat(first.get("cardNo").asText()).startsWith("ID" + TODAY.getYear() + "-");
        assertThat(LocalDate.parse(first.get("validUntil").asText())).isAfter(TODAY);
        byte[] pdf = s.call(get("/api/registry/students/" + studentA + "/id-card.pdf"), registrar, null)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        s.call(post("/api/registry/students/" + studentA + "/id-card"), registrar, null).andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
        JsonNode again = s.body(s.call(post("/api/registry/students/" + studentA + "/id-card"), registrar, "{\"reason\":\"Lost\"}"));
        assertThat(again.at("/current/cardNo").asText()).isNotEqualTo(first.get("cardNo").asText());
        assertThat(again.get("history").size()).isEqualTo(2);
        assertThat(again.at("/history/1/status").asText()).isEqualTo("REPLACED");

        String code = again.at("/current/verificationCode").asText();
        s.call(get("/api/public/verify/" + code), null, null)
                .andExpect(jsonPath("$.documentType").value("STUDENT_ID_CARD")).andExpect(jsonPath("$.status").value("VALID"))
                .andExpect(jsonPath("$.registrationNo").isNotEmpty()).andExpect(jsonPath("$.studentStatus").value("ACTIVE"));
        s.call(get("/api/public/verify/" + first.get("verificationCode").asText()), null, null)
                .andExpect(jsonPath("$.status").value("SUPERSEDED"));

        // batch: the other active student needs a photo first, then gets a card in the batch PDF
        s.call(get("/api/registry/id-cards/batch?programmeId=" + inst.programmeId()), registrar, null)
                .andExpect(jsonPath("$.ready").value(0)).andExpect(jsonPath("$.withoutPhoto").value(1)).andExpect(jsonPath("$.alreadyCarded").value(1));
        s.call(post("/api/registry/id-cards/batch"), registrar, "{\"programmeId\":" + inst.programmeId() + "}").andExpect(jsonPath("$.code").value("NOTHING_TO_ISSUE"));
        s.call(photo(studentB, png(300, 400)), registrar, null).andExpect(status().isOk());
        pdf = s.call(post("/api/registry/id-cards/batch"), registrar, "{\"programmeId\":" + inst.programmeId() + "}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        s.call(get("/api/registry/students/" + studentB + "/id-card"), registrar, null).andExpect(jsonPath("$.current.status").value("VALID"));

        // revoke
        long cardId = again.at("/current/id").asLong();
        s.call(post("/api/registry/students/" + studentA + "/id-cards/" + cardId + "/revoke"), registrar, "{\"reason\":\"Confiscated\"}")
                .andExpect(jsonPath("$.current").doesNotExist());
    }

    static byte[] png(int w, int h) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    MockMultipartHttpServletRequestBuilder photo(long studentId, byte[] bytes) {
        MockMultipartHttpServletRequestBuilder b = multipart("/api/registry/students/" + studentId + "/photo");
        b.file(new MockMultipartFile("file", "photo.png", "application/octet-stream", bytes));
        b.with(r -> { r.setMethod("PUT"); return r; });
        return b;
    }
}
