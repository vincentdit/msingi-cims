package tz.co.msingi.cims.platform.audit;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.integration.log.IntegrationLogQuery;
import tz.co.msingi.cims.integration.log.IntegrationLogQuery.CallPage;
import tz.co.msingi.cims.integration.log.IntegrationLogQuery.Filter;
import tz.co.msingi.cims.integration.log.IntegrationLogQuery.SystemHealth;
import tz.co.msingi.cims.platform.tenancy.TenantContext;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/** The institution's own calls to NECTA, NACTVET, GePG and the SMS gateway, for its auditor (as NOAS keeps integration logs). */
@Tag(name = "Audit trail")
@RestController
@RequestMapping("/api/audit/integrations")
@RequiredArgsConstructor
public class IntegrationLogController {

    private static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");

    private final IntegrationLogQuery query;

    @Operation(summary = "Health of each outside system for this institution over the last hours")
    @GetMapping("/health")
    @PreAuthorize("hasAnyRole('AUDITOR','INSTITUTION_ADMIN')")
    @Transactional(readOnly = true)
    public List<SystemHealth> health(@RequestParam(defaultValue = "168") int hours) {
        return query.health(TenantContext.requireTenantId(), hours);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('AUDITOR','INSTITUTION_ADMIN')")
    @Transactional(readOnly = true)
    public CallPage calls(@RequestParam(required = false) String system, @RequestParam(required = false) String outcome,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                          @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return query.calls(new Filter(TenantContext.requireTenantId(), system, outcome,
                from == null ? null : from.atStartOfDay(ZONE).toOffsetDateTime(),
                to == null ? null : to.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime()), page, size);
    }
}
