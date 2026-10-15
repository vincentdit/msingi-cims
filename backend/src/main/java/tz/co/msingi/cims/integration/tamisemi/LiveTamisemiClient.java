package tz.co.msingi.cims.integration.tamisemi;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tz.co.msingi.cims.integration.http.JsonApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static tz.co.msingi.cims.integration.http.JsonApi.text;

/**
 * TAMISEMI's selections over HTTPS (JSON, {@code X-Api-Key}). Placeholder path until TAMISEMI issues the interface document;
 * gov-mocks answers it. Select with {@code CIMS_TAMISEMI_MODE=live}.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cims.tamisemi", name = "mode", havingValue = "live")
public class LiveTamisemiClient implements TamisemiClient {

    private final JsonApi api;
    private final String path;

    public LiveTamisemiClient(@Value("${cims.tamisemi.base-url}") String baseUrl, @Value("${cims.tamisemi.api-key:}") String apiKey,
                              @Value("${cims.tamisemi.selections-path}") String path) {
        this.api = new JsonApi("TAMISEMI", baseUrl, apiKey, 8, 60);
        this.path = path;
        log.info("TAMISEMI: live at {}", baseUrl);
    }

    @Override
    public List<Selection> selections(String institutionCode, int year) {
        JsonNode r = api.get(path, Map.of("institution_code", institutionCode, "year", year));
        List<Selection> out = new ArrayList<>();
        for (JsonNode s : r.path("selections")) {
            out.add(new Selection(text(s, "index_number"), s.hasNonNull("exam_year") ? s.get("exam_year").asInt() : null, text(s, "first_name"),
                    text(s, "middle_name"), text(s, "surname"), text(s, "sex"), text(s, "date_of_birth"), text(s, "nationality"), text(s, "phone"),
                    text(s, "programme_code"), text(s, "reference")));
        }
        return out;
    }
}
