package cn.sky.ncm.endpoint;

import static cn.sky.ncm.endpoint.EndpointSupport.anyBody;
import static cn.sky.ncm.endpoint.EndpointSupport.apiName;
import static cn.sky.ncm.endpoint.EndpointSupport.apiNameParam;
import static cn.sky.ncm.endpoint.EndpointSupport.ok;
import static cn.sky.ncm.endpoint.EndpointSupport.op;
import static cn.sky.ncm.endpoint.EndpointSupport.returns;
import static cn.sky.ncm.endpoint.EndpointSupport.returnsAny;

import cn.sky.ncm.api.ApiRegistry;
import cn.sky.ncm.api.DebugApiPolicy;
import cn.sky.ncm.endpoint.dto.NcmApiMetaList;
import cn.sky.ncm.gateway.NcmGateway;
import cn.sky.ncm.setting.SettingService;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

/**
 * 前台接口：
 * <pre>
 * GET|POST /apis/api.ncm.halo.run/v1alpha1/public/{name}     公开接口（匿名可用）
 * GET|POST /apis/api.ncm.halo.run/v1alpha1/personal/{name}   个人 / 写操作接口（需登录 Halo）；
 *                                                             写操作只能 POST，并带请求头 X-NCM-Request: 1
 * GET      /apis/api.ncm.halo.run/v1alpha1/meta/apis         接口清单与移植状态
 * </pre>
 */
@Component
public class NcmApiEndpoint implements CustomEndpoint {

    static final String TAG = "api.ncm.halo.run/v1alpha1/Ncm";
    private static final Set<String> PUBLIC = Set.of("public");
    private static final Set<String> PERSONAL_READ = Set.of("personal");
    private static final Set<String> PERSONAL = Set.of("personal", "write");

    private final NcmGateway gateway;
    private final ApiRegistry registry;
    private final SettingService settings;

    public NcmApiEndpoint(NcmGateway gateway, ApiRegistry registry, SettingService settings) {
        this.gateway = gateway;
        this.registry = registry;
        this.settings = settings;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return SpringdocRouteBuilder.route()
            .GET("public/{name}", r -> invoke(r, PUBLIC), b -> returnsAny(apiNameParam(
                op(b, "InvokePublicNcmApi", TAG, "调用公开等级的网易云接口（匿名可用）"))))
            .POST("public/{name}", r -> invoke(r, PUBLIC), b -> returnsAny(anyBody(apiNameParam(
                op(b, "InvokePublicNcmApiByPost", TAG, "以 POST 方式调用公开等级的网易云接口（匿名可用）")))))
            .GET("personal/{name}", r -> invoke(r, PERSONAL_READ), b -> returnsAny(apiNameParam(
                op(b, "InvokePersonalNcmApi", TAG, "调用本人绑定账号的只读接口"))))
            .POST("personal/{name}", r -> EndpointSupport.writeUsername(r).then(Mono.defer(() -> invoke(r, PERSONAL))),
                b -> returnsAny(anyBody(apiNameParam(
                op(b, "InvokePersonalNcmApiByPost", TAG,
                    "以 POST 方式调用个人或写操作等级的网易云接口；写操作必须带请求头 X-NCM-Request: 1（防跨站请求伪造），"
                        + "上传类接口用 multipart，文件字段为 songFile / imgFile")))))
            .GET("meta/apis", this::listApis, b -> returns(
                op(b, "ListNcmApis", TAG, "列出全部网易云接口的等级和移植状态"), NcmApiMetaList.class))
            .build();
    }

    Mono<ServerResponse> invoke(ServerRequest r, Set<String> tiers) {
        apiName(r);
        return gateway.invoke(r, tiers);
    }

    Mono<ServerResponse> listApis(ServerRequest request) {
        return ok(settings.basic().map(basic -> {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            var list = out.putArray("items");
            registry.all().forEach(d -> {
                var n = list.addObject();
                n.put("name", d.name());
                n.put("tier", d.tier());
                n.put("status", d.status());
                n.put("path", ("public".equals(d.tier()) ? "/public/" : "/personal/") + d.name());
                boolean frontTier = "public".equals(d.tier()) || "personal".equals(d.tier()) || "write".equals(d.tier());
                n.put("open", frontTier && d.executable() && basic.apiOpen(d.name()));
                n.put("debuggable", DebugApiPolicy.readable(d));
            });
            out.put("total", registry.all().size());
            return out;
        }), NcmApiMetaList.class);
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("api.ncm.halo.run/v1alpha1");
    }
}
