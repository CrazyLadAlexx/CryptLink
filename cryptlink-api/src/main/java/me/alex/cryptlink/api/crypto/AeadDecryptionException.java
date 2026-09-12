package me.alex.cryptlink.api.crypto;

public final class AeadDecryptionException extends Exception {
    public AeadDecryptionException(Throwable cause) {
        super("Message authentication failed", cause);
    }
}
