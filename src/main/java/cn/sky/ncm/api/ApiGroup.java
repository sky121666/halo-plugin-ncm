package cn.sky.ncm.api;

import cn.sky.ncm.client.NcmResponse;
import cn.sky.ncm.client.RequestOptions;
import java.util.Map;
import java.util.Set;
import reactor.core.publisher.Mono;

/** 一组手写接口。CustomApis 会自动收集所有实现类。 */
public interface ApiGroup {

    Set<String> names();

    Mono<NcmResponse> handle(String name, Map<String, String> q, Map<String, UploadFile> files, RequestOptions opt);

    static String or(String v, String fallback) {
        return v != null && !v.isEmpty() ? v : fallback;
    }

    static boolean blank(String v) {
        return v == null || v.isEmpty();
    }
}
