package me.alex.cryptlink.paper;

import me.alex.cryptlink.api.SecureMessage;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class TopicSubscriptions {
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<BiConsumer<String, byte[]>>> handlers = new ConcurrentHashMap<>();
    private final Logger logger;

    public TopicSubscriptions(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public void subscribe(String topic, BiConsumer<String, byte[]> handler) {
        SecureMessage.validateTopic(topic);
        Objects.requireNonNull(handler, "handler");
        handlers.computeIfAbsent(topic, ignored -> new CopyOnWriteArrayList<>()).add(handler);
    }

    public void dispatch(SecureMessage message) {
        var subscribers = handlers.get(message.topic());
        if (subscribers == null) {
            return;
        }
        for (var handler : subscribers) {
            try {
                handler.accept(message.sourceServer(), message.payload());
            } catch (RuntimeException exception) {
                logger.log(Level.WARNING, "CryptLink topic handler failed", exception);
            }
        }
    }

    public void clear() {
        handlers.clear();
    }
}
