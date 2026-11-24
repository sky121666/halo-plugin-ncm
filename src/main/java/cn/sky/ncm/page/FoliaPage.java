package cn.sky.ncm.page;

import static cn.sky.ncm.setting.NcmMusicPageMetadata.safeImageUrl;

import cn.sky.ncm.client.Js;
import cn.sky.ncm.finder.vo.AlbumVo;
import cn.sky.ncm.setting.NcmMusicPageMetadata;
import cn.sky.ncm.setting.PageSetting;
import cn.sky.ncm.setting.PlayerSetting;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.util.HtmlUtils;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.ExternalUrlSupplier;
import run.halo.app.infra.SystemSetting;

/** Folia 的入口由插件直接提供，静态资源经插件的 ReverseProxy 分发。 */
@Component
final class FoliaPage {
    private static final Pattern SLOT = Pattern.compile("__NCM_FOLIA_(CONFIG|TITLE|HEAD)__");
    private static final MediaType HTML = new MediaType("text", "html", StandardCharsets.UTF_8);
    private static final String DEFAULT_ICON = "/plugins/plugin-ncm/assets/static/brand-mark.png";
    private final String index;
    private final ExternalUrlSupplier externalUrl;
    private final ReactiveExtensionClient client;

    record Detail(String type, String path, String title, String description, String coverUrl,
                  String sameAs, Object item, Map<String, Object> attributes) {
    }

    FoliaPage(ExternalUrlSupplier externalUrl, ReactiveExtensionClient client) {
        this.externalUrl = externalUrl;
        this.client = client;
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
        return pageIcon(page.metadataOrDefault())
            .flatMap(icon -> renderIndex(page, player, search, detail, status, version, icon));
    }

    private Mono<String> pageIcon(NcmMusicPageMetadata metadata) {
        if (metadata.usesCustomIcon()) {
            return Mono.just(safeImageUrl(metadata.icon()));
        }
        return client.fetch(ConfigMap.class, SystemSetting.SYSTEM_CONFIG)
            .mapNotNull(ConfigMap::getData)
            .mapNotNull(data -> SystemSetting.get(data, SystemSetting.Basic.GROUP, SystemSetting.Basic.class))
            .map(basic -> safeImageUrl(basic.getFavicon()))
            .defaultIfEmpty("")
            .onErrorReturn("");
    }

    private Mono<ServerResponse> renderIndex(PageSetting page, PlayerSetting player, boolean search, Detail detail,
                                             HttpStatus status, String version, String icon) {
        String pageTitle = page.titleOrDefault();
        var metadata = page.metadataOrDefault();
        String seoTitle = orDefault(metadata.seoTitle(), pageTitle);
        String title = detail != null ? detail.title() + " · " + pageTitle
            : search ? "点唱台 · " + pageTitle : seoTitle;
        var settings = new LinkedHashMap<String, Object>(Map.ofEntries(
            Map.entry("title", pageTitle),
            Map.entry("seoTitle", seoTitle),
            Map.entry("icon", icon),
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
        boolean library = detail != null && "library".equals(detail.type());
        boolean nonPublic = search || library || status.isError();
        String head = pageHead(page, title, orDefault(icon, DEFAULT_ICON), detail, nonPublic);
        String html = SLOT.matcher(index).replaceAll(match -> Matcher.quoteReplacement(switch (match.group(1)) {
            case "CONFIG" -> config;
            case "TITLE" -> HtmlUtils.htmlEscape(title);
            default -> head;
        }));
        var response = ServerResponse.status(status).contentType(HTML)
            .header("Cache-Control", MusicPageRouter.NO_STORE);
        if (nonPublic) {
            response.header("X-Robots-Tag", "noindex, follow");
        }
        if (status == HttpStatus.SERVICE_UNAVAILABLE) {
            response.header("Retry-After", "30");
        }
        return response.bodyValue(html);
    }

    private String pageHead(PageSetting page, String title, String icon, Detail detail, boolean nonPublic) {
        var head = new StringBuilder()
            .append(meta("name", "apple-mobile-web-app-title", page.titleOrDefault()))
            .append(link("icon", icon))
            .append(link("apple-touch-icon", icon));
        if (nonPublic) {
            return head.toString();
        }

        String canonical = absoluteUrl(detail == null ? "/music" : detail.path());
        String description = detail == null ? page.descriptionOrEmpty()
            : orDefault(shortDescription(detail.description()), page.descriptionOrEmpty());
        String shareImage = orDefault(detail == null ? "" : safeImageUrl(detail.coverUrl()),
            safeImageUrl(page.metadataOrDefault().shareImage()));
        head.append(link("canonical", canonical))
            .append(meta("name", "description", description))
            .append(meta("property", "og:type", "website"))
            .append(meta("property", "og:site_name", page.titleOrDefault()))
            .append(meta("property", "og:title", title))
            .append(meta("property", "og:description", description))
            .append(meta("property", "og:url", canonical))
            .append(meta("property", "og:image", absoluteUrl(shareImage)))
            .append(meta("name", "twitter:card", shareImage.isEmpty() ? "summary" : "summary_large_image"));
        if (detail != null) {
            head.append(catalogSchema(detail, canonical, description));
        }
        return head.toString();
    }

    private String absoluteUrl(String path) {
        var base = externalUrl.getRaw();
        return base != null && path.startsWith("/") ? URI.create(base.toExternalForm()).resolve(path).toString() : path;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static String shortDescription(String value) {
        String description = orDefault(value, "");
        int end = description.offsetByCodePoints(0, Math.min(160, description.codePointCount(0, description.length())));
        return description.substring(0, end);
    }

    private static String meta(String attribute, String key, String content) {
        return content.isEmpty() ? "" : "<meta " + attribute + "=\"" + key + "\" content=\""
            + HtmlUtils.htmlEscape(content) + "\">\n";
    }

    private static String link(String rel, String href) {
        return "<link rel=\"" + rel + "\" href=\"" + HtmlUtils.htmlEscape(href) + "\">\n";
    }

    private static String catalogSchema(Detail detail, String canonical, String description) {
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
        schema.put("url", canonical);
        schema.put("description", description);
        schema.put("image", detail.coverUrl());
        schema.put("sameAs", detail.sameAs());
        schema.putAll(detail.attributes());
        return "<script type=\"application/ld+json\">"
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
