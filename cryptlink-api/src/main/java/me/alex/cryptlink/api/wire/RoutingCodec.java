package me.alex.cryptlink.api.wire;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

public final class RoutingCodec {
    public static final String CHANNEL = "cryptlink:secure";
    public static final String BROADCAST = "*";
    public static final int MAX_PACKET_SIZE = 32766;
    private static final Pattern SERVER_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    private RoutingCodec() {
    }

    public static byte[] encode(String targetServer, byte[] envelope) {
        validateTarget(targetServer);
        Objects.requireNonNull(envelope, "envelope");
        byte[] target = targetServer.getBytes(StandardCharsets.US_ASCII);
        int size = Short.BYTES + target.length + envelope.length;
        if (envelope.length < WireCodec.MIN_ENVELOPE_SIZE || size > MAX_PACKET_SIZE) {
            throw new IllegalArgumentException("Routed message exceeds the plugin message limit");
        }
        return ByteBuffer.allocate(size).putShort((short) target.length).put(target).put(envelope).array();
    }

    public static RoutedEnvelope decode(byte[] packet) {
        Objects.requireNonNull(packet, "packet");
        if (packet.length < Short.BYTES + 1 + WireCodec.MIN_ENVELOPE_SIZE
                || packet.length > MAX_PACKET_SIZE) {
            throw new IllegalArgumentException("Invalid routed message length");
        }
        ByteBuffer buffer = ByteBuffer.wrap(packet);
        int targetLength = Short.toUnsignedInt(buffer.getShort());
        if (targetLength < 1 || targetLength > 64
                || buffer.remaining() < targetLength + WireCodec.MIN_ENVELOPE_SIZE) {
            throw new IllegalArgumentException("Invalid target length");
        }
        byte[] target = new byte[targetLength];
        buffer.get(target);
        String targetServer = new String(target, StandardCharsets.US_ASCII);
        validateTarget(targetServer);
        byte[] envelope = new byte[buffer.remaining()];
        buffer.get(envelope);
        return new RoutedEnvelope(targetServer, envelope);
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

    public record RoutedEnvelope(String targetServer, byte[] envelope) {
        public RoutedEnvelope {
            validateTarget(targetServer);
            envelope = envelope.clone();
        }

        @Override
        public byte[] envelope() {
            return envelope.clone();
        }
    }
}
