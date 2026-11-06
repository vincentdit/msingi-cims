package tz.co.msingi.cims.integration;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tz.co.msingi.cims.integration.heslb.HeslbClient;
import tz.co.msingi.cims.integration.heslb.LiveHeslbClient;
import tz.co.msingi.cims.integration.http.ExternalSystemUnavailableException;
import tz.co.msingi.cims.integration.nhif.NhifClient;
import tz.co.msingi.cims.integration.muse.LiveMuseClient;
import tz.co.msingi.cims.integration.muse.MuseClient;
import tz.co.msingi.cims.integration.nactvet.LiveNactvetSubmissionClient;
import tz.co.msingi.cims.integration.nactvet.NactvetSubmissionClient;
import tz.co.msingi.cims.integration.nactvet.NactvetUnavailableException;
import tz.co.msingi.cims.integration.nida.LiveNidaClient;
import tz.co.msingi.cims.integration.tamisemi.LiveTamisemiClient;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The live HESLB, NIDA, MUSE, TAMISEMI, NHIF and NACTVET submission clients against a stand-in server answering canned JSON. */
class LiveExchangeClientsTest {

    HttpServer server;
    String base;
    final Map<String, String> answers = new ConcurrentHashMap<>();
    final Map<String, Integer> statuses = new ConcurrentHashMap<>();
    final List<String> received = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(ex.getRequestMethod() + " " + ex.getRequestURI() + " key=" + ex.getRequestHeaders().getFirst("X-Api-Key")
                    + " auth=" + ex.getRequestHeaders().getFirst("Authorization") + " sys=" + ex.getRequestHeaders().getFirst("SystemId") + "/"
                    + ex.getRequestHeaders().getFirst("SubSystemId") + " " + body);
            byte[] out = answers.getOrDefault(path, "{}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(statuses.getOrDefault(path, 200), out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void heslb() {
        LiveHeslbClient c = new LiveHeslbClient(base, "k1", "/r", "/b", "/d");
        answers.put("/r", "{\"submission_ref\":\"H-1\",\"received\":2,\"accepted\":1,\"rejected\":[{\"registration_no\":\"R2\",\"reason\":\"No index\"}]}");
        HeslbClient.SubmissionResult r = c.sendRegistrations("INST", "2027/2028", "REGISTRATION", List.of(
                new HeslbClient.Student("S1/1", "R1", "A", null, "B", "F", "P", "Prog", 1, "ACTIVE", "0712")));
        assertThat(r.reference()).isEqualTo("H-1");
        assertThat(r.rejected()).extracting(HeslbClient.Rejected::reason).containsExactly("No index");
        assertThat(received.get(0)).contains("key=k1").contains("\"index_number\":\"S1/1\"");
        answers.put("/b", "{\"beneficiaries\":[{\"index_number\":\"S1/1\",\"loan_number\":\"LN1\",\"allocations\":["
                + "{\"item\":\"TUITION_FEE\",\"amount\":900000,\"payable_to\":\"INSTITUTION\"},{\"item\":\"MEALS\",\"amount\":5,\"payable_to\":\"STUDENT\"}]}]}");
        assertThat(c.beneficiaries("INST", "2027/2028").get(0).toInstitution()).isEqualByComparingTo("900000");
        assertThat(received.get(1)).startsWith("GET /b?").contains("institution_code=INST");
        answers.put("/d", "{\"disbursements\":[{\"batch_no\":\"B1\",\"disbursed_on\":\"2027-11-02\",\"amount\":450000,\"students\":[{\"index_number\":\"S1/1\",\"amount\":450000}]}]}");
        assertThat(c.disbursements("INST", "2027/2028").get(0).disbursedOn()).isEqualTo(LocalDate.of(2027, 11, 2));
        statuses.put("/d", 503);
        assertThatThrownBy(() -> c.disbursements("INST", "2027/2028")).isInstanceOf(ExternalSystemUnavailableException.class).hasMessageContaining("503");
        assertThatThrownBy(() -> new LiveHeslbClient(base, "", "/r", "/b", "/d")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nidaMuseTamisemi() {
        answers.put("/v", "{\"status\":\"valid\",\"first_name\":\"ASHA\"}");
        assertThat(new LiveNidaClient(base, "k2", "/v").verify("20050314111110000112", "Asha", "Juma", LocalDate.of(2005, 3, 14)).status()).isEqualTo("VALID");

        LiveMuseClient muse = new LiveMuseClient(base, "k3", "/j");
        MuseClient.Journal j = new MuseClient.Journal("V1", "REF1", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 31), LocalDate.of(2027, 2, 1), "TZS",
                List.of(new MuseClient.Line("BANK", null, "x", BigDecimal.TEN, BigDecimal.ZERO), new MuseClient.Line("REVENUE", "1", "y", BigDecimal.ZERO, BigDecimal.TEN)));
        answers.put("/j", "{\"status\":\"POSTED\",\"muse_ref\":\"JV9\"}");
        assertThat(muse.post(j)).isEqualTo(new MuseClient.PostResult("POSTED", "JV9", null));
        statuses.put("/j", 409);
        assertThat(muse.post(j).status()).isEqualTo("DUPLICATE");
        statuses.put("/j", 422);
        answers.put("/j", "{\"status\":\"REJECTED\",\"message\":\"Debits and credits differ\"}");
        assertThat(muse.post(j).message()).isEqualTo("Debits and credits differ");

        answers.put("/s", "{\"selections\":[{\"index_number\":\"S0101/0001\",\"exam_year\":2026,\"first_name\":\"Asha\",\"surname\":\"Juma\",\"programme_code\":\"BTCIT\"}]}");
        assertThat(new LiveTamisemiClient(base, "k4", "/s").selections("INST", 2027).get(0).examYear()).isEqualTo(2026);
        assertThat(received.get(received.size() - 1)).contains("year=2027").contains("key=k4");
    }

    @Test
    void nactvetSubmissions() {
        LiveNactvetSubmissionClient c = new LiveNactvetSubmissionClient(base, "/reg", "/regs", "/res", "/ress", "auth", "CODE");
        answers.put("/reg", "{\"code\":200,\"message\":\"Received\",\"params\":{\"batch_ref\":\"REG-1\"}}");
        assertThat(c.submitRegistrations("INST", "2027/2028", List.of(new NactvetSubmissionClient.RegStudent("R1", "NV1", "S1", "A", null, "B", "F",
                "2005-01-01", "Tanzanian", "0712", "P", 1)))).isEqualTo("REG-1");
        assertThat(received.get(0)).contains("\"authorization\":\"auth\"").contains("\"api_code\":\"CODE\"").contains("\"verification_number\":\"NV1\"");
        answers.put("/regs", "{\"code\":200,\"params\":{\"status\":\"COMPLETED\",\"items\":[{\"registration_no\":\"R1\",\"status\":\"REGISTERED\",\"nactvet_number\":\"NS1\"}]}}");
        assertThat(c.registrationStatus("REG-1").items().get(0).nactvetNumber()).isEqualTo("NS1");
        answers.put("/res", "{\"code\":401,\"message\":\"Invalid authorization\"}");
        assertThatThrownBy(() -> c.submitResults("INST", new NactvetSubmissionClient.Results("P", "2027/2028", "Semester 1", 1, "MAIN", List.of())))
                .isInstanceOf(NactvetUnavailableException.class).hasMessageContaining("401");
    }

    @Test
    void nhif() {
        var c = new tz.co.msingi.cims.integration.nhif.LiveNhifClient(base, "/connect/token", "/api/v2");
        var agent = new NhifClient.Agent("agent-1", "s3cret", "student.registration", 7, 8);
        assertThat(agent.toString()).doesNotContain("s3cret");
        answers.put("/connect/token", "{\"access_token\":\"TOK1\",\"token_type\":\"Bearer\",\"expires_in\":3600}");

        // one student → /student/register, with the token and both headers; a refusal named per student comes back as a result
        answers.put("/api/v2/student/register", "{\"succeeded\":0,\"failed\":[{\"index\":0,\"identifier\":\"S0101-0001-2023\","
                + "\"errors\":[{\"code\":2111,\"field\":\"FirstName\",\"message\":\"A member with the same first name already exists.\"}]}],"
                + "\"totalRequested\":1,\"hasFailures\":true,\"isFullSuccess\":false}");
        statuses.put("/api/v2/student/register", 400);
        var one = c.registerStudents(agent, List.of(registration("S0101-0001-2023")));
        assertThat(one.fullSuccess()).isFalse();
        assertThat(one.failed()).singleElement().satisfies(f -> {
            assertThat(f.identifier()).isEqualTo("S0101-0001-2023");
            assertThat(f.reason()).contains("2111");
        });
        assertThat(received.get(0)).startsWith("POST /connect/token").contains("client_id=agent-1").contains("grant_type=client_credentials");
        assertThat(received.get(1)).startsWith("POST /api/v2/student/register").contains("auth=Bearer TOK1").contains("sys=7/8")
                .contains("\"formFourIndexNo\":\"S0101-0001-2023\"").contains("\"dateOfBirth\":\"2006-03-04\"").contains("\"gender\":\"Female\"");

        // several → /students/register, wrapped in data; the token is reused
        answers.put("/api/v2/students/register", "{\"data\":{\"succeeded\":2,\"failed\":[],\"totalRequested\":2,\"isFullSuccess\":true}}");
        assertThat(c.registerStudents(agent, List.of(registration("S0101-0001-2023"), registration("S0101-0002-2023"))).fullSuccess()).isTrue();
        assertThat(received.get(2)).startsWith("POST /api/v2/students/register");

        // card application and control numbers
        answers.put("/api/v2/student/cardapplication", "{\"succeeded\":1,\"failed\":[],\"totalRequested\":1,\"isFullSuccess\":true}");
        var applied = c.applyForCards(agent, "AG1/2027/2028/00001-X", "202710", "Students 2027/2028", List.of(
                new NhifClient.CardApplicationItem("HC1-1", "20060304111110000112", "0712345678", "202710", null, 2, 1, 1)));
        assertThat(applied.fullSuccess()).isTrue();
        assertThat(received.get(3)).contains("\"batchNo\":\"AG1/2027/2028/00001-X\"").contains("\"correlationID\":\"HC1-1\"").contains("\"category\":1")
                .doesNotContain("\"cardNo\"");
        answers.put("/api/v2/studentcontrolnobybatchno", "[{\"correlationID\":\"HC1-1\",\"batchNo\":\"AG1/2027/2028/00001-X\",\"controlNo\":\"995000000001\","
                + "\"invoiceNo\":\"INV-1\",\"invoiceAmount\":50400.00,\"categoryID\":1}]");
        var cn = c.controlNumbers(agent, "AG1/2027/2028/00001-X");
        assertThat(cn).singleElement().satisfies(x -> {
            assertThat(x.controlNo()).isEqualTo("995000000001");
            assertThat(x.amount()).isEqualByComparingTo("50400");
        });
        assertThat(received.get(4)).startsWith("GET /api/v2/studentcontrolnobybatchno?batchNo=AG1");

        // card check: succeeded / failed
        answers.put("/api/v2/verifystudentcard", "{\"succeeded\":{\"cardNo\":\"SN1234561\",\"fullName\":\"ASHA JUMA MWINYI\",\"remarks\":\"ACTIVE\","
                + "\"contributionEnds\":-1,\"verificationCode\":\"X1\"},\"failed\":null}");
        assertThat(c.verifyStudentCard(agent, "SN1234561").active()).isTrue();
        answers.put("/api/v2/verifystudentcard", "{\"succeeded\":null,\"failed\":{\"code\":2137,\"field\":\"cardNo\",\"message\":\"CardNo does not exist or is not valid.\"}}");
        var bad = c.verifyStudentCard(agent, "SN1234560");
        assertThat(bad.active()).isFalse();
        assertThat(bad.error().code()).isEqualTo(2137);

        // intakes
        answers.put("/api/v2/agentintakes", "[{\"intakeName\":\"October 2027 Intake\",\"intakeCode\":\"202710\",\"startDate\":\"2027-10-01T00:00:00\",\"endDate\":\"2027-10-31\"}]");
        assertThat(c.intakes(agent)).singleElement().satisfies(i -> assertThat(i.startDate()).isEqualTo(LocalDate.of(2027, 10, 1)));

        // an expired token: 401 → a new token, once
        int before = (int) received.stream().filter(r -> r.startsWith("POST /connect/token")).count();
        statuses.put("/api/v2/agentintakes", 401);
        assertThatThrownBy(() -> c.intakes(agent)).isInstanceOf(ExternalSystemUnavailableException.class).hasMessageContaining("credentials");
        assertThat(received.stream().filter(r -> r.startsWith("POST /connect/token")).count()).isEqualTo(before + 1);
        statuses.put("/api/v2/agentintakes", 503);
        assertThatThrownBy(() -> c.intakes(agent)).isInstanceOf(ExternalSystemUnavailableException.class);

        assertThat(NhifClient.normalise(" sn-1023-4561 ")).isEqualTo("SN10234561");
        assertThat(NhifClient.normalise("12")).isNull();
        assertThat(NhifClient.formFourIndex("S0101/0001", 2023)).isEqualTo("S0101-0001-2023");
        assertThat(NhifClient.batchNo("NH-12", "2027/2028", "00001-X")).isEqualTo("NH12/2027/2028/00001-X");
        assertThat(tz.co.msingi.cims.health.HealthCoverServiceTestAccess.sameSurname("ASHA JUMA MWINYI", "Mwinyi")).isTrue();
        assertThat(tz.co.msingi.cims.health.HealthCoverServiceTestAccess.sameSurname("NEEMA JOHN KIMARO", "Mwinyi")).isFalse();
    }

    static NhifClient.Registration registration(String index) {
        return new NhifClient.Registration("Asha", "Juma", "Mwinyi", "Female", LocalDate.of(2006, 3, 4), "asha@example.com", "0712345678",
                "20060304111110000112", index, "DEMO/27/0001", 2, "Ordinary Diploma in IT", "ICT", 1, "2027/2028", "202710", null);
    }
}
