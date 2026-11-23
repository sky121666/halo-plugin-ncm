package cn.sky.ncm.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretBoxTest {

    static byte[] key(int b) {
        byte[] k = new byte[32];
        Arrays.fill(k, (byte) b);
        return k;
    }

    @Test
    void roundTrip() {
        var box = new SecretBox(key(1), "test");
        String enc = box.encrypt("MUSIC_U=abc; __csrf=def");
        assertTrue(enc.startsWith(SecretBox.PREFIX));
        assertNotEquals(enc, box.encrypt("MUSIC_U=abc; __csrf=def"), "每次 iv 不同");
        assertEquals("MUSIC_U=abc; __csrf=def", box.decrypt(enc));
    }

    @Test
    void legacyPlaintextPassesThrough() {
        var box = new SecretBox(key(1), "test");
        assertEquals("MUSIC_U=abc", box.decrypt("MUSIC_U=abc"));
        assertEquals(null, box.decrypt(null));
        assertEquals("", box.encrypt(""));
    }

    @Test
    void wrongKeyFails() {
        String enc = new SecretBox(key(1), "test").encrypt("secret");
        assertThrows(SecretBox.SecretException.class, () -> new SecretBox(key(2), "test").decrypt(enc));
        assertThrows(SecretBox.SecretException.class, () -> new SecretBox(key(1), "test").decrypt(SecretBox.PREFIX + "!!"));
    }

    @Test
    void keyFileIsCreatedOnceAndReused(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("plugin-ncm/secret.key");
        byte[] k1 = SecretBox.loadOrCreate(f);
        byte[] k2 = SecretBox.loadOrCreate(f);
        assertEquals(32, k1.length);
        assertTrue(Arrays.equals(k1, k2));
        assertTrue(Files.size(f) > 0);
    }
}
