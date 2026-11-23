package cn.sky.ncm.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ApiRegistryTest {

    @Test
    void allReadyDescriptorsEvaluate() {
        var registry = new ApiRegistry();
        assertEquals(459, registry.all().size());
        registry.all().stream().filter(d -> "ready".equals(d.status())).forEach(d -> {
            Map<String, Object> q = new HashMap<>(Map.of("id", "1", "uid", "1", "keywords", "a", "limit", "10",
                "type", "0", "t", "1"));
            assertDoesNotThrow(() -> {
                for (String[] p : d.pre()) {
                    q.put(p[0], ExprEval.eval(p[1], q));
                }
                ExprEval.template(d.uri(), q);
                d.fields().values().forEach(v -> ExprEval.eval(v, q));
            }, d.name());
            assertTrue(d.uri().startsWith("/api/"), d.name() + " uri");
        });
    }

    /**
     * 会修改数据的接口不能是 public / personal：这两个等级允许 GET 调用（可能被跨站链接触发），public 还允许匿名访问。
     * 规则：接口地址里有一段是明确的修改动作（update、delete、create、submit……）。读取类接口如果被误判，加进 READ_ONLY。
     */
    static final Pattern MUTATING_SEGMENT = Pattern.compile(
        "^(submit|replace\\w*|update\\w*|delete\\w*|del|remove\\w*|create|upload\\w*|trash|signin|kickoff|obtain|add|send|bind"
            + "|unbind|edit|modify|set|clear|reset|logout)$", Pattern.CASE_INSENSITIVE);
    static final Set<String> READ_ONLY = Set.of();

    @Test
    void mutatingApisAreNotPublicOrPersonal() {
        List<String> wrong = new ArrayList<>();
        new ApiRegistry().all().forEach(d -> {
            if (!("public".equals(d.tier()) || "personal".equals(d.tier())) || d.uri() == null
                || READ_ONLY.contains(d.name())) {
                return;
            }
            for (String seg : d.uri().split("/")) {
                if (!seg.contains("${") && MUTATING_SEGMENT.matcher(seg).matches()) {
                    wrong.add(d.name() + " " + d.uri());
                    return;
                }
            }
        });
        assertTrue(wrong.isEmpty(), "这些接口会修改数据，等级应为 write：" + wrong);
    }

    @Test
    void knownMisclassificationsAreFixed() {
        var registry = new ApiRegistry();
        for (String name : List.of("user_replacephone", "playlist_privacy", "yunbei_rcmd_song")) {
            assertEquals("write", registry.find(name).orElseThrow().tier(), name);
        }
        assertFalse(registry.find("song_like_check").map(d -> "write".equals(d.tier())).orElse(true));
    }
}
