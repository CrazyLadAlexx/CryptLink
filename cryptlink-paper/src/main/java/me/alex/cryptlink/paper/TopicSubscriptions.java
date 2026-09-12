package me.alex.cryptlink.paper;

import me.alex.cryptlink.api.SecureMessage;
import me.alex.cryptlink.api.Subscription;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class TopicSubscriptions implements AutoCloseable {
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Entry>> handlers = new ConcurrentHashMap<>();
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final Logger logger;

    public TopicSubscriptions(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public Subscription subscribe(String topic, BiConsumer<String, byte[]> handler) {
        SecureMessage.validateTopic(topic);
        Objects.requireNonNull(handler, "handler");
        if (!open.get()) {
            throw new IllegalStateException("CryptLink subscriptions are closed");
        }
        Entry entry = new Entry(topic, handler);
        handlers.computeIfAbsent(topic, ignored -> new CopyOnWriteArrayList<>()).add(entry);
        if (!open.get()) {
            entry.close();
            throw new IllegalStateException("CryptLink subscriptions are closed");
        }
        return entry;
    }

    public void dispatch(SecureMessage message) {
        if (!open.get()) {
            return;
        }
        var subscribers = handlers.get(message.topic());
        if (subscribers == null) {
            return;
        }
        for (Entry entry : subscribers) {
            if (!entry.active.get()) {
                continue;
            }
            try {
                entry.handler.accept(message.sourceServer(), message.payload());
            } catch (RuntimeException exception) {
                logger.log(Level.WARNING, "CryptLink topic handler failed", exception);
            }
        }
    }

    @Override
    public void close() {
        if (open.compareAndSet(true, false)) {
            handlers.values().forEach(entries -> entries.forEach(entry -> entry.active.set(false)));
            handlers.clear();
        }
    }

    private final class Entry implements Subscription {
        private final String topic;
        private final BiConsumer<String, byte[]> handler;
        private final AtomicBoolean active = new AtomicBoolean(true);

        private Entry(String topic, BiConsumer<String, byte[]> handler) {
            this.topic = topic;
            this.handler = handler;
        }

        @Override
        public boolean isActive() {
            return active.get();
        }

        @Override
        public void close() {
            if (!active.compareAndSet(true, false)) {
                return;
            }
            handlers.computeIfPresent(topic, (ignored, entries) -> {
                entries.remove(this);
                return entries.isEmpty() ? null : entries;
            });
        }
    }
}
