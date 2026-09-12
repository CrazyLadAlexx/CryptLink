package me.alex.cryptlink.api.security;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

public final class SecurityTelemetry {
    private final long intervalNanos;
    private final Consumer<String> logger;
    private final LongSupplier ticker;
    private final Map<RejectionReason, Counter> counters = new EnumMap<>(RejectionReason.class);

    public SecurityTelemetry(Duration logInterval, Consumer<String> logger) {
        this(logInterval, logger, System::nanoTime);
    }

    SecurityTelemetry(Duration logInterval, Consumer<String> logger, LongSupplier ticker) {
        if (logInterval.isNegative() || logInterval.isZero()) {
            throw new IllegalArgumentException("Telemetry log interval must be positive");
        }
        this.intervalNanos = logInterval.toNanos();
        this.logger = Objects.requireNonNull(logger, "logger");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        for (RejectionReason reason : RejectionReason.values()) {
            counters.put(reason, new Counter());
        }
    }

    public synchronized void record(RejectionReason reason) {
        Counter counter = counters.get(Objects.requireNonNull(reason, "reason"));
        counter.total++;
        counter.pending++;
        long now = ticker.getAsLong();
        if (!counter.logged || now - counter.lastLog >= intervalNanos) {
            logger.accept("CryptLink rejected " + counter.pending + " message(s): "
                    + reason.name().toLowerCase(Locale.ROOT));
            counter.pending = 0;
            counter.lastLog = now;
            counter.logged = true;
        }
    }

    public synchronized long count(RejectionReason reason) {
        return counters.get(reason).total;
    }

    private static final class Counter {
        private long total;
        private long pending;
        private long lastLog;
        private boolean logged;
    }
}
