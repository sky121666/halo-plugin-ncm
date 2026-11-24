package cn.sky.ncm.endpoint;

import static cn.sky.ncm.endpoint.EndpointSupport.jsonBody;
import static cn.sky.ncm.endpoint.EndpointSupport.op;
import static cn.sky.ncm.endpoint.EndpointSupport.privateOk;
import static cn.sky.ncm.endpoint.EndpointSupport.returns;
import static cn.sky.ncm.endpoint.EndpointSupport.writeHeader;
import static cn.sky.ncm.endpoint.EndpointSupport.writeUsername;

import cn.sky.ncm.setting.NcmToolConfig;
import cn.sky.ncm.setting.ToolSettingsService;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

@Component
public class NcmToolSettingsEndpoint implements CustomEndpoint {
    private static final String PATH = "tool-settings/-";
    private static final String TAG = "console.api.ncm.halo.run/v1alpha1/ToolSettings";
    private final ToolSettingsService service;

    public NcmToolSettingsEndpoint(ToolSettingsService service) {
        this.service = service;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return SpringdocRouteBuilder.route()
            .GET(PATH, request -> privateOk(service.read(), NcmToolConfig.class),
                b -> returns(op(b, "GetNcmToolSettings", TAG, "读取网抑云工具设置"), NcmToolConfig.class))
            .PUT(PATH, request -> privateOk(writeUsername(request)
                    .then(request.bodyToMono(NcmToolConfig.class)
                        .switchIfEmpty(Mono.error(new ServerWebInputException("缺少请求体"))))
                    .flatMap(service::save), NcmToolConfig.class),
                b -> returns(writeHeader(jsonBody(op(b, "UpdateNcmToolSettings", TAG,
                    "保存网抑云工具设置，版本冲突时返回 409"), NcmToolConfig.class)), NcmToolConfig.class))
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("console.api.ncm.halo.run/v1alpha1");
    }
}
