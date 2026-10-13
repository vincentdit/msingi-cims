package tz.co.msingi.cims.integration.nactvet;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tz.co.msingi.cims.integration.http.ReachableIpRequestFactory;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NACTVET's institution/programme catalogue, as NOAS reads it: a JSON POST to
 * {@code /nacteapi/index.php/api-out-service/institutionprogrammeall} with the authorization token, API code, page number and
 * page size; rows come under {@code data} with a {@code pagination.total_pages}. Every page is read. Some NACTVET addresses do
 * not answer, so the client connects to one that does (TLS and Host stay bound to the host name). Falls back to sending the
 * credentials as query parameters when the body is refused, as NOAS does.
 *
 * Active when {@code cims.nactvet.catalogue.mode=live}; uses the NACTE credentials of the awards service.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cims.nactvet.catalogue", name = "mode", havingValue = "live")
public class LiveNactvetCatalogueClient implements NactvetCatalogueClient {

    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_PAGES = 1000;

    private final RestClient http;
    private final String path;
    private final String authorization;
    private final String apiCode;
    private final int pageSize;

    public LiveNactvetCatalogueClient(@Value("${cims.nactvet.awards.base-url:https://www.nacte.go.tz}") String baseUrl,
                                      @Value("${cims.nactvet.catalogue.catalogue-path:/nacteapi/index.php/api-out-service/institutionprogrammeall}") String path,
                                      @Value("${cims.nactvet.awards.authorization:}") String authorization,
                                      @Value("${cims.nactvet.awards.api-code:}") String apiCode,
                                      @Value("${cims.nactvet.catalogue.page-size:200}") int pageSize,
                                      @Value("${cims.nactvet.awards.prefer-ip:}") String preferIp) {
        if (authorization == null || authorization.isBlank() || apiCode == null || apiCode.isBlank()) {
            throw new IllegalStateException("cims.nactvet.catalogue.mode=live needs NACTE_AUTHORIZATION and NACTE_API_CODE");
        }
        String host;
        try {
            host = new URI(baseUrl).getHost();
        } catch (Exception e) {
            host = "www.nacte.go.tz";
        }
        ReachableIpRequestFactory factory = new ReachableIpRequestFactory(host, preferIp == null || preferIp.isBlank() ? null : preferIp);
        factory.setConnectTimeout(Duration.ofSeconds(8));
        factory.setReadTimeout(Duration.ofSeconds(90));
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.path = path;
        this.authorization = authorization;
        this.apiCode = apiCode;
        this.pageSize = Math.max(10, Math.min(pageSize, 1000));
        log.info("NACTVET catalogue: live at {}{}", baseUrl, path);
    }

    @Override
    public List<Offering> fetchOfferings() {
        JsonNode first = null;
        String used = null;
        for (String strategy : List.of("BODY", "QUERY")) {
            JsonNode root = request(strategy, 1);
            if (root == null || forbidden(root)) continue;
            first = root;
            used = strategy;
            break;
        }
        if (first == null) {
            throw new NactvetUnavailableException("NACTVET refused or did not answer the catalogue request. Check NACTE_AUTHORIZATION, "
                    + "NACTE_API_CODE and that this server can reach www.nacte.go.tz.", null);
        }
        int pages = Math.min(totalPages(first), MAX_PAGES);
        Map<String, Offering> byKey = new LinkedHashMap<>();
        add(byKey, parse(first));
        for (int page = 2; page <= pages; page++) {
            JsonNode root = request(used, page);
            if (root == null || forbidden(root)) {
                throw new NactvetUnavailableException("NACTVET stopped answering at page " + page + " of " + pages + ". Try the sync again.", null);
            }
            List<Offering> rows = parse(root);
            if (rows.isEmpty()) break;
            int before = byKey.size();
            add(byKey, rows);
            if (byKey.size() == before) {
                log.warn("NACTVET catalogue page {} added nothing new; stopping", page);
                break;
            }
        }
        log.info("NACTVET catalogue: {} offerings from {} page(s)", byKey.size(), pages);
        return new ArrayList<>(byKey.values());
    }

    private JsonNode request(String strategy, int page) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                if ("BODY".equals(strategy)) {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("authorization", authorization);
                    body.put("api_code", apiCode);
                    body.put("page_number", page);
                    body.put("page_size", pageSize);
                    return http.post().uri(path).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                            .body(body).retrieve().body(JsonNode.class);
                }
                String uri = UriComponentsBuilder.fromPath(path).queryParam("authorization", authorization).queryParam("api_code", apiCode)
                        .queryParam("page_number", page).queryParam("page_size", pageSize).build().toUriString();
                return http.get().uri(uri).accept(MediaType.APPLICATION_JSON).retrieve().body(JsonNode.class);
            } catch (Exception e) {
                log.warn("NACTVET catalogue {} page {} attempt {}/{} failed: {}", strategy, page, attempt, MAX_ATTEMPTS, e.getMessage());
            }
        }
        return null;
    }

    private static void add(Map<String, Offering> byKey, List<Offering> rows) {
        for (Offering o : rows) {
            String key = o.programmeId() != null ? o.programmeId() : o.institutionId() + "|" + o.programmeName();
            byKey.putIfAbsent(key, o);
        }
    }

    private static boolean forbidden(JsonNode root) {
        JsonNode code = root.get("code");
        if (code != null && code.asInt(0) == 403) return true;
        JsonNode msg = root.get("message");
        return msg != null && msg.asText("").toLowerCase().contains("forbidden");
    }

    private static int totalPages(JsonNode root) {
        JsonNode p = root.get("pagination");
        return p != null && p.get("total_pages") != null ? Math.max(1, p.get("total_pages").asInt(1)) : 1;
    }

    static List<Offering> parse(JsonNode root) {
        JsonNode rows = locate(root);
        List<Offering> out = new ArrayList<>();
        if (rows == null || !rows.isArray()) return out;
        for (JsonNode r : rows) {
            String programmeId = first(r, "programme_id", "programe_id", "program_id");
            String programmeName = first(r, "programme", "programme_name", "program_name");
            if (programmeId == null && programmeName == null) continue;
            String[] nta = ntaEntry(r);
            out.add(new Offering(
                    first(r, "institution_id", "institutionId"), first(r, "institution", "institution_name", "institutionname"),
                    first(r, "institution_code", "institutioncode"), first(r, "region", "region_name"), first(r, "district", "district_name"),
                    first(r, "registration_number", "reg_number"), first(r, "mobile_number", "phone", "mobile"),
                    first(r, "ownership", "ownership_type", "owner"),
                    programmeId, programmeName, first(r, "programme_level", "nta_level", "level"), first(r, "course_code", "coursecode"),
                    first(r, "board_name", "boardname"), first(r, "board_code", "boardcode"), first(r, "category_name", "categoryname"),
                    first(r, "category_code", "categorycode"), first(r, "programme_fee", "program_fee", "fee"),
                    first(r, "entry_requirement", "entry_requirements"), first(r, "selection_criteria", "selectioncriteria"),
                    joinArray(r, "specefic_necta_requirement", "specific_necta_requirement"),
                    joinArray(r, "specefic_nta_requirement", "specific_nta_requirement"),
                    nta[0], nta[1],
                    firstInt(r, "duration", "duration_years"), firstInt(r, "male_capacity", "malecapacity"),
                    firstInt(r, "female_capacity", "femalecapacity"), firstInt(r, "total_capacity", "capacity", "totalcapacity")));
        }
        return out;
    }

    private static JsonNode locate(JsonNode root) {
        if (root.isArray()) return root;
        for (String key : List.of("data", "params", "results", "result", "institutions", "programmes", "items", "records")) {
            JsonNode n = root.get(key);
            if (n != null && n.isArray()) return n;
        }
        var it = root.fields();
        while (it.hasNext()) {
            var e = it.next();
            if (e.getValue().isArray()) return e.getValue();
        }
        return null;
    }

    private static String first(JsonNode node, String... names) {
        for (String n : names) {
            JsonNode v = node.get(n);
            if (v != null && !v.isNull() && !v.isContainerNode() && !v.asText().isBlank()) return v.asText().trim();
        }
        return null;
    }

    private static Integer firstInt(JsonNode node, String... names) {
        for (String n : names) {
            JsonNode v = node.get(n);
            if (v != null && v.isNumber()) return v.asInt();
            if (v != null && v.isTextual() && v.asText().trim().matches("\\d+")) return Integer.parseInt(v.asText().trim());
        }
        return null;
    }

    /** A JSON array of subject requirements as a comma list ("Mathematics:D,Physics:D" or names only). */
    private static String joinArray(JsonNode node, String... names) {
        JsonNode arr = null;
        for (String n : names) {
            JsonNode c = node.get(n);
            if (c != null && c.isArray()) {
                arr = c;
                break;
            }
        }
        if (arr == null || arr.isEmpty()) return null;
        List<String> parts = new ArrayList<>();
        for (JsonNode el : arr) {
            if (el.isTextual() && !el.asText().isBlank()) {
                parts.add(el.asText().trim());
            } else if (el.isObject()) {
                String label = first(el, "subject_name", "name", "subject", "programme_dip_name");
                if (label == null) label = first(el, "subject_code", "code", "programme_dip_code", "abbreviation");
                if (label == null) continue;
                String grade = first(el, "minimum_grade", "grade", "minimum_points", "min_points", "points", "minimum_GPA", "minimum_gpa");
                parts.add(grade != null ? label + ":" + grade : label);
            }
        }
        return parts.isEmpty() ? null : String.join(",", parts);
    }

    /** The lowest minimum GPA and its exam type (7 = holder of NTA 4, 8 = holder of NTA 5) among the NTA routes, as in NOAS. */
    private static String[] ntaEntry(JsonNode node) {
        JsonNode arr = node.get("specefic_nta_requirement");
        if (arr == null || !arr.isArray()) arr = node.get("specific_nta_requirement");
        String[] out = {null, null};
        if (arr == null || !arr.isArray()) return out;
        double best = Double.MAX_VALUE;
        for (JsonNode el : arr) {
            if (!el.isObject()) continue;
            String gpa = first(el, "minimum_GPA", "minimum_gpa", "min_gpa", "gpa");
            double g;
            try {
                g = gpa == null ? 0 : Double.parseDouble(gpa);
            } catch (NumberFormatException e) {
                g = 0;
            }
            if (out[0] == null || g < best) {
                best = g;
                out[0] = gpa;
                out[1] = first(el, "exam_type", "examtype");
            }
        }
        return out;
    }
}
