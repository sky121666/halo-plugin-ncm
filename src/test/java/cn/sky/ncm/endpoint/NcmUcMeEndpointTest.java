package cn.sky.ncm.endpoint;

import static cn.sky.ncm.me.MeFixture.api;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.sky.ncm.bootstrap.NcmBootstrap;
import cn.sky.ncm.client.RequestOptions;
import cn.sky.ncm.me.MeFixture;
import cn.sky.ncm.security.RateLimiter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

class NcmUcMeEndpointTest {

    private final MeFixture f = new MeFixture();
    private final NcmBootstrap bootstrap = mock(NcmBootstrap.class);
    private final NcmUcMeEndpoint endpoint;

    NcmUcMeEndpointTest() {
        when(bootstrap.ready()).thenReturn(Mono.empty());
        endpoint = new NcmUcMeEndpoint(f.service, new RateLimiter(), f.settings, bootstrap);
    }

    private WebTestClient as(String username) {
        var auth = new UsernamePasswordAuthenticationToken(username, null, List.of());
        var router = endpoint.endpoint().filter((request, next) -> next.handle(request)
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)));
        return WebTestClient.bindToRouterFunction(router).build();
    }

    @Test
    void anonymousGets401() {
        WebTestClient.bindToRouterFunction(endpoint.endpoint()).build()
            .get().uri("/me/-/likes/check?ids=1").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void unboundUserGets403() {
        as("reader").get().uri("/me/-/playlists").exchange().expectStatus().isForbidden();
        verify(f.executor, never()).execute(any(), anyMap(), any(RequestOptions.class));
    }

    @Test
    void cloudPlaybackCannotResolveAnIdOutsideTheBoundAccountsCloud() {
        f.givenPersonalAccount("alice", 101L);
        f.givenApi("user_cloud_detail", "{\"code\":200,\"data\":[{\"songId\":9}]}");
        as("alice").get().uri("/me/-/cloud/8/play").exchange().expectStatus().isNotFound();
        verify(f.plays, never()).resolveForAccount(any(), any(), anyMap());
    }

    @Test
    void playbackReportUsesWriteGuardAndCurrentAccount() {
        f.givenPersonalAccount("alice", 101L);
        f.givenApi("scrobble_v1", "{\"code\":200}");
        var client = as("alice");
        String report = "{\"songId\":\"8\",\"playedSeconds\":45,\"totalSeconds\":120,\"quality\":\"lossless\"}";
        client.post().uri("/me/-/records/playback").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(report).exchange().expectStatus().isForbidden();
        client.post().uri("/me/-/records/playback").contentType(MediaType.APPLICATION_JSON)
            .header("X-NCM-Request", "1").bodyValue(report).exchange().expectStatus().isNoContent();
        verify(f.executor, times(1)).execute(api("scrobble_v1"), anyMap(), any(RequestOptions.class));
    }

    @Test
    void likesCheckContract() {
        f.givenPersonalAccount("alice", 101L);
        f.givenApi("likelist", "{\"code\":200,\"ids\":[1,2]}");

        as("alice").get().uri("/me/-/likes/check?ids=1,3").exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
            .expectHeader().valueMatches("Cache-Control", ".*no-store.*")
            .expectBody()
            .jsonPath("$.liked['1']").isEqualTo(true)
            .jsonPath("$.liked['3']").isEqualTo(false)
            .jsonPath("$.liked['2']").doesNotExist();

        // 第二次走缓存，不再请求网易云
        as("alice").get().uri("/me/-/likes/check?ids=2").exchange()
            .expectStatus().isOk().expectBody().jsonPath("$.liked['2']").isEqualTo(true);
        verify(f.executor, times(1)).execute(api("likelist"), anyMap(), any(RequestOptions.class));
    }

    @Test
    void likesCheckValidatesIds() {
        f.givenPersonalAccount("alice", 101L);
        as("alice").get().uri("/me/-/likes/check?ids=abc").exchange().expectStatus().isBadRequest();
        as("alice").get().uri("/me/-/likes/check").exchange().expectStatus().isBadRequest();
        StringBuilder many = new StringBuilder();
        for (int i = 1; i <= 201; i++) {
            many.append(i).append(',');
        }
        as("alice").get().uri("/me/-/likes/check?ids=" + many).exchange().expectStatus().isBadRequest();
    }

    @Test
    void likeNeedsRequestHeaderAndClearsCache() {
        f.givenPersonalAccount("alice", 101L);
        f.givenApi("likelist", "{\"code\":200,\"ids\":[1]}");
        f.givenApi("like", "{\"code\":200}");

        as("alice").put().uri("/me/-/likes/3").exchange().expectStatus().isForbidden();
        verify(f.executor, never()).execute(api("like"), anyMap(), any(RequestOptions.class));

        as("alice").get().uri("/me/-/likes/check?ids=3").exchange().expectStatus().isOk();
        as("alice").put().uri("/me/-/likes/3").header("X-NCM-Request", "1").exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.songId").isEqualTo("3")
            .jsonPath("$.liked").isEqualTo(true);
        as("alice").delete().uri("/me/-/likes/3").header("X-NCM-Request", "1").exchange()
            .expectStatus().isOk()
            .expectBody().jsonPath("$.liked").isEqualTo(false);
        as("alice").get().uri("/me/-/likes/check?ids=3").exchange().expectStatus().isOk();
        verify(f.executor, times(2)).execute(api("likelist"), anyMap(), any(RequestOptions.class));
    }

    @Test
    void writeValidatesBody() {
        f.givenPersonalAccount("alice", 101L);
        as("alice").post().uri("/me/-/playlists").header("X-NCM-Request", "1")
            .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"name\":\"  \"}")
            .exchange().expectStatus().isBadRequest();
        as("alice").post().uri("/me/-/playlists").header("X-NCM-Request", "1")
            .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"name\":\"新歌单\",\"privacy\":3}")
            .exchange().expectStatus().isBadRequest();
        as("alice").post().uri("/playlists/abc/tracks").header("X-NCM-Request", "1")
            .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"songIds\":[\"1\"]}")
            .exchange().expectStatus().isBadRequest();
        verify(f.executor, never()).execute(any(), anyMap(), any(RequestOptions.class));
    }

    @Test
    void cannotDeleteOthersPlaylist() {
        f.givenPersonalAccount("alice", 101L);
        f.givenApi("playlist_detail", "{\"code\":200,\"playlist\":{\"id\":5,\"creator\":{\"userId\":999}}}");
        as("alice").delete().uri("/me/-/playlists/5").header("X-NCM-Request", "1").exchange()
            .expectStatus().isForbidden();
        verify(f.executor, never()).execute(api("playlist_delete"), anyMap(), any(RequestOptions.class));
    }

    @Test
    void deleteOwnPlaylistReturns204() {
        f.givenPersonalAccount("alice", 101L);
        f.givenApi("playlist_detail", "{\"code\":200,\"playlist\":{\"id\":5,\"creator\":{\"userId\":101}}}");
        f.givenApi("playlist_delete", "{\"code\":200}");
        as("alice").delete().uri("/me/-/playlists/5").header("X-NCM-Request", "1").exchange()
            .expectStatus().isNoContent();
    }

    @Test
    void routerBuildsWithOpenApiDocs() {
        org.junit.jupiter.api.Assertions.assertNotNull(endpoint.endpoint());
    }

    @Test
    void collectionStatusAndWriteUseTheCurrentUser() {
        f.givenPersonalAccount("alice", 101L);
        f.givenApi("playlist_detail", "{\"code\":200,\"playlist\":{\"id\":5,\"userId\":999,\"subscribed\":false}}");
        f.givenApi("playlist_subscribe", "{\"code\":200}");
        as("alice").get().uri("/me/-/subscriptions/5").exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.subscribed").isEqualTo(false).jsonPath("$.own").isEqualTo(false);
        as("alice").put().uri("/me/-/subscriptions/5").exchange().expectStatus().isForbidden();
        as("alice").put().uri("/me/-/subscriptions/5").header("X-NCM-Request", "1")
            .exchange().expectStatus().isOk().expectBody().jsonPath("$.subscribed").isEqualTo(true);
        as("alice").delete().uri("/me/-/subscriptions/5").header("X-NCM-Request", "1")
            .exchange().expectStatus().isOk().expectBody().jsonPath("$.subscribed").isEqualTo(false);
        verify(f.executor, times(2)).execute(api("playlist_subscribe"), anyMap(), any(RequestOptions.class));
    }
}
