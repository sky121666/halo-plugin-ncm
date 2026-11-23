package cn.sky.ncm.client;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 易盾反作弊 token。
 * <ul>
 *   <li>v3：对应上游 register_checktoken_v3，纯 HTTP，每次实时获取、不缓存</li>
 *   <li>v2：上游在 jsdom 里运行易盾 Watchman SDK，Java 里没有浏览器环境；暂时用 v3 代替（待验证服务端是否接受）</li>
 * </ul>
 * 获取失败时返回空字符串，与上游一致，由服务端决定是否拒绝。
 */
@Component
public class CheckTokenService {

    static final String V3_URL = "https://ac.dun.163yun.com/v3/b?pn=YD00000558929251";
    static final Pattern V3_PATTERN = Pattern.compile("null\\(\\[(\\d+),\\d+,\"([^\"]+)\"]\\)");

    private final NcmHttp http;

    public CheckTokenService(NcmHttp http) {
        this.http = http;
    }

    public Mono<String> token(String version) {
        if (version == null || version.isEmpty()) {
            return Mono.just("");
        }
        return v3();
    }

    public Mono<String> v3() {
        return http.current().get().uri(V3_URL).retrieve().bodyToMono(String.class)
            .timeout(Duration.ofSeconds(10))
            .map(body -> {
                Matcher m = V3_PATTERN.matcher(body);
                return m.find() && "200".equals(m.group(1)) ? m.group(2) : "";
            })
            .onErrorReturn("")
            .defaultIfEmpty("");
    }
}
