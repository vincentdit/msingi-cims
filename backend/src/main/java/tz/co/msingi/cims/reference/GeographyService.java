package tz.co.msingi.cims.reference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

/**
 * Tanzania's administrative areas — regions, districts, wards and villages/streets (mitaa) — for the cascading
 * address pickers of applicants and their next of kin. The data is the set NOAS ships, kept gzipped under
 * {@code geo/} and loaded once. Look-ups are by name and ignore case. If the data cannot be loaded the lists
 * are empty and addresses are not checked, rather than stopping the application.
 */
@Slf4j
@Service
public class GeographyService {

    private final ObjectMapper mapper = new ObjectMapper();
    private List<String> regions = List.of();
    private final Map<String, String> regionSlug = new HashMap<>();                 // lower(name) -> slug
    private final Map<String, String> regionName = new HashMap<>();                 // slug -> name
    private final Map<String, List<String>> districts = new HashMap<>();            // region slug -> names
    private final Map<String, String> districtSlug = new HashMap<>();               // region|lower(name) -> slug
    private final Map<String, List<String>> wards = new HashMap<>();                // region|district -> names
    private final Map<String, String> wardSlug = new HashMap<>();                   // region|district|lower(name) -> slug
    private final Map<String, List<String>> streets = new HashMap<>();              // region|district|ward -> names

    @PostConstruct
    void load() {
        try {
            List<String> names = new ArrayList<>();
            for (JsonNode r : read("geo/regions.json.gz")) {
                names.add(r.path("name").asText());
                regionSlug.put(r.path("name").asText().toLowerCase(), r.path("slug").asText());
                regionName.put(r.path("slug").asText(), r.path("name").asText());
            }
            names.sort(String.CASE_INSENSITIVE_ORDER);
            regions = List.copyOf(names);
            for (JsonNode d : read("geo/districts.json.gz")) {
                String r = d.path("region_slug").asText();
                districts.computeIfAbsent(r, k -> new ArrayList<>()).add(d.path("name").asText());
                districtSlug.put(r + "|" + d.path("name").asText().toLowerCase(), d.path("slug").asText());
            }
            for (JsonNode w : read("geo/wards.json.gz")) {
                String key = w.path("region_slug").asText() + "|" + w.path("district_slug").asText();
                wards.computeIfAbsent(key, k -> new ArrayList<>()).add(w.path("name").asText());
                wardSlug.put(key + "|" + w.path("name").asText().toLowerCase(), w.path("slug").asText());
            }
            for (JsonNode s : read("geo/streets.json.gz")) {
                String key = s.path("region_slug").asText() + "|" + s.path("district_slug").asText() + "|" + s.path("ward_slug").asText();
                streets.computeIfAbsent(key, k -> new ArrayList<>()).add(s.path("name").asText());
            }
            for (Map<String, List<String>> m : List.of(districts, wards, streets)) {
                m.replaceAll((k, v) -> v.stream().distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList());
            }
            log.info("Tanzania areas loaded: {} regions, {} districts, {} wards, {} village/street groups",
                    regions.size(), districtSlug.size(), wardSlug.size(), streets.size());
        } catch (Exception e) {
            log.warn("Tanzania area data could not be loaded; address pickers will be empty: {}", e.getMessage());
        }
    }

    private JsonNode read(String path) throws Exception {
        try (InputStream in = new GZIPInputStream(new ClassPathResource(path).getInputStream())) {
            return mapper.readTree(in);
        }
    }

    public boolean loaded() {
        return !regions.isEmpty();
    }

    public List<String> regions() {
        return regions;
    }

    public List<String> districts(String region) {
        return rSlug(region).map(r -> districts.getOrDefault(r, List.of())).orElse(List.of());
    }

    public List<String> wards(String region, String district) {
        return dKey(region, district).map(k -> wards.getOrDefault(k, List.of())).orElse(List.of());
    }

    public List<String> streets(String region, String district, String ward) {
        return dKey(region, district).flatMap(k -> Optional.ofNullable(wardSlug.get(k + "|" + lower(ward))).map(w -> k + "|" + w))
                .map(k -> streets.getOrDefault(k, List.of())).orElse(List.of());
    }

    /** The region's name as the data spells it, if it is a region. */
    public Optional<String> region(String name) {
        return rSlug(name).map(regionName::get);
    }

    /** The name as spelt in {@code options}, ignoring case, if it is one of them. */
    public static Optional<String> match(List<String> options, String name) {
        if (name == null) return Optional.empty();
        return options.stream().filter(o -> o.equalsIgnoreCase(name.trim())).findFirst();
    }

    private Optional<String> rSlug(String region) {
        return Optional.ofNullable(region == null ? null : regionSlug.get(lower(region)));
    }

    private Optional<String> dKey(String region, String district) {
        return rSlug(region).flatMap(r -> Optional.ofNullable(districtSlug.get(r + "|" + lower(district))).map(d -> r + "|" + d));
    }

    private static String lower(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }
}
