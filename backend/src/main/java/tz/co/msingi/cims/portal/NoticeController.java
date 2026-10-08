package tz.co.msingi.cims.portal;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.portal.PortalDtos.ChangeRequestView;
import tz.co.msingi.cims.portal.PortalDtos.DecisionRequest;
import tz.co.msingi.cims.portal.PortalDtos.NoticeRequest;
import tz.co.msingi.cims.portal.PortalDtos.NoticeView;
import tz.co.msingi.cims.portal.PortalDtos.PublishRequest;
import tz.co.msingi.cims.portal.PortalDtos.RejectRequest;

import java.util.List;
import java.util.Map;

/** Staff side of the student portal: notices, and Registry's decisions on requests to update details. */
@Tag(name = "Student portal — staff")
@RestController
@RequiredArgsConstructor
public class NoticeController {

    private static final String NOTICE_READ = "hasAnyRole('INSTITUTION_ADMIN','REGISTRAR','DEPUTY_PRINCIPAL_ACADEMIC','BURSAR','ACCOUNTANT',"
            + "'EXAMINATIONS_OFFICER','HOSTEL_WARDEN','ADMISSIONS_OFFICER','MANAGEMENT_VIEWER')";
    private static final String NOTICE_WRITE = "hasAnyRole('INSTITUTION_ADMIN','REGISTRAR','DEPUTY_PRINCIPAL_ACADEMIC','BURSAR',"
            + "'EXAMINATIONS_OFFICER','HOSTEL_WARDEN')";
    private static final String REGISTRY_READ = "hasAnyRole('INSTITUTION_ADMIN','REGISTRAR','DEPUTY_PRINCIPAL_ACADEMIC','MANAGEMENT_VIEWER')";
    private static final String REGISTRY = "hasAnyRole('INSTITUTION_ADMIN','REGISTRAR')";

    private final NoticeService notices;
    private final ChangeRequestService requests;

    @GetMapping("/api/notices")
    @PreAuthorize(NOTICE_READ)
    public List<NoticeView> list() {
        return notices.list();
    }

    @PostMapping("/api/notices")
    @PreAuthorize(NOTICE_WRITE)
    public NoticeView create(@Valid @RequestBody NoticeRequest req) {
        return notices.create(req);
    }

    @PutMapping("/api/notices/{id}")
    @PreAuthorize(NOTICE_WRITE)
    public NoticeView update(@PathVariable Long id, @Valid @RequestBody NoticeRequest req) {
        return notices.update(id, req);
    }

    @PostMapping("/api/notices/{id}/publish")
    @PreAuthorize(NOTICE_WRITE)
    public NoticeView publish(@PathVariable Long id, @RequestBody(required = false) PublishRequest req) {
        return notices.publish(id, req);
    }

    @PostMapping("/api/notices/{id}/withdraw")
    @PreAuthorize(NOTICE_WRITE)
    public NoticeView withdraw(@PathVariable Long id) {
        return notices.withdraw(id);
    }

    @GetMapping("/api/registry/change-requests")
    @PreAuthorize(REGISTRY_READ)
    public List<ChangeRequestView> requests(@RequestParam(defaultValue = "PENDING") String status) {
        return requests.list(status);
    }

    @GetMapping("/api/registry/change-requests/count")
    @PreAuthorize(REGISTRY_READ)
    public Map<String, Long> pendingCount() {
        return Map.of("pending", requests.pendingCount());
    }

    @PostMapping("/api/registry/change-requests/{id}/approve")
    @PreAuthorize(REGISTRY)
    public ChangeRequestView approve(@PathVariable Long id, @Valid @RequestBody(required = false) DecisionRequest req) {
        return requests.approve(id, req == null ? null : req.note());
    }

    @PostMapping("/api/registry/change-requests/{id}/reject")
    @PreAuthorize(REGISTRY)
    public ChangeRequestView reject(@PathVariable Long id, @Valid @RequestBody RejectRequest req) {
        return requests.reject(id, req.note());
    }
}
