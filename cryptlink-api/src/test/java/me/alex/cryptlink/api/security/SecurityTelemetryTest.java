package me.alex.cryptlink.api.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SecurityTelemetryTest {
    @Test
    void countersRemainAccurateWhileLogsAreRateLimited() {
        AtomicLong ticker = new AtomicLong(1);
        List<String> logs = new ArrayList<>();
        SecurityTelemetry telemetry = new SecurityTelemetry(Duration.ofSeconds(30), logs::add, ticker::get);
        telemetry.record(RejectionReason.REPLAY);
        telemetry.record(RejectionReason.REPLAY);
        assertEquals(2, telemetry.count(RejectionReason.REPLAY));
        assertEquals(1, logs.size());
        ticker.addAndGet(Duration.ofSeconds(30).toNanos());
        telemetry.record(RejectionReason.REPLAY);
        assertEquals(2, logs.size());
        assertEquals(3, telemetry.count(RejectionReason.REPLAY));
    }
}
