package cn.sky.ncm.me;

import static cn.sky.ncm.me.MeFixture.api;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import cn.sky.ncm.client.NcmResponse;
import cn.sky.ncm.client.RequestOptions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;

class MeServiceTest {

    private final MeFixture f = new MeFixture();
    private final ObjectMapper mapper = new ObjectMapper();

    JsonNode json(String s) throws Exception {
        return mapper.readTree(s);
    }

    // ---------- 身份 ----------

    @Test
    void unboundUserIsForbiddenWithGuidance() {
        StepVerifier.create(f.service.identity("reader"))
            .expectErrorSatisfies(e -> {
                var rse = (ResponseStatusException) e;
                assertEquals(HttpStatus.FORBIDDEN, rse.getStatusCode());
                assertEquals(MeService.UNBOUND, rse.getReason());
            })
            .verify();
    }

    @Test
    void neverBorrowsSiteAccountOfSomeoneElse() {
        f.givenSiteAccount("admin", 101L);
        StepVerifier.create(f.service.identity("reader"))
            .expectErrorSatisfies(e -> assertEquals(HttpStatus.FORBIDDEN, ((ResponseStatusException) e).getStatusCode()))
            .verify();
    }

    @Test
    void adminWithoutPersonalBindingUsesOwnSiteAccount() {
        f.givenSiteAccount("admin", 101L);
        StepVerifier.create(f.service.identity("admin"))
            .assertNext(id -> {
                assertEquals("101", id.uid());
                assertTrue(id.viaSiteAccount());
                assertEquals("fixture-site", id.cookie().get("MUSIC_U"));
            })
            .verifyComplete();
    }

    @Test
    void personalBindingWins() {
        f.givenSiteAccount("admin", 101L);
        f.givenPersonalAccount("admin", 202L);
        StepVerifier.create(f.service.identity("admin"))
            .assertNext(id -> {
                assertEquals("202", id.uid());
                assertFalse(id.viaSiteAccount());
            })
            .verifyComplete();
    }

    @Test
    void albumSubscriptionReturnsTheAccountStateAfterTheWrite() {
        f.givenApi("album_sub", "{\"code\":200}");
        f.givenApi("album_detail_dynamic", "{\"code\":200,\"isSub\":true}");
        var identity = f.identity("reader", "101");
        StepVerifier.create(f.service.subscribeAlbum(identity, "123", true))
            .assertNext(result -> assertEquals(true, result.get("subscribed")))
            .verifyComplete();
        verify(f.executor).execute(api("album_sub"), argThat(params ->
            "123".equals(params.get("id")) && "1".equals(params.get("t"))),
            argThat(options -> identity.cookie().equals(options.cookie())));
    }

    @Test
    void sceneRadioPassesTheSceneAndFallsBackWhenTheModeReturnsNoSongs() {
        f.givenApi("personal_fm_mode", "{\"code\":200,\"data\":[]}");
        f.givenApi("personal_fm", "{\"code\":200,\"data\":[{\"id\":123,\"name\":\"FM\"}]}");
        StepVerifier.create(f.service.foliaPersonalFm(f.identity("reader", "101"), "SCENE_RCMD", "STUDY"))
            .assertNext(result -> assertEquals(1, ((List<?>) result.get("songs")).size()))
            .verifyComplete();
        verify(f.executor).execute(api("personal_fm_mode"), argThat(params ->
            "SCENE_RCMD".equals(params.get("mode")) && "STUDY".equals(params.get("submode"))), any());
    }

    @Test
    void dailyRecommendationDislikeReportsTheQuotaResult() {
        f.givenApi("recommend_songs_dislike", "{\"code\":432}");
        StepVerifier.create(f.service.dislikeRecommendation(f.identity("reader", "101"), "99"))
            .assertNext(result -> assertEquals(true, result.get("limitReached")))
            .verifyComplete();
    }

    @Test
    void recentSongLimitAndPlaylistFailureAreIndependent() {
        String record = "{\"playTime\":1700000000000,\"data\":{\"id\":123,\"name\":\"测试\"}}";
        String records = String.join(",", java.util.Collections.nCopies(105, record));
        f.givenApi("record_recent_song", "{\"code\":200,\"data\":{\"list\":[" + records + "]}}");
        f.givenApi("record_recent_playlist", "{\"code\":503}");

        StepVerifier.create(f.service.recent(f.identity("reader", "101")))
            .assertNext(result -> {
                assertEquals(MeService.RECENT_LIMIT, result.getSongs().size());
                assertTrue(result.getPlaylists().isEmpty());
                assertTrue(result.isPlaylistsUnavailable());
            })
            .verifyComplete();
    }

    @Test
    void recentPlaylistsPreserveReportedTrackCounts() {
        f.givenApi("record_recent_song", """
            {"code":200,"data":{"list":[]}}
            """);
        f.givenApi("record_recent_playlist", """
            {"code":200,"data":{"list":[
                {"data":{"id":10,"name":"数量未知"}},
                {"data":{"id":20,"name":"空歌单","trackCount":0}},
                {"data":{"id":30,"name":"有歌曲","trackCount":28}}
            ]}}
            """);

        StepVerifier.create(f.service.recent(f.identity("reader", "101")))
            .assertNext(result -> {
                var entries = result.getPlaylists();
                assertEquals(3, entries.size());
                assertNull(entries.get(0).getPlaylist().getTrackCount());
                assertEquals(0, entries.get(1).getPlaylist().getTrackCount());
                assertEquals(28, entries.get(2).getPlaylist().getTrackCount());
                JsonNode serialized = mapper.valueToTree(result);
                JsonNode playlists = serialized.path("playlists");
                assertFalse(playlists.get(0).path("playlist").has("trackCount"));
                assertTrue(playlists.get(1).path("playlist").has("trackCount"));
            })
            .verifyComplete();
    }

    // ---------- 只能改自己创建的歌单 ----------

    @Test
    void checkOwnComparesCreatorWithMyUid() throws Exception {
        JsonNode mine = json("{\"id\":5,\"creator\":{\"userId\":101},\"specialType\":0}");
        assertSame(mine, MeService.checkOwn(mine, "5", "101", true));

        JsonNode others = json("{\"id\":5,\"creator\":{\"userId\":999}}");
        var e = assertThrows(ResponseStatusException.class, () -> MeService.checkOwn(others, "5", "101", false));
        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
        assertEquals(MeService.NOT_OWN, e.getReason());

        // 没有 creator 时用 userId
        JsonNode byUserId = json("{\"id\":5,\"userId\":101}");
        assertSame(byUserId, MeService.checkOwn(byUserId, "5", "101", false));

        // 不知道创建者、或者不知道自己的 UID：一律拒绝
        assertThrows(ResponseStatusException.class, () -> MeService.checkOwn(json("{\"id\":5}"), "5", "101", false));
        assertThrows(ResponseStatusException.class, () -> MeService.checkOwn(mine, "5", null, false));
    }

    @Test
    void likedSongsPlaylistCannotBeDeletedButCanBeEdited() throws Exception {
        JsonNode liked = json("{\"id\":5,\"creator\":{\"userId\":101},\"specialType\":5}");
        var e = assertThrows(ResponseStatusException.class, () -> MeService.checkOwn(liked, "5", "101", true));
        assertEquals(MeService.LIKED_PLAYLIST, e.getReason());
        assertSame(liked, MeService.checkOwn(liked, "5", "101", false));
    }

    @Test
    void deletingSomeoneElsesPlaylistNeverCallsDelete() {
        f.givenApi("playlist_detail", "{\"code\":200,\"playlist\":{\"id\":5,\"creator\":{\"userId\":999}}}");
        f.givenApi("playlist_delete", "{\"code\":200}");
        StepVerifier.create(f.service.deletePlaylist(f.identity("alice", "101"), "5"))
            .expectErrorSatisfies(e -> assertEquals(HttpStatus.FORBIDDEN, ((ResponseStatusException) e).getStatusCode()))
            .verify();
        verify(f.executor, never()).execute(api("playlist_delete"), anyMap(), any(RequestOptions.class));
    }

    @Test
    void addingTracksToOwnPlaylistPassesFixedParams() {
        f.givenApi("playlist_detail", "{\"code\":200,\"playlist\":{\"id\":5,\"creator\":{\"userId\":101}}}");
        f.givenApi("playlist_tracks", "{\"status\":200,\"body\":{\"code\":200,\"count\":2}}");
        StepVerifier.create(f.service.tracks(f.identity("alice", "101"), "5", List.of("11", "12"), true))
            .assertNext(r -> {
                assertEquals("add", r.getOp());
                assertEquals(List.of("11", "12"), r.getSongIds());
            })
            .verifyComplete();
        verify(f.executor).execute(api("playlist_tracks"),
            argThat(q -> "add".equals(q.get("op")) && "5".equals(q.get("pid")) && "11,12".equals(q.get("tracks"))),
            any(RequestOptions.class));
    }

    @Test
    void missingPlaylistIsNotFound() {
        f.givenApi("playlist_detail", "{\"code\":404,\"message\":\"资源不存在\"}");
        StepVerifier.create(f.service.deletePlaylist(f.identity("alice", "101"), "5"))
            .expectErrorSatisfies(e -> assertEquals(HttpStatus.BAD_REQUEST, ((ResponseStatusException) e).getStatusCode()))
            .verify();

        f.givenApi("playlist_detail", "{\"code\":200,\"playlist\":{\"id\":6,\"creator\":{\"userId\":101}}}");
        StepVerifier.create(f.service.deletePlaylist(f.identity("alice", "101"), "5"))
            .expectErrorSatisfies(e -> assertEquals(HttpStatus.NOT_FOUND, ((ResponseStatusException) e).getStatusCode()))
            .verify();
    }

    // ---------- likes/check ----------

    @Test
    void checkLikesReturnsEveryRequestedIdAndUsesCache() {
        f.givenApi("likelist", "{\"code\":200,\"ids\":[1,2,30]}");
        MeIdentity me = f.identity("alice", "101");

        StepVerifier.create(f.service.checkLikes(me, List.of("2", "3")))
            .assertNext(r -> {
                assertEquals(2, r.getLiked().size());
                assertEquals(true, r.getLiked().get("2"));
                assertEquals(false, r.getLiked().get("3"));
                assertEquals(List.of("2", "3"), List.copyOf(r.getLiked().keySet()));
            })
            .verifyComplete();
        StepVerifier.create(f.service.checkLikes(me, List.of("30"))).expectNextCount(1).verifyComplete();
        verify(f.executor, times(1)).execute(api("likelist"), anyMap(), any(RequestOptions.class));
        verify(f.executor).execute(api("likelist"), argThat(q -> "101".equals(q.get("uid"))), any(RequestOptions.class));
    }

    @Test
    void likeClearsTheCache() {
        f.givenApi("likelist", "{\"code\":200,\"ids\":[1]}");
        f.givenApi("like", "{\"code\":200}");
        MeIdentity me = f.identity("alice", "101");

        StepVerifier.create(f.service.checkLikes(me, List.of("1"))).expectNextCount(1).verifyComplete();
        StepVerifier.create(f.service.like(me, "2", true))
            .assertNext(r -> {
                assertEquals("2", r.getSongId());
                assertTrue(r.getLiked());
            })
            .verifyComplete();
        StepVerifier.create(f.service.checkLikes(me, List.of("1"))).expectNextCount(1).verifyComplete();
        verify(f.executor, times(2)).execute(api("likelist"), anyMap(), any(RequestOptions.class));
        verify(f.executor).execute(api("like"), argThat(q -> "2".equals(q.get("id")) && "true".equals(q.get("like"))),
            any(RequestOptions.class));
    }

    @Test
    void expiredCookieBecomesForbidden() {
        f.givenApi("likelist", "{\"code\":301,\"msg\":\"需要登录\"}");
        StepVerifier.create(f.service.checkLikes(f.identity("alice", "101"), List.of("1")))
            .expectErrorSatisfies(e -> {
                assertEquals(HttpStatus.FORBIDDEN, ((ResponseStatusException) e).getStatusCode());
                assertEquals(MeService.EXPIRED, ((ResponseStatusException) e).getReason());
            })
            .verify();
    }

    // ---------- 其他 ----------

    @Test
    void likesPageKeepsLikelistOrder() {
        f.givenApi("likelist", "{\"code\":200,\"ids\":[3,1,2]}");
        f.givenApi("song_detail", "{\"code\":200,\"songs\":[{\"id\":1,\"name\":\"一\"},{\"id\":2,\"name\":\"二\"}],"
            + "\"privileges\":[{\"id\":1,\"st\":0,\"pl\":320000}]}");
        StepVerifier.create(f.service.likes(f.identity("alice", "101"), 1, 2))
            .assertNext(p -> {
                assertEquals(3, p.getTotal());
                assertEquals(List.of("1", "2"), p.getItems().stream().map(s -> s.getId()).toList());
                assertEquals("/music/song/1", p.getItems().get(0).getSitePath());
                assertEquals(true, p.getItems().get(0).getPlayable());
            })
            .verifyComplete();
        verify(f.executor).execute(api("song_detail"), argThat(q -> "1,2".equals(q.get("ids"))),
            any(RequestOptions.class));
    }

    @Test
    void playlistsPutLikedSongsFirstThenOwn() {
        f.givenApi("user_playlist", "{\"code\":200,\"playlist\":["
            + "{\"id\":1,\"name\":\"收藏\",\"creator\":{\"userId\":999}},"
            + "{\"id\":2,\"name\":\"自建\",\"creator\":{\"userId\":101}},"
            + "{\"id\":3,\"name\":\"我喜欢的音乐\",\"specialType\":5,\"creator\":{\"userId\":101}}]}");
        StepVerifier.create(f.service.playlists(f.identity("alice", "101")))
            .assertNext(l -> {
                assertEquals(List.of("3", "2", "1"), l.getItems().stream().map(p -> p.getId()).toList());
                assertTrue(l.getItems().get(0).getLikedSongs());
                assertFalse(l.getItems().get(2).getOwn());
                assertEquals("/music/playlist/1", l.getItems().get(2).getSitePath());
            })
            .verifyComplete();
    }

    @Test
    void errorCodes() throws Exception {
        assertEquals(200, MeService.ncmCode(new NcmResponse(200, json("{\"status\":200,\"body\":{\"code\":200}}"), List.of())));
        assertEquals(502, MeService.ncmCode(new NcmResponse(200, json("{\"code\":502}"), List.of())));
        var e = assertThrows(ResponseStatusException.class, () ->
            MeService.ensureOk(new NcmResponse(200, json("{\"code\":502,\"message\":\"歌曲已存在\"}"), List.of()), "加入歌单"));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertTrue(e.getReason().contains("歌曲已存在"));
    }
}
