package me.alex.cryptlink.paper;

import me.alex.cryptlink.api.SecureMessage;
import me.alex.cryptlink.api.crypto.AeadDecryptionException;
import me.alex.cryptlink.api.crypto.AesGcmCipher;
import me.alex.cryptlink.api.crypto.ReplayGuard;
import me.alex.cryptlink.api.security.RejectionReason;
import me.alex.cryptlink.api.security.SecurityTelemetry;
import me.alex.cryptlink.api.wire.MessageCodec;
import me.alex.cryptlink.api.wire.RoutingCodec;
import me.alex.cryptlink.api.wire.WireCodec;
import me.alex.cryptlink.api.wire.WireEnvelope;

import javax.crypto.SecretKey;
import java.util.Optional;

public final class InboundMessageProcessor {
    private final PaperConfig config;
    private final AesGcmCipher cipher;
    private final ReplayGuard replayGuard;
    private final SecurityTelemetry telemetry;

    public InboundMessageProcessor(PaperConfig config, AesGcmCipher cipher,
                                   ReplayGuard replayGuard, SecurityTelemetry telemetry) {
        this.config = config;
        this.cipher = cipher;
        this.replayGuard = replayGuard;
        this.telemetry = telemetry;
    }

    public Optional<SecureMessage> process(byte[] packet) {
        RoutingCodec.ForwardedPacket forwarded;
        try {
            forwarded = RoutingCodec.decodeForwarded(packet);
        } catch (IllegalArgumentException exception) {
            telemetry.record(RejectionReason.MALFORMED_PACKET);
            return Optional.empty();
        }
        WireEnvelope envelope;
        try {
            envelope = WireCodec.decode(forwarded.envelope());
        } catch (IllegalArgumentException exception) {
            telemetry.record(RejectionReason.MALFORMED_PACKET);
            return Optional.empty();
        }
        if (!replayGuard.acceptsTimestamp(envelope.timestamp())) {
            telemetry.record(RejectionReason.STALE_TIMESTAMP);
            return Optional.empty();
        }
        SecretKey key = config.keyRing().find(envelope.keyVersion(), forwarded.sourceServer()).orElse(null);
        if (key == null) {
            telemetry.record(RejectionReason.UNKNOWN_KEY_VERSION);
            return Optional.empty();
        }
        SecureMessage message;
        try {
            message = MessageCodec.decode(cipher.decrypt(envelope, key));
        } catch (AeadDecryptionException exception) {
            telemetry.record(RejectionReason.AUTHENTICATION_FAILURE);
            return Optional.empty();
        } catch (IllegalArgumentException exception) {
            telemetry.record(RejectionReason.MALFORMED_PACKET);
            return Optional.empty();
        }
        if (!forwarded.sourceServer().equals(message.sourceServer())) {
            telemetry.record(RejectionReason.SOURCE_MISMATCH);
            return Optional.empty();
        }
        boolean broadcast = RoutingCodec.BROADCAST.equals(message.targetServer());
        if ((!broadcast && !config.serverName().equals(message.targetServer()))
                || (broadcast && config.serverName().equals(message.sourceServer()))) {
            telemetry.record(RejectionReason.UNKNOWN_DESTINATION);
            return Optional.empty();
        }
        ReplayGuard.Result replay = replayGuard.record(message.sourceServer(), envelope.keyVersion(), envelope.nonce());
        if (replay != ReplayGuard.Result.ACCEPTED) {
            telemetry.record(replay == ReplayGuard.Result.REPLAY
                    ? RejectionReason.REPLAY : RejectionReason.RATE_LIMIT);
            return Optional.empty();
        }
        return Optional.of(message);
    }

    public void clear() {
        replayGuard.clear();
    }
}
