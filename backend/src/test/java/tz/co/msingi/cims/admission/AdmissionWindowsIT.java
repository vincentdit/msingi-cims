package tz.co.msingi.cims.admission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.msingi.cims.admission.domain.AdmissionRepositories.AdmissionWindowRepository;
import tz.co.msingi.cims.admission.service.AdmissionWindowService;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.support.AdmissionScenario;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** BP-01, FR-ADM-01: the Admissions Office and the administrator publish, open, close, reopen and extend admission windows. */
@IntegrationTest
class AdmissionWindowsIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;
    @Autowired AdmissionWindowRepository windows;
    @Autowired AdmissionWindowService windowService;
    @Autowired TransactionTemplate tx;

    AdmissionScenario s;
    TestData.Institution inst;
    String admissions, admin, registrar, viewer;
    long yearId, intakeId;

    @BeforeEach
    void setUp() throws Exception {
        String tenant = "W" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        inst = data.institution(tenant, BigDecimal.ZERO);
        data.staff(inst.tenant(), "admissions", Role.ADMISSIONS_OFFICER);
        data.staff(inst.tenant(), "admin", Role.INSTITUTION_ADMIN);
        data.staff(inst.tenant(), "registrar", Role.REGISTRAR);
        data.staff(inst.tenant(), "viewer", Role.MANAGEMENT_VIEWER);
        s = new AdmissionScenario(mvc, json, tenant, inst);
        admissions = s.staffToken("admissions");
        admin = s.staffToken("admin");
        registrar = s.staffToken("registrar");
        viewer = s.staffToken("viewer");
        JsonNode w = s.body(s.call(get("/api/admission/windows/" + inst.windowId()), admissions, null));
        yearId = w.get("academicYearId").asLong();
        intakeId = w.get("intakeId").asLong();
    }

    String windowJson(String name, OffsetDateTime opens, OffsetDateTime closes) {
        return "{\"academicYearId\":" + yearId + ",\"intakeId\":" + intakeId + ",\"name\":\"" + name + "\",\"opensAt\":\"" + opens
                + "\",\"closesAt\":\"" + closes + "\",\"applicationFee\":0,\"maxChoices\":3,\"offerAcceptanceDays\":14}";
    }

    long newWindow(String name, OffsetDateTime opens, OffsetDateTime closes) throws Exception {
        long id = s.body(s.call(post("/api/admission/windows"), admissions, windowJson(name, opens, closes)).andExpect(status().isCreated()))
                .get("id").asLong();
        s.call(post("/api/admission/windows/" + id + "/programmes"), admissions, "{\"programmeId\":" + inst.programmeId() + ",\"capacity\":40,"
                + "\"entryRules\":{\"summary\":\"Four CSEE passes\",\"pathways\":[{\"label\":\"CSEE\",\"conditions\":[{\"type\":\"CSEE_MIN_PASSES\",\"count\":4,\"minGrade\":\"D\"}]}]}}")
                .andExpect(status().isCreated());
        return id;
    }

    List<String> publicWindows(String path) throws Exception {
        return s.body(s.call(get(path), null, null)).findValuesAsText("name");
    }

    List<String> actions(long id) throws Exception {
        return s.body(s.call(get("/api/admission/windows/" + id + "/events"), viewer, null).andExpect(status().isOk())).findValuesAsText("action");
    }

    @Test
    void admissionsOfficeOpensClosesReopensAndExtendsWindows() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        long id = newWindow("March intake 2027", now.plusDays(10), now.plusDays(40));
        s.call(get("/api/admission/windows/" + id), admissions, null).andExpect(jsonPath("$.state").value("DRAFT"));

        // publishing a window that opens later: applicants see it as coming, not open
        s.call(post("/api/admission/windows/" + id + "/publish"), registrar, null).andExpect(status().isForbidden());
        s.call(post("/api/admission/windows/" + id + "/publish"), admissions, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("SCHEDULED")).andExpect(jsonPath("$.open").value(false));
        assertThat(publicWindows("/api/public/windows/upcoming")).contains("March intake 2027");
        assertThat(publicWindows("/api/public/windows")).doesNotContain("March intake 2027");

        // the Admissions Office opens it now
        s.call(post("/api/admission/windows/" + id + "/open"), registrar, null).andExpect(status().isForbidden());
        JsonNode opened = s.body(s.call(post("/api/admission/windows/" + id + "/open"), admissions, null).andExpect(status().isOk()));
        assertThat(opened.get("state").asText()).isEqualTo("OPEN");
        assertThat(OffsetDateTime.parse(opened.get("opensAt").asText())).isBefore(OffsetDateTime.now().plusSeconds(1));
        assertThat(publicWindows("/api/public/windows")).contains("March intake 2027");
        s.call(post("/api/admission/windows/" + id + "/open"), admissions, null).andExpect(jsonPath("$.code").value("ALREADY_OPEN"));

        // an open window keeps its opening date; closing dates must stay in the future
        s.call(put("/api/admission/windows/" + id), admissions, windowJson("March intake 2027", now.plusDays(2), now.plusDays(40)))
                .andExpect(jsonPath("$.code").value("ALREADY_OPEN"));
        s.call(put("/api/admission/windows/" + id), admissions, windowJson("March intake 2027", OffsetDateTime.parse(opened.get("opensAt").asText()), OffsetDateTime.parse(opened.get("opensAt").asText()).plusNanos(1_000_000)))
                .andExpect(jsonPath("$.code").value("CLOSING_DATE_PAST"));

        // extend it
        s.call(put("/api/admission/windows/" + id), admissions, windowJson("March intake 2027", OffsetDateTime.parse(opened.get("opensAt").asText()), now.plusDays(50)))
                .andExpect(status().isOk());

        // an applicant starts but does not submit
        String applicant = s.applicantToken();
        s.call(post("/api/applicant/applications"), applicant, "{\"windowId\":" + id + "}").andExpect(jsonPath("$.status").value("DRAFT"));

        // closing early needs a reason, tells applicants who have not submitted, and stops new applications
        s.call(post("/api/admission/windows/" + id + "/close"), registrar, "{\"reason\":\"x\"}").andExpect(status().isForbidden());
        s.call(post("/api/admission/windows/" + id + "/close"), admissions, "{\"reason\":\"\"}").andExpect(status().isBadRequest());
        JsonNode closed = s.body(s.call(post("/api/admission/windows/" + id + "/close"), admissions,
                "{\"reason\":\"All places are expected to be filled.\"}").andExpect(status().isOk()));
        assertThat(closed.get("state").asText()).isEqualTo("CLOSED");
        assertThat(OffsetDateTime.parse(closed.get("closesAt").asText())).isBefore(OffsetDateTime.now().plusSeconds(1));
        assertThat(publicWindows("/api/public/windows")).doesNotContain("March intake 2027");
        s.call(post("/api/applicant/applications"), s.applicantToken(), "{\"windowId\":" + id + "}").andExpect(jsonPath("$.code").value("WINDOW_NOT_OPEN"));
        s.call(post("/api/admission/windows/" + id + "/close"), admissions, "{\"reason\":\"again\"}").andExpect(jsonPath("$.code").value("WINDOW_CLOSED"));
        s.call(post("/api/admission/windows/" + id + "/open"), admissions, null).andExpect(jsonPath("$.code").value("WINDOW_CLOSED"));
        s.call(put("/api/admission/windows/" + id), admissions, windowJson("March intake 2027", now, now.plusDays(60)))
                .andExpect(jsonPath("$.code").value("WINDOW_CLOSED"));

        // reopening needs a reason and a future closing date; the administrator may do it too
        s.call(post("/api/admission/windows/" + id + "/reopen"), admin, "{\"closesAt\":\"" + now.plusDays(7) + "\"}").andExpect(status().isBadRequest());
        s.call(post("/api/admission/windows/" + id + "/reopen"), admin, "{\"closesAt\":\"" + now.minusDays(1) + "\",\"reason\":\"More places\"}")
                .andExpect(jsonPath("$.code").value("CLOSING_DATE_PAST"));
        s.call(post("/api/admission/windows/" + id + "/reopen"), admin, "{\"closesAt\":\"" + now.plusDays(7) + "\",\"reason\":\"NACTVET approved 20 more places\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("OPEN"));
        assertThat(publicWindows("/api/public/windows")).contains("March intake 2027");

        // the record of changes, newest first, with who did it and how many applicants were told
        JsonNode events = s.body(s.call(get("/api/admission/windows/" + id + "/events"), viewer, null));
        assertThat(events.findValuesAsText("action")).containsExactly("REOPENED", "CLOSED", "EXTENDED", "OPENED", "PUBLISHED", "CREATED");
        assertThat(events.get(0).get("reason").asText()).isEqualTo("NACTVET approved 20 more places");
        assertThat(events.get(0).get("applicantsNotified").asInt()).isEqualTo(1);
        assertThat(events.get(0).get("byName").asText()).isEqualTo("Staff admin");
        assertThat(events.get(1).get("applicantsNotified").asInt()).isEqualTo(1);
        assertThat(events.get(1).get("byName").asText()).isEqualTo("Staff admissions");
        List<String> sms = s.body(s.call(get("/api/messaging/messages?size=100"), viewer, null)).get("content").findValuesAsText("templateCode");
        assertThat(sms).contains("ADMISSION_WINDOW_CLOSED", "ADMISSION_WINDOW_REOPENED");

        // windows close by themselves when the closing date passes
        TenantContext.runAs(inst.tenant().toInfo(), () -> tx.executeWithoutResult(t -> windows.findById(id).ifPresent(w -> {
            w.setOpensAt(OffsetDateTime.now().minusDays(2));
            w.setClosesAt(OffsetDateTime.now().minusMinutes(1));
        })));
        s.call(get("/api/admission/windows/" + id), admissions, null).andExpect(jsonPath("$.state").value("CLOSED"));
        int n = TenantContext.callAs(inst.tenant().toInfo(), windowService::closeExpired);
        assertThat(n).isEqualTo(1);
        JsonNode auto = s.body(s.call(get("/api/admission/windows/" + id + "/events"), viewer, null)).get(0);
        assertThat(auto.get("action").asText()).isEqualTo("CLOSED");
        assertThat(auto.get("automatic").asBoolean()).isTrue();
        s.call(get("/api/admission/windows/" + id), admissions, null).andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void draftsCannotBeClosedAndSelectionStopsReopening() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        long id = newWindow("Draft window", now.plusDays(1), now.plusDays(20));
        s.call(post("/api/admission/windows/" + id + "/close"), admissions, "{\"reason\":\"No longer needed\"}").andExpect(jsonPath("$.code").value("INVALID_STATUS"));
        // a draft can be opened straight away
        s.call(post("/api/admission/windows/" + id + "/open"), admin, "{\"closesAt\":\"" + now.plusDays(30) + "\"}")
                .andExpect(jsonPath("$.state").value("OPEN"));
        assertThat(actions(id)).containsExactly("OPENED", "CREATED");

        // a published window with no programmes cannot be opened
        long empty = s.body(s.call(post("/api/admission/windows"), admissions, windowJson("Empty", now.plusDays(1), now.plusDays(20)))).get("id").asLong();
        s.call(post("/api/admission/windows/" + empty + "/open"), admissions, null).andExpect(jsonPath("$.code").value("NO_PROGRAMMES"));

        // once selection has started for a window it cannot take more applications
        s.apply("S0101/0001");
        s.call(post("/api/admission/windows/" + inst.windowId() + "/close"), admissions, "{\"reason\":\"Closing date brought forward\",\"notifyApplicants\":false}")
                .andExpect(status().isOk());
        s.call(post("/api/admission/windows/" + inst.windowId() + "/selection-rounds"), s.staffToken("officer"), null).andExpect(status().isOk());
        s.call(post("/api/admission/windows/" + inst.windowId() + "/reopen"), admissions, "{\"closesAt\":\"" + now.plusDays(7) + "\",\"reason\":\"Late applicants\"}")
                .andExpect(jsonPath("$.code").value("SELECTION_STARTED"));
    }

    @Test
    void entryRequirementsAndPlacesCanBeChangedUntilTheWindowCloses() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        long id = newWindow("Requirements window", now.plusDays(1), now.plusDays(20));
        long wp = s.body(s.call(get("/api/admission/windows/" + id), admissions, null)).get("programmes").get(0).get("id").asLong();
        String rules = "{\"programmeId\":" + inst.programmeId() + ",\"capacity\":60,\"entryRules\":{\"summary\":\"Four CSEE passes including Physics, or NVA Level 3\","
                + "\"pathways\":[{\"label\":\"CSEE\",\"conditions\":[{\"type\":\"CSEE_MIN_PASSES\",\"count\":4,\"minGrade\":\"D\"},"
                + "{\"type\":\"CSEE_SUBJECT\",\"count\":1,\"minGrade\":\"D\",\"subjects\":[\"PHYSICS\"]}]},"
                + "{\"label\":\"NVA holders\",\"conditions\":[{\"type\":\"PRIOR_AWARD\",\"family\":\"NVA\",\"minLevel\":3}]}]}}";
        s.call(put("/api/admission/windows/" + id + "/programmes/" + wp), admissions, rules).andExpect(status().isOk())
                .andExpect(jsonPath("$.capacity").value(60))
                .andExpect(jsonPath("$.requirementSummary").value("Four CSEE passes including Physics, or NVA Level 3"))
                .andExpect(jsonPath("$.entryRules.pathways.length()").value(2));
        // the summary applicants read cannot be left empty, and subject conditions need subjects
        s.call(put("/api/admission/windows/" + id + "/programmes/" + wp), admissions, rules.replace("Four CSEE passes including Physics, or NVA Level 3", " "))
                .andExpect(jsonPath("$.code").value("INVALID_RULE"));
        s.call(put("/api/admission/windows/" + id + "/programmes/" + wp), admissions, rules.replace("[\"PHYSICS\"]", "[]"))
                .andExpect(jsonPath("$.code").value("INVALID_RULE"));
        // only the admissions office changes them
        s.call(put("/api/admission/windows/" + id + "/programmes/" + wp), viewer, rules).andExpect(status().isForbidden());
    }
}
