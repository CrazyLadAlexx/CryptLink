package me.alex.cryptlink.velocity;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

final class RateLimiterRegistry {
    private final Set<String> allowedServers;
    private final VelocityConfig config;
    private final LongSupplier ticker;
    private final Map<String, SourceLimiter> limiters = new HashMap<>();
    private long nextCleanup;

    RateLimiterRegistry(VelocityConfig config) {
        this(config, System::nanoTime);
    }

    RateLimiterRegistry(VelocityConfig config, LongSupplier ticker) {
        this.allowedServers = config.allowedServers();
        this.config = config;
        this.ticker = ticker;
    }

    synchronized boolean allowBase(String sourceServer, int bytes) {
        SourceLimiter limiter = limiter(sourceServer);
        return limiter != null && limiter.messages.consume(1, now()) && limiter.bytes.consume(bytes, now());
    }

    synchronized boolean allowBroadcast(String sourceServer) {
        SourceLimiter limiter = limiter(sourceServer);
        return limiter != null && limiter.broadcasts.consume(1, now());
    }

    synchronized int stateCount() {
        cleanup(now());
        return limiters.size();
    }

    private SourceLimiter limiter(String sourceServer) {
        if (!allowedServers.contains(sourceServer)) {
            return null;
        }
        long now = now();
        cleanup(now);
        SourceLimiter limiter = limiters.computeIfAbsent(sourceServer, ignored -> new SourceLimiter(config, now));
        limiter.lastUsed = now;
        return limiter;
    }

    private long now() {
        return ticker.getAsLong();
    }

    private void cleanup(long now) {
        if (now < nextCleanup) {
            return;
        }
        long idle = config.rateLimitIdle().toNanos();
        limiters.entrySet().removeIf(entry -> now - entry.getValue().lastUsed >= idle);
        nextCleanup = now + Math.min(idle, 60_000_000_000L);
    }

    private static final class SourceLimiter {
        private final TokenBucket messages;
        private final TokenBucket bytes;
        private final TokenBucket broadcasts;
        private long lastUsed;

        private SourceLimiter(VelocityConfig config, long now) {
            messages = new TokenBucket(config.messagesPerSecond(), config.messageBurst(), now);
            bytes = new TokenBucket(config.bytesPerSecond(), config.byteBurst(), now);
            broadcasts = new TokenBucket(config.broadcastsPerSecond(), config.broadcastBurst(), now);
            lastUsed = now;
        }
    }

    private static final class TokenBucket {
        private final double refillPerNano;
        private final double capacity;
        private double tokens;
        private long updated;

        private TokenBucket(long rate, long capacity, long now) {
            this.refillPerNano = rate / 1_000_000_000d;
            this.capacity = capacity;
            this.tokens = capacity;
            this.updated = now;
        }

        private boolean consume(long amount, long now) {
            long elapsed = Math.max(0, now - updated);
            tokens = Math.min(capacity, tokens + elapsed * refillPerNano);
            updated = now;
            if (amount > tokens) {
                return false;
            }
            tokens -= amount;
            return true;
        }
    }
}
