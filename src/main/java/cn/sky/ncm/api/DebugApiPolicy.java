package cn.sky.ncm.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.Locale;
import java.util.regex.Pattern;

/** 调试使用公开只读接口，并以 JSON 显示脱敏结果。 */
public final class DebugApiPolicy {

    private static final Pattern CREDENTIAL_KEY = Pattern.compile(
        "cookie|token|secret|password|authorization|sessionkey|music[au]|csrf|checktoken");
    private static final Pattern INLINE_CREDENTIAL = Pattern.compile(
        "(?i)\\b(?:MUSIC_[AU]|__csrf|cookie|authorization|token|password)\\s*[=:]");

    private DebugApiPolicy() {
    }

    public static boolean readable(ApiDef api) {
        return api != null && api.executable() && "public".equals(api.tier()) && !api.name().endsWith("_302");
    }

    public static JsonNode safeBody(JsonNode body) {
        if (body == null || body.isTextual()) {
            return JsonNodeFactory.instance.objectNode().put("message", "上游未返回结构化数据");
        }
        return removeCredentials(body);
    }

    private static JsonNode removeCredentials(JsonNode value) {
        if (value instanceof ObjectNode object) {
            var safe = JsonNodeFactory.instance.objectNode();
            object.properties().forEach(entry -> {
                String key = entry.getKey().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
                if (!CREDENTIAL_KEY.matcher(key).find()) {
                    safe.set(entry.getKey(), removeCredentials(entry.getValue()));
                }
            });
            return safe;
        }
        if (value instanceof ArrayNode array) {
            var safe = JsonNodeFactory.instance.arrayNode();
            array.forEach(item -> safe.add(removeCredentials(item)));
            return safe;
        }
        return value.isTextual() && INLINE_CREDENTIAL.matcher(value.asText()).find()
            ? TextNode.valueOf("[已隐藏凭据信息]") : value;
    }
}
