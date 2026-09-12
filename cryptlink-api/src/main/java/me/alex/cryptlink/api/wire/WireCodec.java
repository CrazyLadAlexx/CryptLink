package me.alex.cryptlink.api.wire;

import java.nio.ByteBuffer;
import java.util.Objects;

public final class WireCodec {
    public static final int HEADER_SIZE = 1 + WireEnvelope.NONCE_SIZE + Integer.BYTES;
    public static final int MIN_ENVELOPE_SIZE = HEADER_SIZE + WireEnvelope.TAG_SIZE;

    private WireCodec() {
    }

    public static byte[] encode(WireEnvelope envelope) {
        byte[] ciphertext = envelope.ciphertext();
        return ByteBuffer.allocate(HEADER_SIZE + ciphertext.length)
                .put((byte) envelope.keyVersion())
                .put(envelope.nonce())
                .putInt(envelope.timestamp())
                .put(ciphertext)
                .array();
    }

    public static WireEnvelope decode(byte[] data) {
        Objects.requireNonNull(data, "data");
        if (data.length < MIN_ENVELOPE_SIZE || data.length > RoutingCodec.MAX_PACKET_SIZE) {
            throw new IllegalArgumentException("Invalid envelope length");
        }
        ByteBuffer buffer = ByteBuffer.wrap(data);
        int version = Byte.toUnsignedInt(buffer.get());
        byte[] nonce = new byte[WireEnvelope.NONCE_SIZE];
        buffer.get(nonce);
        int timestamp = buffer.getInt();
        byte[] ciphertext = new byte[buffer.remaining()];
        buffer.get(ciphertext);
        return new WireEnvelope(version, nonce, timestamp, ciphertext);
    }

    public static byte[] header(int version, byte[] nonce, int timestamp) {
        if (version < 0 || version > 255 || nonce.length != WireEnvelope.NONCE_SIZE) {
            throw new IllegalArgumentException("Invalid envelope header");
        }
        return ByteBuffer.allocate(HEADER_SIZE)
                .put((byte) version).put(nonce).putInt(timestamp).array();
    }
}
