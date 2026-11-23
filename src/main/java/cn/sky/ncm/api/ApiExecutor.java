package cn.sky.ncm.api;

import cn.sky.ncm.client.NcmClient;
import cn.sky.ncm.client.NcmResponse;
import cn.sky.ncm.client.RequestOptions;
import cn.sky.ncm.client.UnsupportedCryptoException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** 执行一个接口：手写实现优先，否则按描述拼参数并发请求。 */
@Component
public class ApiExecutor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final NcmClient client;
    private final CustomApis customApis;

    public ApiExecutor(NcmClient client, CustomApis customApis) {
        this.client = client;
        this.customApis = customApis;
    }

    public Mono<NcmResponse> execute(ApiDef def, Map<String, String> query, RequestOptions options) {
        return execute(def, query, Map.of(), options);
    }

    public Mono<NcmResponse> execute(ApiDef def, Map<String, String> query, Map<String, UploadFile> files,
                                     RequestOptions options) {
        return doExecute(def, query, files, options)
            .onErrorResume(UnsupportedCryptoException.class, ex -> Mono.just(NcmResponse.error(501, ex.getMessage())));
    }

    private Mono<NcmResponse> doExecute(ApiDef def, Map<String, String> query, Map<String, UploadFile> files,
                                        RequestOptions options) {
        if ("custom".equals(def.status())) {
            return customApis.handle(def.name(), query, files, options);
        }
        if (!"ready".equals(def.status())) {
            return Mono.just(NcmResponse.error(501, "接口 " + def.name() + " 尚未移植（" + def.status() + "）"));
        }
        try {
            Map<String, Object> q = new LinkedHashMap<>(query);
            for (String[] p : def.pre()) {
                q.put(p[0], ExprEval.eval(p[1], q));
            }
            String uri = ExprEval.template(def.uri(), q);
            Map<String, Object> data = new LinkedHashMap<>();
            for (var e : def.fields().entrySet()) {
                Object v = ExprEval.eval(e.getValue(), q);
                if (v != null) {
                    data.put(e.getKey(), v);
                }
            }
            RequestOptions opt = options.withCrypto(def.crypto()).withCheckToken(def.checkToken());
            Mono<NcmResponse> result = client.request(uri, data, opt);
            if ("renameAvatarImgId".equals(def.post())) {
                result = result.map(ApiExecutor::renameAvatarImgId);
            }
            return result;
        } catch (IllegalArgumentException e) {
            return Mono.just(NcmResponse.error(500, e.getMessage()));
        }
    }

    static NcmResponse renameAvatarImgId(NcmResponse r) {
        try {
            String text = MAPPER.writeValueAsString(r.body()).replace("avatarImgId_str", "avatarImgIdStr");
            return r.withBody(MAPPER.readTree(text));
        } catch (Exception e) {
            return r;
        }
    }
}
