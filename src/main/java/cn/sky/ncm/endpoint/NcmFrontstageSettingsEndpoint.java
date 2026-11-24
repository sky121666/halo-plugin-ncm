package cn.sky.ncm.endpoint;

import static cn.sky.ncm.endpoint.EndpointSupport.jsonBody;
import static cn.sky.ncm.endpoint.EndpointSupport.op;
import static cn.sky.ncm.endpoint.EndpointSupport.privateOk;
import static cn.sky.ncm.endpoint.EndpointSupport.returns;
import static cn.sky.ncm.endpoint.EndpointSupport.writeHeader;
import static cn.sky.ncm.endpoint.EndpointSupport.writeUsername;

import cn.sky.ncm.setting.FrontstageSettingsService;
import cn.sky.ncm.setting.NcmFrontstageConfig;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

@Component
public class NcmFrontstageSettingsEndpoint implements CustomEndpoint {

    private static final String PATH = "frontstage-settings/-";
    private static final String TAG = "console.api.ncm.halo.run/v1alpha1/FrontstageSettings";
    private final FrontstageSettingsService service;

    public NcmFrontstageSettingsEndpoint(FrontstageSettingsService service) {
        this.service = service;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return SpringdocRouteBuilder.route()
            .GET(PATH, request -> privateOk(service.read(), NcmFrontstageConfig.class),
                builder -> returns(op(builder, "GetNcmFrontstageSettings", TAG, "读取音乐页设置"), NcmFrontstageConfig.class))
            .PUT(PATH, request -> privateOk(writeUsername(request)
                    .then(request.bodyToMono(NcmFrontstageConfig.class)
                        .switchIfEmpty(Mono.error(new ServerWebInputException("缺少请求体"))))
                    .flatMap(service::save), NcmFrontstageConfig.class),
                builder -> returns(writeHeader(jsonBody(op(builder, "UpdateNcmFrontstageSettings", TAG,
                    "保存音乐页设置，旧播放模式和音量字段在写入时忽略。版本冲突时返回 409"),
                    NcmFrontstageConfig.class)), NcmFrontstageConfig.class))
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("console.api.ncm.halo.run/v1alpha1");
    }
}
