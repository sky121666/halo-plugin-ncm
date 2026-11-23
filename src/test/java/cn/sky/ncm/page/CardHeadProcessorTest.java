package cn.sky.ncm.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CardHeadProcessorTest {

    @Test
    void onlyPostsAndPages() {
        assertTrue(CardHeadProcessor.shouldInject("post"));
        assertTrue(CardHeadProcessor.shouldInject("page"));
        assertTrue(CardHeadProcessor.shouldInject("single-page"));
        assertTrue(CardHeadProcessor.shouldInject(" post "));
        assertFalse(CardHeadProcessor.shouldInject(null));
        assertFalse(CardHeadProcessor.shouldInject(""));
        assertFalse(CardHeadProcessor.shouldInject("index"));
        assertFalse(CardHeadProcessor.shouldInject("archives"));
        assertFalse(CardHeadProcessor.shouldInject("category"));
        assertFalse(CardHeadProcessor.shouldInject("tag"));
        assertFalse(CardHeadProcessor.shouldInject("gateway_modules/login"));
        assertFalse(CardHeadProcessor.shouldInject("plugin:plugin-ncm:music-song"));
        assertFalse(CardHeadProcessor.shouldInject("posts"));
    }

    @Test
    void assets() {
        Map<String, String> link = CardHeadProcessor.linkAttributes("?v=1.2.0");
        assertEquals("stylesheet", link.get("rel"));
        assertEquals("/plugins/plugin-ncm/assets/static/card.css?v=1.2.0", link.get("href"));
        Map<String, String> script = CardHeadProcessor.scriptAttributes("?v=1.2.0");
        assertEquals("/plugins/plugin-ncm/assets/static/card.js?v=1.2.0", script.get("src"));
        assertTrue(script.containsKey("defer"));
        assertEquals("/music/card/", script.get("data-endpoint"));
    }
}
