package tz.co.msingi.cims.platform.tenancy;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Holds the institution (tenant) for the current request or job thread.
 * Set by {@link TenantResolutionFilter} for HTTP requests and explicitly by
 * background jobs via {@link #callAs(TenantInfo, Supplier)}.
 */
public final class TenantContext {

    private static final ThreadLocal<TenantInfo> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(TenantInfo tenant) {
        CURRENT.set(tenant);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static Optional<TenantInfo> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static Long currentTenantIdOrNull() {
        TenantInfo t = CURRENT.get();
        return t == null ? null : t.id();
    }

    public static Long requireTenantId() {
        return require().id();
    }

    public static TenantInfo require() {
        TenantInfo t = CURRENT.get();
        if (t == null) {
            throw new IllegalStateException("No tenant in context — the request was not resolved to an institution");
        }
        return t;
    }

    /** Runs work as the given tenant, restoring the previous context afterwards. */
    public static <T> T callAs(TenantInfo tenant, Supplier<T> work) {
        TenantInfo previous = CURRENT.get();
        CURRENT.set(tenant);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static void runAs(TenantInfo tenant, Runnable work) {
        callAs(tenant, () -> {
            work.run();
            return null;
        });
    }

    public record TenantInfo(Long id, String code) {
    }
}
