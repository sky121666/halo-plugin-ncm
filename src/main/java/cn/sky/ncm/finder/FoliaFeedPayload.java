package cn.sky.ncm.finder;

import cn.sky.ncm.finder.vo.SongVo;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Selects the catalogue fields used by Folia's NetEase feeds. */
public final class FoliaFeedPayload {

    private static final int MAX_ITEMS = 200;

    private FoliaFeedPayload() {
    }

    public static List<SongVo> songs(JsonNode body, String... paths) {
        return NcmVoMapper.songList(firstArray(body, paths), Map.of()).stream().limit(MAX_ITEMS).toList();
    }

    public static List<SongVo> dailySongs(JsonNode body) {
        JsonNode data = body.path("data");
        List<SongVo> songs = songs(data, "dailySongs", "songs");
        if (firstArray(data, "dailySongs", "songs").isArray()) {
            return songs;
        }
        JsonNode recommended = body.path("recommendData");
        return recommended.path("dailySongs").isArray()
            ? songs(recommended, "dailySongs") : songs(body, "songs", "recommend");
    }

    public static List<Map<String, Object>> collections(JsonNode rows) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (JsonNode row : rows) {
            if (!row.hasNonNull("id") || !row.hasNonNull("name")) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.path("id").asText());
            item.put("name", row.path("name").asText());
            for (String field : List.of("description", "copywriter", "updateFrequency", "ToplistType")) {
                copyText(row, item, field);
            }
            for (String field : List.of("size", "trackCount", "publishTime", "playCount", "playcount")) {
                if (row.path(field).isNumber()) {
                    item.put(field, row.path(field).asLong());
                }
            }
            for (String field : List.of("picUrl", "coverImgUrl")) {
                String url = NcmVoMapper.https(row.path(field).asText(null));
                if (url != null) {
                    item.put(field, url);
                }
            }
            item.put("artists", artists(firstArray(row, "artists", "ar")));
            JsonNode creator = row.path("creator");
            if (creator.hasNonNull("userId")) {
                item.put("creator", Map.of("id", creator.path("userId").asText(),
                    "nickname", creator.path("nickname").asText("")));
            }
            items.add(item);
            if (items.size() == MAX_ITEMS) {
                break;
            }
        }
        return items;
    }

    public static Map<String, Object> home(JsonNode body) {
        JsonNode data = body.path("data");
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (JsonNode block : data.path("blocks")) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (JsonNode creative : block.path("creatives")) {
                for (JsonNode resource : creative.path("resources")) {
                    if (items.size() < MAX_ITEMS) {
                        addResource(items, resource);
                    }
                }
            }
            if (!items.isEmpty()) {
                blocks.add(Map.of("blockCode", block.path("blockCode").asText(),
                    "uiElement", ui(block.path("uiElement")),
                    "creatives", List.of(Map.of("resources", items))));
            }
        }
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("blocks", blocks);
        JsonNode cursor = data.path("cursor");
        if (cursor.isTextual() || cursor.isObject()) {
            page.put("cursor", cursor.isTextual() ? cursor.asText() : cursor.toString());
        }
        page.put("hasMore", data.path("hasMore").asBoolean(false));
        return Map.of("data", page);
    }

    private static void addResource(List<Map<String, Object>> items, JsonNode resource) {
        String type = resource.path("resourceType").asText();
        JsonNode info = resource.path("resourceExtInfo");
        Map<String, Object> detail = new LinkedHashMap<>();
        if ("song".equals(type)) {
            JsonNode song = info.hasNonNull("songData") ? info.path("songData") : info.path("song");
            if (!song.hasNonNull("id")) {
                return;
            }
            detail.put("songData", NcmVoMapper.song(song, info.get("songPrivilege")));
        } else if ("album".equals(type)) {
            detail.put("artists", artists(info.path("artists")));
        } else if ("list".equals(type)) {
            detail.put("playCount", info.path("playCount").asLong());
        } else {
            return;
        }
        items.add(Map.of("resourceType", type, "resourceId", resource.path("resourceId").asText(),
            "uiElement", ui(resource.path("uiElement")), "resourceExtInfo", detail));
    }

    private static Map<String, Object> ui(JsonNode node) {
        Map<String, Object> ui = new LinkedHashMap<>();
        for (String field : List.of("mainTitle", "subTitle")) {
            String title = node.path(field).path("title").asText(null);
            if (title != null) {
                ui.put(field, Map.of("title", title));
            }
        }
        String cover = NcmVoMapper.https(node.path("image").path("imageUrl").asText(null));
        if (cover != null) {
            ui.put("image", Map.of("imageUrl", cover));
        }
        return ui;
    }

    private static List<Map<String, String>> artists(JsonNode rows) {
        List<Map<String, String>> artists = new ArrayList<>();
        for (JsonNode artist : rows) {
            if (artist.hasNonNull("id") && artist.hasNonNull("name")) {
                artists.add(Map.of("id", artist.path("id").asText(), "name", artist.path("name").asText()));
            }
        }
        return artists;
    }

    private static JsonNode firstArray(JsonNode node, String... fields) {
        for (String field : fields) {
            if (node.path(field).isArray()) {
                return node.path(field);
            }
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private static void copyText(JsonNode row, Map<String, Object> item, String field) {
        if (row.path(field).isTextual()) {
            item.put(field, row.path(field).asText());
        }
    }
}
