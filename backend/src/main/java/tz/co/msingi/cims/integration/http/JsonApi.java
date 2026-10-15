package tz.co.msingi.cims.integration.http;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * A small JSON-over-HTTPS client for the government exchanges that authenticate with an API key ({@code X-Api-Key}): HESLB,
 * NIDA, MUSE and TAMISEMI. Answers in {@link #accepted} statuses are returned to the caller as JSON; anything else, and any
 * failure to connect, becomes an {@link ExternalSystemUnavailableException} naming the system, so nothing is half-decided.
 */
public final class JsonApi {

    private final String system;
    private final RestClient http;

    public JsonApi(String system, String baseUrl, String apiKey, int connectSeconds, int readSeconds) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(system + " is set to live but has no API key (CIMS_" + system + "_API_KEY)");
        }
        JdkClientHttpRequestFactory f = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(connectSeconds)).build());
        f.setReadTimeout(Duration.ofSeconds(readSeconds));
        this.system = system;
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(f).defaultHeader("X-Api-Key", apiKey.trim()).build();
    }

    public record Answer(int status, JsonNode body) {
    }

    public JsonNode get(String path, Map<String, ?> query) {
        return call(() -> http.get().uri(b -> {
            b.path(path);
            query.forEach(b::queryParam);
            return b.build();
        }).accept(MediaType.APPLICATION_JSON).exchange((req, res) -> answer(res.getStatusCode(), res.bodyTo(JsonNode.class))), Set.of(200)).body();
    }

    public Answer post(String path, Object body, Set<Integer> accepted) {
        return call(() -> http.post().uri(path).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).body(body)
                .exchange((req, res) -> answer(res.getStatusCode(), res.bodyTo(JsonNode.class))), accepted);
    }

    private static Answer answer(HttpStatusCode code, JsonNode body) {
        return new Answer(code.value(), body);
    }

    private Answer call(java.util.function.Supplier<Answer> s, Set<Integer> accepted) {
        Answer a;
        try {
            a = s.get();
        } catch (RestClientResponseException e) {
            throw new ExternalSystemUnavailableException(system, system + " answered " + e.getStatusCode().value() + ". Please try again later.", e);
        } catch (RestClientException e) {
            throw new ExternalSystemUnavailableException(system, system + " could not be reached. Please try again later.", e);
        }
        if (!accepted.contains(a.status())) {
            String msg = a.body() != null && a.body().hasNonNull("error") ? a.body().get("error").asText()
                    : a.body() != null && a.body().hasNonNull("message") ? a.body().get("message").asText() : "HTTP " + a.status();
            throw new ExternalSystemUnavailableException(system, system + " did not accept the request: " + msg, null);
        }
        return a;
    }

    public static String text(JsonNode n, String key) {
        JsonNode v = n == null ? null : n.get(key);
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText().trim();
    }
}
