package tz.co.msingi.cims.academic.campus;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.academic.campus.CampusRepositories.CampusRepository;
import tz.co.msingi.cims.academic.campus.CampusRepositories.FacultyRepository;
import tz.co.msingi.cims.academic.campus.CampusRepositories.ProgrammeCampusRepository;
import tz.co.msingi.cims.academic.campus.CampusRepositories.StudentCategoryRepository;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.ProgrammeRepository;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.platform.audit.AuditService;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Campuses, faculties, where programmes are taught, and student categories (FR-ACA-01, FR-FIN-01). */
@Service
@RequiredArgsConstructor
public class CampusService {

    private final CampusRepository campuses;
    private final FacultyRepository faculties;
    private final StudentCategoryRepository categories;
    private final ProgrammeCampusRepository programmeCampuses;
    private final ProgrammeRepository programmes;
    private final AuditService audit;

    static final String CODE = "[A-Z0-9_\\-]{2,20}";

    public record CampusRequest(@NotBlank @Pattern(regexp = CODE) String code, @NotBlank @Size(max = 150) String name,
                                @Size(max = 60) String region, @Size(max = 80) String district, @Size(max = 250) String address,
                                boolean main, boolean active) {
    }

    public record CampusView(Long id, String code, String name, String region, String district, String address, boolean main,
                             boolean active, long programmes) {
    }

    public record NamedRequest(@NotBlank @Pattern(regexp = CODE) String code, @NotBlank @Size(max = 150) String name, boolean active) {
    }

    public record NamedView(Long id, String code, String name, boolean active) {
    }

    public record ProgrammeCampusesRequest(List<Long> campusIds) {
    }

    // ---------------------------------------------------------------- campuses

    @Transactional(readOnly = true)
    public List<CampusView> campuses() {
        Map<Long, Long> counts = new HashMap<>();
        programmeCampuses.findAll().forEach(pc -> counts.merge(pc.getCampusId(), 1L, Long::sum));
        return campuses.findAllByOrderByMainDescNameAsc().stream().map(c -> view(c, counts.getOrDefault(c.getId(), 0L))).toList();
    }

    @Transactional
    public CampusView saveCampus(Long id, CampusRequest req) {
        Campus c = id == null ? new Campus() : campuses.findById(id).orElseThrow(() -> new NotFoundException("Campus", id));
        if ((id == null || !c.getCode().equalsIgnoreCase(req.code())) && campuses.existsByCodeIgnoreCase(req.code())) {
            throw new BusinessRuleException("CODE_EXISTS", "Campus code " + req.code() + " is already used");
        }
        boolean wasMain = c.isMain();
        if (wasMain && !req.main()) {
            throw new BusinessRuleException("MAIN_REQUIRED", "Make another campus the main campus instead");
        }
        if (req.main() && !req.active()) {
            throw new BusinessRuleException("MAIN_INACTIVE", "The main campus cannot be inactive");
        }
        if (req.main() && !wasMain) {
            campuses.findFirstByMainTrue().ifPresent(old -> {
                old.setMain(false);
                campuses.saveAndFlush(old);
            });
        } else if (id == null && campuses.findFirstByMainTrue().isEmpty()) {
            // the first campus is the main one
            c.setMain(true);
        }
        c.setCode(req.code());
        c.setName(req.name().trim());
        c.setRegion(blank(req.region()));
        c.setDistrict(blank(req.district()));
        c.setAddress(blank(req.address()));
        if (req.main()) c.setMain(true);
        c.setActive(req.active() || c.isMain());
        campuses.save(c);
        audit.record(id == null ? "CREATE" : "UPDATE", "Campus", c.getId(), Map.of("code", c.getCode(), "main", c.isMain()));
        return view(c, programmeCampuses.findAll().stream().filter(pc -> pc.getCampusId().equals(c.getId())).count());
    }

    /** The main campus, or null when the institution has not listed campuses. */
    @Transactional(readOnly = true)
    public Campus mainCampus() {
        return campuses.findFirstByMainTrue().orElse(null);
    }

    /** Where a programme is taught: its listed campuses, else the main campus. */
    @Transactional(readOnly = true)
    public List<Long> campusesOf(Long programmeId) {
        List<Long> ids = programmeCampuses.findByProgrammeId(programmeId).stream().map(ProgrammeCampus::getCampusId).toList();
        if (!ids.isEmpty()) return ids;
        Campus main = mainCampus();
        return main == null ? List.of() : List.of(main.getId());
    }

    /** The campus a new student of the programme is placed at: its only campus, else the main campus. */
    @Transactional(readOnly = true)
    public Long defaultCampusFor(Long programmeId) {
        List<Long> ids = programmeCampuses.findByProgrammeId(programmeId).stream().map(ProgrammeCampus::getCampusId).toList();
        if (ids.size() == 1) return ids.get(0);
        Campus main = mainCampus();
        return main == null ? null : main.getId();
    }

    @Transactional
    public List<Long> setProgrammeCampuses(Long programmeId, List<Long> campusIds) {
        programmes.findById(programmeId).orElseThrow(() -> new NotFoundException("Programme", programmeId));
        Set<Long> ids = new LinkedHashSet<>(campusIds == null ? List.of() : campusIds);
        for (Long cid : ids) {
            Campus c = campuses.findById(cid).orElseThrow(() -> new NotFoundException("Campus", cid));
            if (!c.isActive()) throw new BusinessRuleException("INACTIVE_CAMPUS", c.getName() + " is not active");
        }
        programmeCampuses.deleteByProgrammeId(programmeId);
        programmeCampuses.flush();
        for (Long cid : ids) {
            ProgrammeCampus pc = new ProgrammeCampus();
            pc.setProgrammeId(programmeId);
            pc.setCampusId(cid);
            programmeCampuses.save(pc);
        }
        audit.record("PROGRAMME_CAMPUSES_SET", "Programme", programmeId, Map.of("campuses", ids.toString()));
        return campusesOf(programmeId);
    }

    @Transactional(readOnly = true)
    public Map<Long, List<Long>> programmeCampusMap() {
        Map<Long, List<Long>> m = new HashMap<>();
        programmeCampuses.findAll().forEach(pc -> m.computeIfAbsent(pc.getProgrammeId(), k -> new java.util.ArrayList<>()).add(pc.getCampusId()));
        return m;
    }

    @Transactional(readOnly = true)
    public String campusName(Long id) {
        if (id == null) {
            Campus main = mainCampus();
            return main == null ? null : main.getName();
        }
        return campuses.findById(id).map(Campus::getName).orElse(null);
    }

    // ---------------------------------------------------------------- faculties and categories

    @Transactional(readOnly = true)
    public List<NamedView> faculties() {
        return faculties.findAllByOrderByNameAsc().stream().map(f -> new NamedView(f.getId(), f.getCode(), f.getName(), f.isActive())).toList();
    }

    @Transactional
    public NamedView saveFaculty(Long id, NamedRequest req) {
        Faculty f = id == null ? new Faculty() : faculties.findById(id).orElseThrow(() -> new NotFoundException("Faculty", id));
        if ((id == null || !f.getCode().equalsIgnoreCase(req.code())) && faculties.existsByCodeIgnoreCase(req.code())) {
            throw new BusinessRuleException("CODE_EXISTS", "Faculty code " + req.code() + " is already used");
        }
        f.setCode(req.code());
        f.setName(req.name().trim());
        f.setActive(req.active());
        faculties.save(f);
        audit.record(id == null ? "CREATE" : "UPDATE", "Faculty", f.getId(), Map.of("code", f.getCode()));
        return new NamedView(f.getId(), f.getCode(), f.getName(), f.isActive());
    }

    @Transactional(readOnly = true)
    public List<NamedView> categories() {
        return categories.findAllByOrderByNameAsc().stream().map(c -> new NamedView(c.getId(), c.getCode(), c.getName(), c.isActive())).toList();
    }

    @Transactional
    public NamedView saveCategory(Long id, NamedRequest req) {
        StudentCategory c = id == null ? new StudentCategory() : categories.findById(id).orElseThrow(() -> new NotFoundException("Student category", id));
        if ((id == null || !c.getCode().equalsIgnoreCase(req.code())) && categories.existsByCodeIgnoreCase(req.code())) {
            throw new BusinessRuleException("CODE_EXISTS", "Category code " + req.code() + " is already used");
        }
        c.setCode(req.code());
        c.setName(req.name().trim());
        c.setActive(req.active());
        categories.save(c);
        audit.record(id == null ? "CREATE" : "UPDATE", "StudentCategory", c.getId(), Map.of("code", c.getCode()));
        return new NamedView(c.getId(), c.getCode(), c.getName(), c.isActive());
    }

    @Transactional(readOnly = true)
    public String categoryName(Long id) {
        return id == null ? null : categories.findById(id).map(StudentCategory::getName).orElse(null);
    }

    private static CampusView view(Campus c, long programmes) {
        return new CampusView(c.getId(), c.getCode(), c.getName(), c.getRegion(), c.getDistrict(), c.getAddress(), c.isMain(), c.isActive(), programmes);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
