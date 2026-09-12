package me.alex.cryptlink.api.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

final class HkdfSha256 {
    private static final byte[] SALT = "CryptLink-v2-HKDF-SHA256".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] INFO = "CryptLink-v2-sender-key".getBytes(StandardCharsets.US_ASCII);

    private HkdfSha256() {
    }

    static SecretKeySpec derive(byte[] masterKey, int keyVersion, String sourceServer) {
        byte[] extracted = hmac(SALT, masterKey);
        byte[] source = sourceServer.getBytes(StandardCharsets.US_ASCII);
        byte[] context = ByteBuffer.allocate(INFO.length + 1 + source.length + 1)
                .put(INFO).put((byte) keyVersion).put(source).put((byte) 1).array();
        byte[] derived = hmac(extracted, context);
        Arrays.fill(extracted, (byte) 0);
        return new SecretKeySpec(derived, "AES");
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HKDF-SHA-256 is unavailable", exception);
        }
    }
}
