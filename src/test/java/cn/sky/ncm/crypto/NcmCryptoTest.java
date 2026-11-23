package cn.sky.ncm.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NcmCryptoTest {

    @Test
    void eapiRoundTrip() {
        String url = "/api/v1/album/123";
        String text = "{\"id\":\"123\",\"e_r\":false}";
        String params = NcmCrypto.eapi(url, text).get("params");
        String plain = NcmCrypto.eapiReqDecrypt(params);
        assertTrue(plain.startsWith(url + "-36cd479b6b5-" + text + "-36cd479b6b5-"));
        assertTrue(plain.endsWith(NcmCrypto.md5Hex("nobody" + url + "use" + text + "md5forencrypt")));
    }

    @Test
    void weapiShape() {
        Map<String, String> out = NcmCrypto.weapi("{}", "aaaaaaaaaaaaaaaa");
        assertEquals(256, out.get("encSecKey").length());
        assertTrue(out.get("params").endsWith("="));
    }

    /** 与 Node 上游逐字节对比。先运行 tools/gen-vectors.js 生成 src/test/resources/vectors.json。 */
    @Test
    void matchesUpstreamVectors() throws Exception {
        InputStream in = getClass().getClassLoader().getResourceAsStream("vectors.json");
        assumeTrue(in != null, "未找到 vectors.json，跳过对比测试");
        JsonNode root = new ObjectMapper().readTree(in);
        for (JsonNode c : root.path("weapi")) {
            Map<String, String> out = NcmCrypto.weapi(c.path("input").asText(), c.path("secretKey").asText());
            assertEquals(c.path("params").asText(), out.get("params"), "weapi params");
            assertEquals(c.path("encSecKey").asText(), out.get("encSecKey"), "weapi encSecKey");
        }
        for (JsonNode c : root.path("eapi")) {
            assertEquals(c.path("params").asText(),
                NcmCrypto.eapi(c.path("url").asText(), c.path("input").asText()).get("params"), "eapi");
        }
        for (JsonNode c : root.path("linuxapi")) {
            assertEquals(c.path("eparams").asText(), NcmCrypto.linuxapi(c.path("input").asText()).get("eparams"),
                "linuxapi");
        }
    }
}
