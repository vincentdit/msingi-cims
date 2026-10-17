package tz.co.msingi.cims.integration.nactvet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.integration.nactvet.NactvetGateway.Background;
import tz.co.msingi.cims.integration.nactvet.NactvetGateway.Candidate;
import tz.co.msingi.cims.integration.nactvet.NactvetGateway.Heading;
import tz.co.msingi.cims.integration.nactvet.NactvetGateway.Outcome;
import tz.co.msingi.cims.integration.nactvet.NactvetGateway.Result;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The live adapter against a stand-in HTTP server: what it sends (as NOAS sends it), how it reads NACTVET's answers, and how it fails. */
class LiveNactvetGatewayTest {

    static final String UPLOAD = "/nacteapi/index.php/api/upload";
    static final String FEEDBACK = "/nacteapi/index.php/api-out-service/verifiedresultsall";
    static final String CONFIRM = "/nacteapi/index.php/api-out-service/admissionconfirmation";
    static final Heading HEADING = new Heading("SEPTEMBER", "4501", "2026", "6", "991100223344");

    final ObjectMapper json = new ObjectMapper();
    final Map<String, String> answers = new ConcurrentHashMap<>();   // path (#page for feedback) -> answer
    final Map<String, JsonNode> received = new ConcurrentHashMap<>(); // path (#page for feedback) -> request body
    volatile int httpStatus = 200;
    HttpServer server;
    LiveNactvetGateway gateway;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String key = ex.getRequestURI().getPath();
            byte[] in = ex.getRequestBody().readAllBytes();
            if (in.length > 0) {
                JsonNode body = json.readTree(in);
                if (body.has("page_number")) key += "#" + body.get("page_number").asInt();
                received.put(key, body);
            }
            byte[] out = answers.getOrDefault(key, "{\"code\":404,\"message\":\"no stub\"}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(httpStatus, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        gateway = gateway(true);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    LiveNactvetGateway gateway(boolean mockCodes) {
        return new LiveNactvetGateway("http://127.0.0.1:" + server.getAddress().getPort(), UPLOAD, FEEDBACK, CONFIRM,
                "token", "API-1", 3, 10, mockCodes, 2, 5);
    }

    static Candidate candidate(String ref, String index, String firstName, LocalDate dob) {
        return new Candidate(ref, index, 2024, firstName, "Juma", "Mussa", "F", dob, "Tanzanian", "0754000111",
                "4501", "Ordinary Diploma in IT", 6, null,
                new Background(null, "S0101/0501", 2026, "NS0001/0002/2023", 2023, null, null, "asha@example.com", "P.O. Box 1",
                        "Dodoma", "Dodoma CBD", "Juma Mussa", "P.O. Box 1", null, "0754000222", "Dodoma", "Father"));
    }

    static Candidate candidate(String ref, String index) {
        return candidate(ref, index, "Asha", LocalDate.of(2006, 3, 4));
    }

    @Test
    void uploadsOneProgrammeAsAnArrayWrappingTheHeadingAndParticulars() {
        answers.put(UPLOAD, "{\"code\":200,\"message\":\"Uploaded successfully\"}");
        assertThat(gateway.upload(HEADING, List.of(candidate("APP-1", "S0101/0001")))).contains("Uploaded successfully");

        JsonNode sent = received.get(UPLOAD);
        assertThat(sent.isArray()).isTrue();
        assertThat(sent).hasSize(1);
        JsonNode h = sent.get(0).path("heading");
        assertThat(h.path("authorization").asText()).isEqualTo("token");
        assertThat(h.path("intake").asText()).isEqualTo("SEPTEMBER");
        assertThat(h.path("programme_id").asText()).isEqualTo("4501");
        assertThat(h.path("application_year").asText()).isEqualTo("2026");
        assertThat(h.path("level").asText()).isEqualTo("6");
        assertThat(h.path("payment_reference_number").asText()).isEqualTo("991100223344");
        JsonNode p = sent.get(0).path("students").get(0).path("particulars");
        assertThat(p.path("firstname").asText()).isEqualTo("Asha");
        assertThat(p.path("secondname").asText()).isEqualTo("Juma");
        assertThat(p.path("DOB").asText()).isEqualTo("4-03-2006");
        assertThat(p.path("gender").asText()).isEqualTo("Female");
        assertThat(p.path("impairement").asText()).isEqualTo("None");
        assertThat(p.path("form_four_indexnumber").asText()).isEqualTo("S0101/0001");
        assertThat(p.path("form_four_year").asText()).isEqualTo("2024");
        assertThat(p.path("form_six_indexnumber").asText()).isEqualTo("S0101/0501");
        assertThat(p.path("NTA4_reg").asText()).isEqualTo("NS0001/0002/2023");
        assertThat(p.path("NTA5_reg").asText()).isEmpty();
        assertThat(p.path("mobile_number").asText()).isEqualTo("0754000111");
        assertThat(p.path("next_kin_relation").asText()).isEqualTo("Father");
        assertThat(p.has("next_kin_email_address")).isTrue();   // every field is sent, empty when unknown

        answers.put(UPLOAD, "Success");   // not JSON: accepted as it is, as NOAS does
        assertThat(gateway.upload(HEADING, List.of(candidate("APP-1", "S0101/0001")))).isEqualTo("Success");
    }

    @Test
    void uploadRefusalsAreExplainedWithNactvetsCodes() {
        answers.put(UPLOAD, "{\"code\":463,\"message\":\"Expired token\"}");
        assertThatThrownBy(() -> gateway.upload(HEADING, List.of(candidate("A", "S1/0001"))))
                .isInstanceOf(NactvetUnavailableException.class).hasMessageContaining("credentials").hasMessageContaining("Token expired");

        answers.put(UPLOAD, "{\"code\":409,\"message\":\"Applicants already uploaded\"}");
        assertThatThrownBy(() -> gateway.upload(HEADING, List.of(candidate("A", "S1/0001"))))
                .isInstanceOf(NactvetUnavailableException.class).hasMessageContaining("code 409: Conflict. Applicants already uploaded");

        httpStatus = 401;
        answers.put(UPLOAD, "{\"code\":401,\"message\":\"Invalid authorization\"}");
        assertThatThrownBy(() -> gateway.upload(HEADING, List.of(candidate("A", "S1/0001"))))
                .isInstanceOf(NactvetUnavailableException.class).hasMessageContaining("credentials");

        httpStatus = 503;
        answers.put(UPLOAD, "Service Unavailable");
        assertThatThrownBy(() -> gateway.upload(HEADING, List.of(candidate("A", "S1/0001"))))
                .isInstanceOf(NactvetUnavailableException.class).hasMessageContaining("could not be reached");
    }

    @Test
    void pullsFeedbackPageByPageAndMatchesItToTheBatch() {
        answers.put(FEEDBACK + "#1", """
                {"code":200,"page_number":1,"page_size":3,"total_records":7,"total_pages":3,"params":[
                  {"user_id":"S1/0001","student_verification_id":"NV-0001","programme_id":"4501","verification_status":"Verified and Approved",
                   "eligibility":"Eligible","multiple_selection":"No multiple selection","academic_year":"2026","intake":"SEPTEMBER"},
                  {"user_id":"S1-0003-2024","student_verification_id":"NV-0003","programme_id":"4501","verification_status":"Approved",
                   "eligibility":"Eligible","multiple_selection":"Multiple Selection"},
                  {"user_id":"S1/0007","programme_id":"4501","verification_status":"Returned for correction","remarks":"Name does not match NECTA"}]}""");
        answers.put(FEEDBACK + "#2", """
                {"code":200,"page_number":2,"page_size":3,"total_records":7,"total_pages":3,"params":[
                  {"user_id":"NACTVET-77","first_name":"Neema","last_name":"MUSSA","date_of_birth":"2005-11-20","programme_id":"4501",
                   "verification_status":"Not Approved","remarks":"Does not meet the minimum entry requirements"},
                  {"user_id":"S1/0005","programme_id":"4501","verification_status":"Pending"},
                  {"user_id":"S1/0006","student_verification_id":"NV-0006","programme_id":"4501","verification_status":"Verified and Approved",
                   "eligibility":"Not Eligible","remarks":"Not eligible for the programme"}]}""");
        answers.put(FEEDBACK + "#3", """
                {"code":200,"page_number":3,"page_size":3,"total_records":7,"total_pages":3,"params":[
                  {"user_id":"S1/0008","programme_id":"9999","verification_status":"Verified and Approved","eligibility":"Eligible"},
                  {"user_id":"S1/0007","student_verification_id":"NV-0007","programme_id":"4501","verification_status":"Verified and Approved",
                   "eligibility":"Eligible"},
                  {"user_id":"S9/9999","programme_id":"4501","verification_status":"Verified and Approved","eligibility":"Eligible"}]}""");

        List<Outcome> out = gateway.fetchOutcomes("2026", "SEPTEMBER", List.of(candidate("APP-1", "S1/0001"), candidate("APP-3", "S1/0003"),
                candidate("APP-7", "S1/0007"), candidate("APP-9", "S1/0009", "Neema", LocalDate.of(2005, 11, 20)),
                candidate("APP-5", "S1/0005"), candidate("APP-6", "S1/0006"), candidate("APP-8", "S1/0008")));

        Map<String, Outcome> by = new java.util.HashMap<>();
        out.forEach(o -> by.put(o.applicationRef(), o));
        assertThat(by).containsOnlyKeys("APP-1", "APP-3", "APP-7", "APP-9", "APP-6");   // APP-5 pending, APP-8 is another programme
        assertThat(by.get("APP-1").result()).isEqualTo(Result.VERIFIED);
        assertThat(by.get("APP-1").verificationNo()).isEqualTo("NV-0001");
        assertThat(by.get("APP-1").multipleAdmission()).isFalse();
        assertThat(by.get("APP-3").multipleAdmission()).isTrue();          // index number written with the exam year, other separators
        assertThat(by.get("APP-7").result()).isEqualTo(Result.VERIFIED);   // queried, then approved later in the feedback
        assertThat(by.get("APP-9").result()).isEqualTo(Result.REJECTED);   // matched by name and date of birth
        assertThat(by.get("APP-9").reason()).contains("minimum entry requirements");
        assertThat(by.get("APP-6").result()).isEqualTo(Result.REJECTED);   // approved but not eligible
        assertThat(by.get("APP-6").verificationNo()).isNull();

        JsonNode asked = received.get(FEEDBACK + "#1");
        assertThat(asked.path("authorization").asText()).isEqualTo("token");
        assertThat(asked.path("api_code").asText()).isEqualTo("API-1");
        assertThat(asked.path("year").asText()).isEqualTo("2026");
        assertThat(asked.path("intake").asText()).isEqualTo("SEPTEMBER");
        assertThat(asked.path("page_size").asInt()).isEqualTo(3);
        assertThat(received).containsKeys(FEEDBACK + "#2", FEEDBACK + "#3");
    }

    @Test
    void feedbackRefusalsAndOutagesBecomeNactvetUnavailable() {
        answers.put(FEEDBACK + "#1", "{\"code\":401,\"message\":\"Invalid authorization\",\"params\":[]}");
        assertThatThrownBy(() -> gateway.fetchOutcomes("2026", "SEPTEMBER", List.of(candidate("A", "S1/0001"))))
                .isInstanceOf(NactvetUnavailableException.class).hasMessageContaining("credentials");

        httpStatus = 503;
        answers.put(FEEDBACK + "#1", "{\"code\":503}");
        assertThatThrownBy(() -> gateway.fetchOutcomes("2026", "SEPTEMBER", List.of(candidate("A", "S1/0001"))))
                .isInstanceOf(NactvetUnavailableException.class).hasMessageContaining("could not be reached");

        server.stop(0);
        assertThatThrownBy(() -> gateway.fetchOutcomes("2026", "SEPTEMBER", List.of(candidate("A", "S1/0001"))))
                .isInstanceOf(NactvetUnavailableException.class);
    }

    @Test
    void readsNactvetsStatusesAsNoasDoes() {
        assertThat(LiveNactvetGateway.classify("Verified and Approved", "Eligible")).isEqualTo(Result.VERIFIED);
        assertThat(LiveNactvetGateway.classify("APPROVED", null)).isEqualTo(Result.VERIFIED);
        assertThat(LiveNactvetGateway.classify("Approved", "Not Eligible")).isEqualTo(Result.REJECTED);
        assertThat(LiveNactvetGateway.classify("Not Approved", "Eligible")).isEqualTo(Result.REJECTED);
        assertThat(LiveNactvetGateway.classify("Rejected", null)).isEqualTo(Result.REJECTED);
        assertThat(LiveNactvetGateway.classify("Queried", null)).isEqualTo(Result.QUERIED);
        assertThat(LiveNactvetGateway.classify("Pending", null)).isNull();
        assertThat(LiveNactvetGateway.classify(null, null)).isNull();
    }

    @Test
    void confirmsAdmissionAndExplainsAConfirmationElsewhere() {
        answers.put(CONFIRM, "{\"code\":200,\"params\":{\"confirmed\":1}}");
        assertThat(gateway.confirmAdmission("REG/DEMO/001", "S0101/0003", " 123456 ")).isTrue();
        assertThat(received.get(CONFIRM).path("confirmation_code").asText()).isEqualTo("123456");

        answers.put(CONFIRM, "{\"code\":200,\"params\":{\"confirmed\":0,\"reason\":\"WRONG_CODE\"}}");
        assertThat(gateway.confirmAdmission("REG/DEMO/001", "S0101/0003", "000000")).isFalse();

        answers.put(CONFIRM, "{\"code\":404,\"message\":\"No multiple admission\"}");
        assertThat(gateway.confirmAdmission("REG/DEMO/001", "S0101/0003", "000000")).isFalse();

        answers.put(CONFIRM, "{\"code\":200,\"params\":{\"confirmed\":0,\"reason\":\"ALREADY_CONFIRMED_ELSEWHERE\"}}");
        assertThatThrownBy(() -> gateway.confirmAdmission("REG/DEMO/001", "S0101/0003", "123456"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("another institution");
    }

    @Test
    void showsTheMockConfirmationCodeOnlyWhenAsked() {
        answers.put("/mock/confirmation-codes/S0101-0003", "{\"index_number\":\"S0101-0003\",\"confirmation_code\":\"654321\"}");
        assertThat(gateway.developmentConfirmationCode("S0101/0003")).contains("654321");
        assertThat(gateway(false).developmentConfirmationCode("S0101/0003")).isEmpty();
    }

    @Test
    void refusesToStartWithoutCredentials() {
        assertThatThrownBy(() -> new LiveNactvetGateway("http://localhost:1", "/u", "/f", "/c", "", "API", 500, 10, false, 1, 1))
                .isInstanceOf(IllegalStateException.class);
    }
}
