package cn.sky.ncm.page;

import cn.sky.ncm.client.Js;
import cn.sky.ncm.finder.vo.AlbumVo;
import cn.sky.ncm.setting.PageSetting;
import cn.sky.ncm.setting.PlayerSetting;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.util.HtmlUtils;
import reactor.core.publisher.Mono;

/** Folia 的入口由插件直接提供，静态资源经插件的 ReverseProxy 分发。 */
final class FoliaPage {
    private static final Pattern SLOT = Pattern.compile("__NCM_FOLIA_(CONFIG|TITLE|HEAD)__");
    private static final MediaType HTML = new MediaType("text", "html", StandardCharsets.UTF_8);
    private final String index;

    record Detail(String type, String path, String title, String description, String coverUrl,
                  String sameAs, Object item, Map<String, Object> attributes) {
    }

    FoliaPage() {
        try (var stream = FoliaPage.class.getResourceAsStream("/folia/index.html")) {
            index = stream == null ? null : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("无法读取 Folia Web 入口", e);
        }
    }

    Mono<ServerResponse> render(PageSetting page, PlayerSetting player, boolean search, String version) {
        return render(page, player, search, null, HttpStatus.OK, version);
    }

    Mono<ServerResponse> render(PageSetting page, PlayerSetting player, boolean search, Detail detail,
                                HttpStatus status, String version) {
        if (index == null) {
            return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Cache-Control", MusicPageRouter.NO_STORE)
                .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .bodyValue("音乐页面暂时不可用，请检查插件构建。");
        }
        String pageTitle = page.titleOrDefault();
        String title = detail != null ? detail.title() + " · " + pageTitle
            : search ? "点唱台 · " + pageTitle : pageTitle;
        var settings = new LinkedHashMap<String, Object>(Map.ofEntries(
            Map.entry("title", pageTitle),
            Map.entry("description", page.descriptionOrEmpty()),
            Map.entry("search", page.searchOpen()),
            Map.entry("suggest", page.suggestOpen()),
            Map.entry("pageSize", page.size()),
            Map.entry("volume", player.volumePercent() / 100.0),
            Map.entry("mode", player.playMode()),
            Map.entry("resume", player.resumeOn()),
            Map.entry("version", version)));
        if (detail != null) {
            settings.put("entry", Map.of("type", detail.type(), "path", detail.path(), "item", detail.item()));
        }
        String config = HtmlUtils.htmlEscape(Js.json(settings));
        String escapedTitle = HtmlUtils.htmlEscape(title);
        boolean library = detail != null && "library".equals(detail.type());
        String head = detail == null || library || status.isError() ? "" : catalogHead(detail);
        String html = SLOT.matcher(index).replaceAll(match -> Matcher.quoteReplacement(switch (match.group(1)) {
            case "CONFIG" -> config;
            case "TITLE" -> escapedTitle;
            default -> head;
        }));
        var response = ServerResponse.status(status).contentType(HTML)
            .header("Cache-Control", MusicPageRouter.NO_STORE);
        if (search || library || status.isError()) {
            response.header("X-Robots-Tag", "noindex, follow");
        }
        if (status == HttpStatus.SERVICE_UNAVAILABLE) {
            response.header("Retry-After", "30");
        }
        return response.bodyValue(html);
    }

    private static String catalogHead(Detail detail) {
        var schemaType = switch (detail.type()) {
            case "playlist" -> "MusicPlaylist";
            case "album" -> "MusicAlbum";
            case "artist" -> "MusicGroup";
            case "song" -> "MusicRecording";
            default -> throw new IllegalArgumentException("未知音乐条目类型");
        };
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("@context", "https://schema.org");
        schema.put("@type", schemaType);
        schema.put("name", detail.title());
        schema.put("url", detail.path());
        schema.put("description", detail.description());
        schema.put("image", detail.coverUrl());
        schema.put("sameAs", detail.sameAs());
        schema.putAll(detail.attributes());
        String description = detail.description() == null ? "" : detail.description().strip();
        String meta = description.isEmpty() ? ""
            : "<meta name=\"description\" content=\""
                + HtmlUtils.htmlEscape(description.substring(0, Math.min(160, description.length())))
                + "\">\n";
        return "<link rel=\"canonical\" href=\"" + HtmlUtils.htmlEscape(detail.path()) + "\">\n"
            + meta + "<script type=\"application/ld+json\">"
            + Js.json(schema).replace("<", "\\u003c") + "</script>";
    }

    // 复用 Finder 的展示字段。Instant 转成 Folia 接收的毫秒数。
    static Map<String, Object> album(AlbumVo album) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", album.getId());
        data.put("name", album.getName());
        data.put("coverUrl", album.getCoverUrl());
        data.put("description", album.getDescription());
        data.put("publisher", album.getCompany());
        data.put("publishedAt", album.getPublishTime() == null ? null : album.getPublishTime().toEpochMilli());
        data.put("artists", album.getArtists());
        data.put("trackCount", album.getSize());
        data.put("tracks", album.getTracks());
        return data;
    }
}
