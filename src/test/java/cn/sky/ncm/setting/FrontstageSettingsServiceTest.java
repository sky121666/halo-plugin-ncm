package cn.sky.ncm.setting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import tools.jackson.databind.json.JsonMapper;

class FrontstageSettingsServiceTest {

    private static final Duration WAIT = Duration.ofSeconds(3);
    private final Map<String, ConfigMap> resources = new LinkedHashMap<>();
    private final ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
    private final FrontstageSettingsService service = new FrontstageSettingsService(client);

    @BeforeEach
    void configureStorage() {
        when(client.fetch(eq(ConfigMap.class), anyString())).thenAnswer(invocation -> {
            String name = invocation.getArgument(1);
            return Mono.defer(() -> Mono.justOrEmpty(resources.get(name)).map(FrontstageSettingsServiceTest::copy));
        });
        when(client.create(any(ConfigMap.class))).thenAnswer(invocation -> {
            ConfigMap incoming = invocation.getArgument(0);
            return Mono.fromSupplier(() -> {
                incoming.getMetadata().setVersion(0L);
                resources.put(incoming.getMetadata().getName(), copy(incoming));
                return copy(incoming);
            });
        });
        when(client.update(any(ConfigMap.class))).thenAnswer(invocation -> {
            ConfigMap incoming = invocation.getArgument(0);
            return Mono.fromSupplier(() -> {
                String name = incoming.getMetadata().getName();
                long version = resources.get(name).getMetadata().getVersion();
                if (incoming.getMetadata().getVersion() != version) {
                    throw new OptimisticLockingFailureException("配置已变更");
                }
                incoming.getMetadata().setVersion(version + 1);
                resources.put(name, copy(incoming));
                return copy(incoming);
            });
        });
    }

    @Test
    void migratesExistingValuesWithoutRewritingLegacyGroups() {
        var original = Map.of(
            "page", """
                {"enabled":false,"title":"","description":"","pageSize":80,"searchEnabled":true,
                 "suggestEnabled":false,"searchPerMinute":35,"suggestPerMinute":83,"featuredPlaylistIds":"123"}
                """,
            "finder", """
                {"siteProfile":false,"sitePlaylists":true,"siteRecent":true,"siteRank":false,
                 "siteRecommend":false,"cacheSeconds":1234}
                """,
            "player", """
                {"mode":"order","volume":0,"resume":false,"lyric":"off"}
                """,
            "advanced", """
                {"proxy":"","rateLimitPublic":0,"musicSearchPerMinute":45}
                """
        );
        putTechnical(original);
        service.initialize().block(WAIT);

        var initial = service.read().block(WAIT);
        assertThat(initial).isNotNull();
        assertThat(initial.settings()).isEqualTo(new NcmFrontstageSettings(false, "", "", 80, true, false,
            false, true, false, "order", 0, false));
        var after = resources.get(ProxySettings.CONFIG).getData();
        assertThat(after).containsEntry("page", original.get("page")).containsEntry("finder", original.get("finder"))
            .containsEntry("player", original.get("player"));
        var advanced = JsonMapper.shared().readValue(after.get("advanced"), AdvancedSetting.class);
        assertThat(advanced.publicLimit()).isZero();
        assertThat(advanced.musicSearchPerMinute()).isEqualTo(45);
        assertThat(advanced.musicSuggestPerMinute()).isEqualTo(83);
        assertThat(advanced.siteContentCacheSeconds()).isEqualTo(1234);
    }

    @Test
    void keepsSettingsIndependentAndRejectsAnOlderWindow() {
        putTechnical(Map.of("advanced", "{\"rateLimitPublic\":40}"));
        service.initialize().block(WAIT);
        var initial = service.read().block(WAIT);
        assertThat(initial).isNotNull();
        var storedBefore = Map.copyOf(resources.get(ProxySettings.CONFIG).getData());
        var edited = new NcmFrontstageSettings(true, "首页音乐", "", 50, true, true,
            true, true, false, "single", 0, false);

        var saved = service.save(new NcmFrontstageConfig(initial.revision(), edited)).block(WAIT);
        assertThat(saved).isNotNull();
        assertThat(saved.revision()).isGreaterThan(initial.revision());
        var expected = edited.withStoredDefaults(initial.settings());
        assertThat(saved.settings()).isEqualTo(expected);
        assertThat(service.current().block(WAIT)).isEqualTo(expected);
        assertThat(service.legacyPlayer().block(WAIT)).isEqualTo(new PlayerSetting("loop", 100, false));
        assertThat(resources.get(ProxySettings.CONFIG).getData()).isEqualTo(storedBefore);

        var technical = copy(resources.get(ProxySettings.CONFIG));
        technical.setData(Map.of("advanced", "{\"rateLimitPublic\":99}"));
        client.update(technical).block(WAIT);
        service.initialize().block(WAIT);
        assertThat(service.read().block(WAIT)).isEqualTo(saved);
        assertThat(resources.get(ProxySettings.CONFIG).getData().get("advanced")).contains("\"rateLimitPublic\":99");

        StepVerifier.create(service.save(initial))
            .expectErrorSatisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT))
            .verify(WAIT);
        assertThat(service.read().block(WAIT)).isEqualTo(saved);
    }

    @Test
    void olderClientsPreserveMetadataAndEmptyValuesClearIndividualFields() {
        putTechnical(Map.of());
        service.initialize().block(WAIT);
        var initial = service.read().block(WAIT);
        var metadata = new NcmMusicPageMetadata("/media/icon.svg", "歌单推荐", "https://img.example.org/share.jpg");
        var edited = new NcmFrontstageSettings(true, "音乐馆", "简介", 50, true, true,
            true, true, false, "loop", 100, false, metadata);
        var saved = service.save(new NcmFrontstageConfig(initial.revision(), edited)).block(WAIT);
        var legacy = service.save(new NcmFrontstageConfig(saved.revision(), initial.settings())).block(WAIT);
        assertThat(legacy.settings().metadata()).isEqualTo(metadata);

        var clearing = new NcmFrontstageSettings(true, "音乐馆", "简介", 50, true, true,
            true, true, false, "loop", 100, false, new NcmMusicPageMetadata("", null, null));
        var cleared = service.save(new NcmFrontstageConfig(legacy.revision(), clearing)).block(WAIT);
        assertThat(cleared.settings().metadata())
            .isEqualTo(new NcmMusicPageMetadata("", metadata.seoTitle(), metadata.shareImage()));

        var invalid = new NcmFrontstageSettings(true, "音乐馆", "简介", 50, true, true,
            true, true, false, "loop", 100, false, new NcmMusicPageMetadata("//example.org/icon.svg", "", ""));
        StepVerifier.create(service.save(new NcmFrontstageConfig(cleared.revision(), invalid)))
            .expectErrorSatisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST))
            .verify(WAIT);
        assertThat(service.read().block(WAIT)).isEqualTo(cleared);
    }

    @Test
    void preservesTheCustomIconWhenSwitchingSources() {
        putTechnical(Map.of());
        service.initialize().block(WAIT);
        var initial = service.read().block(WAIT);
        var metadata = new NcmMusicPageMetadata("/media/music.svg", "精选", "/media/share.jpg", "custom");
        var selected = new NcmFrontstageSettings(true, "音乐馆", "简介", 50, true, true,
            true, true, false, "loop", 100, false, metadata);
        var saved = service.save(new NcmFrontstageConfig(initial.revision(), selected)).block(WAIT);

        var inherit = new NcmFrontstageSettings(true, "音乐馆", "简介", 50, true, true,
            true, true, false, "loop", 100, false, new NcmMusicPageMetadata(null, null, null, "site"));
        var inherited = service.save(new NcmFrontstageConfig(saved.revision(), inherit)).block(WAIT);
        var inheritedMetadata = inherited.settings().metadata();
        assertThat(inheritedMetadata.usesCustomIcon()).isFalse();
        assertThat(inheritedMetadata.icon()).isEqualTo(metadata.icon());

        var restored = service.save(new NcmFrontstageConfig(inherited.revision(), selected)).block(WAIT);
        assertThat(restored.settings().metadata()).isEqualTo(metadata);

        var oldClient = new NcmFrontstageSettings(true, "音乐馆", "简介", 50, true, true,
            true, true, false, "loop", 100, false, new NcmMusicPageMetadata("", null, null));
        var cleared = service.save(new NcmFrontstageConfig(restored.revision(), oldClient)).block(WAIT);
        assertThat(cleared.settings().metadata())
            .isEqualTo(new NcmMusicPageMetadata("", metadata.seoTitle(), metadata.shareImage(), "site"));
    }

    @Test
    void migratesApiAccessIndependentlyOfNativeAndFrontstageSettings() {
        putTechnical(Map.of("basic", """
            {"publicIdentity":"site","siteMaxLevel":"standard","apiScope":"all","extraApis":"album",
             "allowClientCookie":false,"cacheSeconds":0,"realIp":"","randomCnIp":false}
            """, "advanced", "{\"rateLimitPublic\":40}"));
        service.initialize().block(WAIT);
        var technicalBefore = Map.copyOf(resources.get(ProxySettings.CONFIG).getData());
        var frontstageBefore = service.read().block(WAIT);
        var access = new ApiAccessSettingsService(client);
        access.initialize().block(WAIT);
        var storedBeforeUpgrade = copy(resources.get(ApiAccessSettingsService.CONFIG));
        storedBeforeUpgrade.setData(Map.of("settings", """
            {"publicIdentity":"site","siteMaxLevel":"standard","allowClientCookie":true,"apiScope":"all",
             "extraApis":"album","cacheSeconds":0,"realIp":"","randomCnIp":false}
            """));
        client.update(storedBeforeUpgrade).block(WAIT);

        var initial = access.read().block(WAIT);
        assertThat(initial).isNotNull();
        assertThat(initial.settings()).isEqualTo(new NcmApiAccessSettings(
            "all", "album", 0, "", false));
        assertThat(access.current().block(WAIT).allApisOpen()).isTrue();
        var edited = new NcmApiAccessSettings("common", "", 300, "", false);
        var saved = access.save(new NcmApiAccessConfig(initial.revision(), edited)).block(WAIT);
        assertThat(saved).isNotNull();
        assertThat(access.current().block(WAIT).allApisOpen()).isFalse();
        assertThat(resources.get(ApiAccessSettingsService.CONFIG).getData().get("settings"))
            .doesNotContain("publicIdentity", "siteMaxLevel", "allowClientCookie");
        access.initialize().block(WAIT);

        assertThat(access.read().block(WAIT)).isEqualTo(saved);
        assertThat(service.read().block(WAIT)).isEqualTo(frontstageBefore);
        assertThat(resources.get(ProxySettings.CONFIG).getData()).isEqualTo(technicalBefore);
        StepVerifier.create(access.save(initial))
            .expectErrorSatisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT))
            .verify(WAIT);
    }

    @Test
    void refusesAnUnownedFrontstageResource() {
        putTechnical(Map.of());
        var unexpected = new ConfigMap();
        var metadata = new Metadata();
        metadata.setName(FrontstageSettingsService.CONFIG);
        metadata.setVersion(0L);
        unexpected.setMetadata(metadata);
        unexpected.setData(Map.of(FrontstageSettingsService.SETTINGS, "{}"));
        resources.put(FrontstageSettingsService.CONFIG, unexpected);

        StepVerifier.create(service.initialize())
            .expectErrorMessage("音乐页设置资源归属不匹配")
            .verify(WAIT);
        assertThat(resources.get(FrontstageSettingsService.CONFIG)).isSameAs(unexpected);
    }

    private void putTechnical(Map<String, String> data) {
        var metadata = new Metadata();
        metadata.setName(ProxySettings.CONFIG);
        metadata.setVersion(0L);
        var config = new ConfigMap();
        config.setMetadata(metadata);
        config.setData(new LinkedHashMap<>(data));
        resources.put(ProxySettings.CONFIG, config);
    }

    private static ConfigMap copy(ConfigMap source) {
        var metadata = new Metadata();
        metadata.setName(source.getMetadata().getName());
        metadata.setVersion(source.getMetadata().getVersion());
        metadata.setLabels(source.getMetadata().getLabels());
        var copy = new ConfigMap();
        copy.setMetadata(metadata);
        copy.setData(new LinkedHashMap<>(source.getData()));
        return copy;
    }
}
