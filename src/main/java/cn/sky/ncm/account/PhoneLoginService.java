package cn.sky.ncm.account;

import cn.sky.ncm.api.CustomApis;
import cn.sky.ncm.api.more.UserApis;
import cn.sky.ncm.client.NcmClient;
import cn.sky.ncm.client.RequestOptions;
import cn.sky.ncm.security.ClientIp;
import cn.sky.ncm.security.RateLimiter;
import cn.sky.ncm.security.SecretBox;
import cn.sky.ncm.setting.SettingService;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/** 手机号绑定：发验证码 → 用验证码（或密码）登录 → 保存 Cookie。返回给前端的结果里不含 Cookie。 */
@Component
public class PhoneLoginService {

    private final NcmClient client;
    private final UserApis userApis;
    private final CustomApis customApis;
    private final AccountService accounts;
    private final SettingService settings;
    private final RateLimiter limiter;

    public PhoneLoginService(NcmClient client, UserApis userApis, CustomApis customApis, AccountService accounts,
                             SettingService settings, RateLimiter limiter) {
        this.client = client;
        this.userApis = userApis;
        this.customApis = customApis;
        this.accounts = accounts;
        this.settings = settings;
        this.limiter = limiter;
    }

    private static RequestOptions anonymous() {
        return new RequestOptions("", Map.of(), null, null, false);
    }

    /** 对应 module/captcha_sent.js。 */
    public Mono<ObjectNode> sendCaptcha(Map<String, String> p, String username, ServerRequest request) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ctcode", p.getOrDefault("ctcode", p.getOrDefault("countrycode", "86")));
        data.put("secrete", "music_middleuser_pclogin");
        data.put("cellphone", p.get("phone"));
        data.values().removeIf(v -> v == null || "".equals(v));
        data.putIfAbsent("ctcode", "86");
        return authorize(p, username, request, true)
            .then(Mono.defer(() -> client.request("/api/sms/captcha/sent", data, anonymous().withCrypto("weapi"))))
            .map(r -> {
                ObjectNode out = JsonNodeFactory.instance.objectNode();
                int code = r.bodyObject().path("code").asInt(r.status());
                out.put("code", code);
                out.put("message", code == 200 ? "验证码请求已提交" : "网易云暂时无法发送验证码");
                return out;
            });
    }

    /** 参数：phone、captcha 或 password、countrycode（默认 86）。 */
    public Mono<ObjectNode> login(Map<String, String> p, String accountName, String username,
                                  ServerRequest request) {
        return authorize(p, username, request, false).flatMap(owner -> accounts.begin(accountName, owner))
            .flatMap(change -> userApis.loginCellphone(p, anonymous()).flatMap(response -> {
                int code = response.bodyObject().path("code").asInt(response.status());
                var result = JsonNodeFactory.instance.objectNode().put("code", code);
                if (code != 200) {
                    return Mono.just(result.put("message", "网易云登录失败，请核对账号或验证码"));
                }
                return accounts.save(change, response.cookies(), customApis::loginStatus).map(account -> {
                    result.put("message", "绑定成功");
                    result.set("account", AccountService.view(account));
                    return result;
                });
            }));
    }

    private Mono<AccountService.Owner> authorize(Map<String, String> params, String username,
                                                 ServerRequest request, boolean captcha) {
        String code = params.getOrDefault("ctcode", params.getOrDefault("countrycode", "86"));
        String target = code.replaceFirst("^0+(?!$)", "") + ":" + params.get("phone").replaceFirst("^0+(?!$)", "");
        String phoneKey = HexFormat.of().formatHex(SecretBox.sha256(target));
        String operation = captcha ? "ncm-captcha:" : "ncm-cellphone:";
        return accounts.requireOwner(username).zipWith(settings.advanced()).flatMap(pair -> {
            var owner = pair.getT1();
            String ip = ClientIp.resolve(request, pair.getT2());
            if (!limiter.tryAcquire(operation + "owner:" + owner, captcha ? 2 : 6)
                || !limiter.tryAcquire(operation + "phone:" + phoneKey, captcha ? 1 : 6)
                || !limiter.tryAcquire(operation + "ip:" + ip, captcha ? 10 : 25)
                || !limiter.tryAcquire(operation + "total", captcha ? 30 : 120)) {
                return Mono.error(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "操作过于频繁，请稍后再试"));
            }
            return Mono.just(owner);
        });
    }
}
