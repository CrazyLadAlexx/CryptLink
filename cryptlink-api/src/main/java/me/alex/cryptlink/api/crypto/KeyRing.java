package me.alex.cryptlink.api.crypto;

import me.alex.cryptlink.api.wire.RoutingCodec;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class KeyRing {
    private final Map<Integer, SecretKeySpec> masterKeys;
    private final int currentVersion;

    public KeyRing(Map<Integer, String> encodedKeys, int currentVersion) {
        if (Objects.requireNonNull(encodedKeys, "keys").isEmpty()) {
            throw new IllegalArgumentException("At least one AES-256 master key is required");
        }
        Map<Integer, SecretKeySpec> decoded = new HashMap<>();
        encodedKeys.forEach((version, encoded) -> decoded.put(validVersion(version), decode(version, encoded)));
        masterKeys = Map.copyOf(decoded);
        if (!masterKeys.containsKey(currentVersion)) {
            throw new IllegalArgumentException("current-key-version does not exist in keys.yml: " + currentVersion);
        }
        this.currentVersion = currentVersion;
    }

    public VersionedKey current(String sourceServer) {
        return new VersionedKey(currentVersion, derive(currentVersion, sourceServer));
    }

    public Optional<SecretKey> find(int version, String sourceServer) {
        return masterKeys.containsKey(version) ? Optional.of(derive(version, sourceServer)) : Optional.empty();
    }

    public int currentVersion() {
        return currentVersion;
    }

    private SecretKey derive(int version, String sourceServer) {
        RoutingCodec.validateServerName(sourceServer);
        byte[] master = masterKeys.get(version).getEncoded();
        try {
            return HkdfSha256.derive(master, version, sourceServer);
        } finally {
            Arrays.fill(master, (byte) 0);
        }
    }

    private static int validVersion(Integer version) {
        if (version == null || version < 0 || version > 255) {
            throw new IllegalArgumentException("Key versions must be between 0 and 255");
        }
        return version;
    }

    private static SecretKeySpec decode(int version, String encoded) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(Objects.requireNonNull(encoded, "key"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Key version " + version + " is not valid base64");
        }
        if (bytes.length != 32) {
            Arrays.fill(bytes, (byte) 0);
            throw new IllegalArgumentException("Key version " + version + " must decode to exactly 32 bytes");
        }
        SecretKeySpec key = new SecretKeySpec(bytes, "AES");
        Arrays.fill(bytes, (byte) 0);
        return key;
    }

    public record VersionedKey(int version, SecretKey key) {
    }
}
