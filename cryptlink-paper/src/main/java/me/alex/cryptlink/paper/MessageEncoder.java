package me.alex.cryptlink.paper;

import me.alex.cryptlink.api.SecureMessage;
import me.alex.cryptlink.api.crypto.AesGcmCipher;
import me.alex.cryptlink.api.crypto.KeyRing;
import me.alex.cryptlink.api.wire.MessageCodec;
import me.alex.cryptlink.api.wire.RoutingCodec;
import me.alex.cryptlink.api.wire.WireCodec;

public final class MessageEncoder {
    private final String sourceServer;
    private final KeyRing keyRing;
    private final AesGcmCipher cipher;

    public MessageEncoder(String sourceServer, KeyRing keyRing, AesGcmCipher cipher) {
        RoutingCodec.validateServerName(sourceServer);
        this.sourceServer = sourceServer;
        this.keyRing = keyRing;
        this.cipher = cipher;
    }

    public byte[] encode(String targetServer, String topic, byte[] payload) {
        SecureMessage message = new SecureMessage(sourceServer, targetServer, topic, payload);
        KeyRing.VersionedKey key = keyRing.current(sourceServer);
        byte[] envelope = WireCodec.encode(cipher.encrypt(MessageCodec.encode(message), key.version(), key.key()));
        RoutingCodec.encodeForwarded(sourceServer, envelope);
        return RoutingCodec.encodeUpstream(targetServer, envelope);
    }
}
