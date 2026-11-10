package tz.co.msingi.cims.integration.nhif;

import tools.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tz.co.msingi.cims.integration.http.ExternalSystemUnavailableException;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * NHIF's Student Registration API over HTTPS, as NHIF documents it (api/v2):
 * <ul>
 *   <li>an OAuth2 access token from {@code /connect/token} (client_credentials, form-encoded), kept until a minute before it
 *       expires and asked for again when NHIF answers 401;</li>
 *   <li>every call with {@code Authorization: Bearer}, and the agent's {@code SystemId} and {@code SubSystemId} headers;</li>
 *   <li>NHIF's answers: BulkOperationResult (possibly inside {@code data}), DataOperationResult ({@code succeeded}/{@code failed})
 *       for card checks, and plain arrays for intakes and control numbers.</li>
 * </ul>
 * Select with {@code CIMS_NHIF_MODE=live}; the address is {@code CIMS_NHIF_URL}. The credentials are each institution's own
 * (Integrations › NHIF), never configuration of the server.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cims.nhif", name = "mode", havingValue = "live")
public class LiveNhifClient implements NhifClient {

    private final RestClient http;
    private final String tokenPath;
    private final String api;
    private final Map<String, Token> tokens = new ConcurrentHashMap<>();

    private record Token(String value, Instant until) {
    }

    private record Answer(int status, JsonNode body) {
    }

    public LiveNhifClient(@Value("${cims.nhif.base-url}") String baseUrl, @Value("${cims.nhif.token-path:/connect/token}") String tokenPath,
                          @Value("${cims.nhif.api-path:/api/v2}") String apiPath) {
        JdkClientHttpRequestFactory f = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        f.setReadTimeout(Duration.ofSeconds(90));
        this.http = tz.co.msingi.cims.common.web.Json2Http.builder().baseUrl(baseUrl).requestFactory(f).build();
        this.tokenPath = tokenPath;
        this.api = apiPath.endsWith("/") ? apiPath.substring(0, apiPath.length() - 1) : apiPath;
        log.info("NHIF Student Registration API: live at {}{}", baseUrl, this.api);
    }

    // ------------------------------------------------------------------ operations

    @Override
    public BulkResult registerStudents(Agent agent, List<Registration> students) {
        List<Map<String, Object>> body = students.stream().map(LiveNhifClient::student).toList();
        // one student goes to /student/register (which checks SystemId/SubSystemId), several to the bulk endpoint
        Answer a = students.size() == 1 ? post(agent, "/student/register", body.get(0)) : post(agent, "/students/register", body);
        return bulk(a.body(), students.size());
    }

    @Override
    public BulkResult applyForCards(Agent agent, String batchNo, String intakeCode, String description, List<CardApplicationItem> items) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("batchNo", batchNo);
        body.put("intakeCode", intakeCode);
        body.put("description", description);
        body.put("details", items.stream().map(i -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("correlationID", i.correlationId());
            if (i.nationalId() != null) m.put("nationalID", i.nationalId());
            m.put("mobileNo", i.mobileNo());
            m.put("intakeCode", i.intakeCode());
            if (i.cardNo() != null) m.put("cardNo", i.cardNo());
            m.put("educationLevelID", i.educationLevelId());
            m.put("yearOfStudy", i.yearOfStudy());
            m.put("category", i.category());
            return m;
        }).toList());
        return bulk(post(agent, "/student/cardapplication", body).body(), items.size());
    }

    @Override
    public CardCheck verifyStudentCard(Agent agent, String cardNoOrNin) {
        JsonNode r = get(agent, "/verifystudentcard", Map.of("cardNo", cardNoOrNin));
        JsonNode ok = r == null ? null : r.get("succeeded");
        if (ok != null && ok.isObject()) {
            JsonNode ends = ok.get("contributionEnds");
            return new CardCheck(text(ok, "cardNo"), text(ok, "fullName"), text(ok, "remarks"), ends == null || !ends.isNumber() ? null : ends.asInt(),
                    text(ok, "verificationCode"), null);
        }
        JsonNode bad = r == null ? null : r.get("failed");
        Error e = bad != null && bad.isObject() ? error(bad) : new Error(null, "cardNo", "NHIF gave no answer for this card");
        return new CardCheck(cardNoOrNin, null, null, null, null, e);
    }

    @Override
    public List<Intake> intakes(Agent agent) {
        return list(get(agent, "/agentintakes", Map.of()), n -> new Intake(text(n, "intakeName"), text(n, "intakeCode"), date(text(n, "startDate")),
                date(text(n, "endDate"))));
    }

    @Override
    public List<ControlNumber> controlNumbers(Agent agent, String batchNo) {
        return list(get(agent, "/studentcontrolnobybatchno", Map.of("batchNo", batchNo)), n -> new ControlNumber(text(n, "correlationID"),
                text(n, "cardNo"), text(n, "batchNo"), text(n, "mobileNo"), text(n, "invoiceNo"), text(n, "controlNo"), decimal(n.get("invoiceAmount")),
                n.hasNonNull("categoryID") ? n.get("categoryID").asInt() : null, text(n, "membershipNo")));
    }

    @Override
    public BulkResult changeRequest(Agent agent, int category, String cardNo, String academicYear, String formFourIndexNo) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("category", category);
        body.put("cardNo", cardNo);
        body.put("academicYear", academicYear);
        body.put("formFourIndexNo", formFourIndexNo);
        return bulk(post(agent, "/student/changerequest", body).body(), 1);
    }

    // ------------------------------------------------------------------ HTTP

    private Answer post(Agent agent, String path, Object body) {
        return call(agent, path, t -> http.post().uri(api + path).headers(h -> headers(h, agent, t)).contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON).body(body).exchange((req, res) -> new Answer(res.getStatusCode().value(), readJson(res))), true);
    }

    private JsonNode get(Agent agent, String path, Map<String, ?> query) {
        return call(agent, path, t -> http.get().uri(b -> {
            b.path(api + path);
            query.forEach(b::queryParam);
            return b.build();
        }).headers(h -> headers(h, agent, t)).accept(MediaType.APPLICATION_JSON)
                .exchange((req, res) -> new Answer(res.getStatusCode().value(), readJson(res))), false).body();
    }

    private static JsonNode readJson(org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse res) {
        try {
            return res.bodyTo(JsonNode.class);
        } catch (RuntimeException e) {
            return null;   // an empty or non-JSON body (e.g. a gateway's HTML error page)
        }
    }

    private static void headers(HttpHeaders h, Agent agent, String token) {
        h.setBearerAuth(token);
        h.set("SystemId", String.valueOf(agent.systemId()));
        h.set("SubSystemId", String.valueOf(agent.subSystemId()));
    }

    /** Makes the call with a token, asks for a new token once when NHIF answers 401, and turns any other refusal into an error. */
    private Answer call(Agent agent, String path, Function<String, Answer> exchange, boolean write) {
        Answer a;
        try {
            a = exchange.apply(token(agent, false));
            if (a.status() == 401) a = exchange.apply(token(agent, true));
        } catch (RestClientException e) {
            throw new ExternalSystemUnavailableException("NHIF", "NHIF could not be reached. Please try again later.", e);
        }
        int s = a.status();
        if (s == 200 || s == 201 || s == 202) return a;
        // NHIF answers 400 with a BulkOperationResult when it refuses some or all of the students: that is an answer, not an error
        if (s == 400 && a.body() != null && (a.body().path("failed").isArray() || a.body().path("data").path("failed").isArray())) return a;
        throw new ExternalSystemUnavailableException("NHIF", switch (s) {
            case 400 -> "NHIF did not accept the request" + detail(a.body());
            case 401 -> "NHIF did not accept the institution's credentials (client id and secret, Integrations › NHIF)" + detail(a.body());
            case 403 -> "NHIF says this SystemId/SubSystemId is not authorised for the institution's agent account" + detail(a.body());
            default -> "NHIF answered " + s + (write ? " and may not have taken the request" : "") + ". Please try again later.";
        }, null);
    }

    private String token(Agent agent, boolean renew) {
        String key = agent.clientId() + "|" + agent.scope();
        Token t = tokens.get(key);
        if (!renew && t != null && t.until().isAfter(Instant.now())) return t.value();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", agent.clientId());
        form.add("client_secret", agent.clientSecret());
        form.add("grant_type", "client_credentials");
        form.add("scope", agent.scope() == null ? "" : agent.scope());
        Answer a;
        try {
            a = http.post().uri(tokenPath).contentType(MediaType.APPLICATION_FORM_URLENCODED).accept(MediaType.APPLICATION_JSON).body(form)
                    .exchange((req, res) -> new Answer(res.getStatusCode().value(), readJson(res)));
        } catch (RestClientException e) {
            throw new ExternalSystemUnavailableException("NHIF", "NHIF's sign-in service could not be reached. Please try again later.", e);
        }
        String value = a.body() == null ? null : text(a.body(), "access_token");
        if (a.status() != 200 || value == null) {
            throw new ExternalSystemUnavailableException("NHIF", "NHIF refused the institution's credentials (client id, secret and scope under "
                    + "Integrations › NHIF)" + detail(a.body()), null);
        }
        long seconds = a.body().path("expires_in").asLong(3600);
        tokens.put(key, new Token(value, Instant.now().plusSeconds(Math.max(30, seconds - 60))));
        return value;
    }

    // ------------------------------------------------------------------ NHIF's JSON

    private static Map<String, Object> student(Registration s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("firstName", s.firstName());
        m.put("middleName", s.middleName());
        m.put("surname", s.surname());
        m.put("gender", s.gender());
        m.put("dateOfBirth", s.dateOfBirth() == null ? null : s.dateOfBirth().toString());
        m.put("email", s.email());
        m.put("mobileNo", s.mobileNo());
        m.put("nationalId", s.nationalId());
        m.put("formFourIndexNo", s.formFourIndexNo());
        m.put("admissionNo", s.admissionNo());
        m.put("educationLevelID", s.educationLevelId());
        m.put("programmeOfStudy", s.programmeOfStudy());
        m.put("collageFaculty", s.collegeFaculty());   // NHIF's spelling
        m.put("yearOfStudy", s.yearOfStudy());
        m.put("academicYear", s.academicYear());
        m.put("intakeCode", s.intakeCode());
        if (s.photoBase64() != null) m.put("photoImage", s.photoBase64());
        return m;
    }

    /** BulkOperationResult, at the top or inside {@code data}. */
    static BulkResult bulk(JsonNode root, int sent) {
        JsonNode r = root != null && root.path("data").isObject() ? root.get("data") : root;
        if (r == null || r.isMissingNode() || r.isNull()) return new BulkResult(sent, List.of(), sent);   // 201 with no body: taken
        List<Failure> failed = new ArrayList<>();
        for (JsonNode f : r.path("failed")) {
            List<Error> errors = new ArrayList<>();
            for (JsonNode e : f.path("errors")) errors.add(error(e));
            failed.add(new Failure(f.path("index").asInt(-1), text(f, "identifier"), errors));
        }
        int total = r.path("totalRequested").asInt(sent);
        int ok = r.has("succeeded") && r.get("succeeded").isNumber() ? r.get("succeeded").asInt() : total - failed.size();
        return new BulkResult(ok, failed, total);
    }

    private static Error error(JsonNode e) {
        return new Error(e.hasNonNull("code") && e.get("code").canConvertToInt() ? e.get("code").asInt() : null, text(e, "field"), text(e, "message"));
    }

    private static <T> List<T> list(JsonNode r, Function<JsonNode, T> f) {
        JsonNode arr = r != null && r.isArray() ? r : r != null && r.path("data").isArray() ? r.get("data") : null;
        List<T> out = new ArrayList<>();
        if (arr != null) arr.forEach(n -> out.add(f.apply(n)));
        return out;
    }

    private static String detail(JsonNode body) {
        if (body == null) return ".";
        for (String k : new String[]{"message", "error_description", "error", "title", "detail"}) {
            String v = text(body, k);
            if (v != null) return ": " + v + ".";
        }
        JsonNode errs = body.path("errors");
        if (errs.isArray() && !errs.isEmpty()) return ": " + error(errs.get(0)).message() + ".";
        return ".";
    }

    static String text(JsonNode n, String key) {
        JsonNode v = n == null ? null : n.get(key);
        return v == null || v.isNull() || v.asString().isBlank() ? null : v.asString().trim();
    }

    private static LocalDate date(String s) {
        return s == null || s.length() < 10 ? null : LocalDate.parse(s.substring(0, 10));
    }

    private static BigDecimal decimal(JsonNode n) {
        if (n == null || n.isNull()) return null;
        try {
            return new BigDecimal(n.asString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
