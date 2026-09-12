package me.alex.cryptlink.paper;

import me.alex.cryptlink.api.SecureMessagingApi;
import me.alex.cryptlink.api.Subscription;
import me.alex.cryptlink.api.wire.RoutingCodec;
import me.alex.cryptlink.paper.channel.ChannelSender;

import java.util.function.BiConsumer;

public final class SecureMessagingService implements SecureMessagingApi {
    private final MessageEncoder encoder;
    private final ChannelSender sender;
    private final TopicSubscriptions subscriptions;

    public SecureMessagingService(MessageEncoder encoder,
                                  ChannelSender sender, TopicSubscriptions subscriptions) {
        this.encoder = encoder;
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
    public Subscription subscribe(String topic, BiConsumer<String, byte[]> handler) {
        return subscriptions.subscribe(topic, handler);
    }

    private void transmit(String targetServer, String topic, byte[] payload) {
        sender.send(encoder.encode(targetServer, topic, payload));
    }
}
