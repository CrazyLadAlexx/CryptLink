package me.alex.cryptlink.api.wire;

import java.util.Objects;

public record WireEnvelope(int keyVersion, byte[] nonce, int timestamp, byte[] ciphertext) {
    public static final int NONCE_SIZE = 12;
    public static final int TAG_SIZE = 16;

    public WireEnvelope {
        if (keyVersion < 0 || keyVersion > 255) {
            throw new IllegalArgumentException("Key version must be between 0 and 255");
        }
        if (Objects.requireNonNull(nonce, "nonce").length != NONCE_SIZE) {
            throw new IllegalArgumentException("Nonce must be 12 bytes");
        }
        if (Objects.requireNonNull(ciphertext, "ciphertext").length < TAG_SIZE
                || ciphertext.length > RoutingCodec.MAX_PACKET_SIZE - WireCodec.HEADER_SIZE) {
            throw new IllegalArgumentException("Invalid ciphertext length");
        }
        nonce = nonce.clone();
        ciphertext = ciphertext.clone();
    }

    @Override
    public byte[] nonce() {
        return nonce.clone();
    }

    @Override
    public byte[] ciphertext() {
        return ciphertext.clone();
    }
}
