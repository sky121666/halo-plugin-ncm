package cn.sky.ncm.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.sky.ncm.featured.FeaturedService;
import cn.sky.ncm.finder.NcmFinderImpl;
import cn.sky.ncm.finder.NcmUnavailableException;
import cn.sky.ncm.finder.vo.AlbumVo;
import cn.sky.ncm.finder.vo.ArtistVo;
import cn.sky.ncm.finder.vo.PlaylistSummaryVo;
import cn.sky.ncm.finder.vo.PlaylistVo;
import cn.sky.ncm.finder.vo.SongVo;
import cn.sky.ncm.security.RateLimiter;
import cn.sky.ncm.setting.AdvancedSetting;
import cn.sky.ncm.setting.PageSetting;
import cn.sky.ncm.setting.NcmMusicPageMetadata;
import cn.sky.ncm.setting.PlayerSetting;
import cn.sky.ncm.setting.SettingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.util.HtmlUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.ExternalUrlSupplier;
import run.halo.app.infra.SystemSetting;
import run.halo.app.plugin.PluginContext;

class FoliaPageTest {
    private final SettingService settings = mock(SettingService.class);
    private final NcmFinderImpl finder = mock(NcmFinderImpl.class);
    private final FeaturedService featured = mock(FeaturedService.class);
    private final ExternalUrlSupplier externalUrl = mock(ExternalUrlSupplier.class);
    private final ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
    private WebTestClient web;

    @BeforeEach
    void setup() {
        when(settings.advanced()).thenReturn(Mono.just(AdvancedSetting.DEFAULT));
        when(settings.page()).thenReturn(Mono.just(PageSetting.DEFAULT));
        when(settings.player()).thenReturn(Mono.just(PlayerSetting.DEFAULT));
        when(finder.strict()).thenReturn(finder);
        when(client.fetch(ConfigMap.class, SystemSetting.SYSTEM_CONFIG)).thenReturn(Mono.empty());
        var context = mock(PluginContext.class);
        var router = new MusicPageRouter(settings, finder, new RateLimiter(), context, featured,
            new FoliaPage(externalUrl, client));
        web = WebTestClient.bindToRouterFunction(router.ncmMusicPageRouter()).build();
    }

    @Test
    void galleryKeepsPublicationOrderAndExcludesDuplicatesFromPublicShelf() {
        when(finder.siteProfile()).thenReturn(Mono.empty());
        when(finder.siteWeekRank(10)).thenReturn(Flux.empty());
        var lead = PlaylistVo.builder().id("21").name("主推").build();
        var second = PlaylistVo.builder().id("22").name("第二张").build();
        when(featured.homePlaylists(false)).thenReturn(Mono.just(
            new FeaturedService.HomePlaylists(List.of(lead, second), false)));
        when(finder.sitePlaylists(24)).thenReturn(Flux.just(
            PlaylistSummaryVo.builder().id("21").name("公开重复项").build(),
            PlaylistSummaryVo.builder().id("23").name("站长公开").build()));

        web.get().uri("/music?format=json").exchange().expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
            .expectBody().jsonPath("$.leadPlaylist.id").isEqualTo("21")
            .jsonPath("$.featuredPlaylists[1].id").isEqualTo("22")
            .jsonPath("$.publicPlaylistShelf.length()").isEqualTo(1)
            .jsonPath("$.publicPlaylistShelf[0].id").isEqualTo("23");
    }

    @Test
    void closedMusicPageDoesNotReadGallerySources() {
        when(settings.page()).thenReturn(Mono.just(PageSetting.CLOSED));

        web.get().uri("/music?format=json").exchange().expectStatus().isNotFound();
        verifyNoInteractions(featured);
    }

    @Test
    void homeDefersPublicShowcaseUntilExploreOpens() {
        String preload = "<link rel=\"preload\" as=\"fetch\" href=\"/music?format=json\" "
            + "crossorigin=\"anonymous\">";
        String home = html("/music");
        assertFalse(home.contains(preload));
        assertTrue(home.contains("<title>网抑云</title>"));
        assertFalse(html("/music/search?q=Folia").contains(preload));
        assertFalse(html("/music/library").contains(preload));
        verifyNoInteractions(featured);
    }

    @Test
    void usesConfiguredMetadataInTheInitialHtmlAndKeepsPrivateRoutesOutOfIndexing() throws Exception {
        when(externalUrl.getRaw()).thenReturn(URI.create("https://music.example.org").toURL());
        when(client.fetch(ConfigMap.class, SystemSetting.SYSTEM_CONFIG)).thenReturn(Mono.just(new ConfigMap()
            .putDataItem(SystemSetting.Basic.GROUP, "{\"favicon\":\"/media/site.png\"}")));
        when(settings.page()).thenReturn(Mono.just(new PageSetting(true, "唱片馆", "独立音乐 & 歌单", "",
            50, true, 20, true, 60, new NcmMusicPageMetadata("/media/icon.png", "精选 <唱片>", "/media/share.png"))));

        var home = html("/music");
        assertTrue(home.contains("<title>精选 &lt;唱片&gt;</title>"));
        assertEquals(1, home.split("<meta name=\"description\"", -1).length - 1);
        assertTrue(home.contains("content=\"独立音乐 &amp; 歌单\""));
        assertTrue(home.contains("rel=\"icon\" href=\"/media/icon.png\""));
        assertTrue(home.contains("rel=\"canonical\" href=\"https://music.example.org/music\""));
        assertTrue(home.contains("property=\"og:image\" content=\"https://music.example.org/media/share.png\""));
        assertEquals("唱片馆", config(home).path("title").asText());
        assertEquals("精选 <唱片>", config(home).path("seoTitle").asText());

        when(finder.song("27")).thenReturn(Mono.just(SongVo.builder().id("27").name("单曲").build()));
        var detail = html("/music/song/27");
        assertTrue(detail.contains("<title>" + HtmlUtils.htmlEscape("单曲 · 唱片馆") + "</title>"));
        assertTrue(detail.contains("content=\"https://music.example.org/media/share.png\""));

        for (var path : List.of("/music/search", "/music/library")) {
            var body = web.get().uri(path).exchange().expectStatus().isOk()
                .expectHeader().valueEquals("X-Robots-Tag", "noindex, follow")
                .expectBody(String.class).returnResult().getResponseBody();
            assertFalse(body.contains("rel=\"canonical\""));
            assertFalse(body.contains("property=\"og:image\""));
        }
    }

    @Test
    void inheritsTheSiteFaviconUnlessTheMusicPageSelectsItsOwnIcon() throws Exception {
        var site = new ConfigMap().putDataItem(SystemSetting.Basic.GROUP, "{\"favicon\":\"/media/site.png\"}");
        when(client.fetch(ConfigMap.class, SystemSetting.SYSTEM_CONFIG)).thenReturn(Mono.just(site));

        var home = html("/music");
        assertTrue(home.contains("rel=\"icon\" href=\"/media/site.png\""));
        assertTrue(home.contains("rel=\"apple-touch-icon\" href=\"/media/site.png\""));
        assertEquals("/media/site.png", config(home).path("icon").asText());

        var inherited = new NcmMusicPageMetadata("/media/music.png", "", "", "site");
        when(settings.page()).thenReturn(Mono.just(new PageSetting(true, "音乐", "", "",
            50, true, 20, true, 60, inherited)));
        assertEquals("/media/site.png", config(html("/music/library")).path("icon").asText());

        var custom = new NcmMusicPageMetadata(inherited.icon(), "", "", "custom");
        when(settings.page()).thenReturn(Mono.just(new PageSetting(true, "音乐", "", "",
            50, true, 20, true, 60, custom)));
        home = html("/music");
        assertTrue(home.contains("rel=\"icon\" href=\"/media/music.png\""));
        assertEquals("/media/music.png", config(home).path("icon").asText());
    }

    @Test
    void usesTheBundledFaviconWhenTheSiteDoesNotProvideOne() throws Exception {
        var site = new ConfigMap().putDataItem(SystemSetting.Basic.GROUP, "{\"logo\":\"/media/logo.png\"}");
        when(client.fetch(ConfigMap.class, SystemSetting.SYSTEM_CONFIG)).thenReturn(Mono.just(site));

        var home = html("/music");
        assertTrue(home.contains("rel=\"icon\" href=\"/plugins/plugin-ncm/assets/static/brand-mark.png\""));
        assertEquals("", config(home).path("icon").asText());
    }

    @Test
    void foliaEntryPassesPlaybackDefaultsWithoutOverridingNativeControls() throws Exception {
        var savedPlayer = JsonMapper.shared().readValue(
            "{\"mode\":\"shuffle\",\"volume\":35,\"resume\":false,\"position\":\"left\","
                + "\"navigation\":\"none\",\"lyric\":\"off\",\"shortcuts\":false}", PlayerSetting.class);
        when(settings.player()).thenReturn(Mono.just(savedPlayer));

        var config = config(html("/music"));
        assertEquals("shuffle", config.path("mode").asText());
        assertEquals(0.35, config.path("volume").asDouble());
        assertTrue(config.path("resume").isBoolean() && !config.path("resume").asBoolean());
        assertFalse(config.has("position"));
        assertFalse(config.has("lyric"));
        assertFalse(config.has("shortcuts"));
        assertFalse(config.has("navigation"));
    }

    @Test
    void catalogLinksOpenTheRequestedFoliaEntry() throws Exception {
        var playlist = PlaylistVo.builder().id("21").name("精选 <唱片>").build();
        when(finder.playlist("21", 0)).thenReturn(Mono.just(playlist));
        when(featured.decorate("21", playlist)).thenReturn(Mono.just(playlist));
        when(finder.album("22")).thenReturn(Mono.just(AlbumVo.builder().id("22").name("专辑").build()));
        when(finder.artist("23", 50)).thenReturn(Mono.just(ArtistVo.builder().id("23").name("歌手").build()));
        when(finder.song("24")).thenReturn(Mono.just(SongVo.builder().id("24").name("歌曲").build()));

        assertCatalog("playlist", "21", "MusicPlaylist", "精选 <唱片>");
        assertCatalog("album", "22", "MusicAlbum", "专辑");
        assertCatalog("artist", "23", "MusicGroup", "歌手");
        assertCatalog("song", "24", "MusicRecording", "歌曲");
    }

    @Test
    void libraryShellContainsNoPersonalDataAndMakesNoCatalogCalls() throws Exception {
        String body = web.get().uri("/music/library?tab=likes").exchange()
            .expectStatus().isOk()
            .expectHeader().valueEquals("Cache-Control", "no-store")
            .expectHeader().valueEquals("X-Robots-Tag", "noindex, follow")
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
            .expectBody(String.class).returnResult().getResponseBody();
        var entry = config(body).path("entry");
        assertEquals("library", entry.path("type").asText());
        assertEquals("/music/library", entry.path("path").asText());
        assertEquals("个人曲库", entry.path("item").path("name").asText());
        assertEquals(1, entry.path("item").size());
        verifyNoInteractions(featured);

        when(settings.page()).thenReturn(Mono.just(PageSetting.CLOSED));
        web.get().uri("/music/library").exchange().expectStatus().isNotFound();
    }

    @Test
    void catalogEntryKeepsHttpErrorAndJsonContracts() throws Exception {
        when(finder.song("25")).thenReturn(Mono.empty());
        when(finder.song("26")).thenReturn(Mono.error(new NcmUnavailableException("song_detail", "upstream")));
        web.get().uri("/music/song/25").exchange().expectStatus().isNotFound();
        String html = web.get().uri("/music/song/26").exchange().expectStatus().isEqualTo(503)
            .expectHeader().valueEquals("Retry-After", "30")
            .expectBody(String.class).returnResult().getResponseBody();
        assertEquals("error", config(html).path("entry").path("type").asText());
        web.get().uri("/music/song/26?format=json").exchange().expectStatus().isEqualTo(503)
            .expectBody().jsonPath("$.error").isEqualTo("unavailable");
    }

    private void assertCatalog(String type, String id, String schema, String name) throws Exception {
        String path = "/music/" + type + "/" + id;
        String body = html(path);
        var entry = config(body).path("entry");
        assertEquals(type, entry.path("type").asText());
        assertEquals(path, entry.path("path").asText());
        assertEquals(id, entry.path("item").path("id").asText());
        assertEquals(name, entry.path("item").path("name").asText());
        assertTrue(body.contains("<title>" + HtmlUtils.htmlEscape(name + " · 网抑云") + "</title>"));
        assertTrue(body.contains("href=\"" + path + "\""));
        assertTrue(body.contains("\"@type\":\"" + schema + "\""));
    }

    private String html(String path) {
        return web.get().uri(path).exchange().expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
            .expectBody(String.class).returnResult().getResponseBody();
    }

    private static JsonNode config(String body) throws Exception {
        var attr = Pattern.compile("data-ncm-config=\"([^\"]+)\"").matcher(body);
        assertTrue(attr.find());
        return new ObjectMapper().readTree(HtmlUtils.htmlUnescape(attr.group(1)));
    }
}
