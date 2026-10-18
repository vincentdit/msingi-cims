package tz.co.msingi.cims.admission.advert;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.academic.domain.Programme;
import tz.co.msingi.cims.admission.domain.AdmissionRepositories.AdmissionWindowRepository;
import tz.co.msingi.cims.admission.domain.AdmissionRepositories.WindowProgrammeRepository;
import tz.co.msingi.cims.admission.domain.AdmissionWindow;
import tz.co.msingi.cims.admission.domain.WindowProgramme;
import tz.co.msingi.cims.common.exception.NotFoundException;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the public sees of each programme on offer (in a window open now or opening later): the award, duration, places, the
 * year's fee and its instalments from the approved fee structure, the entry requirements in English and Kiswahili written from the
 * rules CIMS checks, the selling points, and the dates. Each programme has a short public link, /p/{code}, for sharing.
 */
@Service
@RequiredArgsConstructor
public class ProgrammeAdvertService {

    private final AdmissionWindowRepository windows;
    private final WindowProgrammeRepository windowProgrammes;
    private final JdbcTemplate jdbc;

    /** The year's fee for a first-year Tanzanian student, in how many instalments, and the first one. */
    public record Fee(BigDecimal total, int instalments, BigDecimal firstInstalment) {
    }

    public record Advert(Long windowId, String windowName, String academicYear, String intake, OffsetDateTime opensAt, OffsetDateTime closesAt,
                         boolean open, BigDecimal applicationFee, Long windowProgrammeId, String code, String name, String awardName,
                         String awardFamily, short awardLevel, int durationSemesters, String department, int capacity, String requirementSummary,
                         String requirementsEn, String requirementsSw, List<String> routesEn, List<String> routesSw, List<String> highlights,
                         Fee fee, String path) {
    }

    @Transactional(readOnly = true)
    public List<Advert> list() {
        OffsetDateTime now = OffsetDateTime.now();
        List<Advert> out = new ArrayList<>();
        Map<String, Boolean> seen = new LinkedHashMap<>();
        List<AdmissionWindow> ws = new ArrayList<>(windows.findOpen(now));
        ws.addAll(windows.findUpcoming(now));
        for (AdmissionWindow w : ws) {
            for (WindowProgramme wp : windowProgrammes.findByWindowIdOrderByIdAsc(w.getId())) {
                String code = wp.getProgramme().getCode().toUpperCase(Locale.ROOT);
                if (seen.putIfAbsent(code, true) != null) continue;   // the open window first, then the next one
                out.add(advert(w, wp, now));
            }
        }
        return out;
    }

    /** The programme with this code in the window open now (or, failing that, the next one to open). */
    @Transactional(readOnly = true)
    public Advert one(String code) {
        return list().stream().filter(a -> a.code().equalsIgnoreCase(code)).findFirst()
                .orElseThrow(() -> new NotFoundException("This programme is not open for applications at the moment"));
    }

    private Advert advert(AdmissionWindow w, WindowProgramme wp, OffsetDateTime now) {
        Programme p = wp.getProgramme();
        var rules = wp.getEntryRules();
        List<String> highlights = wp.getHighlights() == null ? List.of()
                : Arrays.stream(wp.getHighlights().split("\\R")).map(String::strip).filter(s -> !s.isEmpty()).toList();
        boolean open = !w.getOpensAt().isAfter(now) && w.getClosesAt().isAfter(now);
        return new Advert(w.getId(), w.getName(), w.getAcademicYear().getCode(), w.getIntake().getName(), w.getOpensAt(), w.getClosesAt(), open,
                w.getApplicationFee(), wp.getId(), p.getCode(), p.getName(), p.getAwardName(), p.getAwardFamily().name(), p.getAwardLevel(),
                p.getDurationSemesters(), p.getDepartment().getName(), wp.getCapacity(), rules == null ? null : rules.summary(),
                EntryRulesText.sentence(rules, false), EntryRulesText.sentence(rules, true), EntryRulesText.routes(rules, false),
                EntryRulesText.routes(rules, true), highlights, fee(w.getAcademicYear().getId(), p.getId()), path(p.getCode()));
    }

    Fee fee(Long academicYearId, Long programmeId) {
        return jdbc.query("""
                select sum(l.amount), max(l.instalment), coalesce(sum(l.amount) filter (where l.instalment = 1), 0)
                from fee_structures s join fee_structure_lines l on l.fee_structure_id = s.id
                where s.academic_year_id = ? and s.programme_id = ? and s.year_of_study = 1 and s.status = 'APPROVED'
                having count(*) > 0""", (rs, i) -> new Fee(rs.getBigDecimal(1), rs.getInt(2), rs.getBigDecimal(3)), academicYearId, programmeId)
                .stream().findFirst().orElse(null);
    }

    public static String path(String code) {
        return "/p/" + URLEncoder.encode(code, StandardCharsets.UTF_8);
    }
}
