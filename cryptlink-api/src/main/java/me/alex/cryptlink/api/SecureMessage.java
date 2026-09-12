package me.alex.cryptlink.api;

import me.alex.cryptlink.api.wire.RoutingCodec;

import java.util.Objects;

public record SecureMessage(String sourceServer, String targetServer, String topic, byte[] payload) {
    public SecureMessage {
        RoutingCodec.validateServerName(sourceServer);
        RoutingCodec.validateTarget(targetServer);
        validateTopic(topic);
        Objects.requireNonNull(payload, "payload");
        if (payload.length > RoutingCodec.MAX_PACKET_SIZE) {
            throw new IllegalArgumentException("Payload exceeds the plugin message limit");
        }
        payload = payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    public static void validateTopic(String topic) {
        if (Objects.requireNonNull(topic, "topic").isBlank() || topic.length() > 256) {
            throw new IllegalArgumentException("Topic must contain 1 to 256 characters");
        }
    }
}
