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
        return cached("code:" + code.toLowerCase(), () -> tenants.findByCodeIgnoreCase(code));
    }

    @Transactional(readOnly = true)
    public Optional<TenantContext.TenantInfo> byHost(String host) {
        return cached("host:" + host.toLowerCase(), () -> tenants.findByHost(host));
    }

    public void evictAll() {
        cache.clear();
    }

    private Optional<TenantContext.TenantInfo> cached(String key, java.util.function.Supplier<Optional<Tenant>> loader) {
        Cached c = cache.get(key);
        if (c != null && c.expires.isAfter(Instant.now())) {
            return Optional.ofNullable(c.info);
        }
        TenantContext.TenantInfo info = loader.get()
                .filter(t -> !"SUSPENDED".equals(t.getStatus()))
                .map(Tenant::toInfo)
                .orElse(null);
        cache.put(key, new Cached(info, Instant.now().plus(TTL)));
        return Optional.ofNullable(info);
    }

    private record Cached(TenantContext.TenantInfo info, Instant expires) {
    }
}
