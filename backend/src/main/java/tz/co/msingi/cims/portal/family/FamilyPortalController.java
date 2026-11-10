package tz.co.msingi.cims.portal.family;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.security.TokenService;

import java.util.List;
import java.util.Map;

/** The family and sponsor portal, the student's sharing choices and the registry's family links (FR-STU-05). */
@Tag(name = "Family portal")
@RestController
@RequiredArgsConstructor
public class FamilyPortalController {

    private static final String FAMILY = "hasRole('FAMILY')";
    private static final String REGISTRY = "hasAnyRole('INSTITUTION_ADMIN','REGISTRAR')";
    private static final String REGISTRY_READ = "hasAnyRole('INSTITUTION_ADMIN','REGISTRAR','BURSAR','ACCOUNTANT')";

    private final FamilyPortalService family;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public record PhoneRequest(@NotBlank @Size(max = 20) String phone) {
    }

    public record CodeRequest(@NotBlank @Size(max = 20) String phone, @NotBlank @Size(max = 8) String code) {
    }

    public record SharingRequest(boolean shareResults, boolean shareAttendance) {
    }

    public record LinkRequest(@NotBlank @Size(max = 20) String phone, @Size(max = 150) String name, @Size(max = 40) String relation) {
    }

    public record ActiveRequest(boolean active) {
    }

    public record Preferences(boolean feeRemindersOptOut) {
    }

    // ------------------------------------------------------------------ sign-in (public, rate-limited per address)

    @Operation(summary = "Send a sign-in code to a parent's, guardian's or sponsor's phone (same answer whether or not the number is linked)")
    @PostMapping("/api/family-auth/code")
    public FamilyPortalService.CodeSent code(@Valid @RequestBody PhoneRequest r) {
        return family.requestCode(r.phone());
    }

    @Operation(summary = "Sign in to the family portal with the code")
    @PostMapping("/api/family-auth/verify")
    public TokenService.TokenResponse verify(@Valid @RequestBody CodeRequest r) {
        return family.verify(r.phone(), r.code());
    }

    // ------------------------------------------------------------------ the portal

    @GetMapping("/api/family/students")
    @PreAuthorize(FAMILY)
    public List<FamilyPortalService.LinkedStudent> students() {
        return family.myStudents();
    }

    @GetMapping("/api/family/students/{id}/fees")
    @PreAuthorize(FAMILY)
    public tz.co.msingi.cims.finance.accounts.AccountDtos.Statement fees(@PathVariable Long id) {
        return family.fees(id);
    }

    @GetMapping("/api/family/students/{id}/results")
    @PreAuthorize(FAMILY)
    public tz.co.msingi.cims.exams.ExamDtos.StudentResults results(@PathVariable Long id) {
        return family.results(id);
    }

    @GetMapping("/api/family/students/{id}/attendance")
    @PreAuthorize(FAMILY)
    public tz.co.msingi.cims.teaching.TeachingDtos.MyAttendance attendance(@PathVariable Long id) {
        return family.attendance(id);
    }

    // ------------------------------------------------------------------ the student

    @Operation(summary = "Who can follow me in the family portal, and what they see")
    @GetMapping("/api/student/family")
    @PreAuthorize("hasRole('STUDENT')")
    public List<FamilyPortalService.LinkView> myFamily() {
        return family.mine();
    }

    @PutMapping("/api/student/family/{id}")
    @PreAuthorize("hasRole('STUDENT')")
    public FamilyPortalService.LinkView share(@PathVariable Long id, @RequestBody SharingRequest r) {
        return family.share(id, r.shareResults(), r.shareAttendance());
    }

    @GetMapping("/api/student/preferences")
    @PreAuthorize("hasRole('STUDENT')")
    @Transactional(readOnly = true)
    public Preferences preferences() {
        return jdbc.query("select fee_reminders_opt_out from students where user_id = ?", (rs, i) -> new Preferences(rs.getBoolean(1)), CurrentUser.requireId())
                .stream().findFirst().orElse(new Preferences(false));
    }

    @Operation(summary = "Stop (or restart) fee reminder SMS to me; guardians and sponsors are still told")
    @PutMapping("/api/student/preferences")
    @PreAuthorize("hasRole('STUDENT')")
    @Transactional
    public Preferences setPreferences(@RequestBody Preferences p) {
        jdbc.update("update students set fee_reminders_opt_out = ? where user_id = ?", p.feeRemindersOptOut(), CurrentUser.requireId());
        audit.record("FEE_REMINDERS_PREFERENCE", "Student", null, Map.of("optOut", p.feeRemindersOptOut()));
        return preferences();
    }

    // ------------------------------------------------------------------ the registry

    @GetMapping("/api/registry/students/{id}/family")
    @PreAuthorize(REGISTRY_READ)
    public List<FamilyPortalService.LinkView> links(@PathVariable Long id) {
        return family.forStudent(id);
    }

    @PostMapping("/api/registry/students/{id}/family")
    @PreAuthorize(REGISTRY)
    public FamilyPortalService.LinkView add(@PathVariable Long id, @Valid @RequestBody LinkRequest r) {
        return family.add(id, r.phone(), r.name(), r.relation());
    }

    @PutMapping("/api/registry/students/{id}/family/{linkId}")
    @PreAuthorize(REGISTRY)
    public FamilyPortalService.LinkView setActive(@PathVariable Long id, @PathVariable Long linkId, @RequestBody ActiveRequest r) {
        return family.setActive(id, linkId, r.active());
    }

    @GetMapping("/api/family-portal/settings")
    @PreAuthorize("hasAnyRole('INSTITUTION_ADMIN','REGISTRAR','BURSAR')")
    public FamilyPortalService.Settings settings() {
        return family.settings();
    }

    @PutMapping("/api/family-portal/settings")
    @PreAuthorize("hasRole('INSTITUTION_ADMIN')")
    public FamilyPortalService.Settings setSettings(@RequestBody FamilyPortalService.Settings s) {
        return family.setSettings(s);
    }
}
