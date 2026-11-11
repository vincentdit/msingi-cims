package tz.co.msingi.cims.academic.curriculum;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tz.co.msingi.cims.academic.curriculum.CurriculumDtos.CurriculumModuleRequest;
import tz.co.msingi.cims.academic.curriculum.CurriculumDtos.CurriculumRequest;
import tz.co.msingi.cims.academic.curriculum.CurriculumDtos.CurriculumView;
import tz.co.msingi.cims.academic.curriculum.CurriculumRepositories.CurriculumModuleRepository;
import tz.co.msingi.cims.academic.curriculum.CurriculumRepositories.CurriculumRepository;
import tz.co.msingi.cims.academic.curriculum.CurriculumRepositories.ModuleRepository;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.AcademicYearRepository;
import tz.co.msingi.cims.academic.domain.AcademicYear;
import tz.co.msingi.cims.academic.domain.Programme;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.ProgrammeRepository;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.integration.nactvet.NactvetCurriculumClient;
import tz.co.msingi.cims.integration.nactvet.NactvetUnavailableException;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.security.DataScope;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Curricula from NACTVET (FR-ACA-06): for an NTA programme, CIMS asks NACTVET's curriculum-details service for every level and
 * semester of the programme (an Ordinary Diploma over three years: NTA 4, 5 and 6, semesters ONE and TWO), adds the modules to the
 * module catalogue (or brings existing ones up to date unless an approved curriculum fixes them), and puts them into the
 * programme's draft curriculum for the academic year. Approval stays with the Deputy Principal (Academic), who did not import it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NactvetCurriculumImporter {

    static final ZoneId TZ = ZoneId.of("Africa/Dar_es_Salaam");
    private static final Pattern MODULE_CODE = Pattern.compile("[A-Za-z0-9 _/-]{2,20}");
    private static final Pattern NACTVET_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");
    private static final Pattern YEAR = Pattern.compile("(20\\d\\d)");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final NactvetCurriculumClient nactvet;
    private final ProgrammeRepository programmes;
    private final AcademicYearRepository years;
    private final ModuleRepository modules;
    private final CurriculumRepository curricula;
    private final CurriculumModuleRepository curriculumModules;
    private final CurriculumService curriculumService;
    private final DataScope scope;
    private final AuditService audit;
    private final PlatformTransactionManager txm;

    public record ProgrammeRow(Long id, String code, String name, String awardFamily, int awardLevel, int durationSemesters, List<String> levels,
                               String nactvetProgrammeId, boolean idFromCatalogue, LastImport lastImport, String note) {
    }

    public record LastImport(OffsetDateTime at, String outcome, String message, Long curriculumId, String byName) {
    }

    public record Slot(int year, int semester, String level, String curriculumCode, int modules, String message) {
    }

    public record ImportResult(Long programmeId, String programmeCode, String outcome, Long curriculumId, Integer curriculumVersion,
                               boolean replacedDraft, int modulesCreated, int modulesUpdated, int modulesPlaced, List<Slot> slots, List<String> notes,
                               String message) {
    }

    // ------------------------------------------------------------------ programmes and their NACTVET ids

    @Transactional(readOnly = true)
    public List<ProgrammeRow> programmes() {
        var mine = scope.current();
        Map<Long, LastImport> last = new LinkedHashMap<>();
        jdbc.query("""
                select distinct on (programme_id) programme_id, imported_at, outcome, message, curriculum_id, imported_by_name
                from nactvet_curriculum_imports order by programme_id, imported_at desc""", rs -> {
            last.put(rs.getLong(1), new LastImport(rs.getObject(2, OffsetDateTime.class), rs.getString(3), rs.getString(4),
                    rs.getObject(5) == null ? null : rs.getLong(5), rs.getString(6)));
        });
        List<ProgrammeRow> out = new ArrayList<>();
        for (Programme p : programmes.findAll()) {
            if (!p.isActive() || !mine.programme(p)) continue;
            String stored = storedId(p.getId());
            String id = stored != null ? stored : fromCatalogue(p.getNactvetCode()).orElse(null);
            boolean nta = p.getAwardFamily() == Programme.AwardFamily.NTA;
            String note = !nta ? "NACTVET gives curricula of NTA programmes only"
                    : id == null ? "Give NACTVET's programme ID (as on the NACTVET programme list)" : null;
            out.add(new ProgrammeRow(p.getId(), p.getCode(), p.getName(), p.getAwardFamily().name(), p.getAwardLevel(), p.getDurationSemesters(),
                    nta ? levels(p) : List.of(), id, stored == null && id != null, last.get(p.getId()), note));
        }
        out.sort((a, b) -> a.code().compareToIgnoreCase(b.code()));
        return out;
    }

    @Transactional
    public ProgrammeRow setNactvetId(Long programmeId, String nactvetId) {
        scope.requireProgramme(programmeId);
        Programme p = programmes.findById(programmeId).orElseThrow(() -> new NotFoundException("Programme", programmeId));
        String id = nactvetId == null ? "" : nactvetId.trim();
        if (!id.isEmpty() && !NACTVET_ID.matcher(id).matches()) {
            throw new BusinessRuleException("NACTVET_ID_INVALID", "NACTVET's programme ID is letters and digits, e.g. 1b6c88deba258d0f16439fc782e76a5d62a9d6c0");
        }
        jdbc.update("update programmes set nactvet_programme_id = ? where id = ?", id.isEmpty() ? null : id, programmeId);
        audit.record("PROGRAMME_NACTVET_ID_SET", "Programme", programmeId, Map.of("programme", p.getCode()));
        return programmes().stream().filter(r -> r.id().equals(programmeId)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ the import

    /** Fetches the programme's curriculum from NACTVET and puts it into the programme's draft curriculum for the year. */
    public ImportResult importProgramme(Long programmeId, Long effectiveYearId, String intakeIn) {
        scope.requireProgramme(programmeId);
        TransactionTemplate read = new TransactionTemplate(txm);
        read.setReadOnly(true);
        Programme p = read.execute(x -> programmes.findById(programmeId).orElseThrow(() -> new NotFoundException("Programme", programmeId)));
        AcademicYear year = read.execute(x -> years.findById(effectiveYearId == null ? -1L : effectiveYearId)
                .orElseThrow(() -> new BusinessRuleException("YEAR_REQUIRED", "Choose the academic year the curriculum is for")));
        String intake = intakeIn == null ? "SEPT" : intakeIn.trim().toUpperCase(Locale.ROOT);
        if (!intake.equals("SEPT") && !intake.equals("MARCH")) throw new BusinessRuleException("INTAKE_INVALID", "The intake is SEPT or MARCH");
        if (p.getAwardFamily() != Programme.AwardFamily.NTA) {
            throw new BusinessRuleException("NOT_NTA", p.getCode() + " is an NVA programme; NACTVET gives curricula of NTA programmes only");
        }
        String nactvetId = read.execute(x -> {
            String stored = storedId(programmeId);
            return stored != null ? stored : fromCatalogue(p.getNactvetCode()).orElse(null);
        });
        if (nactvetId == null) {
            throw new BusinessRuleException("NACTVET_ID_MISSING", p.getCode() + " has no NACTVET programme ID: give it first (it is on NACTVET's programme list)");
        }
        String academicYear = nactvetYear(year);

        // 1. ask NACTVET for every level and semester of the programme (outside any database transaction)
        List<String> lv = levels(p);
        List<Slot> slots = new ArrayList<>();
        Map<String, List<Placed>> bySlot = new LinkedHashMap<>();
        Set<String> fields = new LinkedHashSet<>();
        String curriculumCode = null;
        for (int i = 1; i <= p.getDurationSemesters(); i++) {
            int y = (i + 1) / 2, sem = i % 2 == 1 ? 1 : 2;
            if (sem > 2) continue;
            String level = lv.get(Math.min(y, lv.size()) - 1);
            NactvetCurriculumClient.Semester s;
            try {
                s = nactvet.fetch(nactvetId, academicYear, level, sem == 1 ? "ONE" : "TWO", intake);
            } catch (NactvetUnavailableException e) {
                String msg = e.getMessage();
                record(programmeId, null, academicYear, intake, "FAILED", slots, 0, 0, msg);
                return new ImportResult(programmeId, p.getCode(), "FAILED", null, null, false, 0, 0, 0, slots, List.of(), msg);
            }
            List<Placed> here = new ArrayList<>();
            for (NactvetCurriculumClient.ModuleRow m : s.modules()) {
                here.add(new Placed(m, (short) y, (short) sem, level));
                if (m.fields() != null) fields.addAll(List.of(m.fields().split(",")));
            }
            bySlot.put(y + "/" + sem, here);
            if (curriculumCode == null && s.curriculumCode() != null) curriculumCode = s.curriculumCode();
            slots.add(new Slot(y, sem, level, s.curriculumCode(), here.size(),
                    here.isEmpty() ? (s.message() == null ? "NACTVET has no modules for this semester" : s.message()) : null));
        }
        if (bySlot.values().stream().allMatch(List::isEmpty)) {
            String msg = "NACTVET has no curriculum for " + p.getCode() + " in " + academicYear + " (" + intake + " intake)";
            record(programmeId, null, academicYear, intake, "FAILED", slots, 0, 0, msg);
            return new ImportResult(programmeId, p.getCode(), "FAILED", null, null, false, 0, 0, 0, slots, List.of(), msg);
        }

        // 2. modules and the draft curriculum, in one transaction
        String code = curriculumCode;
        TransactionTemplate tx = new TransactionTemplate(txm);
        try {
            return tx.execute(x -> write(p, year, academicYear, intake, nactvetId, code, slots, bySlot, fields));
        } catch (BusinessRuleException e) {
            record(programmeId, null, academicYear, intake, "FAILED", slots, 0, 0, e.getMessage());
            throw e;
        }
    }

    private record Placed(NactvetCurriculumClient.ModuleRow row, short year, short semester, String level) {
    }

    private ImportResult write(Programme p0, AcademicYear year, String academicYear, String intake, String nactvetId, String curriculumCode, List<Slot> slots,
                               Map<String, List<Placed>> bySlot, Set<String> fields) {
        Programme p = programmes.findById(p0.getId()).orElseThrow();
        if (storedId(p.getId()) == null) jdbc.update("update programmes set nactvet_programme_id = ? where id = ?", nactvetId, p.getId());
        List<String> notes = new ArrayList<>();
        Set<Long> fixed = new java.util.HashSet<>();
        curricula.findAll().stream().filter(c -> c.getStatus() != Curriculum.Status.DRAFT)
                .forEach(c -> c.getModules().forEach(cm -> fixed.add(cm.getModule().getId())));
        int created = 0, updated = 0;
        List<CurriculumModuleRequest> placed = new ArrayList<>();
        Set<Long> used = new java.util.HashSet<>();
        List<String> noCredits = new ArrayList<>(), badCodes = new ArrayList<>(), twice = new ArrayList<>();
        for (List<Placed> list : bySlot.values()) {
            for (Placed pl : list) {
                NactvetCurriculumClient.ModuleRow r = pl.row();
                String mcode = r.code().trim().toUpperCase(Locale.ROOT);
                if (!MODULE_CODE.matcher(mcode).matches()) {
                    badCodes.add(mcode);
                    continue;
                }
                Short ntaLevel = Short.valueOf(pl.level().replaceAll("\\D", ""));
                String title = r.title() == null || r.title().isBlank() ? mcode : cut(r.title(), 200);
                Optional<Module> existing = modules.findByCodeIgnoreCase(mcode);
                Module m;
                if (existing.isPresent()) {
                    m = existing.get();
                    boolean changed = false;
                    if (!fixed.contains(m.getId())) {
                        if (r.credits() != null && r.credits().signum() > 0 && r.credits().compareTo(m.getCredits()) != 0) {
                            m.setCredits(r.credits());
                            changed = true;
                        }
                        if (r.title() != null && !title.equals(m.getTitle())) {
                            m.setTitle(title);
                            changed = true;
                        }
                    } else if (r.credits() != null && r.credits().compareTo(m.getCredits()) != 0) {
                        notes.add(mcode + ": NACTVET gives " + plain(r.credits()) + " credits; CIMS keeps " + plain(m.getCredits())
                                + " because an approved curriculum uses it");
                    }
                    if (m.getNtaLevel() == null) {
                        m.setNtaLevel(ntaLevel);
                        changed = true;
                    }
                    if (m.getDepartment() == null && p.getDepartment() != null) {
                        m.setDepartment(p.getDepartment());
                        changed = true;
                    }
                    if (!m.isActive()) {
                        m.setActive(true);
                        changed = true;
                    }
                    if (changed) updated++;
                } else {
                    if (r.credits() == null || r.credits().signum() <= 0 || r.credits().compareTo(BigDecimal.valueOf(60)) > 0) {
                        noCredits.add(mcode);
                        continue;
                    }
                    m = new Module();
                    m.setCode(mcode);
                    m.setTitle(title);
                    m.setCredits(r.credits());
                    m.setNtaLevel(ntaLevel);
                    m.setAssessmentType(Module.AssessmentType.GRADED);
                    m.setDepartment(p.getDepartment());
                    m.setDescription("From NACTVET" + (curriculumCode == null ? "" : " curriculum " + curriculumCode) + ".");
                    m.setActive(true);
                    modules.save(m);
                    created++;
                }
                if (!used.add(m.getId())) {
                    twice.add(mcode);
                    continue;
                }
                placed.add(new CurriculumModuleRequest(m.getId(), pl.year(), pl.semester(),
                        Boolean.TRUE.equals(r.elective()) ? CurriculumModule.Category.ELECTIVE : CurriculumModule.Category.CORE));
            }
        }
        modules.flush();
        if (!noCredits.isEmpty()) {
            notes.add("Left out (NACTVET gave no credits" + (fields.isEmpty() ? "" : "; its fields: " + String.join(", ", fields)) + "): "
                    + String.join(", ", noCredits));
        }
        if (!badCodes.isEmpty()) notes.add("Left out (module code not usable): " + String.join(", ", badCodes));
        if (!twice.isEmpty()) notes.add("Listed in more than one semester, kept in the first: " + String.join(", ", twice));
        if (placed.isEmpty()) throw new BusinessRuleException("NO_USABLE_MODULES", "None of NACTVET's modules could be used. " + String.join(" ", notes));

        String fetched = LocalDate.now(TZ).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH));
        String note = cut("From NACTVET" + (curriculumCode == null ? "" : " curriculum " + curriculumCode) + ", " + academicYear + ", " + intake
                + " intake, fetched " + fetched + " by " + CurrentUser.name() + ".", 1000);
        Optional<Curriculum> draft = curricula.findByProgrammeIdOrderByCurriculumVersionDesc(p.getId()).stream()
                .filter(c -> c.getStatus() == Curriculum.Status.DRAFT).findFirst();
        CurriculumRequest req = new CurriculumRequest(p.getId(), year.getId(), note, placed, List.of());
        CurriculumView view = draft.isPresent() ? curriculumService.update(draft.get().getId(), req) : curriculumService.create(req);
        jdbc.update("update curricula set nactvet_curriculum_code = ?, nactvet_fetched_at = now() where id = ?", cut(curriculumCode, 60), view.id());
        if (view.slots().stream().anyMatch(s -> s.electives() > 0)) {
            notes.add("Some semesters have electives: say in the draft how many a student takes before it is approved");
        }
        String message = placed.size() + " modules in draft v" + view.curriculumVersion() + (draft.isPresent() ? " (the earlier draft was replaced)" : "");
        record(p.getId(), view.id(), academicYear, intake, "IMPORTED", slots, created, updated, message);
        audit.record("CURRICULUM_IMPORTED_FROM_NACTVET", "Curriculum", view.id(),
                Map.of("programme", p.getCode(), "year", academicYear, "intake", intake, "modules", placed.size(), "created", created));
        return new ImportResult(p.getId(), p.getCode(), "IMPORTED", view.id(), (int) view.curriculumVersion(), draft.isPresent(), created, updated,
                placed.size(), slots, notes, message);
    }

    // ------------------------------------------------------------------ helpers

    /** The NTA level of each year of study: from the level the programme admits at, rising each year; a Bachelor stays at NTA 7 until its last year. */
    static List<String> levels(Programme p) {
        int years = Math.max(1, (p.getDurationSemesters() + 1) / 2);
        int award = p.getAwardLevel();
        int entry = p.entryLevel();
        List<String> out = new ArrayList<>();
        for (int y = 1; y <= years; y++) {
            int level = award >= 7 ? (y == years ? award : Math.max(7, entry)) : Math.min(award, entry + y - 1);
            out.add("NTA" + level);
        }
        return out;
    }

    /** NACTVET writes the year as 2025/2026. */
    static String nactvetYear(AcademicYear y) {
        String code = y.getCode() == null ? "" : y.getCode();
        if (code.matches("20\\d\\d/20\\d\\d")) return code;
        Matcher m = YEAR.matcher(code);
        int start = m.find() ? Integer.parseInt(m.group(1)) : y.getStartDate().getYear();
        return start + "/" + (start + 1);
    }

    private String storedId(Long programmeId) {
        return jdbc.queryForList("select nactvet_programme_id from programmes where id = ? and nactvet_programme_id is not null", String.class, programmeId)
                .stream().findFirst().orElse(null);
    }

    /** A programme imported from the NACTVET catalogue kept the first 30 characters of the id; the catalogue has it in full. */
    private Optional<String> fromCatalogue(String nactvetCode) {
        if (nactvetCode == null || nactvetCode.isBlank()) return Optional.empty();
        String c = nactvetCode.trim();
        if (c.length() > 30 && NACTVET_ID.matcher(c).matches()) return Optional.of(c);
        List<String> own = jdbc.queryForList("""
                select distinct np.nactvet_programme_id from nactvet_programmes np join nactvet_institutions ni on ni.id = np.institution_id
                where ni.tenant_id = cims_current_tenant() and np.nactvet_programme_id like ? limit 2""", String.class, c + "%");
        if (own.size() == 1) return Optional.of(own.get(0));
        if (c.length() < 30) return Optional.empty();
        List<String> any = jdbc.queryForList("select distinct nactvet_programme_id from nactvet_programmes where nactvet_programme_id like ? limit 2",
                String.class, c + "%");
        return any.size() == 1 ? Optional.of(any.get(0)) : Optional.empty();
    }

    private void record(Long programmeId, Long curriculumId, String academicYear, String intake, String outcome, List<Slot> slots, int created,
                        int updated, String message) {
        new TransactionTemplate(txm).executeWithoutResult(x -> jdbc.update("""
                insert into nactvet_curriculum_imports (tenant_id, programme_id, curriculum_id, academic_year, intake, outcome, slots, modules_created,
                  modules_updated, message, imported_by_name)
                values (cims_current_tenant(), ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)""", programmeId, curriculumId, academicYear, intake, outcome,
                mapper.writeValueAsString(slots), created, updated, cut(message, 1000), CurrentUser.name()));
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static String cut(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
