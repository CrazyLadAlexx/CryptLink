package me.alex.cryptlink.api.wire;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

public final class RoutingCodec {
    public static final String CHANNEL = "cryptlink:secure";
    public static final String BROADCAST = "*";
    public static final int MAX_PACKET_SIZE = 32766;
    public static final int MAX_SERVER_NAME_BYTES = 64;
    private static final int ROUTE_HEADER_SIZE = Protocol.PREFIX_SIZE + 2;
    private static final int FORWARD_HEADER_SIZE = Protocol.PREFIX_SIZE + 1;
    private static final int UNICAST = 0;
    private static final int BROADCAST_KIND = 1;
    private static final Pattern SERVER_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    private RoutingCodec() {
    }

    public static byte[] encodeUpstream(String targetServer, byte[] envelope) {
        validateTarget(targetServer);
        validateEnvelope(envelope);
        boolean broadcast = BROADCAST.equals(targetServer);
        byte[] target = broadcast ? new byte[0] : targetServer.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer buffer = allocate(ROUTE_HEADER_SIZE + target.length + envelope.length);
        Protocol.writePrefix(buffer);
        return buffer.put((byte) (broadcast ? BROADCAST_KIND : UNICAST))
                .put((byte) target.length).put(target).put(envelope).array();
    }

    public static UpstreamPacket decodeUpstream(byte[] packet, int maximumSize) {
        ByteBuffer buffer = packet(packet, ROUTE_HEADER_SIZE + WireCodec.MIN_ENVELOPE_SIZE, maximumSize);
        Protocol.readPrefix(buffer);
        int kind = Byte.toUnsignedInt(buffer.get());
        int targetLength = Byte.toUnsignedInt(buffer.get());
        if (kind != UNICAST && kind != BROADCAST_KIND) {
            throw new IllegalArgumentException("Invalid route kind");
        }
        if ((kind == BROADCAST_KIND && targetLength != 0)
                || (kind == UNICAST && (targetLength < 1 || targetLength > MAX_SERVER_NAME_BYTES))) {
            throw new IllegalArgumentException("Invalid route target length");
        }
        String target = kind == BROADCAST_KIND ? BROADCAST : readServer(buffer, targetLength);
        return new UpstreamPacket(target, readEnvelope(buffer));
    }

    public static byte[] encodeForwarded(String sourceServer, byte[] envelope) {
        validateServerName(sourceServer);
        validateEnvelope(envelope);
        byte[] source = sourceServer.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer buffer = allocate(FORWARD_HEADER_SIZE + source.length + envelope.length);
        Protocol.writePrefix(buffer);
        return buffer.put((byte) source.length).put(source).put(envelope).array();
    }

    public static ForwardedPacket decodeForwarded(byte[] packet) {
        ByteBuffer buffer = packet(packet, FORWARD_HEADER_SIZE + 1 + WireCodec.MIN_ENVELOPE_SIZE, MAX_PACKET_SIZE);
        Protocol.readPrefix(buffer);
        int sourceLength = Byte.toUnsignedInt(buffer.get());
        if (sourceLength < 1 || sourceLength > MAX_SERVER_NAME_BYTES) {
            throw new IllegalArgumentException("Invalid transport source length");
        }
        String source = readServer(buffer, sourceLength);
        return new ForwardedPacket(source, readEnvelope(buffer));
    }

    public static void validateServerName(String name) {
        if (!SERVER_NAME.matcher(Objects.requireNonNull(name, "server name")).matches()) {
            throw new IllegalArgumentException("Server name must contain 1 to 64 ASCII letters, digits, dots, underscores or hyphens");
        }
    }

    public static void validateTarget(String target) {
        if (!BROADCAST.equals(target)) {
            validateServerName(target);
        }
    }

    private static ByteBuffer packet(byte[] packet, int minimumSize, int maximumSize) {
        Objects.requireNonNull(packet, "packet");
        if (maximumSize < minimumSize || maximumSize > MAX_PACKET_SIZE
                || packet.length < minimumSize || packet.length > maximumSize) {
            throw new IllegalArgumentException("Invalid routed packet length");
        }
        return ByteBuffer.wrap(packet);
    }

    private static String readServer(ByteBuffer buffer, int length) {
        if (buffer.remaining() < length + WireCodec.MIN_ENVELOPE_SIZE) {
            throw new IllegalArgumentException("Truncated routed packet");
        }
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        String server = new String(bytes, StandardCharsets.US_ASCII);
        validateServerName(server);
        if (!Arrays.equals(bytes, server.getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("Server name is not ASCII");
        }
        return server;
    }

    private static byte[] readEnvelope(ByteBuffer buffer) {
        if (buffer.remaining() < WireCodec.MIN_ENVELOPE_SIZE) {
            throw new IllegalArgumentException("Truncated encrypted envelope");
        }
        byte[] envelope = new byte[buffer.remaining()];
        buffer.get(envelope);
        return envelope;
    }

    private static void validateEnvelope(byte[] envelope) {
        Objects.requireNonNull(envelope, "envelope");
        if (envelope.length < WireCodec.MIN_ENVELOPE_SIZE) {
            throw new IllegalArgumentException("Encrypted envelope is truncated");
        }
    }

    private static ByteBuffer allocate(int size) {
        if (size > MAX_PACKET_SIZE) {
            throw new IllegalArgumentException("Packet exceeds the plugin message limit");
        }
        return ByteBuffer.allocate(size);
    }

    public record UpstreamPacket(String targetServer, byte[] envelope) {
        public UpstreamPacket {
            validateTarget(targetServer);
            envelope = envelope.clone();
        }

        @Override
        public byte[] envelope() {
            return envelope.clone();
        }
    }

    public record ForwardedPacket(String sourceServer, byte[] envelope) {
        public ForwardedPacket {
            validateServerName(sourceServer);
            envelope = envelope.clone();
        }

        @Override
        public byte[] envelope() {
            return envelope.clone();
        }
    }
}
