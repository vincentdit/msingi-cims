package tz.co.msingi.cims.finance.aggregator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tz.co.msingi.cims.integration.http.ExternalSystemUnavailableException;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.function.Consumer;

/** HTTP for the aggregator adapters: JSON in and out, the answer's status kept, network failures as "unavailable". */
final class AggregatorHttp {

    static final ObjectMapper JSON = new ObjectMapper();
    private static final RestClient HTTP;

    static {
        JdkClientHttpRequestFactory f = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        f.setReadTimeout(Duration.ofSeconds(45));
        HTTP = RestClient.builder().requestFactory(f).build();
    }

    record Answer(int status, JsonNode body) {
        boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private AggregatorHttp() {
    }

    static Answer post(String system, String url, Object body, Consumer<HttpHeaders> headers) {
        return call(system, () -> HTTP.post().uri(url).contentType(body instanceof String ? MediaType.APPLICATION_FORM_URLENCODED : MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON).headers(headers).body(body)
                .exchange((req, res) -> new Answer(res.getStatusCode().value(), parse(res.getBody().readAllBytes())), false));
    }

    static Answer get(String system, String url, Consumer<HttpHeaders> headers) {
        return call(system, () -> HTTP.get().uri(url).accept(MediaType.APPLICATION_JSON).headers(headers)
                .exchange((req, res) -> new Answer(res.getStatusCode().value(), parse(res.getBody().readAllBytes())), false));
    }

    private static Answer call(String system, java.util.function.Supplier<Answer> s) {
        try {
            return s.get();
        } catch (RestClientException e) {
            throw new ExternalSystemUnavailableException(system, system + " could not be reached: " + e.getMessage(), e);
        }
    }

    static JsonNode parse(byte[] bytes) {
        try {
            return bytes.length == 0 ? JSON.nullNode() : JSON.readTree(bytes);
        } catch (Exception e) {
            return JSON.getNodeFactory().textNode(new String(bytes, StandardCharsets.UTF_8));
        }
    }

    static JsonNode parse(String s) {
        return parse(s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8));
    }

    static String text(JsonNode n, String... path) {
        JsonNode v = n;
        for (String p : path) {
            if (v == null) return null;
            v = v.get(p);
        }
        return v == null || v.isNull() || v.isMissingNode() || v.asText().isBlank() ? null : v.asText().trim();
    }

    static BigDecimal amount(JsonNode n, String... path) {
        String t = text(n, path);
        try {
            return t == null ? null : new BigDecimal(t.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static OffsetDateTime time(String s) {
        if (s == null) return null;
        try {
            return OffsetDateTime.parse(s);
        } catch (Exception e) {
            try {
                return java.time.LocalDateTime.parse(s.replace(' ', 'T')).atZone(java.time.ZoneId.of("Africa/Dar_es_Salaam")).toOffsetDateTime();
            } catch (Exception e2) {
                return null;
            }
        }
    }

    /** The aggregator's message from an error answer, for the person who must act on it. */
    static String why(Answer a) {
        if (a.body() == null || a.body().isNull()) return "HTTP " + a.status();
        for (String k : new String[]{"message", "msg", "error_description", "error", "title", "result"}) {
            String t = text(a.body(), k);
            if (t != null) return t + " (HTTP " + a.status() + ")";
        }
        String raw = a.body().toString();
        return (raw.length() > 200 ? raw.substring(0, 200) : raw) + " (HTTP " + a.status() + ")";
    }

    static Map<String, String> lower(Map<String, String> headers) {
        Map<String, String> m = new java.util.HashMap<>();
        headers.forEach((k, v) -> m.put(k.toLowerCase(java.util.Locale.ROOT), v));
        return m;
    }
}
