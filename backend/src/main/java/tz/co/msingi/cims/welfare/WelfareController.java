package tz.co.msingi.cims.welfare;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.platform.security.StaffAccess;

import java.time.OffsetDateTime;
import java.util.List;

/** Student discipline and welfare (FR-WEL-01..04). */
@Tag(name = "Discipline and welfare")
@RestController
@RequiredArgsConstructor
public class WelfareController {

    static final String MANAGE = "hasAnyRole('INSTITUTION_ADMIN','DEAN_OF_STUDENTS')";
    static final String READ = "hasAnyRole('INSTITUTION_ADMIN','DEAN_OF_STUDENTS','MANAGEMENT_VIEWER','AUDITOR')";
    static final String DEAN = "hasRole('DEAN_OF_STUDENTS')";

    private final WelfareService welfare;

    public record TextRequest(String text) {
    }

    public record AppointmentRequest(OffsetDateTime at, Boolean tellStudent) {
    }

    public record OpenRequest(boolean open) {
    }

    @GetMapping("/api/welfare/overview")
    @PreAuthorize(READ)
    public WelfareService.Overview overview() {
        return welfare.overview();
    }

    @Operation(summary = "Report a disciplinary incident (any member of staff)")
    @PostMapping("/api/discipline/cases")
    @PreAuthorize(StaffAccess.ANY)
    public WelfareService.CaseView report(@RequestBody WelfareService.ReportRequest r) {
        return welfare.report(r);
    }

    @Operation(summary = "Cases: all of them for the Dean, administrator, management and auditor; otherwise the ones you reported (status OPEN: not yet closed)")
    @GetMapping("/api/discipline/cases")
    @PreAuthorize(StaffAccess.ANY)
    public List<WelfareService.CaseView> cases(@RequestParam(required = false) String status, @RequestParam(required = false) String q) {
        return welfare.cases(status, q);
    }

    @GetMapping("/api/discipline/cases/{id}")
    @PreAuthorize(StaffAccess.ANY)
    public WelfareService.CaseView get(@PathVariable Long id) {
        return welfare.get(id);
    }

    @PutMapping("/api/discipline/cases/{id}/hearing")
    @PreAuthorize(MANAGE)
    public WelfareService.CaseView hearing(@PathVariable Long id, @RequestBody WelfareService.HearingRequest r) {
        return welfare.setHearing(id, r);
    }

    @PostMapping("/api/discipline/cases/{id}/decision")
    @PreAuthorize(MANAGE)
    public WelfareService.CaseView decide(@PathVariable Long id, @RequestBody WelfareService.DecisionRequest r) {
        return welfare.decide(id, r);
    }

    @PostMapping("/api/discipline/cases/{id}/withdraw")
    @PreAuthorize(MANAGE)
    public WelfareService.CaseView withdraw(@PathVariable Long id, @RequestBody TextRequest r) {
        return welfare.withdraw(id, r.text());
    }

    @PostMapping("/api/discipline/cases/{id}/close")
    @PreAuthorize(MANAGE)
    public WelfareService.CaseView close(@PathVariable Long id) {
        return welfare.close(id);
    }

    @Operation(summary = "Decide the student's appeal (the Principal or the administrator, not whoever decided the case)")
    @PostMapping("/api/discipline/cases/{id}/appeal-decision")
    @PreAuthorize("hasAnyRole('INSTITUTION_ADMIN','MANAGEMENT_VIEWER')")
    public WelfareService.CaseView decideAppeal(@PathVariable Long id, @RequestBody WelfareService.AppealDecisionRequest r) {
        return welfare.decideAppeal(id, r);
    }

    @Operation(summary = "Refer a student to the Dean of Students for counselling (any member of staff)")
    @PostMapping("/api/welfare/referrals")
    @PreAuthorize(StaffAccess.ANY)
    public void refer(@RequestBody WelfareService.ReferralRequest r) {
        welfare.refer(r);
    }

    @GetMapping("/api/welfare/counselling")
    @PreAuthorize(DEAN)
    public List<WelfareService.CounsellingSummary> counselling(@RequestParam(required = false) String status) {
        return welfare.counselling(status);
    }

    @GetMapping("/api/welfare/counselling/{id}")
    @PreAuthorize(DEAN)
    public WelfareService.CounsellingCase counsellingCase(@PathVariable Long id) {
        return welfare.counsellingCase(id);
    }

    @PostMapping("/api/welfare/counselling/{id}/notes")
    @PreAuthorize(DEAN)
    public WelfareService.CounsellingCase note(@PathVariable Long id, @RequestBody TextRequest r) {
        return welfare.addNote(id, r.text());
    }

    @PutMapping("/api/welfare/counselling/{id}/appointment")
    @PreAuthorize(DEAN)
    public WelfareService.CounsellingCase appointment(@PathVariable Long id, @RequestBody AppointmentRequest r) {
        return welfare.appointment(id, r.at(), !Boolean.FALSE.equals(r.tellStudent()));
    }

    @PutMapping("/api/welfare/counselling/{id}/open")
    @PreAuthorize(DEAN)
    public WelfareService.CounsellingCase open(@PathVariable Long id, @RequestBody OpenRequest r) {
        return welfare.setOpen(id, r.open());
    }

    // ------------------------------------------------------------------ students

    @GetMapping("/api/student/discipline")
    @PreAuthorize("hasRole('STUDENT')")
    public List<WelfareService.MyCase> myCases() {
        return welfare.myCases();
    }

    @PostMapping("/api/student/discipline/{id}/statement")
    @PreAuthorize("hasRole('STUDENT')")
    public List<WelfareService.MyCase> statement(@PathVariable Long id, @RequestBody TextRequest r) {
        return welfare.statement(id, r.text());
    }

    @PostMapping("/api/student/discipline/{id}/appeal")
    @PreAuthorize("hasRole('STUDENT')")
    public List<WelfareService.MyCase> appeal(@PathVariable Long id, @RequestBody TextRequest r) {
        return welfare.appeal(id, r.text());
    }

    @GetMapping("/api/student/welfare")
    @PreAuthorize("hasRole('STUDENT')")
    public List<WelfareService.MyRequest> myRequests() {
        return welfare.myRequests();
    }

    @Operation(summary = "Ask to see the Dean of Students (what you write is read only by the Dean)")
    @PostMapping("/api/student/welfare")
    @PreAuthorize("hasRole('STUDENT')")
    public List<WelfareService.MyRequest> ask(@RequestBody WelfareService.ReferralRequest r) {
        return welfare.askForCounselling(r);
    }
}
