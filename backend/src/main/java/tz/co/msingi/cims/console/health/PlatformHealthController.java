package tz.co.msingi.cims.console.health;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.console.audit.PlatformAuditService;
import tz.co.msingi.cims.integration.log.IntegrationLogQuery;
import tz.co.msingi.cims.integration.log.IntegrationLogQuery.CallPage;
import tz.co.msingi.cims.integration.log.IntegrationLogQuery.Filter;
import tz.co.msingi.cims.integration.log.IntegrationLogQuery.SystemHealth;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/** Integration health across all institutions, and the platform audit trail (Msingi staff only). */
@RestController
@RequestMapping("/api/platform")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
@RequiredArgsConstructor
public class PlatformHealthController {

    private static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");

    private final IntegrationLogQuery query;
    private final PlatformAuditService audit;

    @GetMapping("/integrations/health")
    @Transactional(readOnly = true)
    public List<SystemHealth> health(@RequestParam(defaultValue = "24") int hours, @RequestParam(required = false) Long tenantId) {
        return query.health(tenantId, hours);
    }

    @GetMapping("/integrations/calls")
    @Transactional(readOnly = true)
    public CallPage calls(@RequestParam(required = false) Long tenantId, @RequestParam(required = false) String system,
                          @RequestParam(required = false) String outcome,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                          @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return query.calls(new Filter(tenantId, system, outcome, from == null ? null : from.atStartOfDay(ZONE).toOffsetDateTime(),
                to == null ? null : to.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime()), page, size);
    }

    @GetMapping("/audit")
    @Transactional(readOnly = true)
    public PlatformAuditService.EntryPage auditTrail(@RequestParam(required = false) Long tenantId, @RequestParam(required = false) String action,
                                                     @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return audit.page(tenantId, action, page, size);
    }

    @GetMapping("/audit/actions")
    @Transactional(readOnly = true)
    public List<String> auditActions() {
        return audit.actions();
    }
}
