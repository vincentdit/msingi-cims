package tz.co.msingi.cims.platform.tenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Resolves the institution for every request, before Spring Security runs.
 * Order of precedence:
 *   1. X-Tenant header (institution code) — used by SPAs in development and by API clients;
 *   2. the request host name, looked up in tenant_domains (production: apply.college.ac.tz).
 */
@RequiredArgsConstructor
public class TenantResolutionFilter extends OncePerRequestFilter {

    public static final String TENANT_HEADER = "X-Tenant";

    /** Paths that do not belong to a single institution. */
    private static final List<String> TENANTLESS_PREFIXES = List.of(
            "/actuator", "/v3/api-docs", "/swagger-ui", "/api/integrations/gepg", "/error");

    private final TenantLookupService lookup;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        try {
            Optional<TenantContext.TenantInfo> tenant = resolve(request);
            if (tenant.isPresent()) {
                TenantContext.set(tenant.get());
            } else if (isTenantless(path) || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
                // allowed without a tenant
            } else {
                writeUnknownTenant(response);
                return;
            }
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private Optional<TenantContext.TenantInfo> resolve(HttpServletRequest request) {
        String code = request.getHeader(TENANT_HEADER);
        if (StringUtils.hasText(code)) {
            return lookup.byCode(code.trim());
        }
        String host = request.getServerName();
        return StringUtils.hasText(host) ? lookup.byHost(host) : Optional.empty();
    }

    private static boolean isTenantless(String path) {
        return TENANTLESS_PREFIXES.stream().anyMatch(path::startsWith);
    }

    private static void writeUnknownTenant(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.NOT_FOUND.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("""
                {"title":"Not Found","status":404,"code":"UNKNOWN_INSTITUTION",\
                "detail":"This address is not registered to an institution on CIMS."}""");
    }
}
