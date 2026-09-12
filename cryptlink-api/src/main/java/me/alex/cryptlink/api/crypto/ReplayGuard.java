package me.alex.cryptlink.api.crypto;

import me.alex.cryptlink.api.wire.RoutingCodec;
import me.alex.cryptlink.api.wire.WireEnvelope;

import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.function.LongSupplier;

public final class ReplayGuard {
    private final long windowNanos;
    private final long skewSeconds;
    private final int entriesPerSource;
    private final int sourceLimit;
    private final Clock clock;
    private final LongSupplier ticker;
    private final LinkedHashMap<String, SourceCache> sources = new LinkedHashMap<>();

    public ReplayGuard(Duration window, Duration skew, int entriesPerSource, int sourceLimit) {
        this(window, skew, entriesPerSource, sourceLimit, Clock.systemUTC(), System::nanoTime);
    }

    ReplayGuard(Duration window, Duration skew, int entriesPerSource, int sourceLimit,
                Clock clock, LongSupplier ticker) {
        if (window.isZero() || window.isNegative() || skew.isNegative()
                || window.compareTo(skew.multipliedBy(2).plusSeconds(1)) < 0) {
            throw new IllegalArgumentException("Replay window must cover twice the clock skew plus one second");
        }
        if (entriesPerSource < 1 || entriesPerSource > 65536 || sourceLimit < 1 || sourceLimit > 4096) {
            throw new IllegalArgumentException("Replay cache limits are outside the supported range");
        }
        this.windowNanos = window.toNanos();
        this.skewSeconds = skew.toSeconds();
        this.entriesPerSource = entriesPerSource;
        this.sourceLimit = sourceLimit;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
    }

    public boolean acceptsTimestamp(int timestamp) {
        long difference = Integer.toUnsignedLong(timestamp) - clock.instant().getEpochSecond();
        return difference >= -skewSeconds && difference <= skewSeconds;
    }

    public synchronized Result record(String sourceServer, int keyVersion, byte[] nonce) {
        RoutingCodec.validateServerName(sourceServer);
        if (keyVersion < 0 || keyVersion > 255 || nonce.length != WireEnvelope.NONCE_SIZE) {
            throw new IllegalArgumentException("Invalid replay identity");
        }
        long now = ticker.getAsLong();
        evictExpiredSources(now);
        SourceCache cache = sources.get(sourceServer);
        if (cache == null) {
            if (sources.size() >= sourceLimit) {
                return Result.SOURCE_LIMIT;
            }
            cache = new SourceCache();
            sources.put(sourceServer, cache);
        }
        cache.evict(now, windowNanos);
        ReplayId id = new ReplayId(keyVersion, Base64.getEncoder().encodeToString(nonce));
        if (cache.entries.containsKey(id)) {
            return Result.REPLAY;
        }
        if (cache.entries.size() >= entriesPerSource) {
            return Result.SOURCE_LIMIT;
        }
        cache.entries.put(id, now);
        cache.lastSeen = now;
        return Result.ACCEPTED;
    }

    public synchronized void clear() {
        sources.clear();
    }

    private void evictExpiredSources(long now) {
        Iterator<SourceCache> iterator = sources.values().iterator();
        while (iterator.hasNext()) {
            SourceCache cache = iterator.next();
            cache.evict(now, windowNanos);
            if (cache.entries.isEmpty() && now - cache.lastSeen >= windowNanos) {
                iterator.remove();
            }
        }
    }

    public enum Result {
        ACCEPTED,
        REPLAY,
        SOURCE_LIMIT
    }

    private record ReplayId(int keyVersion, String nonce) {
    }

    private static final class SourceCache {
        private final LinkedHashMap<ReplayId, Long> entries = new LinkedHashMap<>();
        private long lastSeen;

        private void evict(long now, long windowNanos) {
            Iterator<Long> iterator = entries.values().iterator();
            while (iterator.hasNext()) {
                if (now - iterator.next() < windowNanos) {
                    break;
                }
                iterator.remove();
            }
        }
    }
}
