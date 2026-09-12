package me.alex.cryptlink.api.crypto;

import me.alex.cryptlink.api.wire.RoutingCodec;
import me.alex.cryptlink.api.wire.WireCodec;
import me.alex.cryptlink.api.wire.WireEnvelope;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

public final class AesGcmCipher {
    private final int keyVersion;
    private final SecureRandom random = new SecureRandom();

    public AesGcmCipher(int keyVersion) {
        if (keyVersion < 0 || keyVersion > 255) {
            throw new IllegalArgumentException("Key version must be between 0 and 255");
        }
        this.keyVersion = keyVersion;
    }

    public WireEnvelope encrypt(byte[] plaintext, SecretKey key) {
        validateKey(key);
        if (plaintext.length > RoutingCodec.MAX_PACKET_SIZE - WireCodec.MIN_ENVELOPE_SIZE) {
            throw new IllegalArgumentException("Plaintext exceeds the plugin message limit");
        }
        long seconds = Instant.now().getEpochSecond();
        if (seconds < 0 || seconds > 0xffff_ffffL) {
            throw new IllegalStateException("Timestamp exceeds the unsigned 32-bit wire range");
        }
        int timestamp = (int) seconds;
        byte[] nonce = new byte[WireEnvelope.NONCE_SIZE];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(WireCodec.header(keyVersion, nonce, timestamp));
            return new WireEnvelope(keyVersion, nonce, timestamp, cipher.doFinal(plaintext));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("AES-256-GCM encryption failed", exception);
        }
    }

    public byte[] decrypt(WireEnvelope envelope, SecretKey key) throws AeadDecryptionException {
        validateKey(key);
        try {
            byte[] nonce = envelope.nonce();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(WireCodec.header(envelope.keyVersion(), nonce, envelope.timestamp()));
            return cipher.doFinal(envelope.ciphertext());
        } catch (AEADBadTagException exception) {
            throw new AeadDecryptionException(exception);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("AES-256-GCM decryption failed", exception);
        }
    }

    private static void validateKey(SecretKey key) {
        Objects.requireNonNull(key, "key");
        byte[] encoded = key.getEncoded();
        if (!"AES".equals(key.getAlgorithm()) || encoded == null || encoded.length != 32) {
            throw new IllegalArgumentException("An AES-256 key is required");
        }
        Arrays.fill(encoded, (byte) 0);
    }
}
