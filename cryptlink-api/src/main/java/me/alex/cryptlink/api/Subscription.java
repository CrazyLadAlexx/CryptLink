package me.alex.cryptlink.api;

public interface Subscription extends AutoCloseable {
    boolean isActive();

    @Override
    void close();
}
