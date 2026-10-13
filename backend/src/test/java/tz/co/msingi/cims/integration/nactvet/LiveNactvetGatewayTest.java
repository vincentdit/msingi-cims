package tz.co.msingi.cims.integration.nactvet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.integration.nactvet.NactvetGateway.Candidate;
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

/** The live adapter against a stand-in HTTP server: what it sends, how it reads NACTVET's answers, and how it fails. */
class LiveNactvetGatewayTest {

    static final String BASE = "/nacteapi/index.php/api-out-service";
    final ObjectMapper json = new ObjectMapper();
    final Map<String, String> answers = new ConcurrentHashMap<>();   // path -> JSON answer
    final Map<String, JsonNode> received = new ConcurrentHashMap<>(); // path -> last request body
    volatile int httpStatus = 200;
    HttpServer server;
    LiveNactvetGateway gateway;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            byte[] in = ex.getRequestBody().readAllBytes();
            if (in.length > 0) received.put(path, json.readTree(in));
            byte[] out = answers.getOrDefault(path, "{\"code\":404,\"message\":\"no stub\"}").getBytes(StandardCharsets.UTF_8);
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
        return new LiveNactvetGateway("http://127.0.0.1:" + server.getAddress().getPort(), BASE + "/admissionverification",
                BASE + "/admissionverificationresults", BASE + "/admissionconfirmation", "token", "API-1", mockCodes, 2, 5);
    }

    static Candidate candidate(String ref, String index) {
        return new Candidate(ref, index, 2024, "Asha", null, "Mussa", "F", LocalDate.of(2006, 3, 14), "Tanzanian", "255754000111",
                "NTA6-ODIT", "Ordinary Diploma in IT", 6, null);
    }

    @Test
    void submitsTheTemplateWithCredentialsAndReturnsTheBatchReference() {
        answers.put(BASE + "/admissionverification", "{\"code\":200,\"message\":\"Received\",\"params\":{\"batch_ref\":\"NV-DEMO-1\",\"received\":1}}");
        assertThat(gateway.submit("REG/DEMO/001", List.of(candidate("APP-1", "S0101/0001")))).isEqualTo("NV-DEMO-1");
        JsonNode sent = received.get(BASE + "/admissionverification");
        assertThat(sent.path("authorization").asText()).isEqualTo("token");
        assertThat(sent.path("api_code").asText()).isEqualTo("API-1");
        assertThat(sent.path("institution_code").asText()).isEqualTo("REG/DEMO/001");
        JsonNode c = sent.path("candidates").get(0);
        assertThat(c.path("index_number").asText()).isEqualTo("S0101/0001");
        assertThat(c.path("date_of_birth").asText()).isEqualTo("2006-03-14");
        assertThat(c.path("award_level").asInt()).isEqualTo(6);
    }

    @Test
    void readsOutcomesAndLeavesThoseStillProcessingForLater() {
        answers.put(BASE + "/admissionverificationresults", """
                {"code":200,"message":"Success","params":[
                  {"application_ref":"APP-1","status":"VERIFIED","verification_number":"NV/2026/000001","multiple_admission":0},
                  {"application_ref":"APP-3","status":"VERIFIED","verification_number":"NV/2026/000002","multiple_admission":1},
                  {"application_ref":"APP-7","status":"QUERIED","reason":"Name does not match"},
                  {"application_ref":"APP-9","status":"NOT_VERIFIED","reason":"Minimum entry requirements"},
                  {"application_ref":"APP-5","status":"PROCESSING"},
                  {"application_ref":"OTHER","status":"VERIFIED"}]}""");
        List<Outcome> out = gateway.fetchOutcomes("NV-DEMO-1", List.of(candidate("APP-1", "S1/0001"), candidate("APP-3", "S1/0003"),
                candidate("APP-7", "S1/0007"), candidate("APP-9", "S1/0009"), candidate("APP-5", "S1/0005")));
        assertThat(out).extracting(Outcome::applicationRef).containsExactly("APP-1", "APP-3", "APP-7", "APP-9");
        assertThat(out.get(0).verificationNo()).isEqualTo("NV/2026/000001");
        assertThat(out.get(1).multipleAdmission()).isTrue();
        assertThat(out.get(2).result()).isEqualTo(Result.QUERIED);
        assertThat(out.get(3).result()).isEqualTo(Result.REJECTED);
        assertThat(received.get(BASE + "/admissionverificationresults").path("batch_ref").asText()).isEqualTo("NV-DEMO-1");
    }

    @Test
    void confirmsAdmissionAndExplainsAConfirmationElsewhere() {
        answers.put(BASE + "/admissionconfirmation", "{\"code\":200,\"params\":{\"confirmed\":1}}");
        assertThat(gateway.confirmAdmission("REG/DEMO/001", "S0101/0003", " 123456 ")).isTrue();
        assertThat(received.get(BASE + "/admissionconfirmation").path("confirmation_code").asText()).isEqualTo("123456");

        answers.put(BASE + "/admissionconfirmation", "{\"code\":200,\"params\":{\"confirmed\":0,\"reason\":\"WRONG_CODE\"}}");
        assertThat(gateway.confirmAdmission("REG/DEMO/001", "S0101/0003", "000000")).isFalse();

        answers.put(BASE + "/admissionconfirmation", "{\"code\":404,\"message\":\"No multiple admission\"}");
        assertThat(gateway.confirmAdmission("REG/DEMO/001", "S0101/0003", "000000")).isFalse();

        answers.put(BASE + "/admissionconfirmation", "{\"code\":200,\"params\":{\"confirmed\":0,\"reason\":\"ALREADY_CONFIRMED_ELSEWHERE\"}}");
        assertThatThrownBy(() -> gateway.confirmAdmission("REG/DEMO/001", "S0101/0003", "123456"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("another institution");
    }

    @Test
    void refusalsOutagesAndMissingReferencesBecomeNactvetUnavailable() {
        answers.put(BASE + "/admissionverification", "{\"code\":401,\"message\":\"Invalid authorization\"}");
        assertThatThrownBy(() -> gateway.submit("X", List.of(candidate("A", "S1/0001"))))
                .isInstanceOf(NactvetUnavailableException.class).hasMessageContaining("credentials");

        answers.put(BASE + "/admissionverification", "{\"code\":200,\"params\":{}}");
        assertThatThrownBy(() -> gateway.submit("X", List.of(candidate("A", "S1/0001"))))
                .isInstanceOf(NactvetUnavailableException.class).hasMessageContaining("no batch reference");

        httpStatus = 503;
        answers.put(BASE + "/admissionverificationresults", "{\"code\":503}");
        assertThatThrownBy(() -> gateway.fetchOutcomes("NV-1", List.of(candidate("A", "S1/0001"))))
                .isInstanceOf(NactvetUnavailableException.class).hasMessageContaining("could not be reached");

        server.stop(0);
        assertThatThrownBy(() -> gateway.fetchOutcomes("NV-1", List.of(candidate("A", "S1/0001"))))
                .isInstanceOf(NactvetUnavailableException.class);
    }

    @Test
    void showsTheMockConfirmationCodeOnlyWhenAsked() {
        answers.put("/mock/confirmation-codes/S0101-0003", "{\"index_number\":\"S0101-0003\",\"confirmation_code\":\"654321\"}");
        assertThat(gateway.developmentConfirmationCode("S0101/0003")).contains("654321");
        assertThat(gateway(false).developmentConfirmationCode("S0101/0003")).isEmpty();
    }

    @Test
    void refusesToStartWithoutCredentials() {
        assertThatThrownBy(() -> new LiveNactvetGateway("http://localhost:1", "/s", "/r", "/c", "", "API", false, 1, 1))
                .isInstanceOf(IllegalStateException.class);
    }
}
