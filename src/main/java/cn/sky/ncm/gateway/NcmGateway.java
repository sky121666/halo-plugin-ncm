package cn.sky.ncm.gateway;

import cn.sky.ncm.api.ApiDef;
import cn.sky.ncm.api.ApiExecutor;
import cn.sky.ncm.api.ApiRegistry;
import cn.sky.ncm.api.DebugApiPolicy;
import cn.sky.ncm.api.UploadFile;
import cn.sky.ncm.bootstrap.NcmBootstrap;
import cn.sky.ncm.client.NcmClient;
import cn.sky.ncm.client.NcmResponse;
import cn.sky.ncm.client.RequestOptions;
import cn.sky.ncm.security.ClientIp;
import cn.sky.ncm.security.RateLimiter;
import cn.sky.ncm.setting.AdvancedSetting;
import cn.sky.ncm.setting.SettingService;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/** 所有接口调用的统一入口：查描述 → 校验等级 → 解析身份 → 缓存 → 执行。 */
@Slf4j
@Component
public class NcmGateway {

    private static final int DEBUG_REQUESTS_PER_MINUTE = 30;
    private final ApiRegistry registry;
    private final ApiExecutor executor;
    private final IdentityResolver resolver;
    private final SettingService settings;
    private final ResponseCache cache;
    private final NcmBootstrap bootstrap;
    private final RateLimiter limiter;

    public NcmGateway(ApiRegistry registry, ApiExecutor executor, IdentityResolver resolver,
                      SettingService settings, ResponseCache cache, NcmBootstrap bootstrap, RateLimiter limiter) {
        this.limiter = limiter;
        this.registry = registry;
        this.executor = executor;
        this.resolver = resolver;
        this.settings = settings;
        this.cache = cache;
        this.bootstrap = bootstrap;
    }

    public Mono<ServerResponse> invoke(ServerRequest request, Set<String> allowedTiers) {
        return invoke(request, allowedTiers, false);
    }

    public Mono<ServerResponse> debug(ServerRequest request, String operator) {
        if (request.method() != HttpMethod.GET) {
            return respondDebug(NcmResponse.error(405, "接口调试仅支持只读请求"));
        }
        if (!limiter.tryAcquire("debug:" + operator, DEBUG_REQUESTS_PER_MINUTE)) {
            return respondDebug(NcmResponse.error(429, "调试请求太频繁，请稍后再试"));
        }
        return invoke(request, Set.of("public"), true);
    }

    private Mono<ServerResponse> invoke(ServerRequest request, Set<String> allowedTiers, boolean debug) {
        String name = request.pathVariable("name");
        ApiDef def = registry.find(name).orElse(null);
        if (def == null) {
            return respond(NcmResponse.error(404, "没有这个接口: " + name));
        }
        if (debug && !DebugApiPolicy.readable(def)) {
            return respondDebug(NcmResponse.error(403, "该接口不在游客只读调试范围内"));
        }
        if (!allowedTiers.contains(def.tier())) {
            return respond(NcmResponse.error(403, "接口 " + name + " 属于 " + def.tier() + " 等级，不能从这个路径调用"));
        }
        if (!def.executable()) {
            return respond(NcmResponse.error(501, "接口 " + name + " 尚未移植（" + def.status() + "）"));
        }
        NcmResponse denied = mutationGuard(request.method(), request.headers().firstHeader(REQUEST_HEADER), def.tier());
        if (denied != null) {
            return respond(denied);
        }
        boolean allowFiles = "write".equals(def.tier());
        return settings.advanced().flatMap(adv -> {
            Mono<Boolean> quota = debug ? Mono.just(true) : rateLimit(request, def, adv);
            return quota
            .flatMap(allowed -> !allowed
                ? respond(NcmResponse.error(429, "请求太频繁，请稍后再试"))
                : bootstrap.ready()
            .then(readParams(request, allowFiles, adv.maxUploadBytes()))
            .flatMap(p -> {
                Map<String, String> query = p.query();
                Map<String, UploadFile> files = p.files();
                query.remove("cookie");
                Mono<Identity> identity = debug ? Mono.just(Identity.anonymous()) : resolver.resolve(def.tier());
                return identity.flatMap(id -> call(def, query, files, id, adv,
                    !debug && request.method() == HttpMethod.GET));
            })
            .flatMap(response -> debug ? respondDebug(response) : respond(response))
            .onErrorResume(DataBufferLimitException.class, e -> respond(NcmResponse.error(413,
                "上传文件超过 " + adv.maxUploadBytes() / 1024 / 1024 + "MB 上限")))
            .onErrorResume(e -> !(e instanceof ResponseStatusException), e -> unexpected(name, e)));
        });
    }

    /** 插件内部异常（不是网易云返回的错误）：记录完整堆栈，返回 JSON 而不是 Halo 的通用 500 页面。 */
    Mono<ServerResponse> unexpected(String api, Throwable e) {
        log.error("[ncm] 调用接口 {} 时插件内部出错", api, e);
        return respond(NcmResponse.error(500, "插件内部错误（" + e.getClass().getSimpleName() + "），详情见 Halo 日志"));
    }

    /** 会修改数据或登录状态的等级：只能用 POST，并且要带 {@link #REQUEST_HEADER}。 */
    public static final Set<String> MUTATING_TIERS = Set.of("write", "auth", "admin");
    /**
     * 防跨站请求伪造：跨站的链接、图片、表单都没法带自定义请求头，跨站脚本带自定义请求头会先触发 CORS 预检并被拒绝。
     * Halo 对 /apis/** 不做 CSRF 校验，所以由插件自己检查。
     */
    public static final String REQUEST_HEADER = "X-NCM-Request";

    /**
     * write / auth / admin 等级的接口：不是 POST 时 405，没带 X-NCM-Request: 1 时 403；其他情况返回 null（放行）。
     */
    public static NcmResponse mutationGuard(HttpMethod method, String header, String tier) {
        if (!MUTATING_TIERS.contains(tier)) {
            return null;
        }
        if (method != HttpMethod.POST) {
            return NcmResponse.error(405, "这个接口会修改数据或登录状态，只能用 POST 调用，并带上请求头 " + REQUEST_HEADER + ": 1");
        }
        if (!"1".equals(header == null ? null : header.trim())) {
            return NcmResponse.error(403, "缺少请求头 " + REQUEST_HEADER + ": 1（防止跨站请求伪造）");
        }
        return null;
    }

    /** 公开接口按 IP 限流，个人和写操作按 Halo 用户名限流。 */
    Mono<Boolean> rateLimit(ServerRequest request, ApiDef def, AdvancedSetting adv) {
        if ("public".equals(def.tier())) {
            return Mono.fromSupplier(() -> allowPublic(request, adv, limiter));
        }
        String ip = clientIp(request, adv);
        return IdentityResolver.currentUsername()
            .map(u -> limiter.tryAcquire("user:" + u, adv.userLimit()))
            .switchIfEmpty(Mono.fromSupplier(() -> limiter.tryAcquire("ip:" + ip, adv.publicLimit())));
    }

    public static boolean allowPublic(ServerRequest request, AdvancedSetting advanced, RateLimiter limiter) {
        String ip = clientIp(request, advanced);
        return limiter.tryAcquire("ip:" + ip, advanced.publicLimit())
            && limiter.tryAcquire("ncm:public-total", 600);
    }

    /** 访客 IP：只有请求来自可信代理时才看 X-Forwarded-For（从右往左取）/ X-Real-IP，见 {@link ClientIp}。 */
    public static String clientIp(ServerRequest request, AdvancedSetting adv) {
        return ClientIp.resolve(request, adv);
    }

    private Mono<NcmResponse> call(ApiDef def, Map<String, String> query, Map<String, UploadFile> files,
                                   Identity id, AdvancedSetting setting, boolean isGet) {
        RequestOptions options = new RequestOptions(def.crypto(), id.cookie(), null, setting.realIp(),
            setting.useRandomCnIp());
        boolean cacheable = isGet && "public".equals(def.tier()) && !def.name().startsWith("song_url")
            && setting.apiCacheTtl() > 0;
        if (!cacheable) {
            return executor.execute(def, query, files, options);
        }
        String key = def.name() + "|" + id.kind() + "|" + new TreeMap<>(query);
        NcmResponse hit = cache.get(key);
        if (hit != null) {
            return Mono.just(hit);
        }
        return executor.execute(def, query, options).doOnNext(r -> cache.put(key, r, setting.apiCacheTtl()));
    }

    record Params(Map<String, String> query, Map<String, UploadFile> files) {
    }

    /**
     * 合并 query string 与 POST 的 JSON / 表单 / multipart 参数（值统一转成字符串，与上游 query 一致）。
     * multipart 里的文件字段（songFile、imgFile）单独放进 files。
     */
    static Mono<Params> readParams(ServerRequest request, boolean allowFiles, int maxBytes) {
        Map<String, String> q = new TreeMap<>(request.queryParams().toSingleValueMap());
        Map<String, UploadFile> files = new LinkedHashMap<>();
        if (request.method() != HttpMethod.POST) {
            return Mono.just(new Params(q, files));
        }
        MediaType ct = request.headers().contentType().orElse(MediaType.APPLICATION_JSON);
        if (MediaType.MULTIPART_FORM_DATA.isCompatibleWith(ct)) {
            return request.multipartData().flatMapIterable(m -> m.toSingleValueMap().entrySet())
                .concatMap(e -> {
                    if (e.getValue() instanceof FilePart fp) {
                        if (!allowFiles) {
                            return Mono.error(new ResponseStatusException(
                                org.springframework.http.HttpStatus.BAD_REQUEST, "该接口不接受文件上传"));
                        }
                        return DataBufferUtils.join(fp.content(), maxBytes).map(buf -> {
                            byte[] bytes = new byte[buf.readableByteCount()];
                            buf.read(bytes);
                            DataBufferUtils.release(buf);
                            String mime = fp.headers().getContentType() == null ? null
                                : fp.headers().getContentType().toString();
                            files.put(e.getKey(), new UploadFile(fp.filename(), mime, bytes));
                            return e.getKey();
                        });
                    }
                    if (e.getValue() instanceof FormFieldPart ff) {
                        q.put(e.getKey(), ff.value());
                    }
                    return Mono.just(e.getKey());
                })
                .then(Mono.fromSupplier(() -> new Params(q, files)));
        }
        if (MediaType.APPLICATION_FORM_URLENCODED.isCompatibleWith(ct)) {
            return request.formData().map(f -> {
                q.putAll(f.toSingleValueMap());
                return new Params(q, files);
            });
        }
        return request.bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
            })
            .map(body -> {
                body.forEach((k, v) -> {
                    if (v != null) {
                        q.put(k, v instanceof String s ? s : NcmClient.toJson(v));
                    }
                });
                return new Params(q, files);
            })
            .defaultIfEmpty(new Params(q, files));
    }

    Mono<ServerResponse> respond(NcmResponse r) {
        if (r.redirect() != null) {
            // 音频地址二十分钟左右就会过期，不能让浏览器缓存这次跳转
            return ServerResponse.status(302).location(URI.create(r.redirect()))
                .header("Cache-Control", "no-store").build();
        }
        // 自己序列化成字符串：Halo 2.23+（Spring Boot 4）默认用 Jackson 3 编码，不认 Jackson 2 的 JsonNode
        return ServerResponse.status(r.status()).cacheControl(CacheControl.noStore().cachePrivate())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(NcmClient.toJson(r.body()));
    }

    private Mono<ServerResponse> respondDebug(NcmResponse response) {
        return ServerResponse.status(response.redirect() == null ? response.status() : 502)
            .cacheControl(CacheControl.noStore().cachePrivate())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(NcmClient.toJson(DebugApiPolicy.safeBody(response.body())));
    }
}
