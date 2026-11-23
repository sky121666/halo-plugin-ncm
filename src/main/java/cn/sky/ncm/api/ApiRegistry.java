package cn.sky.ncm.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class ApiRegistry {

    private final Map<String, ApiDef> apis;

    public ApiRegistry() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("ncm-apis.json")) {
            if (in == null) {
                throw new IllegalStateException("ncm-apis.json not found");
            }
            JsonNode root = new ObjectMapper().readTree(in);
            Map<String, ApiDef> map = new LinkedHashMap<>();
            root.fields().forEachRemaining(e -> {
                JsonNode n = e.getValue();
                Map<String, String> fields = new LinkedHashMap<>();
                n.path("fields").fields().forEachRemaining(f -> fields.put(f.getKey(), f.getValue().asText()));
                List<String[]> pre = new ArrayList<>();
                n.path("pre").forEach(p -> pre.add(new String[] {p.path(0).asText(), p.path(1).asText()}));
                map.put(e.getKey(), new ApiDef(e.getKey(), n.path("tier").asText("public"),
                    n.path("status").asText("manual"), n.path("uri").asText(null),
                    n.path("crypto").asText(""), n.path("checkToken").asText(""), fields, pre,
                    n.path("post").asText(null)));
            });
            this.apis = Collections.unmodifiableMap(map);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 支持 song_url_v1、song-url-v1 两种写法。 */
    public Optional<ApiDef> find(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(apis.get(name.replace('-', '_')));
    }

    public Collection<ApiDef> all() {
        return apis.values();
    }
}
