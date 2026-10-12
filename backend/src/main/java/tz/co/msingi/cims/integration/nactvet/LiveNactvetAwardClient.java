package tz.co.msingi.cims.integration.nactvet;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tz.co.msingi.cims.integration.http.ReachableIpRequestFactory;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * NACTVET's particulars service, as used by NOAS: a JSON POST to {@code /nacteapi/index.php/api-out-service/particulars}
 * with the authorization token, API code, registration number and graduation year. The award is the first entry of
 * {@code params}; a non-2xx {@code code} is an error. The credentials are never kept in the code: set
 * {@code NACTE_AUTHORIZATION} and {@code NACTE_API_CODE}.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cims.nactvet.awards", name = "mode", havingValue = "live")
public class LiveNactvetAwardClient implements NactvetAwardClient {

    private final RestClient http;
    private final String path;
    private final String authorization;
    private final String apiCode;

    public LiveNactvetAwardClient(@Value("${cims.nactvet.awards.base-url:https://www.nacte.go.tz}") String baseUrl,
                                  @Value("${cims.nactvet.awards.particulars-path:/nacteapi/index.php/api-out-service/particulars}") String path,
                                  @Value("${cims.nactvet.awards.authorization:}") String authorization,
                                  @Value("${cims.nactvet.awards.api-code:}") String apiCode,
                                  @Value("${cims.nactvet.awards.prefer-ip:}") String preferIp) {
        if (authorization == null || authorization.isBlank() || apiCode == null || apiCode.isBlank()) {
            throw new IllegalStateException("cims.nactvet.awards.mode=live needs NACTE_AUTHORIZATION and NACTE_API_CODE");
        }
        String host;
        try {
            host = new URI(baseUrl).getHost();
        } catch (Exception e) {
            host = "www.nacte.go.tz";
        }
        ReachableIpRequestFactory factory = new ReachableIpRequestFactory(host, preferIp == null || preferIp.isBlank() ? null : preferIp);
        factory.setConnectTimeout(Duration.ofSeconds(8));
        factory.setReadTimeout(Duration.ofSeconds(45));
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.path = path;
        this.authorization = authorization;
        this.apiCode = apiCode;
        log.info("NACTVET particulars: live at {}{}", baseUrl, path);
    }

    @Override
    public Optional<NtaAward> fetchAward(String registrationNumber, int graduationYear) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("authorization", authorization);
        body.put("api_code", apiCode);
        body.put("registration_number", registrationNumber);
        body.put("graduation_year", graduationYear);
        JsonNode root;
        try {
            root = http.post().uri(path).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().body(JsonNode.class);
        } catch (Exception e) {
            log.warn("NACTVET particulars request failed for {} {}: {}", registrationNumber, graduationYear, e.getMessage());
            throw new NactvetUnavailableException("NACTVET could not be reached. Please try again in a few minutes.", e);
        }
        if (root == null) return Optional.empty();
        Integer code = code(root);
        if (code != null && code != 200 && code != 201 && code != 202) {
            if (code == 404) return Optional.empty();
            throw new NactvetUnavailableException("NACTVET refused the request (code " + code + "). Please try again later.", null);
        }
        JsonNode params = root.path("params");
        if (!params.isArray() || params.isEmpty()) return Optional.empty();
        JsonNode r = params.get(0);
        List<Module> modules = new ArrayList<>();
        for (JsonNode m : r.path("results")) {
            modules.add(new Module(text(m, "module_code", "code"), text(m, "module_name", "name"), text(m, "grade"),
                    integer(m, "credit", "credits"), text(m, "semester")));
        }
        return Optional.of(new NtaAward(Optional.ofNullable(text(r, "registration_number")).orElse(registrationNumber), graduationYear,
                name(text(r, "firstname", "first_name")), name(text(r, "middle_name", "middlename")), name(text(r, "surname", "last_name")),
                text(r, "sex"), text(r, "institution"), Optional.ofNullable(text(r, "programme")).orElse(text(r, "programme_name", "programe_name")),
                NactvetAwardClient.level(text(r, "level", "programme_level")), gpa(text(r, "GPA", "gpa")), text(r, "class_award", "classaward"),
                text(r, "status"), text(r, "form_four_index_number"), modules));
    }

    private static Integer code(JsonNode root) {
        for (String k : new String[]{"code", "status_code", "statusCode"}) {
            JsonNode v = root.get(k);
            if (v != null && !v.isNull() && v.asText().trim().matches("\\d+")) return Integer.parseInt(v.asText().trim());
        }
        return null;
    }

    private static String text(JsonNode n, String... keys) {
        for (String k : keys) {
            JsonNode v = n.get(k);
            if (v != null && !v.isNull() && !v.asText().isBlank()) return v.asText().trim();
        }
        return null;
    }

    private static Integer integer(JsonNode n, String... keys) {
        String s = text(n, keys);
        return s != null && s.matches("\\d+") ? Integer.parseInt(s) : null;
    }

    private static BigDecimal gpa(String s) {
        try {
            return s == null ? null : new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String name(String s) {
        if (s == null) return null;
        StringBuilder out = new StringBuilder();
        boolean start = true;
        for (char c : s.toLowerCase().toCharArray()) {
            out.append(start ? Character.toUpperCase(c) : c);
            start = c == ' ' || c == '-' || c == '\'';
        }
        return out.toString();
    }
}
