package me.alex.cryptlink.paper;

import me.alex.cryptlink.api.SecureMessagingApi;
import me.alex.cryptlink.api.crypto.AesGcmCipher;
import me.alex.cryptlink.api.crypto.ReplayGuard;
import me.alex.cryptlink.api.security.SecurityTelemetry;
import me.alex.cryptlink.api.wire.RoutingCodec;
import me.alex.cryptlink.paper.channel.ChannelListener;
import me.alex.cryptlink.paper.channel.ChannelSender;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;

public final class CryptLinkPaper extends JavaPlugin {
    private TopicSubscriptions subscriptions;
    private InboundMessageProcessor processor;

    @Override
    public void onEnable() {
        PaperConfig config;
        try {
            config = PaperConfig.load(getDataFolder().toPath(), getLogger());
        } catch (IOException | IllegalArgumentException exception) {
            getLogger().severe("Cannot enable CryptLink: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        AesGcmCipher cipher = new AesGcmCipher();
        ReplayGuard guard = new ReplayGuard(config.replayWindow(), config.clockSkew(),
                config.replayEntriesPerSource(), config.replaySourceLimit());
        SecurityTelemetry telemetry = new SecurityTelemetry(config.telemetryInterval(), getLogger()::warning);
        subscriptions = new TopicSubscriptions(getLogger());
        processor = new InboundMessageProcessor(config, cipher, guard, telemetry);
        ChannelSender sender = new ChannelSender(this);
        ChannelListener listener = new ChannelListener(this, processor, subscriptions);
        var messenger = getServer().getMessenger();
        messenger.registerOutgoingPluginChannel(this, RoutingCodec.CHANNEL);
        messenger.registerIncomingPluginChannel(this, RoutingCodec.CHANNEL, listener);
        getServer().getServicesManager().register(SecureMessagingApi.class,
                new SecureMessagingService(new MessageEncoder(config.serverName(), config.keyRing(), cipher),
                        sender, subscriptions), this, ServicePriority.Normal);
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
        getServer().getScheduler().cancelTasks(this);
        if (subscriptions != null) {
            subscriptions.close();
        }
        if (processor != null) {
            processor.clear();
        }
    }
}
