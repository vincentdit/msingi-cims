package tz.co.msingi.cims.integration.nida;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tz.co.msingi.cims.integration.http.JsonApi;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static tz.co.msingi.cims.integration.http.JsonApi.text;

/**
 * NIDA over HTTPS (JSON, {@code X-Api-Key}). The path and fields are placeholders until NIDA grants the institution access to
 * its verification service; gov-mocks answers them. Select with {@code CIMS_NIDA_MODE=live}. The NIN is never logged.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cims.nida", name = "mode", havingValue = "live")
public class LiveNidaClient implements NidaClient {

    private final JsonApi api;
    private final String verifyPath;

    public LiveNidaClient(@Value("${cims.nida.base-url}") String baseUrl, @Value("${cims.nida.api-key:}") String apiKey,
                          @Value("${cims.nida.verify-path}") String verifyPath) {
        this.api = new JsonApi("NIDA", baseUrl, apiKey, 8, 30);
        this.verifyPath = verifyPath;
        log.info("NIDA: live at {}", baseUrl);
    }

    @Override
    public Result verify(String nin, String firstName, String surname, LocalDate dateOfBirth) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nin", nin);
        body.put("first_name", firstName);
        body.put("surname", surname);
        body.put("date_of_birth", dateOfBirth == null ? null : dateOfBirth.toString());
        JsonNode r = api.post(verifyPath, body, Set.of(200)).body();
        String status = text(r, "status");
        return new Result(status == null ? "INVALID" : status.toUpperCase(), text(r, "first_name"), text(r, "surname"));
    }
}
