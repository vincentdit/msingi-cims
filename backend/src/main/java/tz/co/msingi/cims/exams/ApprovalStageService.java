package tz.co.msingi.cims.exams;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.academic.domain.Programme;
import tz.co.msingi.cims.academic.curriculum.Module;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.security.Role;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The approval stages results pass through, as the institution sets them (FR-EXM-05 / FR-ADM-S03):
 * for each module, stages after the Head of Department (e.g. the Department Board, an external examiner's review recorded by the
 * Examinations Officer); for each class result sheet, review stages before the Academic Committee (e.g. the Examinations Committee).
 * The Academic Committee's recommendation and the Governing Board's confirmation stay as the regulations require.
 */
@Service
@RequiredArgsConstructor
public class ApprovalStageService {

    public enum Flow { MODULE_RESULTS, RESULT_SHEET }

    /** Who can be put in charge of a stage. */
    public static final Set<Role> STAGE_ROLES = Set.of(Role.HEAD_OF_DEPARTMENT, Role.EXAMINATIONS_OFFICER, Role.DEPUTY_PRINCIPAL_ACADEMIC,
            Role.MANAGEMENT_VIEWER, Role.INSTITUTION_ADMIN, Role.PROGRAMME_COORDINATOR);

    public record Stage(int order, String name, String role) {
    }

    public record StageRequest(@NotBlank @Size(max = 100) String name, @NotBlank String role) {
    }

    public record Routes(List<Stage> moduleResults, boolean moduleStagesConfigured, List<Stage> resultSheet) {
    }

    public record StageApproval(int order, String name, String byName, OffsetDateTime at, String note) {
    }

    private final JdbcTemplate jdbc;
    private final ExamAccess access;
    private final AuditService audit;
    private final RegulationService regulations;

    @Transactional(readOnly = true)
    public Routes routes() {
        return new Routes(stages(Flow.MODULE_RESULTS), configured(), stages(Flow.RESULT_SHEET));
    }

    /** The stages in force: for modules, the Department Board setting until the institution sets its own stages. */
    @Transactional(readOnly = true)
    public List<Stage> stages(Flow flow) {
        List<Stage> s = jdbc.query("select stage_order, name, role from approval_stages where flow = ? order by stage_order",
                (rs, i) -> new Stage(rs.getInt(1), rs.getString(2), rs.getString(3)), flow.name());
        if (flow == Flow.MODULE_RESULTS && s.isEmpty() && !configured() && regulations.settings().isUseDepartmentBoard()) {
            return List.of(new Stage(1, "Department Board", "DEPARTMENT_BOARD"));
        }
        return s;
    }

    @Transactional
    public Routes save(Flow flow, List<StageRequest> list) {
        access.require("set the approval stages", Role.INSTITUTION_ADMIN, Role.EXAMINATIONS_OFFICER);
        if (list.size() > 6) throw new BusinessRuleException("TOO_MANY_STAGES", "At most six stages");
        for (StageRequest r : list) {
            Role role;
            try {
                role = Role.valueOf(r.role());
            } catch (IllegalArgumentException e) {
                throw new BusinessRuleException("UNKNOWN_ROLE", "Unknown role " + r.role());
            }
            if (!STAGE_ROLES.contains(role)) throw new BusinessRuleException("ROLE_NOT_ALLOWED", role + " cannot be put in charge of a results stage");
        }
        Integer waiting = flow == Flow.MODULE_RESULTS
                ? jdbc.queryForObject("select count(*) from module_offerings where status = 'HOD_APPROVED'", Integer.class)
                : jdbc.queryForObject("select count(*) from result_sheets where status = 'COMPUTED' and stage_no > 0", Integer.class);
        if (waiting != null && waiting > 0) {
            throw new BusinessRuleException("RESULTS_IN_PROGRESS", waiting + " results are part-way through the current stages. Finish or return them first.");
        }
        jdbc.update("delete from approval_stages where flow = ?", flow.name());
        int i = 1;
        for (StageRequest r : list) {
            jdbc.update("insert into approval_stages (tenant_id, flow, stage_order, name, role) values (cims_current_tenant(), ?, ?, ?, ?)",
                    flow.name(), i++, r.name().trim(), r.role());
        }
        if (flow == Flow.MODULE_RESULTS) jdbc.update("update exam_settings set module_stages_configured = true");
        audit.record("APPROVAL_STAGES_SET", "ApprovalStages", null, Map.of("flow", flow.name(), "stages", list.stream().map(r -> r.name() + " (" + r.role() + ")").toList()));
        return routes();
    }

    /** Whether the signed-in person can act for this stage of this module's or class's results. */
    public boolean canAct(Stage stage, Module module, Programme programme) {
        if ("DEPARTMENT_BOARD".equals(stage.role())) {
            return (module != null && access.moderates(module)) || access.has(Role.EXAMINATIONS_OFFICER, Role.INSTITUTION_ADMIN);
        }
        Role role = Role.valueOf(stage.role());
        if (!access.has(role)) return false;
        if (role == Role.HEAD_OF_DEPARTMENT) {
            if (module != null) return access.moderates(module);
            return programme != null && programme.getDepartment() != null && access.headsDepartment(programme.getDepartment().getId());
        }
        return true;
    }

    void record(Flow flow, Long subject, Stage stage, String note) {
        jdbc.update("""
                insert into result_stage_approvals (tenant_id, flow, subject_id, stage_order, stage_name, approved_by_id, approved_by_name, note)
                values (cims_current_tenant(), ?, ?, ?, ?, ?, ?, ?)""", flow.name(), subject, stage.order(), stage.name(),
                CurrentUser.id().orElse(null), CurrentUser.name(), note == null || note.isBlank() ? null : note.trim());
    }

    /** The stages passed in the current round (since the results were last submitted or computed). */
    List<StageApproval> approvals(Flow flow, Long subject, int passed) {
        if (passed <= 0) return List.of();
        List<StageApproval> all = jdbc.query("""
                select stage_order, stage_name, approved_by_name, approved_at, note from result_stage_approvals
                where flow = ? and subject_id = ? order by approved_at desc, id desc limit ?""",
                (rs, i) -> new StageApproval(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getObject(4, OffsetDateTime.class), rs.getString(5)),
                flow.name(), subject, passed);
        List<StageApproval> out = new ArrayList<>(all);
        java.util.Collections.reverse(out);
        return out;
    }

    private boolean configured() {
        List<Boolean> r = jdbc.queryForList("select module_stages_configured from exam_settings", Boolean.class);
        return !r.isEmpty() && Boolean.TRUE.equals(r.get(0));
    }
}
