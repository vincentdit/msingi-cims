package tz.co.msingi.cims.ipt;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tz.co.msingi.cims.common.exception.TooManyRequestsException;
import tz.co.msingi.cims.common.web.RateLimiter;

@Tag(name = "IPT workplace assessment")
@RestController
@RequiredArgsConstructor
public class WorkplaceAssessmentController {

    private final WorkplaceAssessmentService service;
    private final RateLimiter limiter = new RateLimiter(60, 60_000, java.time.Clock.systemUTC());

    @Operation(summary = "Send the workplace supervisor the link to assess the student")
    @PostMapping("/api/ipt/placements/{id}/workplace-link")
    @PreAuthorize("isAuthenticated()")
    public WorkplaceAssessmentService.LinkSent send(@PathVariable Long id, @Valid @RequestBody WorkplaceAssessmentService.LinkRequest req) {
        return service.send(id, req);
    }

    @GetMapping("/api/public/ipt-assessment/{token}")
    public WorkplaceAssessmentService.FormView form(@PathVariable String token, HttpServletRequest http) {
        limit(http);
        return service.form(token);
    }

    @PostMapping("/api/public/ipt-assessment/{token}")
    public WorkplaceAssessmentService.FormView submit(@PathVariable String token, @Valid @RequestBody WorkplaceAssessmentService.Submission sub, HttpServletRequest http) {
        limit(http);
        return service.submit(token, sub);
    }

    private void limit(HttpServletRequest http) {
        if (!limiter.tryAcquire(http.getRemoteAddr())) throw new TooManyRequestsException("Too many requests. Try again in a minute.");
    }
}
