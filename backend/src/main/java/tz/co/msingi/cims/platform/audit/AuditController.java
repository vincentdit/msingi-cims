package tz.co.msingi.cims.platform.audit;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.common.web.PageResponse;
import tz.co.msingi.cims.platform.audit.AuditQueryService.Entry;
import tz.co.msingi.cims.platform.audit.AuditQueryService.Facets;
import tz.co.msingi.cims.platform.audit.AuditQueryService.Filter;
import tz.co.msingi.cims.platform.audit.AuditQueryService.Summary;

import java.time.LocalDate;

/** The audit trail (Document 03: the Auditor reads records and audit logs; the Institution Administrator too). */
@Tag(name = "Audit trail")
@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private static final String READ = "hasAnyRole('AUDITOR','INSTITUTION_ADMIN')";

    private final AuditQueryService service;

    @Operation(summary = "Audit entries, newest first; a record's full history when entity and entityId are given")
    @GetMapping("/logs")
    @PreAuthorize(READ)
    public PageResponse<Entry> logs(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                    @RequestParam(required = false) String area, @RequestParam(required = false) String entity,
                                    @RequestParam(required = false) Long entityId, @RequestParam(required = false) String action,
                                    @RequestParam(required = false) Long actorId, @RequestParam(required = false) String q,
                                    @RequestParam(defaultValue = "false") boolean security,
                                    @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return service.search(new Filter(from, to, area, entity, entityId, action, actorId, q, security), page, size);
    }

    @Operation(summary = "Choices for the filters: areas, records, actions and people that appear in the trail")
    @GetMapping("/facets")
    @PreAuthorize(READ)
    public Facets facets() {
        return service.facets();
    }

    @Operation(summary = "Activity and security events over a period (default the last 7 days)")
    @GetMapping("/summary")
    @PreAuthorize(READ)
    public Summary summary(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Filter f = AuditQueryService.normalise(new Filter(from, to, null, null, null, null, null, null, false));
        return service.summary(f.from(), f.to());
    }

    @Operation(summary = "The filtered trail as CSV (the export is itself recorded)")
    @GetMapping(value = "/logs.csv", produces = "text/csv")
    @PreAuthorize(READ)
    public ResponseEntity<byte[]> export(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(required = false) String area, @RequestParam(required = false) String entity,
                                         @RequestParam(required = false) Long entityId, @RequestParam(required = false) String action,
                                         @RequestParam(required = false) Long actorId, @RequestParam(required = false) String q,
                                         @RequestParam(defaultValue = "false") boolean security) {
        Filter f = AuditQueryService.normalise(new Filter(from, to, area, entity, entityId, action, actorId, q, security));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audit-trail-" + f.from() + "-to-" + f.to() + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(service.export(f));
    }
}
