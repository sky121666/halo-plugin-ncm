package cn.sky.ncm.endpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import cn.sky.ncm.featured.FeaturedService;
import cn.sky.ncm.featured.NcmFeaturedPlaylist;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.reactive.function.server.MockServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.extension.Metadata;

class NcmFeaturedEndpointTest {

    @Test
    void routerBuilds() {
        assertNotNull(new NcmFeaturedEndpoint(mock(FeaturedService.class)).endpoint());
    }

    @Test
    void writesNeedRequestHeader() {
        var post = MockServerRequest.builder().method(HttpMethod.POST).build();
        StepVerifier.create(NcmFeaturedEndpoint.guarded(post, () -> ServerResponse.ok().build()))
            .expectErrorMatches(e -> e instanceof ResponseStatusException r && r.getStatusCode() == HttpStatus.FORBIDDEN)
            .verify();
        var withHeader = MockServerRequest.builder().method(HttpMethod.POST)
            .header(NcmFeaturedEndpoint.REQUEST_HEADER, "1").build();
        StepVerifier.create(NcmFeaturedEndpoint.guarded(withHeader, () -> ServerResponse.ok().build()))
            .expectNextCount(1).verifyComplete();
        var get = MockServerRequest.builder().method(HttpMethod.GET).build();
        StepVerifier.create(NcmFeaturedEndpoint.guarded(get, () -> ServerResponse.ok().build()))
            .expectNextCount(1).verifyComplete();
        var delete = MockServerRequest.builder().method(HttpMethod.DELETE)
            .header(NcmFeaturedEndpoint.REQUEST_HEADER, "true").build();
        StepVerifier.create(NcmFeaturedEndpoint.guarded(delete, () -> Mono.empty()))
            .expectErrorMatches(e -> e instanceof ResponseStatusException r && r.getStatusCode() == HttpStatus.FORBIDDEN)
            .verify();
        var put = MockServerRequest.builder().method(HttpMethod.PUT).build();
        StepVerifier.create(NcmFeaturedEndpoint.guarded(put, () -> ServerResponse.ok().build()))
            .expectErrorMatches(e -> e instanceof ResponseStatusException r && r.getStatusCode() == HttpStatus.FORBIDDEN)
            .verify();
        assertEquals("X-NCM-Request", NcmFeaturedEndpoint.REQUEST_HEADER);
    }

    @Test
    void itemBodyMarksVisibility() {
        var p = new NcmFeaturedPlaylist();
        var m = new Metadata();
        m.setName("playlist-1");
        p.setMetadata(m);
        var s = new NcmFeaturedPlaylist.Spec();
        s.setPlaylistId("1");
        s.setPublished(true);
        p.setSpec(s);
        var st = new NcmFeaturedPlaylist.Status();
        st.setState("private");
        st.setName("网易云的名字");
        p.setStatus(st);
        Map<String, Object> body = NcmFeaturedEndpoint.itemBody(p);
        assertEquals(false, body.get("visible"), "已发布但私密：访客看不到");
        assertEquals("网易云的名字", body.get("displayTitle"));
        st.setState("ok");
        assertTrue((Boolean) NcmFeaturedEndpoint.itemBody(p).get("visible"));
        s.setTitle("站长的标题");
        assertEquals("站长的标题", NcmFeaturedEndpoint.itemBody(p).get("displayTitle"));
    }
}
