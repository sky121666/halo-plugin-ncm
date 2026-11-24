package cn.sky.ncm.ai;

import cn.sky.ncm.security.NcmRequestSecurity;
import static org.springdoc.core.fn.builders.requestbody.Builder.requestBodyBuilder;
import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.webflux.core.fn.SpringdocRouteBuilder.route;

import cn.sky.ncm.ai.MusicAiService.Segmentation;
import cn.sky.ncm.ai.MusicAiService.SegmentationInput;
import cn.sky.ncm.ai.MusicAiService.SegmentationTruncatedException;
import cn.sky.ncm.ai.MusicAiService.ThemeInput;
import cn.sky.ncm.ai.MusicAiService.ThemePair;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyExtractors;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

@Component
@Order(-1) // 升级期间两个包并存时，优先使用主插件的设置。
public class MusicAiEndpoint implements CustomEndpoint {
    private static final String API_VERSION = "api.ncm-ai.halo.run/v1alpha1";
    private static final String TAG = API_VERSION + "/MusicAi";
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder()
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build();

    private final MusicAiService musicAi;

    MusicAiEndpoint(MusicAiService musicAi) {
        this.musicAi = musicAi;
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion(API_VERSION);
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return route()
            .GET("capabilities/-", request -> ok(authentication().flatMap(musicAi::available).map(Capabilities::new)),
                builder -> builder.operationId("GetMusicAiCapabilities").tag(TAG)
                    .description("检查音乐 AI 开关和默认语言模型")
                    .response(responseBuilder().implementation(Capabilities.class)))
            .POST("themes", request -> ok(writer(request).flatMap(user -> readBody(request, ThemeInput.class)
                .flatMap(input -> musicAi.theme(user, input)))),
                builder -> builder.operationId("GenerateMusicAiTheme").tag(TAG)
                    .description("按歌名或歌词生成浅色与深色配色")
                    .requestBody(requestBodyBuilder().required(true).implementation(ThemeInput.class))
                    .response(responseBuilder().implementation(ThemePair.class)))
            .POST("segmentations", request -> ok(writer(request).flatMap(user -> readBody(request, SegmentationInput.class)
                .flatMap(input -> musicAi.segment(user, input))))
                .onErrorResume(SegmentationTruncatedException.class, ignored -> segmentationTruncated()),
                builder -> builder.operationId("GenerateMusicAiSegmentation").tag(TAG)
                    .description("逐行划分歌词词语，保留原文")
                    .requestBody(requestBodyBuilder().required(true).implementation(SegmentationInput.class))
                    .response(responseBuilder().implementation(Segmentation.class)))
            .build()
            .filter(NcmRequestSecurity::privateEndpoint);
    }

    private static Mono<ServerResponse> segmentationTruncated() {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "AI 分词输出不完整");
        problem.setProperty("code", "SEGMENTATION_TRUNCATED");
        return ServerResponse.status(problem.getStatus())
            .cacheControl(CacheControl.noStore().cachePrivate())
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .bodyValue(problem);
    }

    private static Mono<ServerResponse> ok(Mono<?> body) {
        return body.flatMap(value -> ServerResponse.ok()
            .cacheControl(CacheControl.noStore().cachePrivate())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(value));
    }

    private static Mono<Authentication> authentication() {
        return ReactiveSecurityContextHolder.getContext().map(SecurityContext::getAuthentication)
            .filter(auth -> auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken))
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录 Halo")));
    }

    private static Mono<Authentication> writer(ServerRequest request) {
        return authentication().flatMap(user -> {
            NcmRequestSecurity.requireBrowserWrite(request);
            if (request.headers().contentType().filter(MediaType.APPLICATION_JSON::isCompatibleWith).isEmpty()) {
                return Mono.error(new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "请提交 JSON"));
            }
            return Mono.just(user);
        });
    }

    private static <T> Mono<T> readBody(ServerRequest request, Class<T> type) {
        return DataBufferUtils.join(request.body(BodyExtractors.toDataBuffers()), MAX_BODY_BYTES)
            .onErrorMap(DataBufferLimitException.class, error ->
                new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "请求超出 64 KiB"))
            .switchIfEmpty(Mono.error(new ServerWebInputException("缺少请求内容")))
            .map(buffer -> {
                try {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    T input = JSON.readValue(bytes, type);
                    if (input == null) {
                        throw new ServerWebInputException("缺少请求内容");
                    }
                    return input;
                } catch (JsonProcessingException error) {
                    throw new ServerWebInputException("请求 JSON 格式无效");
                } catch (java.io.IOException error) {
                    throw new ServerWebInputException("无法读取请求内容");
                } finally {
                    DataBufferUtils.release(buffer);
                }
            });
    }

    public record Capabilities(boolean available) {}
}
