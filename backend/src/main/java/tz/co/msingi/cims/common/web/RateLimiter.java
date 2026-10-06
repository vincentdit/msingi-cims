package tz.co.msingi.cims.common.web;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window request limiter per key (e.g. client IP) for public endpoints such as document
 * verification, so codes cannot be guessed by brute force. In-memory: per application instance.
 */
public class RateLimiter {

    private final int limit;
    private final long windowMillis;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimiter(int limit, long windowMillis, Clock clock) {
        this.limit = limit;
        this.windowMillis = windowMillis;
        this.clock = clock;
    }

    /** True if the request is allowed; false once the key has used up its window. */
    public boolean tryAcquire(String key) {
        long now = clock.millis();
        if (windows.size() > 10_000) {
            windows.values().removeIf(w -> now - w.start >= windowMillis); // keep memory bounded
        }
        Window w = windows.compute(key, (k, cur) -> cur == null || now - cur.start >= windowMillis ? new Window(now) : cur);
        synchronized (w) {
            return ++w.count <= limit;
        }
    }

    private static final class Window {
        final long start;
        int count;

        Window(long start) {
            this.start = start;
        }
    }
}
