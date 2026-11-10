package tz.co.msingi.cims.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tz.co.msingi.cims.common.web.RateLimiter;

import java.io.IOException;
import java.time.Clock;
import java.util.List;

/**
 * Limits sign-in, password-reset and sign-in-code requests per client address (NFR-SEC-01), on top of the per-account lock-out:
 * one address may try many logins, which the lock-out alone does not stop. Covers staff, students, applicants and the Msingi
 * console. The address is the one the reverse proxy reports (server.forward-headers-strategy), never a header the client sets.
 */
public class SignInThrottleFilter extends OncePerRequestFilter {

    /** POSTs under these paths are sign-in attempts or ask for a code or a new password. */
    static final List<String> PATHS = List.of("/api/auth/", "/api/applicant-auth/", "/api/student-auth/", "/api/family-auth/", "/api/platform/auth/login");

    private final RateLimiter perMinute;
    private final RateLimiter perHour;

    public SignInThrottleFilter(int perMinute, int perHour, Clock clock) {
        this.perMinute = new RateLimiter(perMinute, 60_000, clock);
        this.perHour = new RateLimiter(perHour, 3_600_000, clock);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) return true;
        String path = request.getRequestURI().substring(request.getContextPath().length());
        // a signed-in person changing their own password or setting up two-step sign-in is not a guess
        if (path.equals("/api/auth/password") || path.startsWith("/api/auth/mfa/setup") || path.startsWith("/api/auth/mfa/enable")
                || path.startsWith("/api/auth/mfa/disable") || path.startsWith("/api/auth/mfa/recovery-codes")) return true;
        return PATHS.stream().noneMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String ip = request.getRemoteAddr();
        boolean minute = perMinute.tryAcquire(ip);
        boolean hour = perHour.tryAcquire(ip);      // every attempt counts towards the hour, refused ones too
        if (!minute || !hour) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", hour ? "60" : "3600");
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"title\":\"Too Many Requests\",\"status\":429,\"code\":\"TOO_MANY_REQUESTS\",\"detail\":\"Too many sign-in attempts from "
                    + "this connection. Wait " + (hour ? "a minute" : "an hour") + " and try again.\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
