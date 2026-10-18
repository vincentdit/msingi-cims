package tz.co.msingi.cims.helpdesk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.msingi.cims.console.auth.PlatformUser;
import tz.co.msingi.cims.console.auth.PlatformUserRepository;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.support.AdmissionScenario;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import java.math.BigDecimal;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The helpdesk: requests from applicants, staff and people without an account, routed by category to the office that answers,
 * staff notes, waiting and resolving, targets and escalation, the knowledge base, and support tickets to Msingi.
 */
@IntegrationTest
class HelpdeskIT {

    static final byte[] PDF = "%PDF-1.4\n% test file\n".getBytes();
    static final byte[] EXE = "MZ\u0090\u0000 not a document".getBytes();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired HelpdeskSlaJob job;
    @Autowired PlatformUserRepository platformUsers;
    @Autowired PasswordEncoder encoder;

    TestData.Institution inst;
    AdmissionScenario s;
    String tenant, admissions, bursar, admin, auditor, lecturer;

    @BeforeEach
    void setUp() throws Exception {
        tenant = "K" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        inst = data.institution(tenant, BigDecimal.ZERO);
        data.staff(inst.tenant(), "admissions", Role.ADMISSIONS_OFFICER);
        data.staff(inst.tenant(), "bursar", Role.BURSAR);
        data.staff(inst.tenant(), "admin", Role.INSTITUTION_ADMIN);
        data.staff(inst.tenant(), "auditor", Role.AUDITOR);
        data.staff(inst.tenant(), "lecturer", Role.LECTURER);
        db(() -> {
            for (String u : List.of("admissions", "bursar", "admin", "lecturer")) {
                jdbc.update("update user_accounts set email = ? where username = ?", u + "@" + tenant.toLowerCase() + ".ac.tz", u);
            }
            return null;
        });
        s = new AdmissionScenario(mvc, json, tenant, inst);
        admissions = s.staffToken("admissions");
        bursar = s.staffToken("bursar");
        admin = s.staffToken("admin");
        auditor = s.staffToken("auditor");
        lecturer = s.staffToken("lecturer");
    }

    <T> T db(Supplier<T> work) {
        return TenantContext.callAs(inst.info(), () -> tx.execute(x -> work.get()));
    }

    long category(String token, String name) throws Exception {
        for (JsonNode c : s.body(s.call(get("/api/help/categories"), token, null).andExpect(status().isOk()))) {
            if (c.get("name").asText().equals(name)) return c.get("id").asLong();
        }
        throw new AssertionError("no category " + name);
    }

    ResultActions send(MockMultipartHttpServletRequestBuilder b, String token, Map<String, String> params, byte[]... files) throws Exception {
        b.header("X-Tenant", tenant);
        if (token != null) b.header("Authorization", "Bearer " + token);
        params.forEach(b::param);
        int i = 0;
        for (byte[] f : files) b.file(new MockMultipartFile("files", "file" + (++i) + ".pdf", "application/pdf", f));
        return mvc.perform(b);
    }

    List<String> sms(String code) {
        return db(() -> jdbc.queryForList("select body from messages where template_code = ? order by id", String.class, code));
    }

    List<String> emailsTo(String user, String code) {
        return db(() -> jdbc.queryForList("select subject from email_messages where to_address = ? and template_code = ? order by id", String.class,
                user + "@" + tenant.toLowerCase() + ".ac.tz", code));
    }

    @Test
    void requestsGoToTheOfficeThatAnswersThemAndBackToTheRequester() throws Exception {
        String applicant = s.applicantToken();
        String other = s.applicantToken();
        // applicants see the categories meant for them only
        List<String> names = s.body(s.call(get("/api/help/categories"), applicant, null)).findValuesAsText("name");
        assertThat(names).contains("Applying for admission", "Signing in and my account").doesNotContain("Fees and invoices", "ICT and system support");

        long admission = category(applicant, "Applying for admission");
        // only PDF, JPEG or PNG files
        send(multipart("/api/help/tickets"), applicant, Map.of("categoryId", "" + admission, "subject", "My results", "body", "Help"), EXE)
                .andExpect(jsonPath("$.code").value("FILE_TYPE"));
        JsonNode t = s.body(send(multipart("/api/help/tickets"), applicant, Map.of("categoryId", "" + admission,
                "subject", "My CSEE results are not found", "body", "The portal says my index number is not found."), PDF)
                .andExpect(status().isCreated()));
        long id = t.at("/ticket/id").asLong();
        assertThat(t.at("/ticket/ticketNo").asText()).isEqualTo("HD-" + Year.now().getValue() + "-00001");
        assertThat(t.at("/ticket/status").asText()).isEqualTo("OPEN");
        assertThat(t.at("/messages/0/attachments/0/fileName").asText()).isEqualTo("file1.pdf");
        long attachment = t.at("/messages/0/attachments/0/id").asLong();
        assertThat(sms("HELPDESK_RECEIVED")).hasSize(1);
        assertThat(emailsTo("admissions", "HELPDESK_NEW")).hasSize(1);
        assertThat(emailsTo("bursar", "HELPDESK_NEW")).isEmpty();

        // another applicant cannot see it
        s.call(get("/api/help/tickets/" + id), other, null).andExpect(status().isNotFound());
        s.call(get("/api/help/attachments/" + attachment), other, null).andExpect(status().isNotFound());
        s.call(get("/api/help/attachments/" + attachment), applicant, null).andExpect(status().isOk());

        // the Bursar's office does not see an admissions question; the Admissions Office does
        assertThat(s.body(s.call(get("/api/helpdesk/tickets"), bursar, null)).get("total").asLong()).isZero();
        s.call(get("/api/helpdesk/tickets/" + id), bursar, null).andExpect(status().isForbidden());
        JsonNode desk = s.body(s.call(get("/api/helpdesk/tickets?view=unassigned"), admissions, null).andExpect(status().isOk()));
        assertThat(desk.get("total").asLong()).isEqualTo(1);
        assertThat(desk.at("/counts/unassigned").asLong()).isEqualTo(1);

        // a note only staff see, then a reply that waits for the applicant
        send(multipart("/api/helpdesk/tickets/" + id + "/messages"), admissions, Map.of("body", "Checked NECTA: index has a typo", "internal", "true"))
                .andExpect(status().isOk());
        JsonNode r = s.body(send(multipart("/api/helpdesk/tickets/" + id + "/messages"), admissions,
                Map.of("body", "Please send a photo of your result slip.", "statusAfter", "WAITING")).andExpect(status().isOk()));
        assertThat(r.at("/ticket/status").asText()).isEqualTo("WAITING");
        assertThat(r.at("/ticket/assigneeName").asText()).isEqualTo("Staff admissions");
        assertThat(r.at("/ticket/firstResponseAt").asText()).isNotEmpty();
        assertThat(sms("HELPDESK_REPLY")).hasSize(1);
        JsonNode mine = s.body(s.call(get("/api/help/tickets/" + id), applicant, null));
        assertThat(mine.get("messages").findValuesAsText("body")).contains("Please send a photo of your result slip.")
                .doesNotContain("Checked NECTA: index has a typo");

        // the applicant answers: back in progress, the assignee is told
        JsonNode back = s.body(send(multipart("/api/help/tickets/" + id + "/messages"), applicant, Map.of("body", "Here it is"), PDF).andExpect(status().isOk()));
        assertThat(back.at("/ticket/status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(emailsTo("admissions", "HELPDESK_REQUESTER_REPLY")).hasSize(1);

        // moved to the Bursar: the Admissions Office no longer has it
        long payments = category(applicant, "Payments and control numbers");
        s.call(post("/api/helpdesk/tickets/" + id + "/route"), admissions, "{\"categoryId\":" + payments + "}").andExpect(status().isNoContent());
        s.call(get("/api/helpdesk/tickets/" + id), admissions, null).andExpect(status().isForbidden());
        JsonNode moved = s.body(s.call(get("/api/helpdesk/tickets/" + id), bursar, null).andExpect(status().isOk()));
        assertThat(moved.at("/ticket/officeRole").asText()).isEqualTo("BURSAR");
        assertThat(moved.at("/ticket/assigneeUserId").isMissingNode() || moved.at("/ticket/assigneeUserId").isNull()).isTrue();
        assertThat(moved.get("messages").findValuesAsText("body")).anyMatch(b -> b.startsWith("Moved from Applying for admission to Payments"));

        // the Auditor reads everything but changes nothing
        s.call(get("/api/helpdesk/tickets/" + id), auditor, null).andExpect(status().isOk());
        s.call(post("/api/helpdesk/tickets/" + id + "/status"), auditor, "{\"status\":\"RESOLVED\"}").andExpect(status().isForbidden());

        // resolved, then closed by the applicant who says it helped
        s.call(post("/api/helpdesk/tickets/" + id + "/status"), bursar, "{\"status\":\"RESOLVED\"}").andExpect(jsonPath("$.ticket.status").value("RESOLVED"));
        assertThat(sms("HELPDESK_RESOLVED")).hasSize(1);
        s.call(post("/api/help/tickets/" + id + "/close"), applicant, "{\"satisfied\":true}").andExpect(jsonPath("$.ticket.status").value("CLOSED"))
                .andExpect(jsonPath("$.ticket.satisfied").value(true));
        send(multipart("/api/help/tickets/" + id + "/messages"), applicant, Map.of("body", "One more thing")).andExpect(jsonPath("$.code").value("TICKET_CLOSED"));

        // a student (an applicant's account handed over to Registry) sees the students' categories
        db(() -> {
            Long requester = jdbc.queryForObject("select requester_user_id from helpdesk_tickets where id = ?", Long.class, id);
            return jdbc.update("insert into user_roles (user_id, role) select id, 'STUDENT' from user_accounts where user_type = 'APPLICANT' and id <> ?", requester);
        });
        assertThat(s.body(s.call(get("/api/help/categories"), other, null)).findValuesAsText("name")).contains("Fees and invoices", "Results and examinations")
                .doesNotContain("Applying for admission");

        // staff ask their own ICT office
        List<String> staffCategories = s.body(s.call(get("/api/help/categories"), lecturer, null)).findValuesAsText("name");
        assertThat(staffCategories).contains("ICT and system support").doesNotContain("Applying for admission");
        long ict = category(lecturer, "ICT and system support");
        long lt = s.body(send(multipart("/api/help/tickets"), lecturer, Map.of("categoryId", "" + ict, "subject", "Projector login", "body", "Cannot sign in"))
                .andExpect(status().isCreated())).at("/ticket/id").asLong();
        s.call(get("/api/helpdesk/tickets/" + lt), admin, null).andExpect(jsonPath("$.ticket.requesterType").value("STAFF"));
        assertThat(emailsTo("lecturer", "HELPDESK_RECEIVED")).hasSize(1);   // no phone on record: by email
        // the lecturer is not an office: no helpdesk queue of their own
        assertThat(s.body(s.call(get("/api/helpdesk/tickets"), lecturer, null)).get("total").asLong()).isZero();

        // assignment: someone from the office (or an administrator), nobody else
        JsonNode agents = s.body(s.call(get("/api/helpdesk/tickets/" + lt + "/agents"), admin, null));
        assertThat(agents.findValuesAsText("name")).contains("Staff admin").doesNotContain("Staff bursar");
        long bursarId = db(() -> jdbc.queryForObject("select id from user_accounts where username = 'bursar'", Long.class));
        s.call(post("/api/helpdesk/tickets/" + lt + "/assign"), admin, "{\"userId\":" + bursarId + "}").andExpect(jsonPath("$.code").value("NOT_AN_AGENT"));
    }

    @Test
    void peopleWithoutAnAccountAskAndFollowWithTheirCode() throws Exception {
        JsonNode cats = s.body(s.call(get("/api/public/help/categories"), null, null).andExpect(status().isOk()));
        assertThat(cats.findValuesAsText("name")).contains("Signing in and my account", "Applying for admission").doesNotContain("Fees and invoices");
        long signIn = 0;
        for (JsonNode c : cats) if (c.get("name").asText().equals("Signing in and my account")) signIn = c.get("id").asLong();
        send(multipart("/api/public/help/tickets"), null, Map.of("name", "Asha", "phone", "123", "categoryId", "" + signIn, "subject", "Code", "body", "x"))
                .andExpect(jsonPath("$.code").value("INVALID_PHONE"));
        JsonNode g = s.body(send(multipart("/api/public/help/tickets"), null, Map.of("name", "Asha Juma", "phone", "0716 555 111",
                "email", "asha@example.com", "categoryId", "" + signIn, "subject", "The SMS code does not arrive", "body", "I tried three times."))
                .andExpect(status().isCreated()));
        String no = g.get("ticketNo").asText();
        String code = g.get("code").asText();
        assertThat(code).hasSize(8);
        // the code is sent, but never kept readable in the message log or the email outbox
        assertThat(sms("HELPDESK_RECEIVED_GUEST")).singleElement().satisfies(b -> assertThat(b).contains(no).doesNotContain(code));
        assertThat(db(() -> jdbc.queryForList("select body from email_messages where to_address = 'asha@example.com'", String.class)))
                .singleElement().satisfies(b -> assertThat(b).doesNotContain(code));

        s.call(post("/api/public/help/tickets/open"), null, "{\"ticketNo\":\"" + no + "\",\"code\":\"WRONG123\"}").andExpect(jsonPath("$.code").value("WRONG_CODE"));
        s.call(post("/api/public/help/tickets/open"), null, "{\"ticketNo\":\"" + no.toLowerCase() + "\",\"code\":\"" + code.toLowerCase() + "\"}")
                .andExpect(jsonPath("$.ticket.requesterName").value("Asha Juma")).andExpect(jsonPath("$.requesterPhone").doesNotExist());

        // the administrators' office answers sign-in problems
        long id = s.body(s.call(get("/api/helpdesk/tickets"), admin, null)).at("/rows/0/id").asLong();
        send(multipart("/api/helpdesk/tickets/" + id + "/messages"), admin, Map.of("body", "Your number was wrong; try again now.")).andExpect(status().isOk());
        assertThat(sms("HELPDESK_REPLY_GUEST")).hasSize(1);
        JsonNode reply = s.body(send(multipart("/api/public/help/tickets/reply"), null, Map.of("ticketNo", no, "code", code, "body", "It works, thanks"), PDF)
                .andExpect(status().isOk()));
        long att = reply.at("/messages/2/attachments/0/id").asLong();
        s.call(post("/api/public/help/tickets/attachments/" + att), null, "{\"ticketNo\":\"" + no + "\",\"code\":\"" + code + "\"}").andExpect(status().isOk());
        s.call(post("/api/public/help/tickets/close"), null, "{\"ticketNo\":\"" + no + "\",\"code\":\"" + code + "\",\"satisfied\":true}")
                .andExpect(jsonPath("$.ticket.status").value("CLOSED"));
    }

    @Test
    void missedTargetsAreEscalatedAndResolvedRequestsCloseThemselves() throws Exception {
        String applicant = s.applicantToken();
        long admission = category(applicant, "Applying for admission");
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(s.body(send(multipart("/api/help/tickets"), applicant, Map.of("categoryId", "" + admission, "subject", "Request " + i, "body", "Help")))
                    .at("/ticket/id").asLong());
        }
        // waiting for the requester stops the resolution clock
        send(multipart("/api/helpdesk/tickets/" + ids.get(1) + "/messages"), admissions, Map.of("body", "Which programme?", "statusAfter", "WAITING"));
        db(() -> {
            jdbc.update("update helpdesk_tickets set response_due_at = now() - interval '1 hour', resolution_due_at = now() - interval '1 minute', created_at = now() - interval '3 days' where id = ?", ids.get(0));
            jdbc.update("update helpdesk_tickets set paused_at = now() - interval '2 hours', resolution_due_at = now() - interval '1 hour' where id = ?", ids.get(1));
            jdbc.update("update helpdesk_tickets set status = 'RESOLVED', resolved_at = now() - interval '8 days' where id = ?", ids.get(2));
            return null;
        });
        job.runNow();
        Map<String, Object> late = db(() -> jdbc.queryForMap("select escalation_level, status from helpdesk_tickets where id = ?", ids.get(0)));
        assertThat(((Number) late.get("escalation_level")).intValue()).isEqualTo(2);
        assertThat(emailsTo("admissions", "HELPDESK_ESCALATED")).hasSize(1);   // no reply in time: the office
        assertThat(emailsTo("admin", "HELPDESK_ESCALATED")).hasSize(1);        // not resolved in time: the administrators
        assertThat(db(() -> jdbc.queryForObject("select escalation_level from helpdesk_tickets where id = ?", Integer.class, ids.get(1)))).isZero();
        assertThat(db(() -> jdbc.queryForObject("select status from helpdesk_tickets where id = ?", String.class, ids.get(2)))).isEqualTo("CLOSED");
        // the requester answers: the two hours waited are added to the resolution target, so it is no longer late
        send(multipart("/api/help/tickets/" + ids.get(1) + "/messages"), applicant, Map.of("body", "Diploma in IT"));
        assertThat(db(() -> jdbc.queryForObject("select resolution_due_at > now() from helpdesk_tickets where id = ?", Boolean.class, ids.get(1)))).isTrue();

        JsonNode perf = s.body(s.call(get("/api/helpdesk/performance?days=30"), admin, null).andExpect(status().isOk()));
        assertThat(perf.at("/overall/received").asLong()).isEqualTo(3);
        assertThat(perf.at("/overall/late").asLong()).isEqualTo(1);
        s.call(get("/api/help/tickets"), applicant, null).andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void theKnowledgeBaseAndCategoriesAreKeptByTheOfficesAndTheAdministrator() throws Exception {
        String applicant = s.applicantToken();
        long admission = category(applicant, "Applying for admission");
        String article = """
                {"categoryId":%d,"title":"My index number is not found","body":"Check the year of the examination and the format S0123/0045.",
                 "forApplicants":true,"forStudents":false,"forStaff":false,"forPublic":true,"published":true}""".formatted(admission);
        // the Admissions Office writes the articles of its categories; the Bursar cannot
        s.call(post("/api/helpdesk/articles"), bursar, article).andExpect(status().isForbidden());
        long id = s.body(s.call(post("/api/helpdesk/articles"), admissions, article).andExpect(status().isCreated())).get("id").asLong();
        s.call(get("/api/help/articles").param("q", "index found"), applicant, null).andExpect(jsonPath("$[0].title").value("My index number is not found"));
        s.call(get("/api/public/help/articles?q=index"), null, null).andExpect(jsonPath("$[0].id").value(id));
        s.call(get("/api/help/articles"), lecturer, null).andExpect(jsonPath("$.length()").value(0));   // the starter answers are not for staff
        s.call(post("/api/public/help/articles/" + id + "/vote"), null, "{\"helpful\":true}").andExpect(status().isNoContent());
        s.call(get("/api/public/help/articles/" + id), null, null).andExpect(status().isOk());
        JsonNode stats = null;
        for (JsonNode a : s.body(s.call(get("/api/helpdesk/articles"), admin, null))) if (a.get("id").asLong() == id) stats = a;
        assertThat(stats.get("helpful").asInt()).isEqualTo(1);
        assertThat(stats.get("views").asInt()).isEqualTo(1);
        // unpublished: gone from the help pages
        s.call(put("/api/helpdesk/articles/" + id), admissions, article.replace("\"published\":true", "\"published\":false")).andExpect(status().isOk());
        s.call(get("/api/public/help/articles/" + id), null, null).andExpect(status().isNotFound());

        String category = """
                {"name":"Library","description":"Books and e-resources","officeRole":"%s","forApplicants":false,"forStudents":true,"forStaff":true,
                 "forGuests":false,"responseHours":%d,"resolutionHours":48,"active":true,"sortOrder":95}""";
        s.call(post("/api/helpdesk/categories"), admissions, category.formatted("REGISTRAR", 8)).andExpect(status().isForbidden());
        s.call(post("/api/helpdesk/categories"), admin, category.formatted("STUDENT", 8)).andExpect(jsonPath("$.code").value("INVALID_OFFICE"));
        s.call(post("/api/helpdesk/categories"), admin, category.formatted("REGISTRAR", 72)).andExpect(jsonPath("$.code").value("INVALID_TIMES"));
        s.call(post("/api/helpdesk/categories"), admin, category.formatted("REGISTRAR", 8)).andExpect(status().isCreated());
        assertThat(s.body(s.call(get("/api/help/categories"), lecturer, null)).findValuesAsText("name")).contains("Library");
    }

    @Test
    void administratorsAskMsingiAndMsingiAnswersFromThePlatformConsole() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        tx.executeWithoutResult(x -> {
            PlatformUser u = new PlatformUser();
            u.setUsername("sup" + suffix);
            u.setFullName("Support " + suffix);
            u.setEmail("sup" + suffix + "@msingi.co.tz");
            u.setPasswordHash(encoder.encode("Sup@2026x"));
            u.setMustChangePassword(false);
            platformUsers.save(u);
        });
        String platform = s.body(mvc.perform(post("/api/platform/auth/login").contentType("application/json")
                .content("{\"username\":\"sup" + suffix + "\",\"password\":\"Sup@2026x\"}"))).get("accessToken").asText();

        // only administrators raise tickets to Msingi
        send(multipart("/api/msingi-support/tickets"), bursar, Map.of("category", "PROBLEM", "subject", "x", "body", "y")).andExpect(status().isForbidden());
        JsonNode t = s.body(send(multipart("/api/msingi-support/tickets"), admin, Map.of("category", "INTEGRATION", "priority", "URGENT",
                "subject", "GePG callbacks not arriving", "body", "Payments since 9:00 are not showing."), PDF).andExpect(status().isCreated()));
        long id = t.at("/ticket/id").asLong();
        assertThat(t.at("/ticket/ticketNo").asText()).startsWith("MS-");
        assertThat(db(() -> jdbc.queryForList("select subject from email_messages where to_address = ?", String.class, "sup" + suffix + "@msingi.co.tz")))
                .anyMatch(x -> x.startsWith("New support ticket"));

        // Msingi sees it with the institution, adds a note the institution never sees, and answers
        JsonNode list = s.body(mvc.perform(get("/api/platform/support/tickets?view=open").header("Authorization", "Bearer " + platform)).andExpect(status().isOk()));
        assertThat(list.get("rows").findValuesAsText("ticketNo")).contains(t.at("/ticket/ticketNo").asText());
        mvc.perform(multipart("/api/platform/support/tickets/" + id + "/messages").param("body", "Bank side outage, checking").param("internal", "true")
                .header("Authorization", "Bearer " + platform)).andExpect(status().isOk());
        mvc.perform(multipart("/api/platform/support/tickets/" + id + "/messages").param("body", "Fixed: the callbacks are flowing again.")
                .param("statusAfter", "RESOLVED").header("Authorization", "Bearer " + platform)).andExpect(jsonPath("$.ticket.status").value("RESOLVED"))
                .andExpect(jsonPath("$.ticket.assigneeName").value("Support " + suffix));
        JsonNode mine = s.body(s.call(get("/api/msingi-support/tickets/" + id), admin, null));
        assertThat(mine.get("messages").findValuesAsText("body")).contains("Fixed: the callbacks are flowing again.").doesNotContain("Bank side outage, checking");
        assertThat(emailsTo("admin", "MSINGI_SUPPORT")).anyMatch(x -> x.startsWith("Resolved: MS-"));

        // another institution's administrator cannot see it
        String other = "L" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        data.institution(other, BigDecimal.ZERO);
        AdmissionScenario o = new AdmissionScenario(mvc, json, other, null);
        o.call(get("/api/msingi-support/tickets/" + id), o.staffToken("officer"), null).andExpect(status().isNotFound());

        // a helpdesk request sent on to Msingi
        long ict = category(lecturer, "ICT and system support");
        long hd = s.body(send(multipart("/api/help/tickets"), lecturer, Map.of("categoryId", "" + ict, "subject", "Results upload fails", "body", "Error 500")))
                .at("/ticket/id").asLong();
        JsonNode esc = s.body(s.call(post("/api/msingi-support/escalate/" + hd), admin, "{\"category\":\"PROBLEM\",\"priority\":\"HIGH\",\"note\":\"Please look\"}")
                .andExpect(status().isCreated()));
        assertThat(esc.at("/ticket/helpdeskTicketNo").asText()).startsWith("HD-");
        s.call(get("/api/helpdesk/tickets/" + hd), admin, null).andExpect(jsonPath("$.msingiTicketNo").value(esc.at("/ticket/ticketNo").asText()));
        s.call(post("/api/msingi-support/escalate/" + hd), admin, "{\"category\":\"PROBLEM\"}").andExpect(jsonPath("$.code").value("ALREADY_ESCALATED"));

        // an urgent ticket with no reply in time is escalated to Msingi staff
        long late = esc.at("/ticket/id").asLong();
        tx.executeWithoutResult(x -> jdbc.update("update support_tickets set response_due_at = now() - interval '5 minutes' where id = ?", late));
        job.runNow();
        Short level = tx.execute(x -> jdbc.queryForObject("select escalation_level from support_tickets where id = ?", Short.class, late));
        assertThat(level).isEqualTo((short) 1);
    }
}
