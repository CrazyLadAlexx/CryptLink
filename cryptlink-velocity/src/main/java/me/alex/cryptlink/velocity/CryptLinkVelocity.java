package me.alex.cryptlink.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import me.alex.cryptlink.api.security.SecurityTelemetry;

import java.io.IOException;
import java.nio.file.Path;

public final class CryptLinkVelocity {
    private final ProxyServer proxy;
    private final Path dataDirectory;

    @Inject
    public CryptLinkVelocity(ProxyServer proxy, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        VelocityConfig config;
        try {
            config = VelocityConfig.load(dataDirectory);
        } catch (IOException | IllegalArgumentException exception) {
            System.getLogger("CryptLink").log(System.Logger.Level.ERROR,
                    "Relay disabled: " + exception.getMessage());
            config = VelocityConfig.denyAll();
        }
        SecurityTelemetry telemetry = new SecurityTelemetry(config.telemetryInterval(),
                message -> System.getLogger("CryptLink").log(System.Logger.Level.WARNING, message));
        proxy.getChannelRegistrar().register(RelayListener.CHANNEL);
        proxy.getEventManager().register(this,
                new RelayListener(proxy, config, new RateLimiterRegistry(config), telemetry));
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        proxy.getChannelRegistrar().unregister(RelayListener.CHANNEL);
        proxy.getEventManager().unregisterListeners(this);
    }
}
