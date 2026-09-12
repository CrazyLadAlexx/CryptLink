package me.alex.cryptlink.api.crypto;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayGuardTest {
    private static final long NOW = 2_200_000_000L;

    @Test
    void timestampBoundariesUseUnsignedEpochSeconds() {
        ReplayGuard guard = guard(new AtomicLong());
        assertTrue(guard.acceptsTimestamp((int) (NOW - 10)));
        assertTrue(guard.acceptsTimestamp((int) (NOW + 10)));
        assertFalse(guard.acceptsTimestamp((int) (NOW - 11)));
        assertFalse(guard.acceptsTimestamp((int) (NOW + 11)));
    }

    @Test
    void replayIdentityIncludesSourceAndKeyVersion() {
        ReplayGuard guard = guard(new AtomicLong());
        byte[] nonce = new byte[12];
        assertEquals(ReplayGuard.Result.ACCEPTED, guard.record("a", 1, nonce));
        assertEquals(ReplayGuard.Result.REPLAY, guard.record("a", 1, nonce));
        assertEquals(ReplayGuard.Result.ACCEPTED, guard.record("a", 2, nonce));
        assertEquals(ReplayGuard.Result.ACCEPTED, guard.record("b", 1, nonce));
    }

    @Test
    void oneSourceCannotExhaustAnotherSourcesPartition() {
        AtomicLong ticker = new AtomicLong();
        ReplayGuard guard = guard(ticker);
        assertEquals(ReplayGuard.Result.ACCEPTED, guard.record("bad", 1, nonce(1)));
        assertEquals(ReplayGuard.Result.ACCEPTED, guard.record("bad", 1, nonce(2)));
        assertEquals(ReplayGuard.Result.SOURCE_LIMIT, guard.record("bad", 1, nonce(3)));
        assertEquals(ReplayGuard.Result.ACCEPTED, guard.record("good", 1, nonce(3)));
        ticker.set(Duration.ofSeconds(30).toNanos());
        assertEquals(ReplayGuard.Result.ACCEPTED, guard.record("bad", 1, nonce(3)));
    }

    @Test
    void sourceMapIsBoundedAndClearRemovesState() {
        ReplayGuard guard = guard(new AtomicLong());
        assertEquals(ReplayGuard.Result.ACCEPTED, guard.record("a", 1, nonce(1)));
        assertEquals(ReplayGuard.Result.ACCEPTED, guard.record("b", 1, nonce(1)));
        assertEquals(ReplayGuard.Result.SOURCE_LIMIT, guard.record("c", 1, nonce(1)));
        guard.clear();
        assertEquals(ReplayGuard.Result.ACCEPTED, guard.record("c", 1, nonce(1)));
    }

    private static ReplayGuard guard(AtomicLong ticker) {
        return new ReplayGuard(Duration.ofSeconds(30), Duration.ofSeconds(10), 2, 2,
                Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC), ticker::get);
    }

    private static byte[] nonce(int value) {
        byte[] nonce = new byte[12];
        nonce[0] = (byte) value;
        return nonce;
    }
}
