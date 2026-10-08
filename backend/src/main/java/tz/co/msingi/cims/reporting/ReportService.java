package tz.co.msingi.cims.reporting;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.AcademicYearRepository;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.IntakeRepository;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.ProgrammeRepository;
import tz.co.msingi.cims.admission.domain.AdmissionRepositories.AdmissionWindowRepository;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.tenancy.Tenant;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;
import tz.co.msingi.cims.reporting.ReportModel.CatalogueEntry;
import tz.co.msingi.cims.reporting.ReportModel.Data;
import tz.co.msingi.cims.reporting.ReportModel.Definition;
import tz.co.msingi.cims.reporting.ReportModel.Filter;
import tz.co.msingi.cims.reporting.ReportModel.Report;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The standard report catalogue (FR-RPT-02): which reports a user may run, running them with the filters each honours,
 * and Excel/PDF exports. Lists of named people are not available to management viewers, and every export is audited.
 */
@Service
public class ReportService {

    static final int SCREEN_ROWS = 500;
    static final int MAX_ROWS = 50_000;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final Pattern HEX = Pattern.compile("^#[0-9a-fA-F]{6}$");

    private final Map<String, Definition> definitions = new LinkedHashMap<>();
    private final AuditService audit;
    private final TenantRepository tenants;
    private final AcademicYearRepository years;
    private final IntakeRepository intakes;
    private final AdmissionWindowRepository windows;
    private final ProgrammeRepository programmes;

    public ReportService(List<ReportProvider> providers, AuditService audit, TenantRepository tenants, AcademicYearRepository years,
                         IntakeRepository intakes, AdmissionWindowRepository windows, ProgrammeRepository programmes) {
        List<String> areaOrder = List.of("Admissions", "Registry", "Finance", "Governance");
        providers.stream().flatMap(p -> p.definitions().stream())
                .sorted((a, b) -> Integer.compare(areaOrder.indexOf(a.area()), areaOrder.indexOf(b.area())) * 1000 + a.code().compareTo(b.code()))
                .forEach(d -> definitions.put(d.code(), d));
        this.audit = audit;
        this.tenants = tenants;
        this.years = years;
        this.intakes = intakes;
        this.windows = windows;
        this.programmes = programmes;
    }

    public List<CatalogueEntry> catalogue() {
        Set<Role> mine = currentRoles();
        return definitions.values().stream().filter(d -> allowed(d, mine))
                .map(d -> new CatalogueEntry(d.code(), d.area(), d.title(), d.description(), d.personalData(), d.filters())).toList();
    }

    /** The report for the screen (first rows only); personal-data reports viewed are audited. */
    @Transactional
    public Report view(String code, Filter filter) {
        Report r = generate(code, filter);
        if (definitions.get(code).personalData()) {
            audit.record("REPORT_VIEWED", "Report", null, Map.of("report", code, "rows", r.rowCount(), "filters", String.join("; ", r.filterLines())));
        }
        if (r.rows().size() <= SCREEN_ROWS) return r;
        return new Report(r.code(), r.area(), r.title(), r.description(), r.filterLines(), r.columns(), r.rows().subList(0, SCREEN_ROWS),
                r.totals(), r.notes(), r.rowCount(), true, r.institution(), r.generatedAt(), r.generatedBy());
    }

    public record Export(byte[] content, String fileName, String contentType) {
    }

    @Transactional
    public Export export(String code, Filter filter, String format) {
        Report r = generate(code, filter);
        String base = (r.code() + "-" + r.title()).replaceAll("[^A-Za-z0-9]+", "-").replaceAll("-+$", "") + "-" + LocalDate.now(Sql.ZONE);
        Export e = switch (format == null ? "" : format.toLowerCase()) {
            case "xlsx" -> new Export(ReportExporter.excel(r), base + ".xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            case "pdf" -> new Export(ReportExporter.pdf(r, colour()), base + ".pdf", "application/pdf");
            default -> throw new BusinessRuleException("INVALID_FORMAT", "Choose xlsx or pdf");
        };
        audit.record("REPORT_EXPORTED", "Report", null, Map.of("report", code, "format", format.toLowerCase(), "rows", r.rowCount(),
                "filters", String.join("; ", r.filterLines())));
        return e;
    }

    private Report generate(String code, Filter raw) {
        Definition d = definitions.get(code);
        if (d == null) throw new NotFoundException("Report", code);
        if (!allowed(d, currentRoles())) throw new AccessDeniedException("Not allowed to run " + code);
        Filter f = honoured(d, raw == null ? new Filter(null, null, null, null, null, null, null, null, null, null, null, null) : raw);
        Data data = d.run().apply(f);
        int count = data.rows().size();
        if (count > MAX_ROWS) {
            throw new BusinessRuleException("TOO_MANY_ROWS", "The report has " + count + " rows; narrow the filters to at most " + MAX_ROWS);
        }
        Tenant t = tenants.findById(TenantContext.requireTenantId()).orElseThrow();
        return new Report(d.code(), d.area(), d.title(), d.description(), describe(d, f), data.columns(), data.rows(), data.totals(),
                data.notes(), count, false, t.getName(), OffsetDateTime.now(), CurrentUser.name());
    }

    /** Only the filters the report honours, with the period defaulting to this month. */
    private static Filter honoured(Definition d, Filter f) {
        List<String> on = d.filters();
        LocalDate from = null, to = null;
        if (on.contains(ReportModel.PERIOD)) {
            from = f.from() != null ? f.from() : LocalDate.now(Sql.ZONE).withDayOfMonth(1);
            to = f.to() != null ? f.to() : LocalDate.now(Sql.ZONE);
            if (from.isAfter(to)) throw new BusinessRuleException("INVALID_PERIOD", "The start of the period is after its end");
        }
        String status = on.contains(ReportModel.APPLICATION_STATUS) || on.contains(ReportModel.STUDENT_STATUS) ? blank(f.status()) : null;
        if (status != null && !status.matches("[A-Z_]{2,30}")) throw new BusinessRuleException("INVALID_FILTER", "Unknown status " + status);
        String sex = on.contains(ReportModel.SEX) ? blank(f.sex()) : null;
        if (sex != null && !sex.matches("[FM]")) throw new BusinessRuleException("INVALID_FILTER", "Sex is F or M");
        return new Filter(on.contains(ReportModel.ACADEMIC_YEAR) ? f.academicYearId() : null, on.contains(ReportModel.INTAKE) ? f.intakeId() : null,
                on.contains(ReportModel.WINDOW) ? f.windowId() : null, on.contains(ReportModel.PROGRAMME) ? f.programmeId() : null,
                on.contains(ReportModel.AWARD) ? blank(f.awardFamily()) : null, on.contains(ReportModel.LEVEL) ? f.level() : null, sex, status,
                on.contains(ReportModel.YEAR_OF_STUDY) ? f.yearOfStudy() : null, from, to, on.contains(ReportModel.TEXT) ? blank(f.text()) : null);
    }

    private List<String> describe(Definition d, Filter f) {
        List<String> out = new ArrayList<>();
        if (f.academicYearId() != null) out.add("Academic year: " + years.findById(f.academicYearId()).map(y -> y.getCode()).orElse("?"));
        if (f.intakeId() != null) out.add("Intake: " + intakes.findById(f.intakeId()).map(i -> i.getName()).orElse("?"));
        if (f.windowId() != null) out.add("Admission window: " + windows.findById(f.windowId()).map(w -> w.getName()).orElse("?"));
        if (f.programmeId() != null) out.add("Programme: " + programmes.findById(f.programmeId()).map(p -> p.getCode() + " " + p.getName()).orElse("?"));
        if (f.awardFamily() != null || f.level() != null) {
            out.add("Award: " + (f.awardFamily() == null ? "all" : f.awardFamily()) + (f.level() == null ? "" : " level " + f.level()));
        }
        if (f.sex() != null) out.add("Sex: " + ("F".equals(f.sex()) ? "Female" : "Male"));
        if (f.status() != null) out.add("Status: " + AdmissionReports.label(f.status()));
        if (f.yearOfStudy() != null) out.add("Year of study: " + f.yearOfStudy());
        if (f.from() != null) out.add("Period: " + DAY.format(f.from()) + " – " + DAY.format(f.to()));
        if (f.text() != null) out.add("Containing: " + f.text());
        if (out.isEmpty()) out.add("All records");
        return out;
    }

    private static boolean allowed(Definition d, Set<Role> mine) {
        for (Role r : mine) {
            if (d.roles().contains(r) && !(d.personalData() && r == Role.MANAGEMENT_VIEWER)) return true;
        }
        return false;
    }

    static Set<Role> currentRoles() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        Set<Role> out = EnumSet.noneOf(Role.class);
        if (auth == null) return out;
        for (GrantedAuthority a : auth.getAuthorities()) {
            String n = a.getAuthority();
            if (n.startsWith("ROLE_")) {
                try {
                    out.add(Role.valueOf(n.substring(5)));
                } catch (IllegalArgumentException ignored) {
                    // not a CIMS role
                }
            }
        }
        return out;
    }

    private String colour() {
        Object c = tenants.findById(TenantContext.requireTenantId()).map(t -> t.getBranding().get("primaryColor")).orElse(null);
        return c instanceof String s && HEX.matcher(s).matches() ? s : "#1F4E8C";
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Areas in catalogue order, for the dashboard's report links. */
    public Map<String, List<CatalogueEntry>> byArea() {
        return catalogue().stream().collect(Collectors.groupingBy(CatalogueEntry::area, LinkedHashMap::new, Collectors.toList()));
    }
}
