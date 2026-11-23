package cn.sky.ncm.api.more;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

class MoreApisTest {

    @Test
    void artistInitial() {
        assertEquals(65L, SimpleApis.artistInitial("a"));
        assertNull(SimpleApis.artistInitial(null));
        assertEquals("", SimpleApis.artistInitial(""));
        assertEquals("1", SimpleApis.artistInitial("1"));
    }

    @Test
    void toBoolean() {
        assertEquals(true, SimpleApis.toBoolean("true"));
        assertEquals(true, SimpleApis.toBoolean("1"));
        assertEquals(false, SimpleApis.toBoolean(null));
        assertEquals("", SimpleApis.toBoolean(""));
    }

    @Test
    void dupkeyLooksLikeUuidV4() {
        String k = UploadApis.dupkey();
        assertTrue(k.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"), k);
    }

    @Test
    void encodeUri() {
        assertEquals("rpc://playlist/import?text=%E6%99%B4%E5%A4%A9%20a", Support.encodeURI("rpc://playlist/import?text=晴天 a"));
    }

    @Test
    void id3() throws Exception {
        var frames = new ByteArrayOutputStream();
        frame(frames, "TIT2", "晴天");
        frame(frames, "TPE1", "周杰伦");
        byte[] f = frames.toByteArray();
        var tag = new ByteArrayOutputStream();
        tag.write(new byte[] {'I', 'D', '3', 3, 0, 0});
        tag.write(new byte[] {0, 0, (byte) (f.length >> 7), (byte) (f.length & 0x7f)});
        tag.write(f);
        var meta = Id3.read(tag.toByteArray());
        assertEquals("晴天", meta.get("title"));
        assertEquals("周杰伦", meta.get("artist"));
    }

    static void frame(ByteArrayOutputStream out, String id, String text) throws Exception {
        byte[] t = text.getBytes(UTF_8);
        int len = t.length + 1;
        out.write(id.getBytes(UTF_8));
        out.write(new byte[] {(byte) (len >> 24), (byte) (len >> 16), (byte) (len >> 8), (byte) len, 0, 0, 3});
        out.write(t);
    }
}
