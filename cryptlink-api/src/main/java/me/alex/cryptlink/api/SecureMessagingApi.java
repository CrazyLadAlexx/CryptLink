package me.alex.cryptlink.api;

import java.util.function.BiConsumer;

public interface SecureMessagingApi {
    void send(String targetServer, String topic, byte[] payload);

    void broadcast(String topic, byte[] payload);

    void subscribe(String topic, BiConsumer<String, byte[]> handler);
}
