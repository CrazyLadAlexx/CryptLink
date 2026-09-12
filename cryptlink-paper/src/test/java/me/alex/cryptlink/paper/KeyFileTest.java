package me.alex.cryptlink.paper;

import me.alex.cryptlink.api.crypto.KeyRing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KeyFileTest {
    @TempDir
    Path directory;

    @Test
    void migrationPreservesExistingProductionKeyAndExplicitVersion() throws Exception {
        Path file = directory.resolve("keys.yml");
        Map<Integer, String> legacy = Map.of(3, key(3), 9, key(9));
        KeyRing migrated = KeyFile.load(file, legacy, 3, Logger.getAnonymousLogger());
        String saved = Files.readString(file);
        KeyRing reloaded = KeyFile.load(file, Map.of(7, key(7)), 7, Logger.getAnonymousLogger());
        assertEquals(saved, Files.readString(file));
        assertEquals(3, migrated.currentVersion());
        assertEquals(3, reloaded.currentVersion());
        assertArrayEquals(migrated.current("a").key().getEncoded(), reloaded.current("a").key().getEncoded());
        assertFalse(saved.contains("BASE64_32_BYTE_KEY"));
    }

    @Test
    void newInstallGeneratesOnceAndNeverOverwrites() throws Exception {
        Path file = directory.resolve("keys.yml");
        KeyRing first = KeyFile.load(file, Map.of(), null, Logger.getAnonymousLogger());
        String contents = Files.readString(file);
        KeyRing second = KeyFile.load(file, Map.of(), null, Logger.getAnonymousLogger());
        assertEquals(contents, Files.readString(file));
        assertArrayEquals(first.current("a").key().getEncoded(), second.current("a").key().getEncoded());
    }

    @Test
    void malformedDuplicateUnknownAndInvalidKeyFilesFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> KeyFile.parse("current-key-version: 1\ncurrent-key-version: 1\nkeys:\n  1: x\n"));
        assertThrows(IllegalArgumentException.class,
                () -> KeyFile.parse("current-key-version: 1\nkeys:\n  1: x\n  1: y\n"));
        assertThrows(IllegalArgumentException.class,
                () -> KeyFile.parse("current-key-version: 1\nkeys:\n  1: x\nkeys:\n  2: y\n"));
        assertThrows(IllegalArgumentException.class,
                () -> KeyFile.parse("current-key-version: 1\nunknown: value\nkeys:\n  1: x\n"));
        assertThrows(IllegalArgumentException.class,
                () -> new KeyRing(KeyFile.parse("current-key-version: 2\nkeys:\n  1: bad\n").keys(), 2));
    }

    @Test
    void legacyPaperConfigurationMigratesWithoutChangingKeyMaterial() throws Exception {
        String secret = key(4);
        Files.writeString(directory.resolve("config.yml"), """
                server-name: survival
                keys:
                  4: "%s"
                current-key-version: 4
                replay-window-seconds: 30
                clock-skew-seconds: 10
                """.formatted(secret));
        PaperConfig migrated = PaperConfig.load(directory, Logger.getAnonymousLogger());
        assertEquals(4, migrated.keyRing().currentVersion());
        assertFalse(Files.readString(directory.resolve("config.yml")).contains(secret));
        String keyFile = Files.readString(directory.resolve("keys.yml"));
        assertEquals(secret, KeyFile.parse(keyFile).keys().get(4));
        PaperConfig reloaded = PaperConfig.load(directory, Logger.getAnonymousLogger());
        assertArrayEquals(migrated.keyRing().current("survival").key().getEncoded(),
                reloaded.keyRing().current("survival").key().getEncoded());
    }

    private static String key(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
