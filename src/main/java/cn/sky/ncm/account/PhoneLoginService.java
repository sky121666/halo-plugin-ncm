package cn.sky.ncm.account;

import cn.sky.ncm.api.CustomApis;
import cn.sky.ncm.api.more.UserApis;
import cn.sky.ncm.client.NcmClient;
import cn.sky.ncm.client.RequestOptions;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** 手机号绑定：发验证码 → 用验证码（或密码）登录 → 保存 Cookie。返回给前端的结果里不含 Cookie。 */
@Component
public class PhoneLoginService {

    private final NcmClient client;
    private final UserApis userApis;
    private final CustomApis customApis;
    private final AccountService accounts;

    public PhoneLoginService(NcmClient client, UserApis userApis, CustomApis customApis, AccountService accounts) {
        this.client = client;
        this.userApis = userApis;
        this.customApis = customApis;
        this.accounts = accounts;
    }

    private static RequestOptions anonymous() {
        return new RequestOptions("", Map.of(), null, null, false);
    }

    /** 对应 module/captcha_sent.js。 */
    public Mono<ObjectNode> sendCaptcha(Map<String, String> p) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ctcode", p.getOrDefault("ctcode", p.getOrDefault("countrycode", "86")));
        data.put("secrete", "music_middleuser_pclogin");
        data.put("cellphone", p.get("phone"));
        data.values().removeIf(v -> v == null || "".equals(v));
        data.putIfAbsent("ctcode", "86");
        return client.request("/api/sms/captcha/sent", data, anonymous().withCrypto("weapi")).map(r -> {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            out.put("code", r.bodyObject().path("code").asInt(r.status()));
            out.put("message", r.bodyObject().path("message").asText(r.bodyObject().path("msg").asText("")));
            return out;
        });
    }

    /** 参数：phone、captcha 或 password、countrycode（默认 86）。 */
    public Mono<ObjectNode> login(Map<String, String> p, String accountName, String owner) {
        return userApis.loginCellphone(p, anonymous()).flatMap(r -> {
            int code = r.bodyObject().path("code").asInt(r.status());
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            out.put("code", code);
            out.put("message", r.bodyObject().path("message").asText(r.bodyObject().path("msg").asText("")));
            if (code != 200) {
                return Mono.just(out);
            }
            return accounts.save(accountName, owner, r.cookies())
                .flatMap(saved -> accounts.cookieOf(accountName))
                .flatMap(cookie -> customApis.loginStatus(new RequestOptions("", cookie, null, null, false)))
                .flatMap(status -> accounts.updateProfile(accountName, status.body()))
                .map(a -> {
                    out.set("account", AccountService.view(a));
                    return out;
                })
                .defaultIfEmpty(out);
        });
    }
}
