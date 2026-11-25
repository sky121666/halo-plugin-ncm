package cn.sky.ncm.page;

import cn.sky.ncm.featured.FeaturedService;
import cn.sky.ncm.setting.ClientConfigService;
import cn.sky.ncm.setting.FrontstageSettingsService;
import cn.sky.ncm.setting.NcmToolSettings;
import cn.sky.ncm.setting.ToolSettingsService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.model.AttributeValueQuotes;
import org.thymeleaf.model.IModel;
import org.thymeleaf.processor.element.IElementModelStructureHandler;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.PluginContext;
import run.halo.app.theme.dialect.TemplateHeadProcessor;

@Component
public class MiniPlayerHeadProcessor implements TemplateHeadProcessor {
    private static final String RESOURCE_DIR = "/static/mini-player/";
    private static final String ASSETS = "/plugins/plugin-ncm/assets" + RESOURCE_DIR;
    private final ToolSettingsService tools;
    private final FrontstageSettingsService frontstage;
    private final FeaturedService featured;
    private final ClientConfigService preferences;
    private final String assetQuery;

    public MiniPlayerHeadProcessor(ToolSettingsService tools, FrontstageSettingsService frontstage,
                                   FeaturedService featured, ClientConfigService preferences, PluginContext plugin) {
        this.tools = tools;
        this.frontstage = frontstage;
        this.featured = featured;
        this.preferences = preferences;
        this.assetQuery = assetQuery(Objects.requireNonNullElse(plugin.getVersion(), "0"));
    }

    private static String assetQuery(String version) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var name : List.of("theme.css", "player.js", "player.css", "lens.js", "playbackSession.js",
                "foliaPlayer.js", "placement.js", "positionPreferences.js", "appearance.js")) {
                try (var resource = MiniPlayerHeadProcessor.class.getResourceAsStream(RESOURCE_DIR + name)) {
                    if (resource == null) {
                        // OpenAPI generation omits the frontend build.
                        return "?v=" + version;
                    }
                    digest.update(resource.readAllBytes());
                }
            }
            return "?v=" + version + "&r=" + HexFormat.of().formatHex(digest.digest(), 0, 8);
        } catch (IOException error) {
            throw new UncheckedIOException("无法读取小播放器资源", error);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    @Override
    public Mono<Void> process(ITemplateContext context, IModel model, IElementModelStructureHandler handler) {
        // Halo scopes this extension to the theme head, including plugin-provided pages.
        return frontstage.current().filter(page -> Boolean.TRUE.equals(page.enabled()))
            .doOnNext(page -> appendAppearance(context, model))
            .flatMap(page -> tools.current().map(NcmToolSettings::miniPlayer)
                .filter(widget -> Boolean.TRUE.equals(widget.enabled()))
                .flatMap(widget -> featured.publishedPlaylist(widget.playlistId())
                    .flatMap(playlist -> preferences.site("mini-player").mapNotNull(ClientConfigService.Snapshot::config)
                        .defaultIfEmpty(Map.of()).onErrorReturn(Map.of())
                        .doOnNext(defaults -> append(context, model, playlist.getSpec().getPlaylistId(),
                            Boolean.TRUE.equals(page.resume()), defaults)))))
            .onErrorComplete()
            .then();
    }

    private void appendAppearance(ITemplateContext context, IModel model) {
        var factory = context.getModelFactory();
        model.add(factory.createStandaloneElementTag("link", Map.of(
            "rel", "stylesheet", "href", ASSETS + "theme.css" + assetQuery
        ), AttributeValueQuotes.DOUBLE, false, true));
        model.add(factory.createOpenElementTag("script", Map.of(
            "type", "module", "src", ASSETS + "appearance.js" + assetQuery, "data-ncm-appearance-source", ""
        ), AttributeValueQuotes.DOUBLE, false));
        model.add(factory.createCloseElementTag("script"));
    }

    private void append(ITemplateContext context, IModel model, String playlistId, boolean resume,
                        Map<String, Object> defaults) {
        var factory = context.getModelFactory();
        var attributes = new LinkedHashMap<>(Map.of(
            "type", "module", "src", ASSETS + "player.js" + assetQuery,
            "data-ncm-mini-player", playlistId, "data-ncm-resume", Boolean.toString(resume)
        ));
        for (String layout : List.of("wide", "compact")) {
            if (defaults.get(layout) instanceof Map<?, ?> point
                && point.get("x") instanceof Number x && point.get("y") instanceof Number y) {
                attributes.put("data-ncm-position-" + layout, x.doubleValue() + "," + y.doubleValue());
            }
        }
        model.add(factory.createOpenElementTag("script", attributes, AttributeValueQuotes.DOUBLE, false));
        model.add(factory.createCloseElementTag("script"));
    }
}
