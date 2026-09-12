package me.alex.cryptlink.velocity;

import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelMessageSource;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import me.alex.cryptlink.api.security.RejectionReason;
import me.alex.cryptlink.api.security.SecurityTelemetry;
import me.alex.cryptlink.api.wire.Protocol;
import me.alex.cryptlink.api.wire.RoutingCodec;
import me.alex.cryptlink.api.wire.WireCodec;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelaySecurityTest {
    @Test
    void unicastIsSourceStampedByVelocity() {
        Fixture fixture = fixture();
        PluginMessageEvent event = event(fixture.connection("a"), fixture.player(),
                RoutingCodec.encodeUpstream("b", envelope()));
        fixture.listener().onPluginMessage(event);
        assertFalse(event.getResult().isAllowed());
        assertEquals(1, fixture.deliveries().size());
        Delivery delivery = fixture.deliveries().getFirst();
        assertEquals("b", delivery.destination());
        var forwarded = RoutingCodec.decodeForwarded(delivery.packet());
        assertEquals("a", forwarded.sourceServer());
        assertArrayEquals(envelope(), forwarded.envelope());
    }

    @Test
    void broadcastReachesAllowedServersExceptSenderWithSameSourceStamp() {
        Fixture fixture = fixture();
        fixture.listener().onPluginMessage(event(fixture.connection("a"), fixture.player(),
                RoutingCodec.encodeUpstream("*", envelope())));
        assertEquals(Set.of("b", "c"), fixture.deliveries().stream()
                .map(Delivery::destination).collect(java.util.stream.Collectors.toSet()));
        assertTrue(fixture.deliveries().stream().allMatch(delivery ->
                RoutingCodec.decodeForwarded(delivery.packet()).sourceServer().equals("a")));
    }

    @Test
    void clientsAndUnlistedBackendsCannotInjectTraffic() {
        Fixture fixture = fixture();
        PluginMessageEvent client = event(fixture.player(), fixture.player(),
                RoutingCodec.encodeUpstream("b", envelope()));
        fixture.listener().onPluginMessage(client);
        fixture.listener().onPluginMessage(event(fixture.connection("blocked"), fixture.player(),
                RoutingCodec.encodeUpstream("b", envelope())));
        assertFalse(client.getResult().isAllowed());
        assertTrue(fixture.deliveries().isEmpty());
        assertEquals(2, fixture.telemetry().count(RejectionReason.UNAUTHORISED_BACKEND));
    }

    @Test
    void malformedUnknownDestinationAndOversizeAreDropped() {
        Fixture fixture = fixture();
        fixture.listener().onPluginMessage(event(fixture.connection("a"), fixture.player(), new byte[]{1, 2}));
        fixture.listener().onPluginMessage(event(fixture.connection("a"), fixture.player(),
                RoutingCodec.encodeUpstream("missing", envelope())));
        byte[] oversized = new byte[fixture.config().maxPacketBytes() + 1];
        fixture.listener().onPluginMessage(event(fixture.connection("a"), fixture.player(), oversized));
        assertTrue(fixture.deliveries().isEmpty());
        assertEquals(2, fixture.telemetry().count(RejectionReason.MALFORMED_PACKET));
        assertEquals(1, fixture.telemetry().count(RejectionReason.UNKNOWN_DESTINATION));
    }

    @Test
    void oversizedInputIsRejectedBeforeRateTokensAreConsumed() {
        VelocityConfig config = new VelocityConfig(Set.of("a", "b", "c"), 100, 1, 1,
                1024, 1024, 1, 1, Duration.ofSeconds(300), Duration.ofSeconds(30));
        Fixture fixture = fixture(config);
        fixture.listener().onPluginMessage(event(fixture.connection("a"), fixture.player(), new byte[101]));
        fixture.listener().onPluginMessage(event(fixture.connection("a"), fixture.player(),
                RoutingCodec.encodeUpstream("b", envelope())));
        assertEquals(1, fixture.deliveries().size());
    }

    @Test
    void unrelatedChannelsAreNotConsumed() {
        Fixture fixture = fixture();
        PluginMessageEvent event = new PluginMessageEvent(fixture.connection("a"), fixture.player(),
                MinecraftChannelIdentifier.from("other:channel"), new byte[0]);
        fixture.listener().onPluginMessage(event);
        assertTrue(event.getResult().isAllowed());
    }

    private static Fixture fixture() {
        return fixture(config(Set.of("a", "b", "c"), 32766, 100, 200));
    }

    private static Fixture fixture(VelocityConfig config) {
        List<Delivery> deliveries = new ArrayList<>();
        Map<String, RegisteredServer> servers = Map.of(
                "a", server("a", deliveries),
                "b", server("b", deliveries),
                "c", server("c", deliveries),
                "blocked", server("blocked", deliveries));
        ProxyServer proxy = stub(ProxyServer.class, (method, arguments) -> switch (method.getName()) {
            case "getServer" -> Optional.ofNullable(servers.get((String) arguments[0]));
            case "getAllServers" -> servers.values();
            default -> objectMethod(method, arguments);
        });
        SecurityTelemetry telemetry = new SecurityTelemetry(Duration.ofDays(1), ignored -> { });
        RelayListener listener = new RelayListener(proxy, config, new RateLimiterRegistry(config), telemetry);
        Player player = stub(Player.class, RelaySecurityTest::objectMethod);
        return new Fixture(config, listener, telemetry, deliveries, servers, player);
    }

    private static RegisteredServer server(String name, List<Delivery> deliveries) {
        ServerInfo info = new ServerInfo(name, new InetSocketAddress("127.0.0.1", 25565));
        return stub(RegisteredServer.class, (method, arguments) -> switch (method.getName()) {
            case "getServerInfo" -> info;
            case "sendPluginMessage" -> {
                deliveries.add(new Delivery(name, ((byte[]) arguments[1]).clone()));
                yield true;
            }
            default -> objectMethod(method, arguments);
        });
    }

    private static PluginMessageEvent event(ChannelMessageSource source, Player target, byte[] data) {
        return new PluginMessageEvent(source, target, RelayListener.CHANNEL, data);
    }

    private static byte[] envelope() {
        ByteBuffer buffer = ByteBuffer.allocate(WireCodec.MIN_ENVELOPE_SIZE);
        Protocol.writePrefix(buffer);
        return buffer.array();
    }

    private static VelocityConfig config(Set<String> servers, int maximum, int rate, int burst) {
        return new VelocityConfig(servers, maximum, rate, burst, 1048576, 2097152,
                10, 20, Duration.ofSeconds(300), Duration.ofSeconds(30));
    }

    private static <T> T stub(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, arguments) -> invocation.invoke(method, arguments == null ? new Object[0] : arguments)));
    }

    private static Object objectMethod(Method method, Object[] arguments) {
        return switch (method.getName()) {
            case "toString" -> "stub";
            case "hashCode" -> 1;
            case "equals" -> false;
            default -> throw new AssertionError("Unexpected call: " + method);
        };
    }

    private record Delivery(String destination, byte[] packet) {
    }

    private record Fixture(VelocityConfig config, RelayListener listener, SecurityTelemetry telemetry,
                           List<Delivery> deliveries, Map<String, RegisteredServer> servers, Player player) {
        ServerConnection connection(String name) {
            RegisteredServer server = servers.get(name);
            return stub(ServerConnection.class, (method, arguments) -> switch (method.getName()) {
                case "getServerInfo" -> server.getServerInfo();
                case "getServer" -> server;
                default -> objectMethod(method, arguments);
            });
        }
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(Method method, Object[] arguments);
    }
}
