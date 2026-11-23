package cn.sky.ncm.crypto;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class NcblTest {

    static final HexFormat HEX = HexFormat.of();

    @Test
    void containerLayout() {
        byte[] keyA = HEX.parseHex("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20");
        byte[] uuid = HEX.parseHex("00112233445566778899aabbccddeeff");
        byte[] meta = "{\"MUSIC_U\":\"x\"}".getBytes(UTF_8);
        byte[] body = new byte[0x8000 + 10];
        Arrays.fill(body, (byte) 7);

        byte[] out = Ncbl.encrypt(meta, body, keyA, uuid, 100);
        ByteBuffer b = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals("NCBL", new String(out, 0, 4, UTF_8));
        assertEquals(3, b.getInt(4));
        int headerLen = b.getShort(8) & 0xffff;
        assertEquals(70 + 4 + meta.length, headerLen);
        assertArrayEquals(uuid, Arrays.copyOfRange(out, 10, 26));
        byte[] keyB = Arrays.copyOfRange(out, 26, 58);
        assertArrayEquals(Ncbl.rsaWrap(keyA), keyB);
        assertEquals(100, b.getInt(58));
        assertEquals(101, b.getInt(62)); // 两帧
        assertEquals(out.length - headerLen, b.getInt(66));

        byte[] nonce = Arrays.copyOfRange(uuid, 0, 12);
        int counter = (int) ((b.getInt(10 + 12) & 0xffffffffL) >>> 2);
        assertEquals(0x4343, b.getShort(70) & 0xffff);
        byte[] metaCipher = Arrays.copyOfRange(out, 74, 74 + meta.length);
        assertArrayEquals(meta, Ncbl.chacha20(keyB, counter, nonce, metaCipher));

        int pos = headerLen;
        int len1 = b.getShort(pos) & 0xffff;
        assertEquals(0x8000, len1);
        assertEquals(100, b.getInt(pos + 2));
        byte[] f1 = Ncbl.chacha20(keyA, counter, nonce, Arrays.copyOfRange(out, pos + 6, pos + 6 + len1));
        assertArrayEquals(Arrays.copyOfRange(body, 0, 0x8000), f1);
    }

    @Test
    void matchesUpstreamVectors() throws Exception {
        InputStream in = getClass().getClassLoader().getResourceAsStream("vectors.json");
        assumeTrue(in != null, "未找到 vectors.json，跳过对比测试");
        JsonNode v = new ObjectMapper().readTree(in).path("ncbl");
        assumeTrue(!v.isMissingNode(), "vectors.json 里没有 ncbl 段");
        assertEquals(v.path("keyB").asText(), HEX.formatHex(Ncbl.rsaWrap(HEX.parseHex(v.path("keyA").asText()))));
        JsonNode c = v.path("chacha");
        assertEquals(c.path("cipher").asText(), HEX.formatHex(Ncbl.chacha20(HEX.parseHex(c.path("key").asText()),
            c.path("counter").asInt(), HEX.parseHex(c.path("nonce").asText()), HEX.parseHex(c.path("plain").asText()))));
    }
}
