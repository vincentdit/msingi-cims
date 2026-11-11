package tz.co.msingi.cims.integration.nactvet;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tz.co.msingi.cims.integration.http.ReachableIpRequestFactory;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * NACTVET's curriculum details: a JSON POST to {@code /nacteapi/index.php/api-out-service/curriculum-details} with the authorization
 * token, API code, programme id, academic year, level (NTA5), semester (ONE/TWO) and intake (SEPT/MARCH). The answer carries
 * {@code curriculum_code}, {@code programme_name} and the modules under {@code params}. Uses the NACTE credentials of the awards
 * service and connects to a NACTVET address that answers, like the catalogue.
 *
 * Active when {@code cims.nactvet.curriculum.mode=live}.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cims.nactvet.curriculum", name = "mode", havingValue = "live")
public class LiveNactvetCurriculumClient implements NactvetCurriculumClient {

    private static final int MAX_ATTEMPTS = 2;

    private final RestClient http;
    private final String path;
    private final String authorization;
    private final String apiCode;

    public LiveNactvetCurriculumClient(@Value("${cims.nactvet.awards.base-url:https://www.nacte.go.tz}") String baseUrl,
                                       @Value("${cims.nactvet.curriculum.path:/nacteapi/index.php/api-out-service/curriculum-details}") String path,
                                       @Value("${cims.nactvet.awards.authorization:}") String authorization,
                                       @Value("${cims.nactvet.awards.api-code:}") String apiCode,
                                       @Value("${cims.nactvet.awards.prefer-ip:}") String preferIp) {
        if (authorization == null || authorization.isBlank() || apiCode == null || apiCode.isBlank()) {
            throw new IllegalStateException("cims.nactvet.curriculum.mode=live needs NACTE_AUTHORIZATION and NACTE_API_CODE");
        }
        String host;
        try {
            host = new URI(baseUrl).getHost();
        } catch (Exception e) {
            host = "www.nacte.go.tz";
        }
        ReachableIpRequestFactory factory = new ReachableIpRequestFactory(host, preferIp == null || preferIp.isBlank() ? null : preferIp);
        factory.setConnectTimeout(Duration.ofSeconds(8));
        factory.setReadTimeout(Duration.ofSeconds(40));
        this.http = tz.co.msingi.cims.common.web.Json2Http.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.path = path;
        this.authorization = authorization;
        this.apiCode = apiCode;
        log.info("NACTVET curriculum: live at {}{}", baseUrl, path);
    }

    @Override
    public Semester fetch(String programmeId, String academicYear, String level, String semester, String intake) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("authorization", authorization);
        body.put("api_code", apiCode);
        body.put("programme_id", programmeId);
        body.put("academic_year", academicYear);
        body.put("level", level);
        body.put("semester", semester);
        body.put("intake", intake);
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                JsonNode root = http.post().uri(path).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                        .body(body).retrieve().body(JsonNode.class);
                if (root == null) throw new NactvetUnavailableException("NACTVET gave an empty answer", null);
                return parse(root);
            } catch (NactvetUnavailableException e) {
                throw e;
            } catch (RuntimeException e) {
                last = e;
                log.warn("NACTVET curriculum {} {} {} attempt {}/{} failed: {}", level, semester, intake, attempt, MAX_ATTEMPTS, e.getMessage());
            }
        }
        throw new NactvetUnavailableException("NACTVET did not answer the curriculum request. Check that this server can reach www.nacte.go.tz.", last);
    }

    static Semester parse(JsonNode root) {
        int code = root.get("code") == null ? 200 : root.get("code").asInt(200);
        String message = text(root, "message", "msg", "error");
        if (code == 401 || code == 403 || (message != null && message.toLowerCase(Locale.ROOT).contains("forbidden"))) {
            throw new NactvetUnavailableException("NACTVET refused the curriculum request (" + (message == null ? code : message)
                    + "). Check NACTE_AUTHORIZATION and NACTE_API_CODE.", null);
        }
        JsonNode rows = root.get("params");
        if (rows == null || !rows.isArray()) rows = root.get("data");
        if (rows == null || !rows.isArray()) rows = root.get("modules");
        List<ModuleRow> modules = new ArrayList<>();
        if (rows != null && rows.isArray()) {
            for (JsonNode r : rows) {
                String mcode = text(r, "module_code", "modulecode", "code");
                if (mcode == null) continue;
                TreeSet<String> fields = new TreeSet<>();
                r.properties().forEach(e -> fields.add(e.getKey()));
                modules.add(new ModuleRow(mcode.trim(), text(r, "module_name", "module_title", "modulename", "title", "name", "module"),
                        number(r, "credits", "credit", "module_credits", "module_credit", "credit_value", "credit_points", "credit_hours"),
                        elective(text(r, "module_type", "type", "category", "module_category", "status", "nature")), String.join(",", fields)));
            }
        }
        String curriculum = text(root, "curriculum_code", "curriculumcode");
        if (curriculum == null && !modules.isEmpty() && rows.get(0) != null) curriculum = text(rows.get(0), "curriculum_code");
        return new Semester(curriculum, text(root, "programme_name", "programme"), modules, code == 200 ? null : message);
    }

    private static Boolean elective(String type) {
        if (type == null) return null;
        String t = type.toLowerCase(Locale.ROOT);
        if (t.contains("elect") || t.contains("option")) return true;
        if (t.contains("core") || t.contains("compuls") || t.contains("fundamental") || t.contains("major")) return false;
        return null;
    }

    private static String text(JsonNode node, String... names) {
        for (String n : names) {
            JsonNode v = node.get(n);
            if (v != null && !v.isNull() && !v.isContainer() && !v.asString().isBlank()) return v.asString().trim();
        }
        return null;
    }

    private static BigDecimal number(JsonNode node, String... names) {
        for (String n : names) {
            JsonNode v = node.get(n);
            if (v == null || v.isNull()) continue;
            try {
                if (v.isNumber()) return v.decimalValue();
                String s = v.asString().trim();
                if (s.matches("\\d+(\\.\\d+)?")) return new BigDecimal(s);
            } catch (RuntimeException ignored) {
                // not a number: try the next name
            }
        }
        return null;
    }
}
