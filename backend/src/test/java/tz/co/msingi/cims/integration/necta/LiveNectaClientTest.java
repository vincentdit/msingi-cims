package tz.co.msingi.cims.integration.necta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The live NECTA client against a local stand-in for {@code /api/results/individual}, using the request/response contract NOAS uses. */
class LiveNectaClientTest {

    static final String FOUND = """
            {"status":{"code":1,"message":"Success"},
             "particulars":{"first_name":"NEEMA","middle_name":"JOHN","last_name":"MWAKYUSA","sex":"F","center_name":"Azania Secondary School","prem_number":"20150123456","center_number":"S0101","exam_id":1},
             "results":{"division":"I","points":9},
             "subjects":[{"subject_code":"041","subject_name":"Basic Mathematics","grade":"B"},
                         {"subject_code":"022","subject_name":"English Language","grade":"a"},
                         {"subject_code":"011","subject_name":"Civics","grade":""}]}""";

    final ObjectMapper json = new ObjectMapper();
    final AtomicReference<String> response = new AtomicReference<>(FOUND);
    final AtomicReference<JsonNode> request = new AtomicReference<>();
    HttpServer server;
    String baseUrl;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/results/individual", ex -> {
            request.set(json.readTree(ex.getRequestBody()));
            byte[] out = response.get().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    LiveNectaClient client() {
        return new LiveNectaClient(baseUrl, "/api/results/individual", "test-key", 2, 5);
    }

    @Test
    void sendsTheIndexInDashFormAndMapsTheCandidate() {
        NectaClient.NectaResult r = client().fetchResults(NectaClient.ExamType.CSEE, "s0101/0001", 2023).orElseThrow();
        assertThat(request.get().get("index_number").asText()).isEqualTo("S0101-0001");
        assertThat(request.get().get("exam_year").asInt()).isEqualTo(2023);
        assertThat(request.get().get("exam_id").asText()).isEqualTo("1");
        assertThat(request.get().get("api_key").asText()).isEqualTo("test-key");

        assertThat(r.indexNumber()).isEqualTo("S0101/0001");
        assertThat(r.firstName()).isEqualTo("Neema");
        assertThat(r.middleName()).isEqualTo("John");
        assertThat(r.surname()).isEqualTo("Mwakyusa");
        assertThat(r.candidateName()).isEqualTo("Neema John Mwakyusa");
        assertThat(r.sex()).isEqualTo("F");
        assertThat(r.centreName()).isEqualTo("AZANIA SECONDARY SCHOOL");
        assertThat(r.division()).isEqualTo("I");
        assertThat(r.points()).isEqualTo(9);
        assertThat(r.subjects()).extracting(NectaClient.SubjectGrade::name, NectaClient.SubjectGrade::grade)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("BASIC MATHEMATICS", "B"), org.assertj.core.groups.Tuple.tuple("ENGLISH LANGUAGE", "A"));
    }

    @Test
    void thePremNumberIsTakenWhereverNectaPutsIt() {
        assertThat(client().fetchResults(NectaClient.ExamType.CSEE, "S0101/0001", 2023).orElseThrow().premNumber()).isEqualTo("20150123456");
        response.set(FOUND.replace("\"prem_number\":\"20150123456\"", "\"x\":1").replace("{\"status\"", "{\"premNumber\":\"20190312\",\"status\""));
        assertThat(client().fetchResults(NectaClient.ExamType.CSEE, "S0101/0001", 2023).orElseThrow().premNumber()).isEqualTo("20190312");
        response.set(FOUND.replace("\"prem_number\":\"20150123456\"", "\"x\":1"));
        assertThat(client().fetchResults(NectaClient.ExamType.CSEE, "S0101/0001", 2023).orElseThrow().premNumber()).isNull();
    }

    @Test
    void acseeUsesExamIdTwo() {
        client().fetchResults(NectaClient.ExamType.ACSEE, "S0101/0001", 2025);
        assertThat(request.get().get("exam_id").asText()).isEqualTo("2");
    }

    @Test
    void anUnknownCandidateIsEmpty() {
        response.set("{\"status\":{\"code\":0,\"message\":\"Candidate not found\"}}");
        assertThat(client().fetchResults(NectaClient.ExamType.CSEE, "S0101/0000", 2023)).isEmpty();
    }

    @Test
    void anUnreachableServiceIsReportedAsUnavailable() {
        server.stop(0);
        assertThatThrownBy(() -> client().fetchResults(NectaClient.ExamType.CSEE, "S0101/0001", 2023))
                .isInstanceOf(NectaUnavailableException.class);
    }

    @Test
    void theApiKeyMustBeConfigured() {
        assertThatThrownBy(() -> new LiveNectaClient(baseUrl, "/api/results/individual", " ", 2, 5))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("NECTA_API_KEY");
    }
}
