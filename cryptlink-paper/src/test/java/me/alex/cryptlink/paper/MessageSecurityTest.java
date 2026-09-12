package me.alex.cryptlink.paper;

import me.alex.cryptlink.api.SecureMessage;
import me.alex.cryptlink.api.Subscription;
import me.alex.cryptlink.api.crypto.AesGcmCipher;
import me.alex.cryptlink.api.crypto.KeyRing;
import me.alex.cryptlink.api.crypto.ReplayGuard;
import me.alex.cryptlink.api.security.RejectionReason;
import me.alex.cryptlink.api.security.SecurityTelemetry;
import me.alex.cryptlink.api.wire.MessageCodec;
import me.alex.cryptlink.api.wire.RoutingCodec;
import me.alex.cryptlink.api.wire.WireCodec;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageSecurityTest {
    private final KeyRing keys = new KeyRing(Map.of(1, key(1), 2, key(2)), 1);
    private final AesGcmCipher cipher = new AesGcmCipher();

    @Test
    void paperASendsAuthenticatedUnicastToPaperB() {
        byte[] upstream = new MessageEncoder("a", keys, cipher).encode("b", "topic", new byte[]{1, 2});
        Optional<SecureMessage> received = processor("b", keys).process(forward(upstream, "a"));
        assertTrue(received.isPresent());
        assertEquals("a", received.orElseThrow().sourceServer());
        assertArrayEquals(new byte[]{1, 2}, received.orElseThrow().payload());
    }

    @Test
    void authenticatedBroadcastIsAcceptedByOtherBackendsOnly() {
        byte[] upstream = new MessageEncoder("a", keys, cipher).encode("*", "topic", new byte[]{4});
        byte[] packet = forward(upstream, "a");
        assertTrue(processor("b", keys).process(packet).isPresent());
        assertTrue(processor("c", keys).process(packet).isPresent());
        assertTrue(processor("a", keys).process(packet).isEmpty());
    }

    @Test
    void backendCannotClaimAnotherBackendIdentity() {
        SecureMessage lie = new SecureMessage("b", "receiver", "topic", new byte[0]);
        KeyRing.VersionedKey aKey = keys.current("a");
        byte[] envelope = WireCodec.encode(cipher.encrypt(MessageCodec.encode(lie), aKey.version(), aKey.key()));
        var result = processor("receiver", keys).process(RoutingCodec.encodeForwarded("a", envelope));
        assertTrue(result.isEmpty());
    }

    @Test
    void changingTransportSourceBreaksAuthentication() {
        byte[] upstream = new MessageEncoder("a", keys, cipher).encode("receiver", "topic", new byte[0]);
        assertTrue(processor("receiver", keys).process(forward(upstream, "b")).isEmpty());
    }

    @Test
    void changingEncryptedSourceOrCiphertextIsRejected() {
        SecureMessage lie = new SecureMessage("b", "receiver", "topic", new byte[0]);
        KeyRing.VersionedKey aKey = keys.current("a");
        byte[] sourceLie = WireCodec.encode(cipher.encrypt(MessageCodec.encode(lie), aKey.version(), aKey.key()));
        TelemetryFixture sourceTelemetry = telemetry();
        assertTrue(processor("receiver", keys, sourceTelemetry.telemetry()).process(
                RoutingCodec.encodeForwarded("a", sourceLie)).isEmpty());
        assertEquals(1, sourceTelemetry.telemetry().count(RejectionReason.SOURCE_MISMATCH));

        byte[] upstream = new MessageEncoder("a", keys, cipher).encode("receiver", "topic", new byte[0]);
        var route = RoutingCodec.decodeUpstream(upstream, 32766);
        byte[] corrupted = route.envelope();
        corrupted[corrupted.length - 1] ^= 1;
        TelemetryFixture authTelemetry = telemetry();
        assertTrue(processor("receiver", keys, authTelemetry.telemetry()).process(
                RoutingCodec.encodeForwarded("a", corrupted)).isEmpty());
        assertEquals(1, authTelemetry.telemetry().count(RejectionReason.AUTHENTICATION_FAILURE));
    }

    @Test
    void routeTargetManipulationDoesNotChangeAuthenticatedDestination() {
        byte[] upstream = new MessageEncoder("a", keys, cipher).encode("b", "topic", new byte[0]);
        byte[] envelope = RoutingCodec.decodeUpstream(upstream, 32766).envelope();
        assertTrue(processor("c", keys).process(RoutingCodec.encodeForwarded("a", envelope)).isEmpty());
    }

    @Test
    void replayIsRejectedAfterAuthentication() {
        byte[] packet = forward(new MessageEncoder("a", keys, cipher).encode("b", "topic", new byte[0]), "a");
        InboundMessageProcessor processor = processor("b", keys);
        assertTrue(processor.process(packet).isPresent());
        assertTrue(processor.process(packet).isEmpty());
        processor.clear();
        assertTrue(processor.process(packet).isPresent());
    }

    @Test
    void oldConfiguredKeyDecryptsWhileDifferentVersionIsActive() {
        KeyRing oldActive = new KeyRing(Map.of(1, key(1), 2, key(2)), 1);
        KeyRing newActive = new KeyRing(Map.of(1, key(1), 2, key(2)), 2);
        byte[] packet = forward(new MessageEncoder("a", oldActive, cipher).encode("b", "topic", new byte[0]), "a");
        assertTrue(processor("b", newActive).process(packet).isPresent());
        assertEquals(2, newActive.currentVersion());
    }

    @Test
    void unknownKeyVersionIsRejectedWithoutThrowing() {
        byte[] upstream = new MessageEncoder("a", keys, cipher).encode("b", "topic", new byte[0]);
        byte[] envelope = RoutingCodec.decodeUpstream(upstream, 32766).envelope();
        envelope[5] = 99;
        TelemetryFixture telemetry = telemetry();
        assertTrue(processor("b", keys, telemetry.telemetry()).process(
                RoutingCodec.encodeForwarded("a", envelope)).isEmpty());
        assertEquals(1, telemetry.telemetry().count(RejectionReason.UNKNOWN_KEY_VERSION));
    }

    @Test
    void subscriptionCancellationAndShutdownAreSafe() {
        TopicSubscriptions subscriptions = new TopicSubscriptions(Logger.getAnonymousLogger());
        AtomicInteger calls = new AtomicInteger();
        Subscription subscription = subscriptions.subscribe("topic", (source, payload) -> calls.incrementAndGet());
        subscriptions.dispatch(new SecureMessage("a", "b", "topic", new byte[0]));
        subscription.close();
        subscription.close();
        subscriptions.dispatch(new SecureMessage("a", "b", "topic", new byte[0]));
        assertEquals(1, calls.get());
        assertFalse(subscription.isActive());
        subscriptions.close();
        assertThrows(IllegalStateException.class,
                () -> subscriptions.subscribe("topic", (source, payload) -> calls.incrementAndGet()));
        subscriptions.dispatch(new SecureMessage("a", "b", "topic", new byte[0]));
        assertEquals(1, calls.get());
    }

    private InboundMessageProcessor processor(String server, KeyRing ring) {
        return processor(server, ring, telemetry().telemetry());
    }

    private InboundMessageProcessor processor(String server, KeyRing ring, SecurityTelemetry telemetry) {
        PaperConfig config = new PaperConfig(server, ring, Duration.ofSeconds(30), Duration.ofSeconds(10),
                128, 32, Duration.ofSeconds(30));
        return new InboundMessageProcessor(config, cipher,
                new ReplayGuard(config.replayWindow(), config.clockSkew(), 128, 32), telemetry);
    }

    private static byte[] forward(byte[] upstream, String actualSource) {
        return RoutingCodec.encodeForwarded(actualSource,
                RoutingCodec.decodeUpstream(upstream, 32766).envelope());
    }

    private static TelemetryFixture telemetry() {
        return new TelemetryFixture(new SecurityTelemetry(Duration.ofDays(1), ignored -> { }));
    }

    private static String key(int value) {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) value);
        return Base64.getEncoder().encodeToString(key);
    }

    private record TelemetryFixture(SecurityTelemetry telemetry) {
    }
}
