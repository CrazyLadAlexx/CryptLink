package me.alex.cryptlink.velocity;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import me.alex.cryptlink.api.wire.RoutingCodec;

public final class RelayListener {
    public static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from(RoutingCodec.CHANNEL);
    private final ProxyServer proxy;
    private final VelocityConfig config;
    private final System.Logger logger = System.getLogger("CryptLink");

    public RelayListener(ProxyServer proxy, VelocityConfig config) {
        this.proxy = proxy;
        this.config = config;
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!CHANNEL.equals(event.getIdentifier())) {
            return;
        }
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection source)) {
            return;
        }
        String sourceName = source.getServerInfo().getName();
        if (!config.allowedServers().contains(sourceName)) {
            return;
        }
        RoutingCodec.RoutedEnvelope route;
        try {
            route = RoutingCodec.decode(event.getData());
        } catch (IllegalArgumentException exception) {
            return;
        }
        byte[] envelope = route.envelope();
        if (RoutingCodec.BROADCAST.equals(route.targetServer())) {
            for (RegisteredServer target : proxy.getAllServers()) {
                String name = target.getServerInfo().getName();
                if (!name.equals(sourceName) && config.allowedServers().contains(name)) {
                    forward(target, envelope);
                }
            }
        } else if (config.allowedServers().contains(route.targetServer())) {
            proxy.getServer(route.targetServer()).ifPresent(target -> forward(target, envelope));
        }
    }

    private void forward(RegisteredServer target, byte[] envelope) {
        if (!target.sendPluginMessage(CHANNEL, envelope)) {
            logger.log(System.Logger.Level.DEBUG, "No plugin-message connection to " + target.getServerInfo().getName());
        }
    }
}
