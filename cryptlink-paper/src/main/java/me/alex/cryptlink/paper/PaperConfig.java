package me.alex.cryptlink.paper;

import me.alex.cryptlink.api.crypto.KeyRing;
import me.alex.cryptlink.api.wire.RoutingCodec;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

public record PaperConfig(String serverName, KeyRing keyRing, Duration replayWindow, Duration clockSkew,
                          int replayEntriesPerSource, int replaySourceLimit, Duration telemetryInterval) {
    private static final Set<String> PROPERTIES = Set.of("server-name", "replay-window-seconds",
            "clock-skew-seconds", "replay-entries-per-source", "replay-source-limit",
            "telemetry-log-interval-seconds", "keys", "current-key-version");

    public PaperConfig {
        RoutingCodec.validateServerName(serverName);
        if (replayWindow.isZero() || replayWindow.isNegative() || clockSkew.isNegative()
                || replayWindow.compareTo(clockSkew.multipliedBy(2).plusSeconds(1)) < 0) {
            throw new IllegalArgumentException("Replay window must cover twice the clock skew plus one second");
        }
        if (replayEntriesPerSource < 1 || replayEntriesPerSource > 65536
                || replaySourceLimit < 1 || replaySourceLimit > 4096) {
            throw new IllegalArgumentException("Replay cache limits are outside the supported range");
        }
        if (telemetryInterval.isZero() || telemetryInterval.isNegative()) {
            throw new IllegalArgumentException("Telemetry interval must be positive");
        }
    }

    public static PaperConfig load(Path dataDirectory, Logger logger) throws IOException {
        Files.createDirectories(dataDirectory);
        Path configFile = dataDirectory.resolve("config.yml");
        copyDefault(configFile);
        YamlConfiguration config = loadYaml(configFile);
        rejectUnknownProperties(config);
        Map<Integer, String> legacyKeys = readLegacyKeys(config.getConfigurationSection("keys"));
        Integer legacyCurrent = config.contains("current-key-version")
                ? integer(config, "current-key-version", 0, 0, 255) : null;
        String external = System.getenv("CRYPTLINK_KEYS_FILE");
        Path keyFile = external == null || external.isBlank()
                ? dataDirectory.resolve("keys.yml") : Path.of(external).toAbsolutePath().normalize();
        KeyRing keyRing = KeyFile.load(keyFile, legacyKeys, legacyCurrent, logger);
        if (!legacyKeys.isEmpty()) {
            config.set("keys", null);
            config.set("current-key-version", null);
            config.save(configFile.toFile());
        }
        return new PaperConfig(requiredString(config, "server-name"), keyRing,
                Duration.ofSeconds(integer(config, "replay-window-seconds", 30, 1, 86400)),
                Duration.ofSeconds(integer(config, "clock-skew-seconds", 10, 0, 3600)),
                integer(config, "replay-entries-per-source", 4096, 1, 65536),
                integer(config, "replay-source-limit", 256, 1, 4096),
                Duration.ofSeconds(integer(config, "telemetry-log-interval-seconds", 30, 1, 3600)));
    }

    private static void copyDefault(Path file) throws IOException {
        if (Files.exists(file)) {
            return;
        }
        try (InputStream defaults = PaperConfig.class.getResourceAsStream("/config.yml")) {
            if (defaults == null) {
                throw new IOException("Missing bundled config.yml");
            }
            Files.copy(defaults, file);
        }
    }

    private static YamlConfiguration loadYaml(Path file) throws IOException {
        if (Files.size(file) > 1048576) {
            throw new IllegalArgumentException("config.yml exceeds 1048576 bytes");
        }
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(file.toFile());
            return config;
        } catch (InvalidConfigurationException exception) {
            throw new IOException("Invalid config.yml", exception);
        }
    }

    private static void rejectUnknownProperties(FileConfiguration config) {
        for (String property : config.getKeys(false)) {
            if (!PROPERTIES.contains(property)) {
                throw new IllegalArgumentException("Unknown config.yml property: " + property);
            }
        }
    }

    private static Map<Integer, String> readLegacyKeys(ConfigurationSection section) {
        if (section == null) {
            return Map.of();
        }
        Map<Integer, String> keys = new HashMap<>();
        for (String value : section.getKeys(false)) {
            int version;
            try {
                version = Integer.parseInt(value);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Legacy key versions must be integers between 0 and 255");
            }
            if (version < 0 || version > 255 || !section.isString(value)
                    || keys.putIfAbsent(version, section.getString(value)) != null) {
                throw new IllegalArgumentException("Invalid legacy key version: " + value);
            }
        }
        return keys;
    }

    private static String requiredString(FileConfiguration config, String path) {
        String value = config.getString(path);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(path + " is required");
        }
        return value;
    }

    private static int integer(FileConfiguration config, String path, int defaultValue, int minimum, int maximum) {
        if (!config.contains(path)) {
            return defaultValue;
        }
        if (!config.isInt(path)) {
            throw new IllegalArgumentException(path + " must be an integer");
        }
        int value = config.getInt(path);
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(path + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }
}
