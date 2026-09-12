package me.alex.cryptlink.paper;

import me.alex.cryptlink.api.SecureMessage;
import me.alex.cryptlink.api.SecureMessagingApi;
import me.alex.cryptlink.api.crypto.AesGcmCipher;
import me.alex.cryptlink.api.wire.MessageCodec;
import me.alex.cryptlink.api.wire.RoutingCodec;
import me.alex.cryptlink.api.wire.WireCodec;
import me.alex.cryptlink.paper.channel.ChannelSender;

import java.util.function.BiConsumer;

public final class SecureMessagingService implements SecureMessagingApi {
    private final PaperConfig config;
    private final AesGcmCipher cipher;
    private final ChannelSender sender;
    private final TopicSubscriptions subscriptions;

    public SecureMessagingService(PaperConfig config, AesGcmCipher cipher,
                                  ChannelSender sender, TopicSubscriptions subscriptions) {
        this.config = config;
        this.cipher = cipher;
        this.sender = sender;
        this.subscriptions = subscriptions;
    }

    @Override
    public void send(String targetServer, String topic, byte[] payload) {
        RoutingCodec.validateServerName(targetServer);
        transmit(targetServer, topic, payload);
    }

    @Override
    public void broadcast(String topic, byte[] payload) {
        transmit(RoutingCodec.BROADCAST, topic, payload);
    }

    @Override
    public void subscribe(String topic, BiConsumer<String, byte[]> handler) {
        subscriptions.subscribe(topic, handler);
    }

    private void transmit(String targetServer, String topic, byte[] payload) {
        SecureMessage message = new SecureMessage(config.serverName(), targetServer, topic, payload);
        byte[] plaintext = MessageCodec.encode(message);
        var envelope = cipher.encrypt(plaintext, config.keyRing().current().key());
        sender.send(RoutingCodec.encode(targetServer, WireCodec.encode(envelope)));
    }
}
