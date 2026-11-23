package cn.sky.ncm.endpoint;

import static cn.sky.ncm.endpoint.EndpointSupport.jsonBody;
import static cn.sky.ncm.endpoint.EndpointSupport.op;
import static cn.sky.ncm.endpoint.EndpointSupport.privateOk;
import static cn.sky.ncm.endpoint.EndpointSupport.returns;
import static cn.sky.ncm.endpoint.EndpointSupport.writeHeader;
import static cn.sky.ncm.endpoint.EndpointSupport.writeUsername;

import cn.sky.ncm.setting.ApiAccessSettingsService;
import cn.sky.ncm.setting.NcmApiAccessConfig;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

@Component
public class NcmApiAccessSettingsEndpoint implements CustomEndpoint {

    private static final String PATH = "api-access-settings/-";
    private static final String TAG = "console.api.ncm.halo.run/v1alpha1/ApiAccessSettings";
    private final ApiAccessSettingsService settings;

    public NcmApiAccessSettingsEndpoint(ApiAccessSettingsService settings) {
        this.settings = settings;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return SpringdocRouteBuilder.route()
            .GET(PATH, request -> privateOk(settings.read(), NcmApiAccessConfig.class),
                b -> returns(op(b, "GetNcmApiAccessSettings", TAG, "读取接口开放设置"), NcmApiAccessConfig.class))
            .PUT(PATH, request -> privateOk(writeUsername(request)
                    .then(request.bodyToMono(NcmApiAccessConfig.class)
                        .switchIfEmpty(Mono.error(new ServerWebInputException("缺少请求体"))))
                    .flatMap(settings::save), NcmApiAccessConfig.class),
                b -> returns(writeHeader(jsonBody(op(b, "UpdateNcmApiAccessSettings", TAG,
                    "保存接口开放设置，版本冲突时返回 409"), NcmApiAccessConfig.class)), NcmApiAccessConfig.class))
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("console.api.ncm.halo.run/v1alpha1");
    }
}
