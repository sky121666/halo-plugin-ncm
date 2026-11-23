package cn.sky.ncm.api.more;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import cn.sky.ncm.client.NcmClient;
import cn.sky.ncm.client.NcmHttp;
import cn.sky.ncm.client.NcmResponse;
import cn.sky.ncm.client.RequestOptions;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class SongUrlRedirectTest {

    private static final String PLAYBACK_PATH = "/api/song/enhance/player/url/v1";
    private final NcmClient client = mock(NcmClient.class);
    private final ToolApis tools = new ToolApis(client, mock(NcmHttp.class));

    @Test
    void redirectsToThePlaybackUrlForAVisitor() {
        var body = JsonNodeFactory.instance.objectNode().put("code", 200);
        body.putArray("data").addObject().put("url", "http://m701.music.126.net/sample.mp3");
        allowPlayback(NcmResponse.ok(body, List.of()));

        NcmResponse response = redirect();

        assertEquals(302, response.status());
        assertEquals("https://m701.music.126.net/sample.mp3", response.redirect());
        verify(client).request(eq(PLAYBACK_PATH),
            eq(Map.of("ids", "[123]", "level", "standard", "encodeType", "flac")),
            argThat(options -> "xeapi".equals(options.crypto()) && options.cookie().isEmpty()));
        verifyNoMoreInteractions(client);
    }

    @Test
    void preservesThePlaybackResponseWhenNoAudioUrlIsAvailable() {
        var body = JsonNodeFactory.instance.objectNode().put("code", 200);
        body.putArray("data").addObject().put("code", 404).putNull("url");
        NcmResponse unavailable = NcmResponse.ok(body, List.of());
        allowPlayback(unavailable);

        assertSame(unavailable, redirect());
    }

    private NcmResponse redirect() {
        var options = new RequestOptions("", Map.of(), null, null, false);
        return tools.handle("song_url_v1_302", Map.of("id", "123"), Map.of(), options)
            .block(Duration.ofSeconds(1));
    }

    private void allowPlayback(NcmResponse response) {
        when(client.request(anyString(), anyMap(), any(RequestOptions.class)))
            .thenAnswer(call -> Mono.just(PLAYBACK_PATH.equals(call.getArgument(0))
                ? response : NcmResponse.error(400, "The download endpoint is unavailable")));
    }
}
