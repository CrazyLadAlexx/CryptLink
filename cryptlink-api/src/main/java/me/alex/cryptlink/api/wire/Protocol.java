package me.alex.cryptlink.api.wire;

import java.nio.ByteBuffer;

public final class Protocol {
    public static final int MAGIC = 0x43524c4b;
    public static final int VERSION = 2;
    public static final int PREFIX_SIZE = Integer.BYTES + Byte.BYTES;

    private Protocol() {
    }

    public static void writePrefix(ByteBuffer buffer) {
        buffer.putInt(MAGIC).put((byte) VERSION);
    }

    public static void readPrefix(ByteBuffer buffer) {
        if (buffer.remaining() < PREFIX_SIZE) {
            throw new IllegalArgumentException("Truncated protocol prefix");
        }
        if (buffer.getInt() != MAGIC) {
            throw new IllegalArgumentException("Invalid protocol magic");
        }
        int version = Byte.toUnsignedInt(buffer.get());
        if (version != VERSION) {
            throw new IllegalArgumentException("Unsupported protocol version: " + version);
        }
    }
}
