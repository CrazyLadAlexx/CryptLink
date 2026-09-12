package me.alex.cryptlink.paper.channel;

import me.alex.cryptlink.api.wire.RoutingCodec;
import me.alex.cryptlink.paper.InboundMessageProcessor;
import me.alex.cryptlink.paper.TopicSubscriptions;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

public final class ChannelListener implements PluginMessageListener {
    private final JavaPlugin plugin;
    private final InboundMessageProcessor processor;
    private final TopicSubscriptions subscriptions;

    public ChannelListener(JavaPlugin plugin, InboundMessageProcessor processor,
                           TopicSubscriptions subscriptions) {
        this.plugin = plugin;
        this.processor = processor;
        this.subscriptions = subscriptions;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] data) {
        if (!RoutingCodec.CHANNEL.equals(channel) || !plugin.isEnabled()) {
            return;
        }
        processor.process(data).ifPresent(message -> {
            if (Bukkit.isPrimaryThread()) {
                subscriptions.dispatch(message);
            } else {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (plugin.isEnabled()) {
                        subscriptions.dispatch(message);
                    }
                });
            }
        });
    }
}
