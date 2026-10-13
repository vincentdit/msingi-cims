package tz.co.msingi.cims.console.nactvet;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.DepartmentRepository;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.ProgrammeRepository;
import tz.co.msingi.cims.academic.domain.Department;
import tz.co.msingi.cims.academic.domain.Programme;
import tz.co.msingi.cims.console.nactvet.CatalogueDtos.ImportResult;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.tenancy.TenantContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Creates an institution's programmes from its NACTVET catalogue entries. Runs inside the institution (row-level security
 * applies). Each NACTVET board becomes a starting department, which the institution can rename or split later. A programme
 * already in CIMS with the same NACTVET code is skipped, so importing again only adds what is new.
 *
 * <ul>
 *   <li>code: NACTVET's course code (made unique), otherwise "N" and the NACTVET programme id;</li>
 *   <li>award: the NTA level (NVA for levels 1–3), with the standard award name;</li>
 *   <li>duration: NACTVET's years × 2 semesters, otherwise a usual duration for the level;</li>
 *   <li>entry level: for a programme open to holders of an NTA 4 or 5 award, the next level;</li>
 *   <li>capacity, fee and entry requirements as NACTVET lists them (fee and requirements in the description).</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ProgrammeImporter {

    private final JdbcTemplate jdbc;
    private final DepartmentRepository departments;
    private final ProgrammeRepository programmes;
    private final AuditService audit;

    /** The NACTVET code CIMS keeps on an imported programme (its NACTVET programme id, else the catalogue key). */
    static String nactvetCode(String programmeId, String key) {
        String c = programmeId != null && !programmeId.isBlank() ? programmeId.trim() : key;
        return c == null ? null : (c.length() > 30 ? c.substring(0, 30) : c);
    }

    /** Imports the chosen programmes (all active ones when {@code programmeIds} is null) of a NACTVET institution. */
    public ImportResult importInto(Long nactvetInstitutionId, Collection<Long> programmeIds) {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || TenantContext.currentTenantIdOrNull() == null) {
            throw new IllegalStateException("Importing programmes needs a transaction inside the institution");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from nactvet_programmes where institution_id = ? and active order by nta_level nulls last, name", nactvetInstitutionId);
        Set<Long> wanted = programmeIds == null ? null : new HashSet<>(programmeIds);
        Set<String> existingCodes = new HashSet<>();
        Set<String> existingNactvet = new HashSet<>();
        for (Programme p : programmes.findAll()) {
            existingCodes.add(p.getCode().toUpperCase(Locale.ROOT));
            if (p.getNactvetCode() != null) existingNactvet.add(p.getNactvetCode());
        }
        Map<String, Department> deptByCode = new LinkedHashMap<>();
        for (Department d : departments.findAll()) deptByCode.put(d.getCode().toUpperCase(Locale.ROOT), d);
        List<String> createdDepartments = new ArrayList<>(), created = new ArrayList<>();
        int skipped = 0;
        for (Map<String, Object> r : rows) {
            Long id = ((Number) r.get("id")).longValue();
            if (wanted != null && !wanted.contains(id)) continue;
            String code = nactvetCode(str(r, "nactvet_programme_id"), str(r, "programme_key"));
            if (code != null && existingNactvet.contains(code)) {
                skipped++;
                continue;
            }
            Department dept = department(r, deptByCode, createdDepartments);
            Programme p = new Programme();
            p.setDepartment(dept);
            p.setCode(uniqueCode(r, existingCodes));
            p.setNactvetCode(code);
            p.setName(CatalogueService.cut(CatalogueService.titleCase(str(r, "name")), 200));
            Integer level = r.get("nta_level") == null ? 6 : ((Number) r.get("nta_level")).intValue();
            Programme.AwardFamily family = level <= 3 ? Programme.AwardFamily.NVA : Programme.AwardFamily.NTA;
            int awardLevel = family == Programme.AwardFamily.NVA ? Math.max(1, level) : Math.min(8, Math.max(4, level));
            p.setAwardFamily(family);
            p.setAwardLevel((short) awardLevel);
            p.setAwardName(Programme.defaultAwardName(family, awardLevel));
            Integer years = r.get("duration_years") == null ? null : ((Number) r.get("duration_years")).intValue();
            p.setDurationSemesters((short) (years != null ? Math.min(16, years * 2) : defaultSemesters(family, awardLevel)));
            Integer holder = r.get("nta_entry_holder_level") == null ? null : ((Number) r.get("nta_entry_holder_level")).intValue();
            if (holder != null && family == Programme.AwardFamily.NTA && holder + 1 <= awardLevel) p.setEntryLevelSet((short) (holder + 1));
            Integer capacity = r.get("total_capacity") == null ? null : ((Number) r.get("total_capacity")).intValue();
            p.setAccreditedCapacity(capacity != null && capacity > 0 ? capacity : null);
            p.setDescription(description(r));
            p.setActive(true);
            programmes.save(p);
            existingNactvet.add(code);
            created.add(p.getCode() + " · " + p.getName());
        }
        programmes.flush();
        if (!created.isEmpty() || !createdDepartments.isEmpty()) {
            audit.record("PROGRAMMES_IMPORTED_FROM_NACTVET", "Programme", null,
                    Map.of("programmes", created.size(), "departments", createdDepartments, "skipped", skipped));
        }
        return new ImportResult(created.size(), skipped, createdDepartments, created);
    }

    private Department department(Map<String, Object> r, Map<String, Department> byCode, List<String> createdNames) {
        String board = str(r, "board_name");
        String boardCode = str(r, "board_code");
        String name = board != null ? CatalogueService.titleCase(board) : "General Studies";
        String code = (boardCode != null ? boardCode : board != null ? CatalogueService.initials(board) : "GEN")
                .toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (code.isBlank()) code = "GEN";
        if (code.length() > 20) code = code.substring(0, 20);
        Department d = byCode.get(code);
        if (d != null) return d;
        d = new Department();
        d.setCode(code);
        d.setName(CatalogueService.cut(name, 120));
        d.setActive(true);
        d = departments.save(d);
        byCode.put(code, d);
        createdNames.add(d.getName());
        return d;
    }

    private static String uniqueCode(Map<String, Object> r, Set<String> taken) {
        String course = str(r, "course_code");
        String base = course != null ? course : "N" + CatalogueService.firstNonBlank(str(r, "nactvet_programme_id"), String.valueOf(r.get("id")));
        base = base.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9-]", "");
        if (base.isBlank()) base = "P" + r.get("id");
        if (base.length() > 26) base = base.substring(0, 26);
        String code = base;
        for (int i = 2; taken.contains(code); i++) code = base + "-" + i;
        taken.add(code);
        return code;
    }

    private static int defaultSemesters(Programme.AwardFamily family, int level) {
        if (family == Programme.AwardFamily.NVA) return 2;
        return switch (level) {
            case 4, 5 -> 2;
            case 6 -> 6;
            case 7 -> 2;
            default -> 4;
        };
    }

    private static String description(Map<String, Object> r) {
        StringBuilder sb = new StringBuilder("From the NACTVET catalogue");
        String cat = str(r, "category_name");
        if (cat != null) sb.append(" (").append(cat).append(')');
        sb.append('.');
        append(sb, "Entry requirements", str(r, "entry_requirement"));
        append(sb, "Specific CSEE/ACSEE subjects", str(r, "necta_requirement"));
        append(sb, "Specific NTA awards", str(r, "nta_requirement"));
        append(sb, "Selection criteria", str(r, "selection_criteria"));
        String fee = str(r, "programme_fee");
        if (fee != null) append(sb, "Fee listed by NACTVET (TZS)", fee);
        Object male = r.get("male_capacity"), female = r.get("female_capacity");
        if (male != null || female != null) append(sb, "Capacity", "male " + (male == null ? "—" : male) + ", female " + (female == null ? "—" : female));
        return sb.toString();
    }

    private static void append(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) sb.append('\n').append(label).append(": ").append(value.trim());
    }

    private static String str(Map<String, Object> r, String k) {
        Object v = r.get(k);
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v).trim();
    }
}
