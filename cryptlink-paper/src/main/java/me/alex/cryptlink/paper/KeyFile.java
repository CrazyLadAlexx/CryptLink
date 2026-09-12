package me.alex.cryptlink.paper;

import me.alex.cryptlink.api.crypto.KeyRing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

final class KeyFile {
    private static final Set<PosixFilePermission> PRIVATE_PERMISSIONS = PosixFilePermissions.fromString("rw-------");

    private KeyFile() {
    }

    static KeyRing load(Path file, Map<Integer, String> migrationKeys, Integer migrationCurrent,
                        Logger logger) throws IOException {
        if (Files.notExists(file)) {
            Files.createDirectories(file.toAbsolutePath().normalize().getParent());
            if (!migrationKeys.isEmpty() && migrationCurrent == null) {
                throw new IllegalArgumentException("Legacy config keys require current-key-version");
            }
            KeyData initial = migrationKeys.isEmpty() ? generate() : new KeyData(migrationCurrent, migrationKeys);
            createPrivateFile(file);
            Files.writeString(file, format(initial), StandardCharsets.UTF_8);
            logger.info(migrationKeys.isEmpty()
                    ? "Generated a private AES-256 master key in " + file.getFileName()
                    : "Migrated encryption keys from config.yml to " + file.getFileName());
        }
        if (Files.size(file) > 65536) {
            throw new IllegalArgumentException("keys.yml exceeds 65536 bytes");
        }
        verifyPermissions(file, logger);
        KeyData data = parse(Files.readString(file, StandardCharsets.UTF_8));
        return new KeyRing(data.keys(), data.currentVersion());
    }

    static KeyData parse(String content) {
        Integer current = null;
        Map<Integer, String> keys = new HashMap<>();
        boolean inKeys = false;
        boolean keysSeen = false;
        for (String rawLine : content.split("\\R", -1)) {
            if (rawLine.isBlank()) {
                continue;
            }
            String line = rawLine.strip();
            if (rawLine.equals(rawLine.stripLeading())) {
                inKeys = false;
                if (line.startsWith("current-key-version:")) {
                    if (current != null) {
                        throw new IllegalArgumentException("Duplicate current-key-version in keys.yml");
                    }
                    current = parseInteger(line.substring(line.indexOf(':') + 1), "current-key-version");
                    continue;
                }
                if (line.equals("keys:")) {
                    if (keysSeen) {
                        throw new IllegalArgumentException("Duplicate keys property in keys.yml");
                    }
                    keysSeen = true;
                    inKeys = true;
                    continue;
                }
                throw new IllegalArgumentException("Unknown property in keys.yml");
            }
            if (!inKeys) {
                throw new IllegalArgumentException("Malformed key entry in keys.yml");
            }
            int separator = line.indexOf(':');
            if (separator < 1) {
                throw new IllegalArgumentException("Malformed key entry in keys.yml");
            }
            int version = parseInteger(line.substring(0, separator), "key version");
            String encoded = unquote(line.substring(separator + 1).strip());
            if (encoded.isEmpty() || keys.putIfAbsent(version, encoded) != null) {
                throw new IllegalArgumentException("Duplicate or empty key version in keys.yml: " + version);
            }
        }
        if (current == null || !keysSeen || keys.isEmpty()) {
            throw new IllegalArgumentException("keys.yml requires current-key-version and at least one key");
        }
        return new KeyData(current, keys);
    }

    private static KeyData generate() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        String encoded = Base64.getEncoder().encodeToString(key);
        Arrays.fill(key, (byte) 0);
        return new KeyData(1, Map.of(1, encoded));
    }

    private static String format(KeyData data) {
        StringBuilder output = new StringBuilder("current-key-version: ").append(data.currentVersion()).append("\nkeys:\n");
        data.keys().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> output.append("  ").append(entry.getKey()).append(": \"")
                        .append(entry.getValue()).append("\"\n"));
        return output.toString();
    }

    private static void createPrivateFile(Path file) throws IOException {
        if (Files.getFileAttributeView(file, PosixFileAttributeView.class) != null) {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PRIVATE_PERMISSIONS));
        } else {
            Files.createFile(file);
        }
    }

    private static void verifyPermissions(Path file, Logger logger) throws IOException {
        if (Files.getFileAttributeView(file, PosixFileAttributeView.class) == null) {
            return;
        }
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
        if (permissions.stream().anyMatch(permission -> permission.name().startsWith("GROUP_")
                || permission.name().startsWith("OTHERS_"))) {
            logger.warning(file.getFileName() + " is readable or writable by group/other users; set permissions to 600");
        }
    }

    private static int parseInteger(String value, String name) {
        try {
            int parsed = Integer.parseInt(value.strip());
            if (parsed < 0 || parsed > 255) {
                throw new IllegalArgumentException(name + " must be between 0 and 255");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer between 0 and 255");
        }
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    record KeyData(int currentVersion, Map<Integer, String> keys) {
        KeyData {
            keys = Map.copyOf(keys);
        }
    }
}
