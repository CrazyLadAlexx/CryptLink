package me.alex.cryptlink.velocity;

import me.alex.cryptlink.api.wire.RoutingCodec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public record VelocityConfig(Set<String> allowedServers) {
    public VelocityConfig {
        allowedServers.forEach(RoutingCodec::validateServerName);
        allowedServers = Set.copyOf(allowedServers);
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
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    private static VelocityConfig parse(String yaml) {
        Set<String> servers = new HashSet<>();
        boolean rootSeen = false;
        boolean blockList = false;
        for (String line : yaml.split("\\R")) {
            String value = line.strip();
            if (value.isEmpty()) {
                continue;
            }
            if (!rootSeen && (value.equals("allowed-servers:") || value.equals("allowed-servers: []"))) {
                rootSeen = true;
                blockList = value.equals("allowed-servers:");
                continue;
            }
            if (!blockList || !value.startsWith("- ")) {
                throw new IllegalArgumentException("config.yml must contain only allowed-servers and its list of server names");
            }
            String name = value.substring(2).strip();
            if (name.length() >= 2 && ((name.startsWith("\"") && name.endsWith("\""))
                    || (name.startsWith("'") && name.endsWith("'")))) {
                name = name.substring(1, name.length() - 1);
            }
            RoutingCodec.validateServerName(name);
            if (!servers.add(name)) {
                throw new IllegalArgumentException("Duplicate allowed server: " + name);
            }
        }
        return new VelocityConfig(servers);
    }
}
