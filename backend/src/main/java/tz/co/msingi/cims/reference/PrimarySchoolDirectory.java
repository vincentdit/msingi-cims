package tz.co.msingi.cims.reference;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

/**
 * Tanzania's primary schools as listed in NECTA's PSLE 2025 results (the list NOAS uses), for the applicant's
 * primary school picker. Searched by name, code, region or council; names starting with the search come first.
 */
@Slf4j
@Service
public class PrimarySchoolDirectory {

    public record School(String code, String name, String region, String council) {
    }

    private static final int MAX_RESULTS = 25;
    private List<School> schools = List.of();

    @PostConstruct
    void load() {
        List<School> list = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(new ClassPathResource("geo/primary_schools.tsv.gz").getInputStream()), StandardCharsets.UTF_8))) {
            in.readLine(); // header
            for (String line; (line = in.readLine()) != null; ) {
                String[] f = line.split("\t", -1);
                if (f.length >= 4 && !f[1].isBlank()) list.add(new School(f[0], f[1], f[2], f[3]));
            }
            list.sort(Comparator.comparing(School::name).thenComparing(School::code));
            schools = List.copyOf(list);
            log.info("Primary schools loaded: {}", schools.size());
        } catch (Exception e) {
            log.warn("Primary school list could not be loaded; the picker will accept typed names only: {}", e.getMessage());
        }
    }

    public List<School> search(String q) {
        String query = q == null ? "" : q.trim().toUpperCase(Locale.ROOT);
        if (query.length() < 2) return List.of();
        List<School> starts = new ArrayList<>();
        List<School> contains = new ArrayList<>();
        for (School s : schools) {
            if (s.name().startsWith(query) || s.code().equals(query)) {
                starts.add(s);
            } else if (contains.size() < MAX_RESULTS && (s.name().contains(query) || s.code().startsWith(query)
                    || s.region().contains(query) || s.council().contains(query))) {
                contains.add(s);
            }
            if (starts.size() >= MAX_RESULTS) break;
        }
        starts.addAll(contains);
        return starts.size() > MAX_RESULTS ? starts.subList(0, MAX_RESULTS) : starts;
    }

    public Optional<School> byCode(String code) {
        if (code == null || code.isBlank()) return Optional.empty();
        String c = code.trim().toUpperCase(Locale.ROOT);
        return schools.stream().filter(s -> s.code().equals(c)).findFirst();
    }
}
