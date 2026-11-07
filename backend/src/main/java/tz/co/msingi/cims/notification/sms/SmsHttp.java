package tz.co.msingi.cims.notification.sms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tz.co.msingi.cims.integration.http.ExternalSystemUnavailableException;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.function.Consumer;

/** HTTP for the SMS provider adapters: JSON in and out, the answer's status kept, network failures as "unavailable". */
final class SmsHttp {

    static final ObjectMapper JSON = new ObjectMapper();
    private static final RestClient HTTP;

    static {
        JdkClientHttpRequestFactory f = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        f.setReadTimeout(Duration.ofSeconds(30));
        HTTP = RestClient.builder().requestFactory(f).build();
    }

    record Answer(int status, JsonNode body) {
        boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private SmsHttp() {
    }

    static Answer post(String system, String url, Object body, Consumer<HttpHeaders> headers) {
        try {
            return HTTP.post().uri(url).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).headers(headers).body(body)
                    .exchange((req, res) -> new Answer(res.getStatusCode().value(), parse(res.getBody().readAllBytes())), false);
        } catch (RestClientException e) {
            throw new ExternalSystemUnavailableException(system, system + " could not be reached: " + e.getMessage(), e);
        }
    }

    static Answer get(String system, String url, Consumer<HttpHeaders> headers) {
        try {
            return HTTP.get().uri(url).accept(MediaType.APPLICATION_JSON).headers(headers)
                    .exchange((req, res) -> new Answer(res.getStatusCode().value(), parse(res.getBody().readAllBytes())), false);
        } catch (RestClientException e) {
            throw new ExternalSystemUnavailableException(system, system + " could not be reached: " + e.getMessage(), e);
        }
    }

    static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString(((user == null ? "" : user) + ":" + (password == null ? "" : password))
                .getBytes(StandardCharsets.UTF_8));
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

    /** The provider's message from an error answer, for the person who must act on it. */
    static String why(Answer a) {
        if (a.body() == null || a.body().isNull()) return "HTTP " + a.status();
        for (String k : new String[]{"message", "error", "detail", "description", "msg"}) {
            String t = text(a.body(), k);
            if (t != null) return t + " (HTTP " + a.status() + ")";
            JsonNode d = a.body().path("data");
            t = text(d, k);
            if (t != null) return t + " (HTTP " + a.status() + ")";
        }
        String raw = a.body().toString();
        return (raw.length() > 200 ? raw.substring(0, 200) : raw) + " (HTTP " + a.status() + ")";
    }

    static String trimUrl(String url) {
        return url == null ? null : url.replaceAll("/+$", "");
    }

    /** A provider's word for a message's fate, as DELIVERED, FAILED or PENDING. */
    static String outcome(String providerStatus) {
        if (providerStatus == null) return "PENDING";
        String s = providerStatus.toUpperCase(Locale.ROOT);
        if (s.startsWith("DELIVERED") || s.equals("DELIVRD") || s.equals("SUCCESS")) return "DELIVERED";
        if (s.contains("UNDELIV") || s.startsWith("FAIL") || s.startsWith("REJECT") || s.startsWith("EXPIRED") || s.contains("BLACKLIST")
                || s.equals("INVALID")) {
            return "FAILED";
        }
        return "PENDING";
    }
}
