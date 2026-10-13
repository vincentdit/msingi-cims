package tz.co.msingi.cims.approvals;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** One list of what waits for the signed-in person's decision. */
@Tag(name = "Approvals")
@RestController
@RequiredArgsConstructor
public class ApprovalsController {

    static final String APPROVERS = "hasAnyRole('INSTITUTION_ADMIN','DEPUTY_PRINCIPAL_ACADEMIC','HEAD_OF_DEPARTMENT','EXAMINATIONS_OFFICER','BURSAR','MANAGEMENT_VIEWER')";

    private final ApprovalsService service;

    @Operation(summary = "Everything waiting for my decision, oldest first, with a link to where it is decided")
    @GetMapping("/api/approvals")
    @PreAuthorize(APPROVERS)
    public ApprovalsService.Inbox inbox() {
        return service.inbox();
    }

    @Operation(summary = "How many items wait for my decision (for the menu)")
    @GetMapping("/api/approvals/count")
    @PreAuthorize(APPROVERS)
    public Map<String, Long> count() {
        return Map.of("actionable", service.inbox().actionable());
    }
}
