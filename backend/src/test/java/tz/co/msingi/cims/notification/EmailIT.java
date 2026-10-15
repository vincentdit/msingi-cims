package tz.co.msingi.cims.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.support.AdmissionScenario;
import tz.co.msingi.cims.support.AdmissionScenario.Applicant;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Email: verified addresses, event emails, admission letters by email and scheduled reports (BR-33, FR-ADM-02/13, FR-RPT-03). */
@IntegrationTest
class EmailIT {

    static final String INSTRUCTIONS = """
            {"reportingDate":"2027-09-27","reportingTime":"08:00","reportingVenue":"Main Hall",
             "documentsToBring":["Original CSEE certificate"],"signatoryName":"Dr. Amina Salum","signatoryTitle":"Principal"}""";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    AdmissionScenario s;
    TestData.Institution inst;
    String officer, chair, bursar, admin;

    @BeforeEach
    void setUp() throws Exception {
        String tenant = "E" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        inst = data.institution(tenant, BigDecimal.ZERO);
        data.staff(inst.tenant(), "chair", Role.DEPUTY_PRINCIPAL_ACADEMIC);
        data.staff(inst.tenant(), "bursar", Role.BURSAR);
        data.staff(inst.tenant(), "admin", Role.INSTITUTION_ADMIN);
        s = new AdmissionScenario(mvc, json, tenant, inst);
        officer = s.staffToken("officer");
        chair = s.staffToken("chair");
        bursar = s.staffToken("bursar");
        admin = s.staffToken("admin");
    }

    private List<Map<String, Object>> emails() {
        return TenantContext.callAs(inst.info(), () -> tx.execute(x -> jdbc.queryForList(
                "select to_address, kind, template_code, subject, body, status, attachment_name, attachment from email_messages order by id")));
    }

    private void verify(String token, String address) throws Exception {
        JsonNode r = s.body(s.call(post("/api/account/email"), token, "{\"email\":\"" + address + "\"}").andExpect(status().isOk()));
        assertThat(r.get("pending").asText()).isEqualTo(address);
        s.call(post("/api/account/email/verify"), token, "{\"code\":\"000000x\"}").andExpect(jsonPath("$.code").value("CODE_INVALID"));
        s.call(post("/api/account/email/verify"), token, "{\"code\":\"" + r.get("devCode").asText() + "\"}")
                .andExpect(jsonPath("$.verified").value(true)).andExpect(jsonPath("$.email").value(address));
    }

    @Test
    void verifiedApplicantsGetTheirNewsAndTheirLetterByEmail() throws Exception {
        s.call(put("/api/admission/windows/" + inst.windowId() + "/joining-instructions"), officer, INSTRUCTIONS).andExpect(status().isOk());
        Applicant a = s.apply("S0101/0001");
        verify(a.token(), "asha.juma@example.com");
        // the code went by email, masked in the outbox
        Map<String, Object> code = emails().get(0);
        assertThat(code.get("kind")).isEqualTo("SECURITY");
        assertThat((String) code.get("body")).contains("******")
                .doesNotContainPattern("(?<![A-Za-z0-9])\\d{6}(?![0-9])");   // a bare code; the random college code (E640635) is not one
        assertThat(code.get("status")).isEqualTo("SENT");

        s.admit(officer, chair, a);   // offer made, accepted, letter issued
        List<Map<String, Object>> all = emails();
        assertThat(all).anyMatch(e -> "EVENT".equals(e.get("kind")) && "asha.juma@example.com".equals(e.get("to_address")));
        Map<String, Object> letter = all.stream().filter(e -> "LETTER".equals(e.get("kind"))).findFirst().orElseThrow();
        assertThat((String) letter.get("attachment_name")).endsWith(".pdf");
        assertThat(letter.get("status")).isEqualTo("SENT");
        assertThat(letter.get("attachment")).isNull();          // not kept once sent
        // another applicant without a verified email gets SMS only
        long otherEmails = all.stream().filter(e -> !"asha.juma@example.com".equals(e.get("to_address"))).count();
        assertThat(otherEmails).isZero();

        // the institution can switch email off; only the administrator
        s.call(put("/api/messaging/emails/switch"), bursar, "{\"enabled\":false}").andExpect(status().isForbidden());
        s.call(put("/api/messaging/emails/switch"), admin, "{\"enabled\":false}").andExpect(jsonPath("$.enabled").value(false));
        JsonNode outbox = s.body(s.call(get("/api/messaging/emails"), admin, null).andExpect(status().isOk()));
        assertThat(outbox.get("rows").get(0).get("to").asText()).startsWith("a***@");
    }

    @Test
    void reportsAreEmailedOnScheduleToStaffOnly() throws Exception {
        verify(bursar, "bursar@college.ac.tz");
        s.call(post("/api/reports/schedules"), bursar, schedule("outsider@gmail.com")).andExpect(jsonPath("$.code").value("RECIPIENT_NOT_ALLOWED"));
        s.call(post("/api/reports/schedules"), bursar, schedule("bursar@college.ac.tz").replace("FIN-01", "ADM-01"))
                .andExpect(jsonPath("$.code").value("REPORT_NOT_ALLOWED"));
        JsonNode sc = s.body(s.call(post("/api/reports/schedules"), bursar, schedule("bursar@college.ac.tz")).andExpect(status().isOk()));
        assertThat(sc.get("mine").asBoolean()).isTrue();
        s.call(post("/api/reports/schedules/" + sc.get("id").asLong() + "/send"), admin, null).andExpect(status().isOk());   // the administrator may
        JsonNode after = s.body(s.call(get("/api/reports/schedules"), bursar, null)).get(0);
        assertThat(after.get("lastStatus").asText()).startsWith("Sent to 1 recipient");
        Map<String, Object> report = emails().stream().filter(e -> "REPORT".equals(e.get("kind"))).findFirst().orElseThrow();
        assertThat((String) report.get("attachment_name")).startsWith("FIN-01").endsWith(".xlsx");

        // next run times
        ZonedDateTime mon = ZonedDateTime.of(2026, 10, 12, 9, 30, 0, 0, ZoneId.of("Africa/Dar_es_Salaam"));   // a Monday
        assertThat(tz.co.msingi.cims.reporting.ReportScheduleServiceAccess.next("DAILY", null, null, 7, mon).getDayOfMonth()).isEqualTo(13);
        assertThat(tz.co.msingi.cims.reporting.ReportScheduleServiceAccess.next("WEEKLY", 1, null, 10, mon).getDayOfMonth()).isEqualTo(12);
        assertThat(tz.co.msingi.cims.reporting.ReportScheduleServiceAccess.next("MONTHLY", null, 5, 7, mon).getMonthValue()).isEqualTo(11);
    }

    private static String schedule(String to) {
        return "{\"reportCode\":\"FIN-01\",\"filters\":{},\"format\":\"XLSX\",\"frequency\":\"WEEKLY\",\"dayOfWeek\":1,\"sendHour\":7,"
                + "\"recipients\":[\"" + to + "\"],\"active\":true}";
    }
}
