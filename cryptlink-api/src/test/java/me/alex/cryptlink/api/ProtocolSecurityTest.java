package me.alex.cryptlink.api;

import me.alex.cryptlink.api.crypto.AeadDecryptionException;
import me.alex.cryptlink.api.crypto.AesGcmCipher;
import me.alex.cryptlink.api.crypto.KeyRing;
import me.alex.cryptlink.api.wire.MessageCodec;
import me.alex.cryptlink.api.wire.Protocol;
import me.alex.cryptlink.api.wire.RoutingCodec;
import me.alex.cryptlink.api.wire.WireCodec;
import me.alex.cryptlink.api.wire.WireEnvelope;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolSecurityTest {
    private final KeyRing keys = keys(7);
    private final AesGcmCipher cipher = new AesGcmCipher();

    @Test
    void completeV2RoundTripPreservesAuthenticatedMessage() throws Exception {
        SecureMessage message = new SecureMessage("survival", "lobby", "economy:credit", new byte[]{0, 1, -1});
        KeyRing.VersionedKey key = keys.current("survival");
        WireEnvelope encrypted = cipher.encrypt(MessageCodec.encode(message), key.version(), key.key());
        byte[] envelope = WireCodec.encode(encrypted);
        byte[] upstream = RoutingCodec.encodeUpstream("lobby", envelope);
        var route = RoutingCodec.decodeUpstream(upstream, RoutingCodec.MAX_PACKET_SIZE);
        byte[] forwarded = RoutingCodec.encodeForwarded("survival", route.envelope());
        var transport = RoutingCodec.decodeForwarded(forwarded);
        WireEnvelope decodedEnvelope = WireCodec.decode(transport.envelope());
        SecureMessage decoded = MessageCodec.decode(cipher.decrypt(decodedEnvelope,
                keys.find(decodedEnvelope.keyVersion(), transport.sourceServer()).orElseThrow()));
        assertEquals(message.sourceServer(), decoded.sourceServer());
        assertEquals(message.targetServer(), decoded.targetServer());
        assertEquals(message.topic(), decoded.topic());
        assertArrayEquals(message.payload(), decoded.payload());
    }

    @Test
    void explicitMagicAndVersionRejectLegacyAndUnknownPackets() {
        byte[] legacy = new byte[WireCodec.MIN_ENVELOPE_SIZE];
        assertThrows(IllegalArgumentException.class, () -> WireCodec.decode(legacy));
        ByteBuffer unknown = ByteBuffer.allocate(WireCodec.MIN_ENVELOPE_SIZE)
                .putInt(Protocol.MAGIC).put((byte) 99);
        assertThrows(IllegalArgumentException.class, () -> WireCodec.decode(unknown.array()));
        byte[] routed = RoutingCodec.encodeUpstream("lobby", validEnvelope());
        routed[4] = 99;
        assertThrows(IllegalArgumentException.class,
                () -> RoutingCodec.decodeUpstream(routed, RoutingCodec.MAX_PACKET_SIZE));
    }

    @Test
    void everyEnvelopeByteIsEitherStructurallyRejectedOrAuthenticated() throws Exception {
        KeyRing.VersionedKey key = keys.current("survival");
        byte[] encoded = WireCodec.encode(cipher.encrypt("payload".getBytes(StandardCharsets.UTF_8), key.version(), key.key()));
        for (int index = 0; index < encoded.length; index++) {
            byte[] mutated = encoded.clone();
            mutated[index] ^= 1;
            try {
                WireEnvelope envelope = WireCodec.decode(mutated);
                assertThrows(AeadDecryptionException.class, () -> cipher.decrypt(envelope, key.key()));
            } catch (IllegalArgumentException accepted) {
                assertTrue(index < Protocol.PREFIX_SIZE);
            }
        }
    }

    @Test
    void senderDomainsProduceDifferentEncryptionKeys() {
        SecretKey survival = keys.current("survival").key();
        SecretKey lobby = keys.current("lobby").key();
        assertFalse(Arrays.equals(survival.getEncoded(), lobby.getEncoded()));
        assertEquals(32, survival.getEncoded().length);
    }

    @Test
    void currentVersionIsExplicitAndOlderVersionsRemainAvailable() {
        String first = encodedKey(1);
        String future = encodedKey(2);
        KeyRing ring = new KeyRing(Map.of(2, future, 9, first), 2);
        assertEquals(2, ring.currentVersion());
        assertTrue(ring.find(9, "survival").isPresent());
        assertFalse(ring.find(8, "survival").isPresent());
        assertThrows(IllegalArgumentException.class, () -> new KeyRing(Map.of(2, future), 3));
    }

    @Test
    void routeKindsCannotBeConfusedOrRetargetedSilently() {
        byte[] broadcast = RoutingCodec.encodeUpstream("*", validEnvelope());
        assertEquals("*", RoutingCodec.decodeUpstream(broadcast, 32766).targetServer());
        broadcast[5] = 0;
        assertThrows(IllegalArgumentException.class, () -> RoutingCodec.decodeUpstream(broadcast, 32766));
        byte[] direct = RoutingCodec.encodeUpstream("lobby", validEnvelope());
        direct[5] = 1;
        assertThrows(IllegalArgumentException.class, () -> RoutingCodec.decodeUpstream(direct, 32766));
    }

    @Test
    void codecsRejectTruncationBadLengthsUtf8AndOversize() {
        assertThrows(IllegalArgumentException.class, () -> RoutingCodec.decodeForwarded(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> WireCodec.decode(new byte[WireCodec.MIN_ENVELOPE_SIZE - 1]));
        assertThrows(IllegalArgumentException.class, () -> MessageCodec.decode(new byte[]{0, 64, 1}));
        byte[] invalidUtf8 = {0, 1, (byte) 0xff, 0, 1, 97, 0, 1, 116};
        assertThrows(IllegalArgumentException.class, () -> MessageCodec.decode(invalidUtf8));
        assertThrows(IllegalArgumentException.class,
                () -> RoutingCodec.decodeUpstream(new byte[RoutingCodec.MAX_PACKET_SIZE + 1], RoutingCodec.MAX_PACKET_SIZE));
        assertThrows(IllegalArgumentException.class,
                () -> new WireEnvelope(1, new byte[12], 1, new byte[15]));
    }

    @Test
    void emptyPayloadAndMaximumLegalPacketRoundTrip() {
        SecureMessage empty = new SecureMessage("a", "b", "t", new byte[0]);
        assertArrayEquals(new byte[0], MessageCodec.decode(MessageCodec.encode(empty)).payload());
        int fixed = Protocol.PREFIX_SIZE + 2 + 1 + WireCodec.MIN_ENVELOPE_SIZE + 6 + 1 + 1 + 1;
        byte[] payload = new byte[RoutingCodec.MAX_PACKET_SIZE - fixed];
        SecureMessage maximum = new SecureMessage("a", "b", "t", payload);
        KeyRing.VersionedKey key = keys.current("a");
        byte[] packet = RoutingCodec.encodeUpstream("b", WireCodec.encode(
                cipher.encrypt(MessageCodec.encode(maximum), key.version(), key.key())));
        assertEquals(RoutingCodec.MAX_PACKET_SIZE, packet.length);
        assertThrows(IllegalArgumentException.class,
                () -> MessageCodec.encode(new SecureMessage("a", "b", "t", new byte[payload.length + 1])));
    }

    @Test
    void hostileRandomInputNeverEscapesControlledDecoderFailures() {
        Random random = new Random(884422);
        for (int attempt = 0; attempt < 20000; attempt++) {
            byte[] input = new byte[random.nextInt(1024)];
            random.nextBytes(input);
            controlled(() -> RoutingCodec.decodeUpstream(input, RoutingCodec.MAX_PACKET_SIZE));
            controlled(() -> RoutingCodec.decodeForwarded(input));
            controlled(() -> WireCodec.decode(input));
            controlled(() -> MessageCodec.decode(input));
        }
    }

    @Test
    void mutableArraysAreDefensivelyCopied() {
        byte[] payload = {1};
        SecureMessage message = new SecureMessage("a", "b", "t", payload);
        payload[0] = 2;
        message.payload()[0] = 3;
        assertArrayEquals(new byte[]{1}, message.payload());
        byte[] nonce = new byte[12];
        byte[] ciphertext = new byte[16];
        WireEnvelope envelope = new WireEnvelope(1, nonce, 1, ciphertext);
        nonce[0] = 1;
        ciphertext[0] = 1;
        assertNotEquals(1, envelope.nonce()[0]);
        assertNotEquals(1, envelope.ciphertext()[0]);
    }

    private byte[] validEnvelope() {
        KeyRing.VersionedKey key = keys.current("survival");
        return WireCodec.encode(cipher.encrypt(new byte[0], key.version(), key.key()));
    }

    private static KeyRing keys(int current) {
        return new KeyRing(Map.of(7, encodedKey(7), 9, encodedKey(9)), current);
    }

    private static String encodedKey(int seed) {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) seed);
        return Base64.getEncoder().encodeToString(key);
    }

    private static void controlled(Runnable decoder) {
        try {
            decoder.run();
        } catch (IllegalArgumentException accepted) {
            return;
        }
    }
}
