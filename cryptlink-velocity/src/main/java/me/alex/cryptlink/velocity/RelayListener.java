package me.alex.cryptlink.velocity;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import me.alex.cryptlink.api.security.RejectionReason;
import me.alex.cryptlink.api.security.SecurityTelemetry;
import me.alex.cryptlink.api.wire.RoutingCodec;

public final class RelayListener {
    public static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from(RoutingCodec.CHANNEL);
    private final ProxyServer proxy;
    private final VelocityConfig config;
    private final RateLimiterRegistry rateLimiters;
    private final SecurityTelemetry telemetry;

    public RelayListener(ProxyServer proxy, VelocityConfig config, RateLimiterRegistry rateLimiters,
                         SecurityTelemetry telemetry) {
        this.proxy = proxy;
        this.config = config;
        this.rateLimiters = rateLimiters;
        this.telemetry = telemetry;
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!CHANNEL.equals(event.getIdentifier())) {
            return;
        }
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection source)) {
            telemetry.record(RejectionReason.UNAUTHORISED_BACKEND);
            return;
        }
        String sourceName = source.getServerInfo().getName();
        if (!config.allowedServers().contains(sourceName)) {
            telemetry.record(RejectionReason.UNAUTHORISED_BACKEND);
            return;
        }
        byte[] data = event.getData();
        if (data.length > config.maxPacketBytes()) {
            telemetry.record(RejectionReason.MALFORMED_PACKET);
            return;
        }
        if (!rateLimiters.allowBase(sourceName, data.length)) {
            telemetry.record(RejectionReason.RATE_LIMIT);
            return;
        }
        RoutingCodec.UpstreamPacket route;
        try {
            route = RoutingCodec.decodeUpstream(data, config.maxPacketBytes());
        } catch (IllegalArgumentException exception) {
            telemetry.record(RejectionReason.MALFORMED_PACKET);
            return;
        }
        if (RoutingCodec.BROADCAST.equals(route.targetServer())) {
            if (!rateLimiters.allowBroadcast(sourceName)) {
                telemetry.record(RejectionReason.RATE_LIMIT);
                return;
            }
            broadcast(sourceName, route.envelope());
        } else {
            unicast(sourceName, route.targetServer(), route.envelope());
        }
    }

    private void unicast(String source, String destination, byte[] envelope) {
        if (!config.allowedServers().contains(destination)) {
            telemetry.record(RejectionReason.UNKNOWN_DESTINATION);
            return;
        }
        proxy.getServer(destination).ifPresentOrElse(
                server -> forward(server, source, envelope),
                () -> telemetry.record(RejectionReason.UNKNOWN_DESTINATION));
    }

    private void broadcast(String source, byte[] envelope) {
        for (RegisteredServer destination : proxy.getAllServers()) {
            String name = destination.getServerInfo().getName();
            if (!name.equals(source) && config.allowedServers().contains(name)) {
                forward(destination, source, envelope);
            }
        }
    }

    private void forward(RegisteredServer destination, String source, byte[] envelope) {
        byte[] packet;
        try {
            packet = RoutingCodec.encodeForwarded(source, envelope);
        } catch (IllegalArgumentException exception) {
            telemetry.record(RejectionReason.MALFORMED_PACKET);
            return;
        }
        if (packet.length > config.maxPacketBytes() || !destination.sendPluginMessage(CHANNEL, packet)) {
            telemetry.record(RejectionReason.UNKNOWN_DESTINATION);
        }
    }
}
