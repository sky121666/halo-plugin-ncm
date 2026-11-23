package cn.sky.ncm.featured;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.sky.ncm.client.NcmResponse;
import cn.sky.ncm.featured.FeaturedInput.Kind;
import cn.sky.ncm.finder.vo.PlaylistVo;
import cn.sky.ncm.gateway.Identity;
import cn.sky.ncm.setting.PageSetting;
import cn.sky.ncm.setting.SettingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

class FeaturedServiceTest {

    static final ObjectMapper M = new ObjectMapper();

    final ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
    final FeaturedRemote remote = mock(FeaturedRemote.class);
    final SettingService settings = mock(SettingService.class);
    final Map<String, NcmFeaturedPlaylist> store = new ConcurrentHashMap<>();
    volatile ConfigMap marker;
    FeaturedService service;

    static NcmResponse resp(int status, String json) {
        try {
            return new NcmResponse(status, M.readTree(json.replace('\'', '"')), List.of());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static NcmResponse playlist(String id, String name, int privacy) {
        return resp(200, "{'code':200,'playlist':{'id':" + id + ",'name':'" + name
            + "','coverImgUrl':'http://p1.music.126.net/a.jpg','trackCount':2,'privacy':" + privacy
            + ",'creator':{'userId':1,'nickname':'作者'}}}");
    }

    static PageSetting page(String ids) {
        return new PageSetting(true, "音乐", "", ids, 50, true, 20, true, 60);
    }

    void put(String id, int priority, boolean published, String state) {
        var p = FeaturedService.newRecord(id, priority, published);
        ((Metadata) p.getMetadata()).setCreationTimestamp(Instant.parse("2026-11-21T00:00:00Z"));
        p.getStatus().setState(state);
        store.put(p.getMetadata().getName(), p);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(settings.legacyFeaturedIds()).thenReturn(Mono.just(page("").featuredIds()));
        when(client.list(eq(NcmFeaturedPlaylist.class), any(), any())).thenAnswer(inv -> {
            Predicate<NcmFeaturedPlaylist> pred = inv.getArgument(1);
            Comparator<NcmFeaturedPlaylist> cmp = inv.getArgument(2);
            return Flux.fromIterable(store.values().stream().filter(pred).sorted(cmp).toList());
        });
        when(client.fetch(eq(NcmFeaturedPlaylist.class), anyString()))
            .thenAnswer(inv -> Mono.justOrEmpty(store.get((String) inv.getArgument(1))));
        when(client.create(any(NcmFeaturedPlaylist.class))).thenAnswer(inv -> {
            NcmFeaturedPlaylist p = inv.getArgument(0);
            String name = p.getMetadata().getName();
            if (store.containsKey(name)) {
                return Mono.error(new IllegalStateException("已存在"));
            }
            ((Metadata) p.getMetadata()).setCreationTimestamp(Instant.now());
            store.put(name, p);
            return Mono.just(p);
        });
        when(client.update(any(NcmFeaturedPlaylist.class))).thenAnswer(inv -> {
            NcmFeaturedPlaylist p = inv.getArgument(0);
            store.put(p.getMetadata().getName(), p);
            return Mono.just(p);
        });
        when(client.delete(any(NcmFeaturedPlaylist.class))).thenAnswer(inv -> {
            NcmFeaturedPlaylist p = inv.getArgument(0);
            store.remove(p.getMetadata().getName());
            return Mono.just(p);
        });
        when(client.fetch(eq(ConfigMap.class), anyString())).thenAnswer(inv -> Mono.justOrEmpty(marker));
        when(client.create(any(ConfigMap.class))).thenAnswer(inv -> {
            marker = inv.getArgument(0);
            return Mono.just(marker);
        });
        when(client.update(any(ConfigMap.class))).thenAnswer(inv -> {
            marker = inv.getArgument(0);
            return Mono.just(marker);
        });
        when(remote.site()).thenReturn(Mono.empty());
        when(remote.detail(anyString(), any(Identity.class))).thenReturn(Mono.just(resp(404, "{'code':404}")));
        when(remote.tracks(anyString(), any(Identity.class)))
            .thenReturn(Mono.just(resp(200, "{'songs':[],'privileges':[]}")));
        service = new FeaturedService(client, remote, settings);
    }

    @Test
    void homeShowsOnlyPublishedAndPublicInAdminOrder() {
        put("1", 20, true, "ok");
        put("2", 10, true, "ok");
        put("3", 5, false, "ok");
        put("4", 30, true, "ok");
        put("5", 40, true, "ok");
        store.get("playlist-1").getSpec().setTitle("站长起的名字");
        when(remote.detail(eq("1"), any(Identity.class))).thenReturn(Mono.just(playlist("1", "一", 0)));
        when(remote.detail(eq("2"), any(Identity.class))).thenReturn(Mono.just(playlist("2", "二", 0)));
        when(remote.detail(eq("3"), any(Identity.class))).thenReturn(Mono.just(playlist("3", "草稿", 0)));
        // 4 已经变成私密，5 已经删除
        when(remote.detail(eq("4"), any(Identity.class))).thenReturn(Mono.just(playlist("4", "私密", 10)));
        when(remote.detail(eq("5"), any(Identity.class))).thenReturn(Mono.just(resp(404, "{'code':404}")));

        StepVerifier.create(service.forHome().map(p -> p.getId() + ":" + p.getName()).collectList())
            .assertNext(list -> assertEquals(List.of("2:二", "1:站长起的名字"), list))
            .verifyComplete();
    }

    @Test
    void homeUsesLastConfirmedStatusOnlyWhenNeteaseIsDown() {
        put("6", 10, true, "ok");
        store.get("playlist-6").getStatus().setName("上次核对的名字");
        put("7", 20, true, "unchecked");
        put("8", 30, true, "private");
        when(remote.detail(anyString(), any(Identity.class))).thenReturn(Mono.just(resp(503, "{'code':503}")));

        StepVerifier.create(service.forHome().map(p -> p.getId() + ":" + p.getName()).collectList())
            .assertNext(list -> assertEquals(List.of("6:上次核对的名字"), list))
            .verifyComplete();
    }

    @Test
    void homeDisclosesPartialFailureAndRetriesCachedErrors() {
        put("1", 10, true, "ok");
        put("2", 20, true, "unchecked");
        when(remote.detail(eq("1"), any(Identity.class))).thenReturn(Mono.just(playlist("1", "一", 0)));
        when(remote.detail(eq("2"), any(Identity.class)))
            .thenReturn(Mono.just(resp(503, "{'code':503}")))
            .thenReturn(Mono.just(playlist("2", "二", 0)));

        StepVerifier.create(service.homePlaylists(false))
            .assertNext(view -> {
                assertTrue(view.unavailable());
                assertEquals(List.of("1"), view.playlists().stream().map(PlaylistVo::getId).toList());
            }).verifyComplete();
        StepVerifier.create(service.homePlaylists(true))
            .assertNext(view -> {
                assertFalse(view.unavailable());
                assertEquals(List.of("1", "2"), view.playlists().stream().map(PlaylistVo::getId).toList());
            }).verifyComplete();
        verify(remote, times(1)).detail(eq("1"), any(Identity.class));
        verify(remote, times(2)).detail(eq("2"), any(Identity.class));
    }

    @Test
    void createDraftThenConflict() {
        when(remote.detail(eq("1"), any(Identity.class))).thenReturn(Mono.just(playlist("1", "一", 0)));
        StepVerifier.create(service.create("1", "  标题  ", "", false))
            .assertNext(p -> {
                assertEquals("playlist-1", p.getMetadata().getName());
                assertFalse(p.getSpec().getPublished(), "默认草稿");
                assertEquals("标题", p.getSpec().getTitle());
                assertEquals(null, p.getSpec().getDescription(), "空简介用网易云的");
                assertEquals(FeaturedCheck.OK, p.getStatus().getState(), "添加后立即核对");
                assertNotNull(p.getStatus().getGuest());
            })
            .verifyComplete();
        StepVerifier.create(service.create("1", null, null, false))
            .expectErrorMatches(e -> e instanceof ResponseStatusException r && r.getStatusCode() == HttpStatus.CONFLICT)
            .verify();
        StepVerifier.create(service.create("abc", null, null, false))
            .expectErrorMatches(e -> e instanceof ResponseStatusException r && r.getStatusCode() == HttpStatus.BAD_REQUEST)
            .verify();
    }

    @Test
    void publishOnlyWhenPublicAndUnpublish() {
        put("1", 10, false, "unchecked");
        put("4", 20, false, "unchecked");
        when(remote.detail(eq("1"), any(Identity.class))).thenReturn(Mono.just(playlist("1", "一", 0)));
        when(remote.detail(eq("4"), any(Identity.class))).thenReturn(Mono.just(playlist("4", "私密", 10)));

        StepVerifier.create(service.setPublished("playlist-4", true))
            .expectErrorMatches(e -> e instanceof ResponseStatusException r && r.getStatusCode() == HttpStatus.CONFLICT
                && r.getReason().contains("私密"))
            .verify();
        assertFalse(store.get("playlist-4").getSpec().getPublished());
        assertEquals(FeaturedCheck.PRIVATE, store.get("playlist-4").getStatus().getState());

        StepVerifier.create(service.setPublished("playlist-1", true))
            .assertNext(p -> assertTrue(p.getSpec().getPublished()))
            .verifyComplete();
        StepVerifier.create(service.setPublished("playlist-1", false))
            .assertNext(p -> assertFalse(p.getSpec().getPublished()))
            .verifyComplete();
    }

    @Test
    void publishIsRefusedWhileUnconfirmed() {
        put("9", 10, false, "unchecked");
        when(remote.detail(eq("9"), any(Identity.class))).thenReturn(Mono.just(resp(429, "{'code':429}")));
        StepVerifier.create(service.setPublished("playlist-9", true))
            .expectErrorMatches(e -> e instanceof ResponseStatusException r && r.getReason().contains("没能确认"))
            .verify();
        assertFalse(store.get("playlist-9").getSpec().getPublished());
    }

    @Test
    void legacyIdsAreMigratedOnceInOrder() {
        when(settings.legacyFeaturedIds()).thenReturn(Mono.just(page("30, 10").featuredIds()));
        StepVerifier.create(service.list().map(l -> l.stream().map(FeaturedOrder::name).toList()))
            .assertNext(names -> assertEquals(List.of("playlist-30", "playlist-10"), names))
            .verifyComplete();
        assertTrue(store.get("playlist-30").getSpec().getPublished(), "和升级前一样显示");
        assertEquals("10,30", marker.getData().get(FeaturedService.MARKER_KEY));

        // 站长删掉一个后重启（新的服务实例），不会再出现；也不会重复创建
        store.remove("playlist-30");
        var again = new FeaturedService(client, remote, settings);
        StepVerifier.create(again.list().map(l -> l.stream().map(FeaturedOrder::name).toList()))
            .assertNext(names -> assertEquals(List.of("playlist-10"), names))
            .verifyComplete();
    }

    @Test
    void migrationDoesNotTouchExistingRecordsAndAppends() {
        put("10", 10, false, "ok");
        store.get("playlist-10").getSpec().setTitle("站长改过");
        when(settings.legacyFeaturedIds()).thenReturn(Mono.just(page("10,20").featuredIds()));
        StepVerifier.create(service.list().map(l -> l.stream().map(FeaturedOrder::name).toList()))
            .assertNext(names -> assertEquals(List.of("playlist-10", "playlist-20"), names))
            .verifyComplete();
        assertFalse(store.get("playlist-10").getSpec().getPublished(), "已有记录保持草稿");
        assertEquals("站长改过", store.get("playlist-10").getSpec().getTitle());
        assertEquals(20, store.get("playlist-20").getSpec().getPriority());
    }

    @Test
    void noLegacyIdsMeansNoMarkerWrite() {
        StepVerifier.create(service.list()).assertNext(l -> assertTrue(l.isEmpty())).verifyComplete();
        verify(client, never()).create(any(ConfigMap.class));
    }

    @Test
    void reorder() {
        put("1", 10, true, "ok");
        put("2", 20, true, "ok");
        StepVerifier.create(service.reorder(List.of("playlist-2")))
            .expectErrorMatches(e -> e instanceof ResponseStatusException r && r.getStatusCode() == HttpStatus.CONFLICT)
            .verify();
        StepVerifier.create(service.reorder(List.of("playlist-2", "playlist-1")).map(l -> l.stream()
                .map(FeaturedOrder::name).toList()))
            .assertNext(names -> assertEquals(List.of("playlist-2", "playlist-1"), names))
            .verifyComplete();
        assertEquals(10, store.get("playlist-2").getSpec().getPriority());
        assertEquals(20, store.get("playlist-1").getSpec().getPriority());
    }

    @Test
    void deleteRemovesFromHome() {
        put("1", 10, true, "ok");
        when(remote.detail(eq("1"), any(Identity.class))).thenReturn(Mono.just(playlist("1", "一", 0)));
        StepVerifier.create(service.delete("playlist-1")).verifyComplete();
        StepVerifier.create(service.forHome().collectList()).assertNext(l -> assertTrue(l.isEmpty())).verifyComplete();
    }

    @Test
    void shortLinksAreExpandedSafely() {
        // FeaturedRemote.expandShort 直接返回识别结果：跳转地址用 parseLocation 校验，没有跳转时为 Parsed.error
        when(remote.expandShort("good1")).thenReturn(Mono.just(
            FeaturedInput.parseLocation("https://y.music.163.com/m/playlist?id=2468&userid=1")));
        when(remote.expandShort("evil1")).thenReturn(Mono.just(
            FeaturedInput.parseLocation("http://127.0.0.1:8090/apis/secret")));
        when(remote.expandShort("dead1")).thenReturn(Mono.just(
            FeaturedInput.Parsed.error("bad_short", "短链无效或已失效")));
        when(remote.expandShort("down1")).thenReturn(Mono.error(new IllegalStateException("timeout")));

        StepVerifier.create(service.resolve("https://163cn.tv/good1"))
            .assertNext(p -> {
                assertEquals(Kind.ID, p.kind());
                assertEquals("2468", p.playlistId());
            })
            .verifyComplete();
        StepVerifier.create(service.resolve("163cn.tv/evil1"))
            .assertNext(p -> assertEquals("redirect_rejected", p.error())).verifyComplete();
        StepVerifier.create(service.resolve("163cn.tv/dead1"))
            .assertNext(p -> assertEquals("bad_short", p.error())).verifyComplete();
        StepVerifier.create(service.resolve("163cn.tv/down1"))
            .assertNext(p -> assertEquals("short_failed", p.error())).verifyComplete();
        // 不是短链时不发请求
        StepVerifier.create(service.resolve("https://evil.com/x"))
            .assertNext(p -> assertEquals("not_netease", p.error())).verifyComplete();
    }

    @Test
    void transientErrorKeepsLastConfirmedState() {
        var p = FeaturedService.newRecord("1", 10, true);
        p.getStatus().setState(FeaturedCheck.OK);
        p.getStatus().setName("旧名字");
        var err = new FeaturedService.CheckResult(
            new FeaturedCheck.Access(FeaturedCheck.ERROR, null, "暂时无法确认"), null, null, null, null, false);
        FeaturedService.applyStatus(p, err, Instant.parse("2026-11-21T10:00:00Z"));
        assertEquals(FeaturedCheck.OK, p.getStatus().getState());
        assertEquals("旧名字", p.getStatus().getName());
        assertEquals("暂时无法确认", p.getStatus().getLastError());
        assertEquals("2026-11-21T10:00:00Z", p.getStatus().getCheckedAt());

        var fresh = FeaturedService.newRecord("2", 10, false);
        FeaturedService.applyStatus(fresh, err, Instant.now());
        assertEquals(FeaturedCheck.ERROR, fresh.getStatus().getState());
    }

    @Test
    void cleanText() {
        assertEquals(null, FeaturedService.clean("   ", 80, "标题"));
        assertEquals("a\nb", FeaturedService.clean(" a\u0007\nb ", 80, "标题"));
        StepVerifier.create(service.create("1", "x".repeat(81), null, false))
            .expectErrorMatches(e -> e instanceof ResponseStatusException r && r.getStatusCode() == HttpStatus.BAD_REQUEST)
            .verify();
    }

    @Test
    void publishUsesThisCheckEvenIfLastWasOk() {
        // 上一次核对是可公开，这一次限流 / 超时：只能保留草稿
        put("9", 10, false, "ok");
        when(remote.detail(eq("9"), any(Identity.class))).thenReturn(Mono.just(resp(429, "{'code':429}")));
        StepVerifier.create(service.setPublished("playlist-9", true))
            .expectErrorMatches(e -> e instanceof ResponseStatusException r && r.getStatusCode() == HttpStatus.CONFLICT)
            .verify();
        assertFalse(store.get("playlist-9").getSpec().getPublished());

        when(remote.detail(eq("9"), any(Identity.class)))
            .thenReturn(Mono.error(new java.util.concurrent.TimeoutException()));
        StepVerifier.create(service.setPublished("playlist-9", true))
            .expectError(ResponseStatusException.class)
            .verify();
        assertFalse(store.get("playlist-9").getSpec().getPublished());
    }

    @Test
    void createAndPublishChecksOnlyOnce() {
        when(remote.detail(eq("1"), any(Identity.class))).thenReturn(Mono.just(playlist("1", "一", 0)));
        StepVerifier.create(service.create("1", null, null, true))
            .assertNext(p -> {
                assertTrue(p.getSpec().getPublished());
                assertEquals(FeaturedCheck.OK, p.getStatus().getState());
            })
            .verifyComplete();
        verify(remote, times(1)).detail(eq("1"), any(Identity.class));
        verify(remote, times(1)).tracks(eq("1"), any(Identity.class));
    }

    @Test
    void createAndPublishRefusedKeepsDraft() {
        when(remote.detail(eq("4"), any(Identity.class))).thenReturn(Mono.just(playlist("4", "私密", 10)));
        StepVerifier.create(service.create("4", null, null, true))
            .expectErrorMatches(e -> e instanceof ResponseStatusException r && r.getStatusCode() == HttpStatus.CONFLICT
                && r.getReason().contains("草稿"))
            .verify();
        assertFalse(store.get("playlist-4").getSpec().getPublished());
        assertEquals(FeaturedCheck.PRIVATE, store.get("playlist-4").getStatus().getState());
        verify(remote, times(1)).detail(eq("4"), any(Identity.class));
    }

    @Test
    void emptyShellOrBusinessFailureTracksArePending() {
        // 歌单有 2 首歌，曲目接口却返回空列表：全部待确认
        when(remote.detail(eq("1"), any(Identity.class))).thenReturn(Mono.just(playlist("1", "一", 0)));
        when(remote.tracks(eq("1"), any(Identity.class)))
            .thenReturn(Mono.just(resp(200, "{'code':200,'songs':[],'privileges':[]}")));
        StepVerifier.create(service.inspect("1"))
            .assertNext(r -> {
                assertEquals(2, r.guest().getPending());
                assertEquals(0, r.guest().getFull());
                assertEquals(0, r.guest().getUnavailable());
            })
            .verifyComplete();
        // 业务失败（body.code 不是 200）
        when(remote.tracks(eq("1"), any(Identity.class)))
            .thenReturn(Mono.just(resp(200, "{'code':-460,'msg':'Cheating'}")));
        StepVerifier.create(service.inspect("1"))
            .assertNext(r -> assertEquals(2, r.guest().getPending()))
            .verifyComplete();
    }

    @Test
    void legacyIdsWithLeadingZerosAreDeduplicatedInOrder() {
        when(settings.legacyFeaturedIds()).thenReturn(Mono.just(page("0030, 10, 30, 010").featuredIds()));
        StepVerifier.create(service.list().map(l -> l.stream().map(FeaturedOrder::name).toList()))
            .assertNext(names -> assertEquals(List.of("playlist-30", "playlist-10"), names))
            .verifyComplete();
        assertEquals("30", store.get("playlist-30").getSpec().getPlaylistId());
    }

    @Test
    void detailPageShowsCustomTextOnlyForVisible() {
        put("1", 10, true, "ok");
        store.get("playlist-1").getSpec().setTitle("站长的标题");
        store.get("playlist-1").getSpec().setDescription("站长的简介");
        put("2", 20, false, "ok");
        store.get("playlist-2").getSpec().setTitle("草稿标题");
        put("3", 30, true, "private");
        store.get("playlist-3").getSpec().setTitle("私密标题");

        StepVerifier.create(service.decorate("1", base("1")))
            .assertNext(p -> {
                assertEquals("站长的标题", p.getName());
                assertEquals("站长的简介", p.getDescription());
            })
            .verifyComplete();
        StepVerifier.create(service.decorate("2", base("2")))
            .assertNext(p -> assertEquals("原名", p.getName())).verifyComplete();
        StepVerifier.create(service.decorate("3", base("3")))
            .assertNext(p -> assertEquals("原名", p.getName())).verifyComplete();
        StepVerifier.create(service.decorate("9", base("9")))
            .assertNext(p -> assertEquals("原简介", p.getDescription())).verifyComplete();
    }

    static PlaylistVo base(String id) {
        return PlaylistVo.builder().id(id).name("原名").description("原简介").tags(List.of()).tracks(List.of()).build();
    }
}
