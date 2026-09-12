package me.alex.cryptlink.api.crypto;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class KeyRing {
    private final Map<Integer, SecretKey> keys;
    private final VersionedKey current;

    public KeyRing(Map<Integer, String> encodedKeys) {
        if (Objects.requireNonNull(encodedKeys, "keys").isEmpty()) {
            throw new IllegalArgumentException("At least one AES-256 key is required");
        }
        Map<Integer, SecretKey> decoded = new HashMap<>();
        encodedKeys.forEach((version, encoded) -> {
            if (version == null || version < 0 || version > 255) {
                throw new IllegalArgumentException("Key versions must be between 0 and 255");
            }
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(Objects.requireNonNull(encoded, "key"));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Key version " + version + " is not valid base64");
            }
            if (bytes.length != 32) {
                throw new IllegalArgumentException("Key version " + version + " must decode to exactly 32 bytes");
            }
            decoded.put(version, new SecretKeySpec(bytes, "AES"));
            Arrays.fill(bytes, (byte) 0);
        });
        keys = Map.copyOf(decoded);
        int version = keys.keySet().stream().mapToInt(Integer::intValue).max().orElseThrow();
        current = new VersionedKey(version, keys.get(version));
    }

    public VersionedKey current() {
        return current;
    }

    public SecretKey get(int version) {
        SecretKey key = keys.get(version);
        if (key == null) {
            throw new IllegalArgumentException("Unknown key version: " + version);
        }
        return key;
    }

    public record VersionedKey(int version, SecretKey key) {
    }
}
