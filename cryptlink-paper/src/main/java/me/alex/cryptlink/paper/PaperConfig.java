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
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

public record PaperConfig(String serverName, KeyRing keyRing, Duration replayWindow, Duration clockSkew) {
    public PaperConfig {
        RoutingCodec.validateServerName(serverName);
        if (replayWindow.isNegative() || replayWindow.isZero() || clockSkew.isNegative()
                || replayWindow.compareTo(clockSkew.multipliedBy(2).plusSeconds(1)) < 0) {
            throw new IllegalArgumentException("Replay window must cover twice the clock skew plus one second");
        }
    }

    public static PaperConfig load(Path dataDirectory, Logger logger) throws IOException {
        Files.createDirectories(dataDirectory);
        Path file = dataDirectory.resolve("config.yml");
        if (Files.notExists(file)) {
            try (InputStream defaults = PaperConfig.class.getResourceAsStream("/config.yml")) {
                if (defaults == null) {
                    throw new IOException("Missing bundled config.yml");
                }
                Files.copy(defaults, file);
            }
        }
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(file.toFile());
        } catch (InvalidConfigurationException exception) {
            throw new IOException("Invalid config.yml", exception);
        }
        String serverName = config.getString("server-name", "");
        Map<Integer, String> keys = readKeys(config.getConfigurationSection("keys"));
        boolean generated = keys.size() == 1 && "BASE64_32_BYTE_KEY".equals(keys.get(1));
        if (generated) {
            keys.put(1, generateKey());
        }
        KeyRing keyRing = new KeyRing(keys);
        if (integer(config, "current-key-version", keyRing.current().version()) != keyRing.current().version()) {
            throw new IllegalArgumentException("current-key-version must equal the highest configured key version");
        }
        PaperConfig loaded = new PaperConfig(serverName, keyRing,
                Duration.ofSeconds(integer(config, "replay-window-seconds", 30)),
                Duration.ofSeconds(integer(config, "clock-skew-seconds", 10)));
        if (generated) {
            config.set("keys.1", keys.get(1));
            config.save(file.toFile());
            logger.info("Generated a private AES-256 key in config.yml; securely copy the keys section to every participating backend");
        }
        return loaded;
    }

    private static Map<Integer, String> readKeys(ConfigurationSection section) {
        if (section == null) {
            throw new IllegalArgumentException("Config requires a keys section");
        }
        Map<Integer, String> keys = new HashMap<>();
        for (String version : section.getKeys(false)) {
            int number;
            try {
                number = Integer.parseInt(version);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Key versions must be integers between 0 and 255");
            }
            if (!section.isString(version) || keys.putIfAbsent(number, section.getString(version)) != null) {
                throw new IllegalArgumentException("Each key version must have one base64 string");
            }
        }
        return keys;
    }

    private static String generateKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        String encoded = Base64.getEncoder().encodeToString(key);
        Arrays.fill(key, (byte) 0);
        return encoded;
    }

    private static int integer(FileConfiguration config, String path, int fallback) {
        if (!config.contains(path)) {
            return fallback;
        }
        if (!config.isInt(path)) {
            throw new IllegalArgumentException(path + " must be an integer");
        }
        return config.getInt(path);
    }
}
