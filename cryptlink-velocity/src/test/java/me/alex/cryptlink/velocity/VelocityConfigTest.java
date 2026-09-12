package me.alex.cryptlink.velocity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VelocityConfigTest {
    private static final String VALID = """
            allowed-servers:
              - "survival"
              - lobby
            max-packet-bytes: 32766
            messages-per-second: 100
            message-burst: 200
            bytes-per-second: 1048576
            byte-burst: 2097152
            broadcasts-per-second: 10
            broadcast-burst: 20
            rate-limit-idle-seconds: 300
            telemetry-log-interval-seconds: 30
            """;

    @Test
    void strictConfigurationAcceptsCompleteValidInput() {
        VelocityConfig config = VelocityConfig.parse(VALID);
        assertEquals(2, config.allowedServers().size());
        assertEquals(100, config.messagesPerSecond());
    }

    @Test
    void duplicateMissingUnknownAndHugeValuesFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> VelocityConfig.parse(VALID + "messages-per-second: 1\n"));
        assertEquals(200, VelocityConfig.parse(VALID.replace("message-burst: 200\n", "")).messageBurst());
        assertThrows(IllegalArgumentException.class,
                () -> VelocityConfig.parse(VALID + "unknown: 1\n"));
        assertThrows(IllegalArgumentException.class,
                () -> VelocityConfig.parse(VALID.replace("messages-per-second: 100", "messages-per-second: 2147483647")));
        assertThrows(IllegalArgumentException.class,
                () -> VelocityConfig.parse(VALID.replace("  - lobby\n", "  - survival\n")));
    }
}
