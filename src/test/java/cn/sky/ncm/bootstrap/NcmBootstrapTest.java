package cn.sky.ncm.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class NcmBootstrapTest {

    @Test
    void encodeIdMatchesUpstream() throws Exception {
        InputStream in = getClass().getClassLoader().getResourceAsStream("vectors.json");
        assumeTrue(in != null, "未找到 vectors.json，跳过对比测试");
        JsonNode root = new ObjectMapper().readTree(in);
        for (JsonNode v : root.path("encodeId")) {
            assertEquals(v.path("encoded").asText(), NcmBootstrap.encodeId(v.path("deviceId").asText()));
        }
    }
}
