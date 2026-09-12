package me.alex.cryptlink.api.crypto;

import me.alex.cryptlink.api.wire.WireEnvelope;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;

public final class ReplayGuard {
    private static final int CAPACITY = 65536;
    private final long windowNanos;
    private final long skewSeconds;
    private final LinkedHashMap<String, Long> nonces = new LinkedHashMap<>();

    public ReplayGuard() {
        this(Duration.ofSeconds(30), Duration.ofSeconds(10));
    }

    public ReplayGuard(Duration window, Duration skew) {
        if (window.isNegative() || window.isZero() || skew.isNegative()
                || window.compareTo(skew.multipliedBy(2).plusSeconds(1)) < 0) {
            throw new IllegalArgumentException("Replay window must cover twice the clock skew plus one second");
        }
        this.windowNanos = window.toNanos();
        this.skewSeconds = skew.toSeconds();
    }

    public boolean acceptsTimestamp(int timestamp) {
        long difference = Integer.toUnsignedLong(timestamp) - Instant.now().getEpochSecond();
        return difference >= -skewSeconds && difference <= skewSeconds;
    }

    public synchronized boolean seen(byte[] nonce) {
        if (nonce.length != WireEnvelope.NONCE_SIZE) {
            throw new IllegalArgumentException("Nonce must be 12 bytes");
        }
        long now = System.nanoTime();
        var iterator = nonces.entrySet().iterator();
        while (iterator.hasNext()) {
            if (now - iterator.next().getValue() < windowNanos) {
                break;
            }
            iterator.remove();
        }
        String value = Base64.getEncoder().encodeToString(nonce);
        if (nonces.containsKey(value) || nonces.size() >= CAPACITY) {
            return false;
        }
        nonces.put(value, now);
        return true;
    }
}
