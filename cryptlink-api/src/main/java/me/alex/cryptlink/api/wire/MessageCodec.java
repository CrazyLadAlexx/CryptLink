package me.alex.cryptlink.api.wire;

import me.alex.cryptlink.api.SecureMessage;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

public final class MessageCodec {
    private MessageCodec() {
    }

    public static byte[] encode(SecureMessage message) {
        byte[] source = message.sourceServer().getBytes(StandardCharsets.UTF_8);
        byte[] target = message.targetServer().getBytes(StandardCharsets.UTF_8);
        byte[] topic = encodeText(message.topic());
        byte[] payload = message.payload();
        int size = 3 * Short.BYTES + source.length + target.length + topic.length + payload.length;
        int routingSize = Short.BYTES + target.length;
        if (size > RoutingCodec.MAX_PACKET_SIZE - routingSize - WireCodec.MIN_ENVELOPE_SIZE) {
            throw new IllegalArgumentException("Message exceeds the plugin message limit");
        }
        return ByteBuffer.allocate(size)
                .putShort((short) source.length).put(source)
                .putShort((short) target.length).put(target)
                .putShort((short) topic.length).put(topic)
                .put(payload).array();
    }

    public static SecureMessage decode(byte[] plaintext) {
        if (plaintext.length > RoutingCodec.MAX_PACKET_SIZE) {
            throw new IllegalArgumentException("Message exceeds the plugin message limit");
        }
        ByteBuffer buffer = ByteBuffer.wrap(plaintext);
        String source = readText(buffer, 64);
        String target = readText(buffer, 64);
        String topic = readText(buffer, 1024);
        byte[] payload = new byte[buffer.remaining()];
        buffer.get(payload);
        return new SecureMessage(source, target, topic, payload);
    }

    private static byte[] encodeText(String text) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(text));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("Invalid UTF-8 text", exception);
        }
    }

    private static String readText(ByteBuffer buffer, int maxLength) {
        if (buffer.remaining() < Short.BYTES) {
            throw new IllegalArgumentException("Missing text length");
        }
        int length = Short.toUnsignedInt(buffer.getShort());
        if (length < 1 || length > maxLength || length > buffer.remaining()) {
            throw new IllegalArgumentException("Invalid text length");
        }
        ByteBuffer text = buffer.slice();
        text.limit(length);
        buffer.position(buffer.position() + length);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(text).toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("Invalid UTF-8 text", exception);
        }
    }
}
