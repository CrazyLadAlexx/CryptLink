package me.alex.cryptlink.paper.channel;

import me.alex.cryptlink.api.SecureMessage;
import me.alex.cryptlink.api.crypto.AeadDecryptionException;
import me.alex.cryptlink.api.crypto.AesGcmCipher;
import me.alex.cryptlink.api.crypto.ReplayGuard;
import me.alex.cryptlink.api.wire.MessageCodec;
import me.alex.cryptlink.api.wire.RoutingCodec;
import me.alex.cryptlink.api.wire.WireCodec;
import me.alex.cryptlink.paper.PaperConfig;
import me.alex.cryptlink.paper.TopicSubscriptions;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

public final class ChannelListener implements PluginMessageListener {
    private final JavaPlugin plugin;
    private final PaperConfig config;
    private final AesGcmCipher cipher;
    private final ReplayGuard replayGuard;
    private final TopicSubscriptions subscriptions;

    public ChannelListener(JavaPlugin plugin, PaperConfig config, AesGcmCipher cipher,
                           ReplayGuard replayGuard, TopicSubscriptions subscriptions) {
        this.plugin = plugin;
        this.config = config;
        this.cipher = cipher;
        this.replayGuard = replayGuard;
        this.subscriptions = subscriptions;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] data) {
        if (!RoutingCodec.CHANNEL.equals(channel) || !plugin.isEnabled()) {
            return;
        }
        SecureMessage message;
        try {
            var envelope = WireCodec.decode(data);
            if (!replayGuard.acceptsTimestamp(envelope.timestamp())) {
                return;
            }
            byte[] plaintext = cipher.decrypt(envelope, config.keyRing().get(envelope.keyVersion()));
            message = MessageCodec.decode(plaintext);
            boolean broadcast = RoutingCodec.BROADCAST.equals(message.targetServer());
            if ((!broadcast && !config.serverName().equals(message.targetServer()))
                    || (broadcast && config.serverName().equals(message.sourceServer()))) {
                return;
            }
            if (!replayGuard.seen(envelope.nonce())) {
                return;
            }
        } catch (IllegalArgumentException | AeadDecryptionException exception) {
            plugin.getLogger().fine("Rejected an invalid CryptLink message");
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            subscriptions.dispatch(message);
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> subscriptions.dispatch(message));
        }
    }
}
