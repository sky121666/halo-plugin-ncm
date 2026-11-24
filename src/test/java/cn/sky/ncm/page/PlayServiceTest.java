package cn.sky.ncm.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.sky.ncm.account.AccountService;
import cn.sky.ncm.account.NcmAccount;
import cn.sky.ncm.api.more.ToolApis;
import cn.sky.ncm.api.more.ToolApis.SongUrl;
import cn.sky.ncm.bootstrap.NcmBootstrap;
import cn.sky.ncm.client.NcmResponse;
import cn.sky.ncm.security.SecretBox;
import cn.sky.ncm.security.SecretStore;
import cn.sky.ncm.setting.AdvancedSetting;
import cn.sky.ncm.setting.SettingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import java.time.Instant;
import run.halo.app.core.extension.User;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Secret;

class PlayServiceTest {

    static JsonNode json(String s) throws Exception {
        return new ObjectMapper().readTree(s.replace('\'', '"'));
    }

    static NcmResponse ok(JsonNode body) {
        return NcmResponse.ok(body, List.of());
    }

    @Test
    void fullSong() throws Exception {
        JsonNode item = json("{'id':1,'url':'https://m701.music.126.net/a.mp3','br':128000,'level':'standard',"
            + "'freeTrialInfo':null,'fee':8}");
        var r = PlayService.classify(new SongUrl(ok(item), "https://m701.music.126.net/a.mp3", item), "standard", "guest");
        assertTrue(r.ok());
        assertFalse(r.trial());
        assertEquals(128000, r.br());
        assertEquals("guest", r.identity());
    }

    @Test
    void trialClip() throws Exception {
        JsonNode item = json("{'id':2,'url':'https://m701.music.126.net/t.mp3','fee':1,"
            + "'freeTrialInfo':{'start':60000,'end':90000}}");
        var r = PlayService.classify(new SongUrl(ok(item), "https://m701.music.126.net/t.mp3", item), "standard", "user");
        assertTrue(r.ok());
        assertTrue(r.trial());
        assertEquals(60000L, r.trialStart());
        assertEquals(90000L, r.trialEnd());
    }

    @Test
    void trialInfoFromNeteaseIsInSeconds() throws Exception {
        // 实测《借过一下 (Live)》：网易云返回 freeTrialInfo { start: 0, end: 30 }，单位是秒
        JsonNode item = json("{'id':5,'url':'https://m701.music.126.net/t.mp3','fee':1,'freeTrialInfo':{'start':0,'end':30}}");
        var r = PlayService.classify(new SongUrl(ok(item), "https://m701.music.126.net/t.mp3", item), "standard", "guest");
        assertTrue(r.trial());
        assertEquals(0L, r.trialStart());
        assertEquals(30000L, r.trialEnd());
        assertEquals(Map.of("start", 0L, "end", 30000L), PlayerRouter.playBody("5", r, false).get("trial"));
        assertEquals(1500L, PlayService.toMs(1.5));
        assertEquals(90000L, PlayService.toMs(90000));
        assertEquals(0L, PlayService.toMs(-1));
    }

    @Test
    void unavailableExplainsWhy() throws Exception {
        JsonNode vip = json("{'id':3,'url':null,'fee':1}");
        var r = PlayService.classify(new SongUrl(ok(vip), null, vip), "standard", "guest");
        assertEquals("unavailable", r.status());
        assertTrue(r.message().contains("会员"));
        assertTrue(r.message().contains("登录并绑定"));
        JsonNode gone = json("{'id':4,'url':null,'fee':0}");
        var r2 = PlayService.classify(new SongUrl(ok(gone), null, gone), "standard", "user");
        assertTrue(r2.message().contains("没有版权"));
        assertFalse(r2.message().contains("登录并绑定"));
    }

    @Test
    void serviceErrors() {
        var r = PlayService.classify(new SongUrl(NcmResponse.error(503, "xeapi 公钥尚未获取"), null, null),
            "standard", "guest");
        assertEquals("service", r.status());
        assertNull(r.url());
        assertTrue(r.message().contains("503"));
        assertEquals("standard", PlayService.level(null));
        assertEquals("standard", PlayService.level("<x>"));
        assertEquals("lossless", PlayService.level("lossless"));
    }

    @Test
    void upstreamBusinessErrorsAreRetryableEvenWhenNormalizedToHttp200() throws Exception {
        var unavailable = json("{'code':404,'url':null}");
        var failed = ok(json("{'code':502}"));
        var failure = PlayService.classify(new SongUrl(failed, null, unavailable), "standard", "guest");
        assertEquals("service", failure.status());
        assertTrue(failure.message().contains("502"));

        var malformed = PlayService.classify(new SongUrl(ok(json("{'code':200,'data':[]}")), null, null),
            "standard", "guest");
        assertEquals("service", malformed.status());

        var limited = PlayService.classify(new SongUrl(NcmResponse.error(429, "limit"), null, null),
            "standard", "guest");
        assertEquals("rate_limited", limited.status());
    }

    @Test
    void playbackUsesGuestForVisitorsAndSignedInUsersWithoutTheirOwnBinding() {
        var accounts = mock(AccountService.class);
        var service = new PlayService(null, accounts, null, null);
        var timeout = Duration.ofSeconds(2);
        var visitor = service.who().block(timeout);
        assertEquals("guest", visitor.label());
        assertTrue(visitor.identity().cookie().isEmpty());
        verifyNoInteractions(accounts);

        when(accounts.binding("qa")).thenReturn(Mono.empty());
        var principal = ReactiveSecurityContextHolder.withAuthentication(
            new TestingAuthenticationToken("qa", null, "authenticated"));
        assertEquals(visitor, service.who().contextWrite(principal).block(timeout));
    }

    @Test
    void sameHaloUserRebindingInvalidatesCachedAndInFlightPlayback() throws Exception {
        var bootstrap = mock(NcmBootstrap.class);
        when(bootstrap.ready()).thenReturn(Mono.empty());
        var settings = mock(SettingService.class);
        when(settings.advanced()).thenReturn(Mono.just(AdvancedSetting.DEFAULT));
        var client = mock(ReactiveExtensionClient.class);
        var box = mock(SecretBox.class);
        var accounts = new AccountService(client, box, new SecretStore(client, box));
        var account = new NcmAccount();
        var metadata = new Metadata();
        metadata.setName("user-qa");
        metadata.setCreationTimestamp(Instant.parse("2026-11-02T00:00:00Z"));
        account.setMetadata(metadata);
        var spec = new NcmAccount.Spec();
        spec.setOwner("qa");
        account.setSpec(spec);
        when(client.fetch(User.class, "qa")).thenReturn(Mono.just(cn.sky.ncm.me.MeFixture.testUser("qa")));
        when(client.fetch(NcmAccount.class, "user-qa")).thenReturn(Mono.just(account));
        var credential = new AtomicReference<>(secret("first-version"));
        when(client.fetch(Secret.class, "ncm-account-user-qa")).thenAnswer(
            invocation -> Mono.just(credential.get()));
        when(box.decrypt("enc:v1:first-version")).thenReturn("MUSIC_U=first");
        when(box.decrypt("enc:v1:second-version")).thenReturn("MUSIC_U=second");
        var first = new SongUrl(ok(json("{'code':200}")), "https://m701.music.126.net/first.mp3",
            json("{'code':200,'url':'https://m701.music.126.net/first.mp3'}"));
        var second = new SongUrl(ok(json("{'code':200}")), "https://m701.music.126.net/second.mp3",
            json("{'code':200,'url':'https://m701.music.126.net/second.mp3','freeTrialInfo':{'start':0,'end':30}}"));
        var olderRequest = Sinks.<SongUrl>one();
        var tools = mock(ToolApis.class);
        when(tools.resolveSongUrl(anyMap(), any())).thenReturn(
            Mono.just(first), olderRequest.asMono(), Mono.just(second));
        var service = new PlayService(tools, accounts, settings, bootstrap);
        var principal = ReactiveSecurityContextHolder.withAuthentication(
            new TestingAuthenticationToken("qa", null, "authenticated"));
        var timeout = Duration.ofSeconds(2);

        assertEquals(first.url(), service.resolve("1", "standard", false).contextWrite(principal).block(timeout).url());
        assertEquals(first.url(), service.resolve("1", "standard", false).contextWrite(principal).block(timeout).url());
        var pending = service.resolve("1", "standard", true).contextWrite(principal).toFuture();
        credential.set(secret("second-version"));
        try {
            var current = service.resolve("1", "standard", false).contextWrite(principal).block(timeout);
            assertEquals(second.url(), current.url());
            assertTrue(current.trial());
        } finally {
            olderRequest.tryEmitValue(first);
            pending.join();
        }
        assertEquals(second.url(), service.resolve("1", "standard", false).contextWrite(principal).block(timeout).url());
        verify(tools, times(3)).resolveSongUrl(anyMap(), any());
    }

    private static Secret secret(String version) {
        var secret = new Secret();
        var metadata = new Metadata();
        metadata.setName("ncm-account-user-qa");
        metadata.setLabels(Map.of(SecretStore.LABEL_PLUGIN, SecretStore.PLUGIN_NAME,
            SecretStore.LABEL_PURPOSE, "account"));
        secret.setMetadata(metadata);
        secret.setStringData(Map.of("cookie", "enc:v1:" + version));
        return secret;
    }

    @Test
    void playBody() throws Exception {
        JsonNode item = json("{'url':'https://a/b.mp3','freeTrialInfo':{'start':0,'end':30000}}");
        var r = PlayService.classify(new SongUrl(ok(item), "https://a/b.mp3", item), "standard", "guest");
        Map<String, Object> withUrl = PlayerRouter.playBody("9", r, true);
        assertEquals("ok", withUrl.get("status"));
        assertEquals("https://a/b.mp3", withUrl.get("url"));
        assertEquals(Map.of("start", 0L, "end", 30000L), withUrl.get("trial"));
        assertFalse(PlayerRouter.playBody("9", r, false).containsKey("url"));
        assertEquals(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
            PlayerRouter.statusOf(PlayService.service("guest", null)));
    }
}
