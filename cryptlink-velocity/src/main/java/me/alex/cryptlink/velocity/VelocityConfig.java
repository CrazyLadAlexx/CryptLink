package me.alex.cryptlink.velocity;

import me.alex.cryptlink.api.wire.RoutingCodec;
import me.alex.cryptlink.api.wire.WireCodec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public record VelocityConfig(Set<String> allowedServers, int maxPacketBytes, int messagesPerSecond,
                             int messageBurst, int bytesPerSecond, int byteBurst,
                             int broadcastsPerSecond, int broadcastBurst,
                             Duration rateLimitIdle, Duration telemetryInterval) {
    private static final Set<String> NUMERIC_PROPERTIES = Set.of("max-packet-bytes", "messages-per-second",
            "message-burst", "bytes-per-second", "byte-burst", "broadcasts-per-second",
            "broadcast-burst", "rate-limit-idle-seconds", "telemetry-log-interval-seconds");

    public VelocityConfig {
        allowedServers.forEach(RoutingCodec::validateServerName);
        allowedServers = Set.copyOf(allowedServers);
        if (allowedServers.size() > 4096) {
            throw new IllegalArgumentException("allowed-servers cannot contain more than 4096 entries");
        }
        if (maxPacketBytes < WireCodec.MIN_ENVELOPE_SIZE + 8
                || maxPacketBytes > RoutingCodec.MAX_PACKET_SIZE) {
            throw new IllegalArgumentException("max-packet-bytes is outside the supported range");
        }
        bounded(messagesPerSecond, 1, 100000, "messages-per-second");
        bounded(messageBurst, 1, 200000, "message-burst");
        bounded(bytesPerSecond, 1024, 67108864, "bytes-per-second");
        bounded(byteBurst, 1024, 134217728, "byte-burst");
        bounded(broadcastsPerSecond, 1, 10000, "broadcasts-per-second");
        bounded(broadcastBurst, 1, 20000, "broadcast-burst");
        if (rateLimitIdle.toSeconds() < 60 || rateLimitIdle.toSeconds() > 86400) {
            throw new IllegalArgumentException("rate-limit-idle-seconds must be between 60 and 86400");
        }
        if (telemetryInterval.toSeconds() < 1 || telemetryInterval.toSeconds() > 3600) {
            throw new IllegalArgumentException("telemetry-log-interval-seconds must be between 1 and 3600");
        }
    }

    public static VelocityConfig load(Path dataDirectory) throws IOException {
        Files.createDirectories(dataDirectory);
        Path file = dataDirectory.resolve("config.yml");
        if (Files.notExists(file)) {
            try (InputStream defaults = VelocityConfig.class.getResourceAsStream("/config.yml")) {
                if (defaults == null) {
                    throw new IOException("Missing bundled config.yml");
                }
                Files.copy(defaults, file);
            }
        }
        if (Files.size(file) > 1048576) {
            throw new IllegalArgumentException("config.yml exceeds 1048576 bytes");
        }
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    public static VelocityConfig denyAll() {
        return new VelocityConfig(Set.of(), RoutingCodec.MAX_PACKET_SIZE, 100, 200,
                1048576, 2097152, 10, 20, Duration.ofSeconds(300), Duration.ofSeconds(30));
    }

    static VelocityConfig parse(String content) {
        Set<String> servers = new HashSet<>();
        Map<String, Integer> values = new HashMap<>();
        boolean listSeen = false;
        boolean inList = false;
        for (String rawLine : content.split("\\R", -1)) {
            if (rawLine.isBlank()) {
                continue;
            }
            String line = rawLine.strip();
            if (line.equals("allowed-servers:") || line.equals("allowed-servers: []")) {
                if (listSeen) {
                    throw new IllegalArgumentException("Duplicate allowed-servers property");
                }
                listSeen = true;
                inList = line.equals("allowed-servers:");
                continue;
            }
            if (inList && rawLine.length() > rawLine.stripLeading().length() && line.startsWith("- ")) {
                String server = unquote(line.substring(2).strip());
                RoutingCodec.validateServerName(server);
                if (!servers.add(server)) {
                    throw new IllegalArgumentException("Duplicate allowed server: " + server);
                }
                continue;
            }
            inList = false;
            int separator = line.indexOf(':');
            if (separator < 1) {
                throw new IllegalArgumentException("Malformed Velocity config property");
            }
            String name = line.substring(0, separator);
            if (!NUMERIC_PROPERTIES.contains(name)) {
                throw new IllegalArgumentException("Unknown Velocity config property: " + name);
            }
            if (values.putIfAbsent(name, parseInteger(line.substring(separator + 1), name)) != null) {
                throw new IllegalArgumentException("Duplicate Velocity config property: " + name);
            }
        }
        if (!listSeen) {
            throw new IllegalArgumentException("allowed-servers is required");
        }
        return new VelocityConfig(servers, value(values, "max-packet-bytes", 32766),
                value(values, "messages-per-second", 100), value(values, "message-burst", 200),
                value(values, "bytes-per-second", 1048576), value(values, "byte-burst", 2097152),
                value(values, "broadcasts-per-second", 10), value(values, "broadcast-burst", 20),
                Duration.ofSeconds(value(values, "rate-limit-idle-seconds", 300)),
                Duration.ofSeconds(value(values, "telemetry-log-interval-seconds", 30)));
    }

    private static int value(Map<String, Integer> values, String name, int fallback) {
        return values.getOrDefault(name, fallback);
    }

    private static int parseInteger(String value, String name) {
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static void bounded(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
    }
}
