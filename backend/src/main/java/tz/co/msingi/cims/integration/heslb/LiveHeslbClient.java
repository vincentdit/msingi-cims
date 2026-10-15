package tz.co.msingi.cims.integration.heslb;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tz.co.msingi.cims.integration.http.JsonApi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static tz.co.msingi.cims.integration.http.JsonApi.text;

/**
 * HESLB over HTTPS (JSON, {@code X-Api-Key}). The paths and field names are placeholders until HESLB issues the institution its
 * interface document; they are configuration ({@code cims.heslb.*}) and gov-mocks answers them. Select with {@code CIMS_HESLB_MODE=live}.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cims.heslb", name = "mode", havingValue = "live")
public class LiveHeslbClient implements HeslbClient {

    private final JsonApi api;
    private final String registrationsPath;
    private final String beneficiariesPath;
    private final String disbursementsPath;

    public LiveHeslbClient(@Value("${cims.heslb.base-url}") String baseUrl, @Value("${cims.heslb.api-key:}") String apiKey,
                           @Value("${cims.heslb.registrations-path}") String registrationsPath,
                           @Value("${cims.heslb.beneficiaries-path}") String beneficiariesPath,
                           @Value("${cims.heslb.disbursements-path}") String disbursementsPath) {
        this.api = new JsonApi("HESLB", baseUrl, apiKey, 8, 60);
        this.registrationsPath = registrationsPath;
        this.beneficiariesPath = beneficiariesPath;
        this.disbursementsPath = disbursementsPath;
        log.info("HESLB: live at {}", baseUrl);
    }

    @Override
    public SubmissionResult sendRegistrations(String institutionCode, String academicYear, String kind, List<Student> students) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("institution_code", institutionCode);
        body.put("academic_year", academicYear);
        body.put("kind", kind);
        body.put("students", students.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index_number", s.indexNumber());
            m.put("registration_no", s.registrationNo());
            m.put("first_name", s.firstName());
            m.put("middle_name", s.middleName());
            m.put("surname", s.surname());
            m.put("sex", s.sex());
            m.put("programme_code", s.programmeCode());
            m.put("programme_name", s.programmeName());
            m.put("year_of_study", s.yearOfStudy());
            m.put("status", s.status());
            m.put("phone", s.phone());
            return m;
        }).toList());
        JsonNode r = api.post(registrationsPath, body, Set.of(200, 201)).body();
        List<Rejected> rejected = new ArrayList<>();
        for (JsonNode x : r.path("rejected")) rejected.add(new Rejected(text(x, "registration_no"), text(x, "reason")));
        return new SubmissionResult(text(r, "submission_ref"), r.path("received").asInt(students.size()), r.path("accepted").asInt(), rejected);
    }

    @Override
    public List<Beneficiary> beneficiaries(String institutionCode, String academicYear) {
        JsonNode r = api.get(beneficiariesPath, Map.of("institution_code", institutionCode, "academic_year", academicYear));
        List<Beneficiary> out = new ArrayList<>();
        for (JsonNode b : r.path("beneficiaries")) {
            BigDecimal toInst = b.hasNonNull("total_to_institution") ? new BigDecimal(b.get("total_to_institution").asText()) : institutionShare(b);
            out.add(new Beneficiary(text(b, "index_number"), text(b, "registration_no"), text(b, "full_name"), text(b, "loan_number"), toInst));
        }
        return out;
    }

    @Override
    public List<Disbursement> disbursements(String institutionCode, String academicYear) {
        JsonNode r = api.get(disbursementsPath, Map.of("institution_code", institutionCode, "academic_year", academicYear));
        List<Disbursement> out = new ArrayList<>();
        for (JsonNode d : r.path("disbursements")) {
            List<DisbursedStudent> st = new ArrayList<>();
            for (JsonNode s : d.path("students")) {
                st.add(new DisbursedStudent(text(s, "index_number"), text(s, "registration_no"), text(s, "loan_number"), new BigDecimal(s.path("amount").asText("0"))));
            }
            out.add(new Disbursement(text(d, "batch_no"), LocalDate.parse(text(d, "disbursed_on").substring(0, 10)), new BigDecimal(d.path("amount").asText("0")), st));
        }
        return out;
    }

    private static BigDecimal institutionShare(JsonNode b) {
        BigDecimal t = BigDecimal.ZERO;
        for (JsonNode a : b.path("allocations")) {
            if ("INSTITUTION".equalsIgnoreCase(a.path("payable_to").asText())) t = t.add(new BigDecimal(a.path("amount").asText("0")));
        }
        return t;
    }
}
