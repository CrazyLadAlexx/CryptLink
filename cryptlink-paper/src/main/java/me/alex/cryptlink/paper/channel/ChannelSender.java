package me.alex.cryptlink.paper.channel;

import me.alex.cryptlink.api.wire.RoutingCodec;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class ChannelSender {
    private final JavaPlugin plugin;

    public ChannelSender(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void send(byte[] packet) {
        if (!plugin.isEnabled()) {
            throw new IllegalStateException("CryptLink is disabled");
        }
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("CryptLink sends must run on the server thread");
        }
        if (packet.length > RoutingCodec.MAX_PACKET_SIZE) {
            throw new IllegalArgumentException("Message exceeds the plugin message limit");
        }
        var players = Bukkit.getOnlinePlayers().iterator();
        if (!players.hasNext()) {
            throw new IllegalStateException("CryptLink requires an online player to carry plugin messages");
        }
        Player carrier = players.next();
        carrier.sendPluginMessage(plugin, RoutingCodec.CHANNEL, packet);
    }
}
