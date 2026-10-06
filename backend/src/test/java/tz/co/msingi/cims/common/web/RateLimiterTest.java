package tz.co.msingi.cims.common.web;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    @Test
    void limitsEachKeyPerWindow() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-06T10:00:00Z"));
        Clock clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        RateLimiter limiter = new RateLimiter(3, 60_000, clock);
        assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();
        assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();
        assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();
        assertThat(limiter.tryAcquire("1.1.1.1")).isFalse();
        assertThat(limiter.tryAcquire("2.2.2.2")).isTrue();   // other clients are unaffected
        now.set(now.get().plus(Duration.ofSeconds(61)));
        assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();   // a new window
    }
}
