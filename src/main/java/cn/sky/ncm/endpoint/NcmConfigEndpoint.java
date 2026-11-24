package cn.sky.ncm.endpoint;

import cn.sky.ncm.security.NcmRequestSecurity;
import static cn.sky.ncm.endpoint.EndpointSupport.REQUEST_HEADER;
import static cn.sky.ncm.endpoint.EndpointSupport.jsonBody;
import static cn.sky.ncm.endpoint.EndpointSupport.op;
import static cn.sky.ncm.endpoint.EndpointSupport.pathParam;
import static cn.sky.ncm.endpoint.EndpointSupport.privateOk;
import static cn.sky.ncm.endpoint.EndpointSupport.returns;
import static cn.sky.ncm.endpoint.EndpointSupport.writeHeader;

import cn.sky.ncm.security.RateLimiter;
import cn.sky.ncm.setting.ClientConfigService;
import cn.sky.ncm.setting.ClientConfigService.Snapshot;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import run.halo.app.core.user.service.RoleService;

@Component
public class NcmConfigEndpoint implements CustomEndpoint {

    private static final String MINE = "my-configs/{clientId}";
    private static final String SITE = "config-defaults/{clientId}";
    private static final String RESTORE = SITE + "/restore";
    private static final String TAG = "api.ncm.halo.run/v1alpha1/ClientConfig";
    private static final String CLIENT_ID = "clientId";
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final int WRITES_PER_MINUTE = 60;
    private static final Pattern REVISION = Pattern.compile("[0-9a-f]{48}");
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .build();

    static {
        JSON.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
            .maxNestingDepth(16).maxStringLength(2048).maxNumberLength(32).build());
    }

    private final ClientConfigService preferences;
    private final RateLimiter limiter;
    private final RoleService roles;

    public NcmConfigEndpoint(ClientConfigService preferences, RateLimiter limiter, RoleService roles) {
        this.preferences = preferences;
        this.limiter = limiter;
        this.roles = roles;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return SpringdocRouteBuilder.route()
            .GET(SITE, request -> privateOk(preferences.site(request.pathVariable(CLIENT_ID)), Snapshot.class),
                builder -> returns(pathParam(op(builder, "GetNcmConfigDefaults", TAG, "读取站点默认"),
                    CLIENT_ID, "已登记的配置客户端标识"), Snapshot.class))
            .GET(MINE, this::mine,
                builder -> returns(pathParam(op(builder, "GetNcmMyConfig", TAG, "读取我的配置"),
                    CLIENT_ID, "已登记的配置客户端标识"), Mine.class))
            .PUT(MINE, this::saveMine,
                builder -> returns(writeHeader(jsonBody(pathParam(op(builder, "SaveNcmMyConfig", TAG,
                    "保存当前 Halo 用户的配置"), CLIENT_ID, "客户端标识"), Write.class)), Snapshot.class))
            .PUT(SITE, this::publish,
                builder -> returns(writeHeader(jsonBody(pathParam(op(builder, "PublishNcmConfigDefaults", TAG,
                    "仅 Halo 超级管理员可发布站点默认"), CLIENT_ID, "客户端标识"), Write.class)), Snapshot.class))
            .POST(RESTORE, this::restore,
                builder -> returns(writeHeader(jsonBody(pathParam(op(builder, "RestoreNcmConfigDefaults", TAG,
                    "恢复上一版站点默认"), CLIENT_ID, "客户端标识"), Restore.class)), Snapshot.class))
            .build();
    }

    private Mono<ServerResponse> mine(ServerRequest request) {
        NcmRequestSecurity.requirePrivateOrigin(request);
        return privateOk(authentication().flatMap(auth -> preferences.personal(request.pathVariable(CLIENT_ID),
            auth.getName()).flatMap(saved -> canViewDiagnostics(auth).map(debug ->
                new Mine(saved.profileKey(), saved.snapshot(), canPublish(auth), debug)))), Mine.class);
    }

    private Mono<ServerResponse> saveMine(ServerRequest request) {
        return privateOk(authentication().flatMap(auth -> readWrite(request, auth.getName(), Write.class)
            .flatMap(body -> preferences.savePersonal(request.pathVariable(CLIENT_ID), auth.getName(),
                body.schemaVersion(), body.revision(), body.config()))), Snapshot.class);
    }

    private Mono<ServerResponse> publish(ServerRequest request) {
        return privateOk(publisher().flatMap(auth -> readWrite(request, auth.getName(), Write.class))
            .flatMap(body -> preferences.saveSite(
                request.pathVariable(CLIENT_ID), body.schemaVersion(), body.revision(), body.config())), Snapshot.class);
    }

    private Mono<ServerResponse> restore(ServerRequest request) {
        return privateOk(publisher().flatMap(auth -> readWrite(request, auth.getName(), Restore.class))
            .flatMap(body -> preferences.restoreSite(
                request.pathVariable(CLIENT_ID), body.schemaVersion(), body.revision())), Snapshot.class);
    }

    private static Mono<Authentication> publisher() {
        return authentication().filter(NcmConfigEndpoint::canPublish)
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "仅超级管理员可管理站点默认")));
    }

    private static Mono<Authentication> authentication() {
        return ReactiveSecurityContextHolder.getContext().map(SecurityContext::getAuthentication)
            .filter(auth -> auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken))
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录 Halo")));
    }

    private static boolean canPublish(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(authority -> "ROLE_super-role".equals(authority.getAuthority()));
    }

    private Mono<Boolean> canViewDiagnostics(Authentication auth) {
        if (canPublish(auth)) {
            return Mono.just(true);
        }
        var names = auth.getAuthorities().stream().map(a -> a.getAuthority())
            .filter(name -> name != null && name.startsWith("ROLE_"))
            .map(name -> name.substring(5)).toList();
        return roles.contains(names, List.of("plugin-ncm-role-debug")).onErrorReturn(false);
    }

    private <T extends VersionedInput> Mono<T> readWrite(ServerRequest request, String username, Class<T> type) {
        NcmRequestSecurity.requireBrowserWrite(request);
        if (request.headers().contentType().filter(MediaType.APPLICATION_JSON::isCompatibleWith).isEmpty()) {
            return Mono.error(new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "请提交 JSON 配置"));
        }
        if (!limiter.tryAcquire("client-config:" + username, WRITES_PER_MINUTE)) {
            return Mono.error(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "保存过于频繁，请稍后重试"));
        }
        return DataBufferUtils.join(request.body(BodyExtractors.toDataBuffers()), MAX_BODY_BYTES)
            .onErrorMap(DataBufferLimitException.class, error ->
                new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "配置体积超出限制"))
            .switchIfEmpty(Mono.error(new ServerWebInputException("缺少配置")))
            .map(buffer -> {
                try {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    return parse(bytes, type);
                } finally {
                    DataBufferUtils.release(buffer);
                }
            });
    }

    private static <T extends VersionedInput> T parse(byte[] bytes, Class<T> type) {
        try {
            var body = JSON.readValue(bytes, type);
            if (body == null || body.schemaVersion() == null || body.schemaVersion() < 1
                || body.revision() == null || !REVISION.matcher(body.revision()).matches()
                || (body instanceof Write write && write.config() == null)) {
                throw new ServerWebInputException("配置版本或内容无效");
            }
            return body;
        } catch (JsonProcessingException error) {
            throw new ServerWebInputException("配置 JSON 格式无效");
        } catch (java.io.IOException error) {
            throw new IllegalStateException("无法读取配置 JSON", error);
        }
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("api.ncm.halo.run/v1alpha1");
    }

    @Schema(name = "NcmMyConfig", requiredProperties = {"profileKey", "snapshot", "canPublishSite", "canViewDiagnostics"})
    public record Mine(String profileKey, Snapshot snapshot, boolean canPublishSite, boolean canViewDiagnostics) {
    }

    private interface VersionedInput {
        Integer schemaVersion();
        String revision();
    }

    @Schema(name = "NcmConfigRestore", requiredProperties = {"schemaVersion", "revision"})
    public record Restore(Integer schemaVersion, @JsonProperty(required = true) String revision)
        implements VersionedInput {
    }

    @Schema(name = "NcmConfigWrite", requiredProperties = {"schemaVersion", "revision", "config"})
    public record Write(Integer schemaVersion, @JsonProperty(required = true) String revision,
                        Map<String, Object> config) implements VersionedInput {
    }
}
