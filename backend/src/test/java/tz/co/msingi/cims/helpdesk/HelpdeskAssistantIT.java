package tz.co.msingi.cims.helpdesk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.support.AdmissionScenario;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The helpdesk assistant: answers predicted while writing (English and Kiswahili), the category guessed, an answer given at once
 * and the requester's verdict, replies learned from solved requests and suggested to staff, frequent questions with no answer.
 */
@IntegrationTest
class HelpdeskAssistantIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    TestData.Institution inst;
    AdmissionScenario s;
    String tenant, admin, registrar;

    @BeforeEach
    void setUp() throws Exception {
        tenant = "A" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        inst = data.institution(tenant, BigDecimal.ZERO);
        data.staff(inst.tenant(), "admin", Role.INSTITUTION_ADMIN);
        data.staff(inst.tenant(), "registrar", Role.REGISTRAR);
        s = new AdmissionScenario(mvc, json, tenant, inst);
        admin = s.staffToken("admin");
        registrar = s.staffToken("registrar");
    }

    <T> T db(Supplier<T> work) {
        return TenantContext.callAs(inst.info(), () -> tx.execute(x -> work.get()));
    }

    ResultActions send(MockMultipartHttpServletRequestBuilder b, String token, Map<String, String> params) throws Exception {
        b.header("X-Tenant", tenant);
        if (token != null) b.header("Authorization", "Bearer " + token);
        params.forEach(b::param);
        return mvc.perform(b);
    }

    long category(String token, String name) throws Exception {
        for (JsonNode c : s.body(s.call(get("/api/help/categories"), token, null))) if (c.get("name").asText().equals(name)) return c.get("id").asLong();
        throw new AssertionError(name);
    }

    @Test
    void answersArePredictedWhileWritingAndOneIsGivenAtOnceWhenConfident() throws Exception {
        String applicant = s.applicantToken();
        // in Kiswahili, against answers written in English; the category is guessed among the applicant's
        JsonNode sug = s.body(s.call(post("/api/help/suggest"), applicant, "{\"subject\":\"Nimesahau nenosiri langu\",\"body\":\"Siwezi kuingia kwenye akaunti\"}")
                .andExpect(status().isOk()));
        assertThat(sug.at("/answers/0/title").asText()).isEqualTo("I forgot my password");
        assertThat(sug.at("/answers/0/confidence").asDouble()).isGreaterThan(0.5);
        assertThat(sug.at("/category/name").asText()).isEqualTo("Signing in and my account");
        // something no answer covers: no suggestion
        JsonNode none = s.body(s.call(post("/api/help/suggest"), applicant, "{\"subject\":\"Lecturer absent\",\"body\":\"The lecturer for ITU 05102 did not come on Monday\"}"));
        assertThat(none.get("answers").size()).isZero();

        // without an account: the suggestion solved it, so no request was raised
        JsonNode pub = s.body(s.call(post("/api/public/help/suggest"), null, "{\"subject\":\"I paid but the payment is not showing\",\"body\":\"\"}"));
        long paid = pub.at("/answers/0/id").asLong();
        assertThat(pub.at("/answers/0/title").asText()).isEqualTo("I paid but the payment does not show");
        s.call(post("/api/public/help/suggest/solved"), null, "{\"articleId\":" + paid + "}").andExpect(status().isNoContent());

        // a confident match is given on the request at once; the requester says it did not solve it
        long signIn = category(applicant, "Signing in and my account");
        JsonNode t = s.body(send(multipart("/api/help/tickets"), applicant, Map.of("categoryId", "" + signIn,
                "subject", "Sikupokea nambari ya siri kwa SMS", "body", "Nimejaribu mara tatu lakini ujumbe haufiki")).andExpect(status().isCreated()));
        long id = t.at("/ticket/id").asLong();
        assertThat(t.get("suggestionOutcome").asText()).isEqualTo("INSTANT");
        assertThat(t.at("/messages/1/authorType").asText()).isEqualTo("ASSISTANT");
        assertThat(t.at("/messages/1/body").asText()).startsWith("I did not receive the SMS code");
        assertThat(t.at("/ticket/firstResponseAt").isMissingNode() || t.at("/ticket/firstResponseAt").isNull()).isTrue();   // the office still owes a reply
        s.call(post("/api/help/tickets/" + id + "/assistant"), applicant, "{\"helpful\":false}").andExpect(jsonPath("$.suggestionOutcome").value("REJECTED"))
                .andExpect(jsonPath("$.ticket.status").value("OPEN"));
        s.call(post("/api/help/tickets/" + id + "/assistant"), applicant, "{\"helpful\":true}").andExpect(jsonPath("$.code").value("NO_SUGGESTION"));
        // the office is not offered the answer the requester turned down
        JsonNode forOffice = s.body(s.call(get("/api/helpdesk/tickets/" + id + "/suggestions"), admin, null));
        assertThat(forOffice.get("replies").findValuesAsText("title")).doesNotContain("I did not receive the SMS code");

        // another one it solves: closed as helped
        JsonNode t2 = s.body(send(multipart("/api/help/tickets"), applicant, Map.of("categoryId", "" + signIn,
                "subject", "I forgot my password", "body", "I forgot my password and cannot sign in to my account")));
        assertThat(t2.get("suggestionOutcome").asText()).isEqualTo("INSTANT");
        s.call(post("/api/help/tickets/" + t2.at("/ticket/id").asLong() + "/assistant"), applicant, "{\"helpful\":true}")
                .andExpect(jsonPath("$.ticket.status").value("CLOSED")).andExpect(jsonPath("$.ticket.satisfied").value(true));

        JsonNode perf = s.body(s.call(get("/api/helpdesk/performance?days=30"), admin, null));
        assertThat(perf.at("/assistant/solvedBeforeAsking").asLong()).isEqualTo(1);
        assertThat(perf.at("/assistant/instantAnswers").asLong()).isEqualTo(2);
        assertThat(perf.at("/assistant/instantAccepted").asLong()).isEqualTo(1);
        assertThat(perf.at("/assistant/instantRejected").asLong()).isEqualTo(1);
        assertThat(perf.at("/assistant/categoryRight").asLong()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void repliesThatSolvedARequestAreSuggestedToStaffAndUnansweredTopicsAreListed() throws Exception {
        String applicant = s.applicantToken();
        String student = s.applicantToken();
        db(() -> {
            Long requester = jdbc.queryForObject("select id from user_accounts where user_type = 'APPLICANT' order by id desc limit 1", Long.class);
            return jdbc.update("insert into user_roles (user_id, role) values (?, 'STUDENT')", requester);
        });
        long records = category(student, "Registration and student records");
        // a student's question, answered by Registry and closed as helped: the reply is learned
        long first = s.body(send(multipart("/api/help/tickets"), student, Map.of("categoryId", "" + records,
                "subject", "Change of programme", "body", "I want to transfer from accountancy to procurement this semester"))).at("/ticket/id").asLong();
        String reply = "To change programme, open Profile, choose Ask for a transfer and pick the programme; the Deputy Principal decides within a week.";
        send(multipart("/api/helpdesk/tickets/" + first + "/messages"), registrar, Map.of("body", reply, "statusAfter", "RESOLVED")).andExpect(status().isOk());
        s.call(post("/api/help/tickets/" + first + "/close"), student, "{\"satisfied\":true}").andExpect(status().isOk());
        assertThat(db(() -> jdbc.queryForObject("select confirmed from helpdesk_learned_answers where ticket_id = ?", Boolean.class, first))).isTrue();

        // a similar question in Kiswahili: Registry is offered the reply that worked
        long second = s.body(send(multipart("/api/help/tickets"), student, Map.of("categoryId", "" + records,
                "subject", "Uhamisho wa programu", "body", "Nataka kuhamia programu nyingine muhula huu"))).at("/ticket/id").asLong();
        JsonNode sug = s.body(s.call(get("/api/helpdesk/tickets/" + second + "/suggestions"), registrar, null).andExpect(status().isOk()));
        assertThat(sug.get("replies").findValuesAsText("text")).contains(reply);
        JsonNode past = null;
        for (JsonNode r : sug.get("replies")) if (r.get("text").asText().equals(reply)) past = r;
        assertThat(past.get("source").asText()).isEqualTo("PAST_REPLY");
        assertThat(past.get("confirmed").asBoolean()).isTrue();
        s.call(post("/api/helpdesk/suggestions/" + past.get("sourceId").asLong() + "/used"), registrar, null).andExpect(status().isNoContent());
        // the guess knows where this belongs, and a requester cannot see staff suggestions
        assertThat(sug.at("/category/name").asText()).isEqualTo("Registration and student records");
        s.call(get("/api/helpdesk/tickets/" + second + "/suggestions"), student, null).andExpect(status().isForbidden());

        // a reply the requester said did not help is not learned
        long third = s.body(send(multipart("/api/help/tickets"), student, Map.of("categoryId", "" + records,
                "subject", "ID card lost", "body", "I lost my student ID card"))).at("/ticket/id").asLong();
        send(multipart("/api/helpdesk/tickets/" + third + "/messages"), registrar, Map.of("body", "Please come to Registry with a police loss report.", "statusAfter", "RESOLVED"));
        s.call(post("/api/help/tickets/" + third + "/close"), student, "{\"satisfied\":false}").andExpect(status().isOk());
        assertThat(db(() -> jdbc.queryForObject("select count(*) from helpdesk_learned_answers where ticket_id = ?", Integer.class, third))).isZero();

        // the same question several times with no answer written: listed for the office
        long other = category(applicant, "Something else");
        for (String q : new String[]{"When do we collect graduation gowns?", "Graduation gown collection date", "Where to collect my graduation gown"}) {
            send(multipart("/api/help/tickets"), applicant, Map.of("categoryId", "" + other, "subject", q, "body", "Asking about the gown for the graduation ceremony"));
        }
        JsonNode gaps = s.body(s.call(get("/api/helpdesk/gaps?days=30"), admin, null).andExpect(status().isOk()));
        JsonNode gown = null;
        for (JsonNode g : gaps) if (g.get("examples").toString().contains("gown")) gown = g;
        assertThat(gown).as(gaps.toString()).isNotNull();
        assertThat(gown.get("requests").asInt()).isEqualTo(3);
        assertThat(gown.get("categoryName").asText()).isEqualTo("Something else");
        // each office sees the gaps in its own categories: Admissions does not get Registry's
        data.staff(inst.tenant(), "admissions", Role.ADMISSIONS_OFFICER);
        JsonNode admissionsGaps = s.body(s.call(get("/api/helpdesk/gaps?days=30"), s.staffToken("admissions"), null));
        assertThat(admissionsGaps.toString()).doesNotContain("gown");
        assertThat(s.body(s.call(get("/api/helpdesk/gaps?days=30"), registrar, null)).toString()).contains("gown");
    }
}
