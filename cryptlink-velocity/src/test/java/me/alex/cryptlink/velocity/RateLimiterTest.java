package me.alex.cryptlink.velocity;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {
    @Test
    void abusiveSourceDoesNotBlockAnotherSource() {
        VelocityConfig config = config(Set.of("bad", "good"));
        RateLimiterRegistry limits = new RateLimiterRegistry(config, new AtomicLong()::get);
        assertTrue(limits.allowBase("bad", 100));
        assertFalse(limits.allowBase("bad", 100));
        assertTrue(limits.allowBase("good", 100));
    }

    @Test
    void messageByteAndBroadcastLimitsRefill() {
        AtomicLong ticker = new AtomicLong();
        RateLimiterRegistry limits = new RateLimiterRegistry(config(Set.of("a")), ticker::get);
        assertTrue(limits.allowBase("a", 100));
        assertFalse(limits.allowBase("a", 100));
        assertTrue(limits.allowBroadcast("a"));
        assertFalse(limits.allowBroadcast("a"));
        ticker.set(Duration.ofSeconds(1).toNanos());
        assertTrue(limits.allowBase("a", 100));
        assertTrue(limits.allowBroadcast("a"));
    }

    @Test
    void unknownSourcesDoNotCreateStateAndIdleStateExpires() {
        AtomicLong ticker = new AtomicLong();
        RateLimiterRegistry limits = new RateLimiterRegistry(config(Set.of("a")), ticker::get);
        assertFalse(limits.allowBase("unknown", 1));
        assertEquals(0, limits.stateCount());
        assertTrue(limits.allowBase("a", 1));
        assertEquals(1, limits.stateCount());
        ticker.set(Duration.ofSeconds(300).toNanos());
        assertEquals(0, limits.stateCount());
    }

    private static VelocityConfig config(Set<String> servers) {
        return new VelocityConfig(servers, 32766, 1, 1, 1024, 1024,
                1, 1, Duration.ofSeconds(300), Duration.ofSeconds(30));
    }
}
