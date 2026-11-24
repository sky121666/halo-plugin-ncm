package cn.sky.ncm.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExprEvalTest {

    private final Map<String, Object> q = new HashMap<>(Map.of("id", "42", "limit", "10", "flag", "true", "t", "1",
        "type", "1"));

    @Test
    void basics() {
        assertEquals("42", ExprEval.eval("query.id", q));
        assertNull(ExprEval.eval("query.missing", q));
        assertEquals("10", ExprEval.eval("query.limit || 30", q));
        assertEquals(30L, ExprEval.eval("query.offset || 30", q));
        assertEquals(-1L, ExprEval.eval("query.offset || -1", q));
        assertEquals("all", ExprEval.eval("query.type2 || 'all'", q));
        assertEquals(true, ExprEval.eval("query.flag == 'true'", q));
        assertEquals(42L, ExprEval.eval("parseInt(query.id)", q));
        assertEquals("\"42\"", ExprEval.eval("JSON.stringify(query.id)", q));
        assertEquals("R_MV_5_42", ExprEval.eval("`R_MV_5_${query.id}`", q));
    }

    @Test
    void operators() {
        assertEquals("sub", ExprEval.eval("query.t == 1 ? 'sub' : 'unsub'", q));
        assertEquals("unsub", ExprEval.eval("query.t === 1 ? 'sub' : 'unsub'", q));
        assertEquals("R_MV_5_42", ExprEval.eval("resourceTypeMap[query.type] + query.id", q));
        assertEquals("R_SO_4_42", ExprEval.eval("'R_SO_4_' + query.id", q));
        assertEquals(604800000L, ExprEval.eval("7 * 24 * 3600 * 1000", q));
        assertEquals(false, ExprEval.eval("!query.flag", q));
        assertEquals(true, ExprEval.eval("isNaN(query.missing)", q));
    }

    @Test
    void objectLiteralsFollowJsonStringify() {
        assertEquals("{\"size\":\"10\",\"cursor\":0}",
            ExprEval.eval("JSON.stringify({ size: query.limit || 10, cursor: query.cursor || 0, x: query.none })", q));
        assertEquals("{\"地区\":\"全部\"}", ExprEval.eval("JSON.stringify({ 地区: query.area || '全部' })", q));
        assertEquals("[\"42\",1]", ExprEval.eval("JSON.stringify([query.id, 1])", q));
    }

    @Test
    void uriTemplate() {
        assertEquals("/api/v1/album/42", ExprEval.template("/api/v1/album/${query.id}", q));
        assertEquals("/api/v1/album/42", ExprEval.apiPath("/api/v1/album/${query.id}", q));
        assertThrows(IllegalArgumentException.class,
            () -> ExprEval.apiPath("/api/v1/album/${query.id}", Map.of("id", "two words")));
    }
}
