package tz.co.msingi.cims.integration.muse;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tz.co.msingi.cims.integration.http.JsonApi;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static tz.co.msingi.cims.integration.http.JsonApi.text;

/**
 * MUSE over HTTPS (JSON, {@code X-Api-Key}). The same batch reference posted twice is answered 409 with the journal already
 * posted, so a retry after a lost answer never posts twice. Placeholder path until the Treasury issues the interface document.
 * Select with {@code CIMS_MUSE_MODE=live}.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cims.muse", name = "mode", havingValue = "live")
public class LiveMuseClient implements MuseClient {

    private final JsonApi api;
    private final String journalsPath;

    public LiveMuseClient(@Value("${cims.muse.base-url}") String baseUrl, @Value("${cims.muse.api-key:}") String apiKey,
                          @Value("${cims.muse.journals-path}") String journalsPath) {
        this.api = new JsonApi("MUSE", baseUrl, apiKey, 8, 60);
        this.journalsPath = journalsPath;
        log.info("MUSE: live at {}", baseUrl);
    }

    @Override
    public PostResult post(Journal j) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vote_code", j.voteCode());
        body.put("batch_ref", j.batchRef());
        body.put("period_from", j.periodFrom().toString());
        body.put("period_to", j.periodTo().toString());
        body.put("posting_date", j.postingDate().toString());
        body.put("currency", j.currency());
        body.put("lines", j.lines().stream().map(l -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("account", l.account());
            m.put("gfs_code", l.gfsCode());
            m.put("description", l.description());
            m.put("debit", l.debit());
            m.put("credit", l.credit());
            return m;
        }).toList());
        JsonApi.Answer a = api.post(journalsPath, body, Set.of(200, 201, 409, 422));
        JsonNode r = a.body();
        if (a.status() == 409) return new PostResult("DUPLICATE", text(r, "muse_ref"), "Already posted");
        if (a.status() == 422 || !"POSTED".equalsIgnoreCase(text(r, "status"))) {
            return new PostResult("REJECTED", null, r == null ? "Refused" : String.valueOf(text(r, "message")));
        }
        return new PostResult("POSTED", text(r, "muse_ref"), text(r, "message"));
    }
}
