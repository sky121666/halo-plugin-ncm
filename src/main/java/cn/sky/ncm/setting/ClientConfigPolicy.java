package cn.sky.ncm.setting;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;
import tools.jackson.databind.json.JsonMapper;

/** 每个客户端声明可同步字段、字段约束和可公开发布的范围。 */
@Component
public class ClientConfigPolicy {

    private static final Pattern CLIENT_ID = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
    private static final Pattern FIELD = Pattern.compile("[A-Za-z0-9_.-]{1,100}");
    private static final Pattern UNSAFE_VALUE = Pattern.compile(
        "(?i)(?:\\b(?:https?|file|blob|data|javascript|vbscript):|(?:url|expression)\\s*\\(|[<>]|\\p{Cntrl})");
    private static final Set<String> RESERVED = Set.of("proto", "prototype", "constructor", "cookie", "cookies",
        "authorization", "apikey", "accesstoken", "refreshtoken", "authtoken", "token", "secret", "password",
        "permissions", "privileges", "canpublishsite", "canviewdiagnostics", "debug", "developer", "consolelog");
    private final Map<String, Policy> clients = new ConcurrentHashMap<>();

    Policy resolve(String clientId) {
        if (!CLIENT_ID.matcher(clientId).matches()) {
            throw new ServerWebInputException("客户端标识无效");
        }
        return clients.computeIfAbsent(clientId, this::load);
    }

    private Policy load(String clientId) {
        try (InputStream source = getClass().getResourceAsStream("/config-clients/" + clientId + ".json")) {
            if (source == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "客户端尚未注册配置协议");
            }
            return JsonMapper.shared().readValue(source, Policy.class);
        } catch (IOException error) {
            throw new IllegalStateException("无法读取客户端配置协议 " + clientId, error);
        }
    }

    record Policy(int schemaVersion, Map<String, Map<String, String>> groups, Set<String> personalOnly) {
        void validate(int version, Map<String, Object> config, boolean site) {
            if (version != schemaVersion || config == null) {
                throw new ServerWebInputException("配置格式版本不受支持");
            }
            validateTree(config, 0);
            config.forEach((groupKey, value) -> {
                Map<String, String> rules = groups.get(groupKey);
                if (rules == null || !(value instanceof Map<?, ?> fields)) {
                    throw invalid(groupKey);
                }
                fields.forEach((key, field) -> {
                    String name = groupKey + "." + key;
                    if (site && personalOnly.contains(name) || !matches(rules.get(key), field)) {
                        throw invalid(name);
                    }
                });
            });
        }

        private static boolean matches(String rule, Object value) {
            if (rule == null) {
                return false;
            }
            if (rule.endsWith("?")) {
                return value == null || matches(rule.substring(0, rule.length() - 1), value);
            }
            if (rule.startsWith("enum:")) {
                return value instanceof String text && Arrays.asList(rule.substring(5).split("\\|")).contains(text);
            }
            if (rule.startsWith("range:")) {
                var bounds = rule.substring(6).split(":");
                return value instanceof Number n && n.doubleValue() >= Double.parseDouble(bounds[0])
                    && n.doubleValue() <= Double.parseDouble(bounds[1]);
            }
            return switch (rule) {
                case "bool" -> value instanceof Boolean;
                case "number" -> value instanceof Number;
                case "text" -> value instanceof String;
                case "object" -> value instanceof Map<?, ?>;
                case "list" -> value instanceof List<?>;
                case "palette" -> palette(value);
                case "json" -> true;
                default -> false;
            };
        }

        private static boolean palette(Object value) {
            if (!(value instanceof Map<?, ?> palette) || !palette.keySet().equals(Set.of("light", "dark"))) {
                return false;
            }
            Set<String> colors = Set.of("backgroundColor", "primaryColor", "accentColor", "secondaryColor");
            return palette.values().stream().allMatch(side -> {
                if (!(side instanceof Map<?, ?> theme)) {
                    return false;
                }
                return colors.stream().allMatch(key -> theme.get(key) instanceof String)
                    && theme.get("name") instanceof String && theme.get("fontStyle") instanceof String
                    && theme.get("animationIntensity") instanceof String;
            });
        }

        private static void validateTree(Object value, int depth) {
            if (depth > 12) {
                throw new ServerWebInputException("配置层级过深");
            }
            if (value instanceof Map<?, ?> map) {
                if (map.size() > 160) {
                    throw new ServerWebInputException("配置字段过多");
                }
                map.forEach((key, child) -> {
                    if (!(key instanceof String name) || !FIELD.matcher(name).matches()
                        || RESERVED.contains(name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""))) {
                        throw new ServerWebInputException("配置包含保留字段");
                    }
                    validateTree(child, depth + 1);
                });
            } else if (value instanceof List<?> list) {
                if (list.size() > 128) {
                    throw new ServerWebInputException("配置列表过长");
                }
                list.forEach(child -> validateTree(child, depth + 1));
            } else if (value instanceof String text && (text.length() > 2048 || UNSAFE_VALUE.matcher(text).find())) {
                throw new ServerWebInputException("配置包含不支持的文本或资源地址");
            } else if (value instanceof Number n && !Double.isFinite(n.doubleValue())) {
                throw new ServerWebInputException("配置数值无效");
            }
        }

        private static ServerWebInputException invalid(String field) {
            return new ServerWebInputException("配置字段无效或不可发布：" + field);
        }
    }
}
