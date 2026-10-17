package tz.co.msingi.cims.integration.nhif;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tz.co.msingi.cims.integration.http.JsonApi;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static tz.co.msingi.cims.integration.http.JsonApi.text;

/**
 * NHIF over HTTPS (JSON, {@code X-Api-Key}). The paths and field names are placeholders until NHIF issues the institution its
 * interface document; they are configuration ({@code cims.nhif.*}) and gov-mocks answers them. Select with {@code CIMS_NHIF_MODE=live}.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cims.nhif", name = "mode", havingValue = "live")
public class LiveNhifClient implements NhifClient {

    private final JsonApi api;
    private final String cardPath;
    private final String registrationsPath;
    private final String statusPath;

    public LiveNhifClient(@Value("${cims.nhif.base-url}") String baseUrl, @Value("${cims.nhif.api-key:}") String apiKey,
                          @Value("${cims.nhif.card-path}") String cardPath,
                          @Value("${cims.nhif.registrations-path}") String registrationsPath,
                          @Value("${cims.nhif.status-path}") String statusPath) {
        this.api = new JsonApi("NHIF", baseUrl, apiKey, 8, 60);
        this.cardPath = cardPath;
        this.registrationsPath = registrationsPath;
        this.statusPath = statusPath;
        log.info("NHIF: live at {}", baseUrl);
    }

    @Override
    public Card verifyCard(String cardNo) {
        JsonNode r = api.post(cardPath, Map.of("card_no", cardNo), Set.of(200)).body();
        return new Card(text(r, "card_no"), status(text(r, "status")), text(r, "full_name"), text(r, "scheme"), date(text(r, "valid_to")),
                text(r, "remarks"));
    }

    @Override
    public Submission registerStudents(String institutionCode, String academicYear, List<Student> students) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("institution_code", institutionCode);
        body.put("academic_year", academicYear);
        body.put("package", "STUDENT");
        body.put("students", students.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("registration_no", s.registrationNo());
            m.put("first_name", s.firstName());
            m.put("middle_name", s.middleName());
            m.put("surname", s.surname());
            m.put("sex", s.sex());
            m.put("date_of_birth", s.dateOfBirth() == null ? null : s.dateOfBirth().toString());
            m.put("nin", s.nin());
            m.put("phone", s.phone());
            m.put("email", s.email());
            m.put("programme", s.programmeName());
            m.put("year_of_study", s.yearOfStudy());
            return m;
        }).toList());
        JsonNode r = api.post(registrationsPath, body, Set.of(200, 201, 202)).body();
        List<Rejected> rejected = new ArrayList<>();
        for (JsonNode x : r.path("rejected")) rejected.add(new Rejected(text(x, "registration_no"), text(x, "reason")));
        return new Submission(text(r, "batch_ref"), r.path("received").asInt(students.size()), rejected);
    }

    @Override
    public Status registrationStatus(String institutionCode, String batchRef) {
        JsonNode r = api.get(statusPath, Map.of("institution_code", institutionCode, "batch_ref", batchRef));
        List<Item> items = new ArrayList<>();
        for (JsonNode x : r.path("items")) {
            items.add(new Item(text(x, "registration_no"), String.valueOf(text(x, "status")).toUpperCase(), text(x, "card_no"),
                    date(text(x, "valid_to")), text(x, "reason")));
        }
        String state = text(r, "state");
        return new Status(state == null ? "PROCESSING" : state.toUpperCase(), items);
    }

    private static String status(String s) {
        if (s == null) return "NOT_FOUND";
        String u = s.trim().toUpperCase();
        return u.equals("ACTIVE") || u.equals("INACTIVE") ? u : u.contains("NOT") ? "NOT_FOUND" : "INACTIVE";
    }

    private static LocalDate date(String s) {
        return s == null || s.length() < 10 ? null : LocalDate.parse(s.substring(0, 10));
    }
}
