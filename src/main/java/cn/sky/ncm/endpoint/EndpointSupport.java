package cn.sky.ncm.endpoint;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.content.Builder.contentBuilder;
import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;
import static org.springdoc.core.fn.builders.requestbody.Builder.requestBodyBuilder;
import static org.springdoc.core.fn.builders.schema.Builder.schemaBuilder;

import cn.sky.ncm.client.Js;
import cn.sky.ncm.endpoint.dto.NcmCaptchaRequest;
import cn.sky.ncm.endpoint.dto.NcmCellphoneLoginRequest;
import cn.sky.ncm.endpoint.dto.NcmQrConfirmRequest;
import cn.sky.ncm.gateway.IdentityResolver;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springdoc.core.fn.builders.operation.Builder;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

/** 三组自定义接口共用的工具：OpenAPI 描述、请求体读取与校验、响应输出。 */
final class EndpointSupport {

    static final Pattern PHONE = Pattern.compile("^\\d{5,15}$");
    static final Pattern CTCODE = Pattern.compile("^\\d{1,4}$");
    static final Pattern CAPTCHA = Pattern.compile("^\\d{4,8}$");
    static final Pattern QR_KEY = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");
    static final Pattern API_NAME = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    static final int MAX_PASSWORD = 128;
    static final String REQUEST_HEADER = "X-NCM-Request";

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private EndpointSupport() {
    }

    // ---------- 响应 ----------

    /**
     * 把服务层返回的 JsonNode 转成文档里声明的 DTO 再输出，保证返回结构和 OpenAPI 描述一致。
     * 自己序列化成字符串：Halo 2.23+（Spring Boot 4）默认用 Jackson 3 编码，插件内部用的是 Jackson 2。
     */
    static <T> Mono<ServerResponse> ok(Mono<?> body, Class<T> type) {
        return jsonResponse(body, type, ServerResponse.ok());
    }

    static <T> Mono<ServerResponse> privateOk(Mono<?> body, Class<T> type) {
        return jsonResponse(body, type, ServerResponse.ok().cacheControl(CacheControl.noStore().cachePrivate()));
    }

    private static <T> Mono<ServerResponse> jsonResponse(Mono<?> body, Class<T> type,
                                                        ServerResponse.BodyBuilder response) {
        return body.flatMap(b -> response.contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Js.json(MAPPER.convertValue(b, type))));
    }

    static Mono<ServerResponse> noContent(Mono<?> before) {
        return before.then(ServerResponse.noContent().build());
    }

    // ---------- 请求 ----------

    static Mono<String> username() {
        return IdentityResolver.currentUsername()
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录")));
    }

    static String qrKey(ServerRequest r) {
        return validQrKey(r.queryParam("key").orElse(""));
    }

    static Mono<String> qrConfirmKey(ServerRequest r) {
        return r.bodyToMono(NcmQrConfirmRequest.class)
            .switchIfEmpty(Mono.error(new ServerWebInputException("缺少请求体")))
            .map(body -> validQrKey(body.key()));
    }

    private static String validQrKey(String key) {
        if (key == null || !QR_KEY.matcher(key).matches()) {
            throw new ServerWebInputException("key 无效");
        }
        return key;
    }

    static Mono<String> writeUsername(ServerRequest r) {
        return Mono.defer(() -> {
            if (!"1".equals(r.headers().firstHeader(REQUEST_HEADER))) {
                return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "请求来源无效，请重新打开页面"));
            }
            return username();
        });
    }

    static Mono<Map<String, String>> captchaBody(ServerRequest r) {
        return r.bodyToMono(NcmCaptchaRequest.class)
            .switchIfEmpty(Mono.error(new ServerWebInputException("缺少请求体")))
            .map(b -> {
                String ctcode = blank(b.getCtcode()) ? "86" : b.getCtcode().trim();
                require(b.getPhone() != null && PHONE.matcher(b.getPhone().trim()).matches(), "phone 应为 5~15 位数字");
                require(CTCODE.matcher(ctcode).matches(), "ctcode 应为 1~4 位数字");
                Map<String, String> p = new LinkedHashMap<>();
                p.put("phone", b.getPhone().trim());
                p.put("ctcode", ctcode);
                return p;
            });
    }

    static Mono<Map<String, String>> cellphoneBody(ServerRequest r) {
        return r.bodyToMono(NcmCellphoneLoginRequest.class)
            .switchIfEmpty(Mono.error(new ServerWebInputException("缺少请求体")))
            .map(b -> {
                String cc = blank(b.getCountrycode()) ? "86" : b.getCountrycode().trim();
                boolean byCaptcha = !blank(b.getCaptcha());
                require(b.getPhone() != null && PHONE.matcher(b.getPhone().trim()).matches(), "phone 应为 5~15 位数字");
                require(CTCODE.matcher(cc).matches(), "countrycode 应为 1~4 位数字");
                require(byCaptcha || !blank(b.getPassword()), "captcha 和 password 至少填一个");
                require(!byCaptcha || CAPTCHA.matcher(b.getCaptcha().trim()).matches(), "captcha 应为 4~8 位数字");
                require(byCaptcha || b.getPassword().length() <= MAX_PASSWORD, "password 过长");
                Map<String, String> p = new LinkedHashMap<>();
                p.put("phone", b.getPhone().trim());
                p.put("countrycode", cc);
                if (byCaptcha) {
                    p.put("captcha", b.getCaptcha().trim());
                } else {
                    p.put("password", b.getPassword());
                }
                return p;
            });
    }

    static void apiName(ServerRequest r) {
        if (!API_NAME.matcher(r.pathVariable("name")).matches()) {
            throw new ServerWebInputException("接口名无效");
        }
    }

    static void require(boolean ok, String message) {
        if (!ok) {
            throw new ServerWebInputException(message);
        }
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    // ---------- OpenAPI 描述 ----------

    static Builder op(Builder b, String operationId, String tag, String description) {
        return b.operationId(operationId).tag(tag).description(description);
    }

    static Builder returns(Builder b, Class<?> type) {
        return b.response(responseBuilder().implementation(type));
    }

    static Builder returnsNothing(Builder b) {
        return b.response(responseBuilder().responseCode("204").description("No Content"));
    }

    /** 返回的是网易云原样数据（结构因接口而异）。 */
    static Builder returnsAny(Builder b) {
        return b.response(responseBuilder().implementation(Object.class)
            .description("网易云接口的原始返回（JSON），结构因接口而异"));
    }

    static Builder jsonBody(Builder b, Class<?> type) {
        return b.requestBody(requestBodyBuilder().required(true)
            .content(contentBuilder().mediaType(MediaType.APPLICATION_JSON_VALUE)
                .schema(schemaBuilder().implementation(type))));
    }

    static Builder qrKeyParam(Builder b) {
        return b.parameter(parameterBuilder().name("key").in(ParameterIn.QUERY).required(true)
            .description("获取二维码时返回的 key").implementation(String.class));
    }

    static Builder pathParam(Builder b, String name, String description) {
        return b.parameter(parameterBuilder().name(name).in(ParameterIn.PATH).required(true)
            .description(description).implementation(String.class));
    }

    static Builder writeHeader(Builder b) {
        return b.parameter(parameterBuilder().name(REQUEST_HEADER).in(ParameterIn.HEADER).required(true)
            .description("同源写入请求填写 1").implementation(String.class));
    }

    /** /xxx/{name}：name 为上游 module 文件名；其他参数和上游一致，GET 走查询字符串，POST 走 JSON 或表单。 */
    static Builder apiNameParam(Builder b) {
        return b.parameter(parameterBuilder().name("name").in(ParameterIn.PATH).required(true)
            .description("接口名，即上游 NeteaseCloudMusicApi 的 module 文件名，例如 cloudsearch、song_url_v1。"
                + "其他参数与上游一致：GET 放在查询字符串里，POST 放在 JSON / 表单 / multipart 请求体里")
            .implementation(String.class));
    }

    static Builder anyBody(Builder b) {
        return b.requestBody(requestBodyBuilder().required(false)
            .content(contentBuilder().mediaType(MediaType.APPLICATION_JSON_VALUE)
                .schema(schemaBuilder().implementation(Object.class))));
    }
}
