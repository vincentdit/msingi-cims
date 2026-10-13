package tz.co.msingi.cims.platform.tenancy;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves institutions by code or host with a short in-memory cache.
 * Tenants and domains are platform tables (no row-level security).
 */
@Service
@RequiredArgsConstructor
public class TenantLookupService {

    private static final Duration TTL = Duration.ofMinutes(5);

    private final TenantRepository tenants;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    @Transactional(readOnly = true)
    public Optional<TenantContext.TenantInfo> byCode(String code) {
        return resolveCode(code).filter(r -> !r.suspended()).map(Resolution::info);
    }

    @Transactional(readOnly = true)
    public Optional<TenantContext.TenantInfo> byHost(String host) {
        return resolveHost(host).filter(r -> !r.suspended()).map(Resolution::info);
    }

    /** The institution for a code, including a suspended one (so the caller can say it is suspended). */
    @Transactional(readOnly = true)
    public Optional<Resolution> resolveCode(String code) {
        return cached("code:" + code.toLowerCase(), () -> tenants.findByCodeIgnoreCase(code));
    }

    @Transactional(readOnly = true)
    public Optional<Resolution> resolveHost(String host) {
        return cached("host:" + host.toLowerCase(), () -> tenants.findByHost(host));
    }

    /**
     * Forgets every cached lookup, e.g. after an institution is suspended or a domain changes. Other servers keep their
     * cache for up to five minutes.
     */
    public void evictAll() {
        cache.clear();
    }

    private Optional<Resolution> cached(String key, java.util.function.Supplier<Optional<Tenant>> loader) {
        Cached c = cache.get(key);
        if (c != null && c.expires.isAfter(Instant.now())) {
            return Optional.ofNullable(c.resolution);
        }
        Resolution r = loader.get().map(t -> new Resolution(t.toInfo(), "SUSPENDED".equals(t.getStatus()), t.getName())).orElse(null);
        cache.put(key, new Cached(r, Instant.now().plus(TTL)));
        return Optional.ofNullable(r);
    }

    public record Resolution(TenantContext.TenantInfo info, boolean suspended, String name) {
    }

    private record Cached(Resolution resolution, Instant expires) {
    }
}
