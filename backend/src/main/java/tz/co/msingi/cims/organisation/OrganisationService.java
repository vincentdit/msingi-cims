package tz.co.msingi.cims.organisation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.academic.campus.CampusRepositories.FacultyRepository;
import tz.co.msingi.cims.academic.campus.Faculty;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.DepartmentRepository;
import tz.co.msingi.cims.academic.domain.Department;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.organisation.OrgRepositories.OrgPositionRepository;
import tz.co.msingi.cims.organisation.OrgRepositories.OrgSettingsRepository;
import tz.co.msingi.cims.organisation.OrgRepositories.OrgUnitRepository;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.security.UserAccount;
import tz.co.msingi.cims.platform.security.UserAccountRepository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The institution's organisation structure, built by the institution: units in a tree (who reports to whom), each with its
 * positions and their holders. Staff see it in the back office; once published, the public site shows it too.
 */
@Service
@RequiredArgsConstructor
public class OrganisationService {

    static final int MAX_UNITS = 400;

    private final OrgUnitRepository units;
    private final OrgPositionRepository positions;
    private final OrgSettingsRepository settings;
    private final DepartmentRepository departments;
    private final FacultyRepository faculties;
    private final UserAccountRepository users;
    private final AuditService audit;

    // ------------------------------------------------------------------ views and requests

    public record PositionView(Long id, String title, boolean head, Long holderUserId, String holder, boolean acting, int sortOrder) {
        public boolean vacant() {
            return holder == null;
        }
    }

    public record UnitView(Long id, Long parentId, String kind, String name, String shortName, Long departmentId, boolean advisory,
                           int sortOrder, String description, List<PositionView> positions, List<UnitView> children) {
    }

    public record StructureView(boolean published, OffsetDateTime publishedAt, String publishedBy, int unitCount, int positionCount,
                                int vacantCount, List<UnitView> roots) {
    }

    public record UnitRequest(Long parentId, @NotNull OrgUnit.Kind kind, @NotBlank @Size(max = 150) String name,
                              @Size(max = 30) String shortName, Long departmentId, boolean advisory, Integer sortOrder,
                              @Size(max = 500) String description) {
    }

    public record PositionRequest(@NotBlank @Size(max = 150) String title, boolean head, Long holderUserId,
                                  @Size(max = 150) String holderName, boolean acting, Integer sortOrder) {
    }

    // ------------------------------------------------------------------ reading

    /** The whole structure; {@code forPublic} leaves out the staff accounts behind the holders. */
    @Transactional(readOnly = true)
    public StructureView structure(boolean forPublic) {
        OrgSettings s = settings.findFirstByOrderByIdAsc().orElse(null);
        boolean published = s != null && s.getPublishedAt() != null;
        if (forPublic && !published) throw new NotFoundException("Organisation structure", 0L);
        List<OrgUnit> all = units.findAllByOrderBySortOrderAscIdAsc();
        List<OrgPosition> allPositions = positions.findAllByOrderBySortOrderAscIdAsc();
        Set<Long> userIds = allPositions.stream().map(OrgPosition::getHolderUserId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> names = users.findAllById(userIds).stream().collect(Collectors.toMap(UserAccount::getId, UserAccount::getFullName));
        Map<Long, List<PositionView>> byUnit = new HashMap<>();
        for (OrgPosition p : allPositions) {
            String holder = p.getHolderUserId() != null ? names.get(p.getHolderUserId()) : p.getHolderName();
            byUnit.computeIfAbsent(p.getUnitId(), k -> new ArrayList<>()).add(new PositionView(p.getId(), p.getTitle(), p.isHead(),
                    forPublic ? null : p.getHolderUserId(), holder, p.isActing(), p.getSortOrder()));
        }
        byUnit.values().forEach(l -> l.sort(Comparator.comparing((PositionView v) -> !v.head()).thenComparingInt(PositionView::sortOrder)));
        Map<Long, List<OrgUnit>> children = new HashMap<>();
        Set<Long> ids = all.stream().map(OrgUnit::getId).collect(Collectors.toSet());
        List<OrgUnit> roots = new ArrayList<>();
        for (OrgUnit u : all) {
            if (u.getParentId() == null || !ids.contains(u.getParentId())) roots.add(u);
            else children.computeIfAbsent(u.getParentId(), k -> new ArrayList<>()).add(u);
        }
        List<UnitView> tree = roots.stream().map(u -> view(u, children, byUnit, forPublic)).toList();
        int vacant = (int) byUnit.values().stream().flatMap(List::stream).filter(PositionView::vacant).count();
        return new StructureView(published, published ? s.getPublishedAt() : null, forPublic || s == null ? null : s.getPublishedBy(),
                all.size(), allPositions.size(), vacant, tree);
    }

    private static UnitView view(OrgUnit u, Map<Long, List<OrgUnit>> children, Map<Long, List<PositionView>> byUnit, boolean forPublic) {
        return new UnitView(u.getId(), u.getParentId(), u.getKind().name(), u.getName(), u.getShortName(), forPublic ? null : u.getDepartmentId(),
                u.isAdvisory(), u.getSortOrder(), u.getDescription(), byUnit.getOrDefault(u.getId(), List.of()),
                children.getOrDefault(u.getId(), List.of()).stream().map(c -> view(c, children, byUnit, forPublic)).toList());
    }

    // ------------------------------------------------------------------ units

    @Transactional
    public StructureView createUnit(UnitRequest req) {
        if (units.count() >= MAX_UNITS) throw new BusinessRuleException("TOO_MANY_UNITS", "The structure can have at most " + MAX_UNITS + " units");
        OrgUnit u = new OrgUnit();
        apply(u, req);
        units.save(u);
        audit.record("ORG_UNIT_CREATED", "OrgUnit", u.getId(), Map.of("name", u.getName()));
        return structure(false);
    }

    @Transactional
    public StructureView updateUnit(Long id, UnitRequest req) {
        OrgUnit u = unit(id);
        apply(u, req);
        audit.record("ORG_UNIT_UPDATED", "OrgUnit", u.getId(), Map.of("name", u.getName()));
        return structure(false);
    }

    /** Removes a unit with its positions; a unit with units under it must be emptied (or they moved) first. */
    @Transactional
    public StructureView deleteUnit(Long id) {
        OrgUnit u = unit(id);
        if (units.existsByParentId(id)) {
            throw new BusinessRuleException("UNIT_HAS_CHILDREN", u.getName() + " has units under it. Move or remove them first.");
        }
        positions.deleteAll(positions.findByUnitId(id));
        units.delete(u);
        audit.record("ORG_UNIT_DELETED", "OrgUnit", id, Map.of("name", u.getName()));
        return structure(false);
    }

    private void apply(OrgUnit u, UnitRequest req) {
        if (req.parentId() != null) {
            unit(req.parentId());
            if (u.getId() != null && wouldLoop(u.getId(), req.parentId())) {
                throw new BusinessRuleException("UNIT_LOOP", "A unit cannot report to itself or to a unit under it");
            }
        }
        if (req.departmentId() != null && !departments.existsById(req.departmentId())) throw new NotFoundException("Department", req.departmentId());
        u.setParentId(req.parentId());
        u.setKind(req.kind());
        u.setName(req.name().trim());
        u.setShortName(blank(req.shortName()));
        u.setDepartmentId(req.departmentId());
        u.setAdvisory(req.advisory());
        if (req.sortOrder() != null) u.setSortOrder(req.sortOrder());
        else if (u.getId() == null) u.setSortOrder(nextOrder(req.parentId()));
        u.setDescription(blank(req.description()));
    }

    private boolean wouldLoop(Long id, Long newParent) {
        Map<Long, Long> parents = units.findAll().stream().filter(x -> x.getParentId() != null)
                .collect(Collectors.toMap(OrgUnit::getId, OrgUnit::getParentId));
        Set<Long> seen = new HashSet<>();
        for (Long p = newParent; p != null && seen.add(p); p = parents.get(p)) {
            if (p.equals(id)) return true;
        }
        return false;
    }

    private int nextOrder(Long parentId) {
        return units.findAll().stream().filter(x -> Objects.equals(x.getParentId(), parentId)).mapToInt(OrgUnit::getSortOrder).max().orElse(-1) + 1;
    }

    private OrgUnit unit(Long id) {
        return units.findById(id).orElseThrow(() -> new NotFoundException("Unit", id));
    }

    // ------------------------------------------------------------------ positions

    @Transactional
    public StructureView createPosition(Long unitId, PositionRequest req) {
        unit(unitId);
        OrgPosition p = new OrgPosition();
        p.setUnitId(unitId);
        apply(p, req);
        if (req.sortOrder() == null) {
            p.setSortOrder(positions.findByUnitId(unitId).stream().mapToInt(OrgPosition::getSortOrder).max().orElse(-1) + 1);
        }
        positions.save(p);
        audit.record("ORG_POSITION_CREATED", "OrgPosition", p.getId(), Map.of("title", p.getTitle()));
        return structure(false);
    }

    @Transactional
    public StructureView updatePosition(Long id, PositionRequest req) {
        OrgPosition p = positions.findById(id).orElseThrow(() -> new NotFoundException("Position", id));
        apply(p, req);
        audit.record("ORG_POSITION_UPDATED", "OrgPosition", p.getId(), Map.of("title", p.getTitle(), "holder", p.getHolderName() == null ? "" : p.getHolderName()));
        return structure(false);
    }

    @Transactional
    public StructureView deletePosition(Long id) {
        OrgPosition p = positions.findById(id).orElseThrow(() -> new NotFoundException("Position", id));
        positions.delete(p);
        audit.record("ORG_POSITION_DELETED", "OrgPosition", id, Map.of("title", p.getTitle()));
        return structure(false);
    }

    private void apply(OrgPosition p, PositionRequest req) {
        if (req.holderUserId() != null) {
            UserAccount u = users.findById(req.holderUserId()).orElseThrow(() -> new NotFoundException("Staff member", req.holderUserId()));
            if (u.getUserType() != UserAccount.UserType.STAFF) {
                throw new BusinessRuleException("NOT_STAFF", "Only a member of staff can be chosen from the accounts; type the name instead");
            }
        }
        if (req.head()) {
            positions.findByUnitId(p.getUnitId()).stream().filter(o -> o.isHead() && !Objects.equals(o.getId(), p.getId())).forEach(o -> o.setHead(false));
        }
        p.setTitle(req.title().trim());
        p.setHead(req.head());
        p.setHolderUserId(req.holderUserId());
        p.setHolderName(req.holderUserId() != null ? null : blank(req.holderName()));
        p.setActing(req.acting() && (req.holderUserId() != null || blank(req.holderName()) != null));
        if (req.sortOrder() != null) p.setSortOrder(req.sortOrder());
    }

    // ------------------------------------------------------------------ template and publishing

    /**
     * Starts an empty structure from the usual shape of a NACTVET college: the Governing Board, the Principal with the Academic
     * Board beside, two Deputy Principals, the academic faculties and departments CIMS already has, and the common offices and
     * units. Every unit and position can then be renamed, moved or removed.
     */
    @Transactional
    public StructureView startFromTemplate() {
        if (units.count() > 0) throw new BusinessRuleException("STRUCTURE_EXISTS", "The structure already has units; the template is only for an empty one");
        OrgUnit board = add(null, OrgUnit.Kind.BOARD, "Governing Board", null, false, "Chairperson");
        OrgUnit principal = add(board.getId(), OrgUnit.Kind.OFFICE, "Office of the Principal", null, false, "Principal");
        add(principal.getId(), OrgUnit.Kind.COMMITTEE, "Academic Board", null, true, "Chairperson");
        add(principal.getId(), OrgUnit.Kind.UNIT, "Internal Audit Unit", null, false, "Internal Auditor");
        add(principal.getId(), OrgUnit.Kind.UNIT, "Procurement Management Unit", null, false, "Head of Procurement");
        add(principal.getId(), OrgUnit.Kind.UNIT, "Quality Assurance Unit", null, false, "Quality Assurance Officer");
        OrgUnit academic = add(principal.getId(), OrgUnit.Kind.DIRECTORATE, "Deputy Principal (Academics, Research and Consultancy)", "DPARC", false,
                "Deputy Principal (Academics, Research and Consultancy)");
        OrgUnit admin = add(principal.getId(), OrgUnit.Kind.DIRECTORATE, "Deputy Principal (Planning, Finance and Administration)", "DPPFA", false,
                "Deputy Principal (Planning, Finance and Administration)");
        add(academic.getId(), OrgUnit.Kind.OFFICE, "Office of the Registrar", null, false, "Registrar");
        add(academic.getId(), OrgUnit.Kind.OFFICE, "Examinations Office", null, false, "Examinations Officer");
        add(academic.getId(), OrgUnit.Kind.OFFICE, "Admissions Office", null, false, "Admissions Officer");
        add(academic.getId(), OrgUnit.Kind.UNIT, "Library", null, false, "Librarian");

        Map<Long, OrgUnit> facultyUnits = new HashMap<>();
        List<Department> depts = departments.findAllByOrderByNameAsc().stream().filter(Department::isActive).toList();
        Map<Long, Faculty> facultyById = faculties.findAllByOrderByNameAsc().stream().filter(Faculty::isActive)
                .collect(Collectors.toMap(Faculty::getId, Function.identity()));
        for (Department d : depts) {
            Long parent = academic.getId();
            Faculty f = d.getFacultyId() == null ? null : facultyById.get(d.getFacultyId());
            if (f != null) {
                parent = facultyUnits.computeIfAbsent(f.getId(), k -> add(academic.getId(), OrgUnit.Kind.FACULTY, f.getName(), null, false, "Dean")).getId();
            }
            OrgUnit unit = add(parent, OrgUnit.Kind.DEPARTMENT, d.getName(), d.getCode(), false, "Head of Department");
            unit.setDepartmentId(d.getId());
        }
        if (depts.isEmpty()) add(academic.getId(), OrgUnit.Kind.DEPARTMENT, "Department of …", null, false, "Head of Department");

        add(admin.getId(), OrgUnit.Kind.UNIT, "Finance and Accounts Unit", null, false, "Bursar");
        add(admin.getId(), OrgUnit.Kind.UNIT, "Human Resources and Administration Unit", null, false, "Human Resources Officer");
        add(admin.getId(), OrgUnit.Kind.UNIT, "Planning Unit", null, false, "Planning Officer");
        add(admin.getId(), OrgUnit.Kind.UNIT, "ICT Unit", null, false, "Head of ICT");
        add(admin.getId(), OrgUnit.Kind.UNIT, "Estates Unit", null, false, "Estates Officer");
        add(admin.getId(), OrgUnit.Kind.OFFICE, "Dean of Students", null, false, "Dean of Students");
        audit.record("ORG_STRUCTURE_TEMPLATE", "OrgUnit", board.getId(), Map.of("departments", depts.size()));
        return structure(false);
    }

    private OrgUnit add(Long parentId, OrgUnit.Kind kind, String name, String shortName, boolean advisory, String headTitle) {
        OrgUnit u = new OrgUnit();
        u.setParentId(parentId);
        u.setKind(kind);
        u.setName(name);
        u.setShortName(shortName);
        u.setAdvisory(advisory);
        u.setSortOrder(nextOrder(parentId));
        units.saveAndFlush(u);
        OrgPosition p = new OrgPosition();
        p.setUnitId(u.getId());
        p.setTitle(headTitle);
        p.setHead(true);
        positions.save(p);
        return u;
    }

    @Transactional
    public StructureView publish(boolean publish) {
        OrgSettings s = settings.findFirstByOrderByIdAsc().orElseGet(OrgSettings::new);
        if (publish && units.count() == 0) throw new BusinessRuleException("STRUCTURE_EMPTY", "Build the structure before publishing it");
        s.setPublishedAt(publish ? OffsetDateTime.now() : null);
        s.setPublishedBy(publish ? CurrentUser.name() : null);
        settings.save(s);
        audit.record(publish ? "ORG_STRUCTURE_PUBLISHED" : "ORG_STRUCTURE_WITHDRAWN", "OrgSettings", s.getId(), Map.of());
        return structure(false);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
