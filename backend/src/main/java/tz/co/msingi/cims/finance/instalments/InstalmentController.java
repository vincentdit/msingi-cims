package tz.co.msingi.cims.finance.instalments;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.finance.instalments.InstalmentService.DueDate;
import tz.co.msingi.cims.finance.instalments.InstalmentService.DueDatesRequest;
import tz.co.msingi.cims.finance.instalments.InstalmentService.PenaltyView;
import tz.co.msingi.cims.finance.instalments.InstalmentService.Rules;
import tz.co.msingi.cims.finance.instalments.InstalmentService.RunResult;

import java.time.LocalDate;
import java.util.List;

/** Instalment due dates, reminder and penalty rules, and penalties applied. */
@Tag(name = "Finance — instalments, reminders and penalties")
@RestController
@RequestMapping("/api/finance")
@RequiredArgsConstructor
public class InstalmentController {

    private static final String READ = "hasAnyRole('INSTITUTION_ADMIN','BURSAR','ACCOUNTANT','MANAGEMENT_VIEWER','AUDITOR')";
    private static final String BURSAR = "hasAnyRole('INSTITUTION_ADMIN','BURSAR')";

    private final InstalmentService service;

    @GetMapping("/instalment-dates")
    @PreAuthorize(READ)
    public List<DueDate> dueDates(@RequestParam Long academicYearId) {
        return service.dueDates(academicYearId);
    }

    @PutMapping("/instalment-dates")
    @PreAuthorize(BURSAR)
    public List<DueDate> setDueDates(@RequestParam Long academicYearId, @Valid @RequestBody DueDatesRequest req) {
        return service.setDueDates(academicYearId, req.dates());
    }

    @GetMapping("/collection-rules")
    @PreAuthorize(READ)
    public Rules rules() {
        return service.rules();
    }

    @PutMapping("/collection-rules")
    @PreAuthorize(BURSAR)
    public Rules setRules(@Valid @RequestBody Rules req) {
        return service.setRules(req);
    }

    @GetMapping("/penalties")
    @PreAuthorize(READ)
    public List<PenaltyView> penalties() {
        return service.penalties();
    }

    @Operation(summary = "Send today's reminders and apply penalties now (also runs every morning); a past date re-runs a missed day")
    @PostMapping("/reminders/run")
    @PreAuthorize(BURSAR)
    public RunResult run(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate today = InstalmentService.today();
        if (date != null && date.isAfter(today)) {
            throw new tz.co.msingi.cims.common.exception.BusinessRuleException("FUTURE_DATE", "Reminders and penalties cannot be run for a future date");
        }
        return service.run(date == null ? today : date);
    }
}
