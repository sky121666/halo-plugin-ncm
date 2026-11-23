package cn.sky.ncm.gateway;

import cn.sky.ncm.account.AccountService;
import cn.sky.ncm.setting.BasicSetting;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * 决定一个请求用谁的网易云身份：
 * <pre>
 * 1. 允许自带 Cookie 且请求头带了 X-NCM-Cookie        → 访客自己的身份（不保存）
 * 2. public   接口：设置为“站长账号”且站长已绑定       → 站长；否则游客
 * 3. personal / write 接口：当前 Halo 用户自己绑定的账号；
 *    管理员本人没有单独绑定时，用他在后台绑定的站长账号（那本来就是他的账号）；
 *    未绑定的账号需要先在个人中心完成绑定。
 * </pre>
 */
@Component
public class IdentityResolver {

    public static final String CLIENT_COOKIE_HEADER = "X-NCM-Cookie";

    private final AccountService accounts;

    public IdentityResolver(AccountService accounts) {
        this.accounts = accounts;
    }

    public Mono<Identity> resolve(String tier, ServerRequest request, BasicSetting setting) {
        if (setting.clientCookieAllowed()) {
            String raw = request.headers().firstHeader(CLIENT_COOKIE_HEADER);
            if (raw != null && !raw.isBlank()) {
                return Mono.just(new Identity("client", AccountService.parseCookie(raw)));
            }
        }
        return switch (tier) {
            case "public" -> setting.publicUsesSite() ? site().defaultIfEmpty(Identity.anonymous())
                : Mono.just(Identity.anonymous());
            case "personal", "write" -> currentUsername()
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录")))
                .flatMap(username -> accounts.cookieOf(AccountService.userAccountName(username))
                    .map(c -> new Identity("user", c))
                    .switchIfEmpty(Mono.defer(() -> accounts.siteCookieIfOwnedBy(username)
                        .map(c -> new Identity("user", c))))
                    .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "请先在个人中心绑定网易云账号"))));
            default -> Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "该接口不对外开放"));
        };
    }

    public Mono<Identity> site() {
        return accounts.cookieOf(AccountService.SITE).map(c -> new Identity("site", c));
    }

    public static Mono<String> currentUsername() {
        return ReactiveSecurityContextHolder.getContext()
            .map(SecurityContext::getAuthentication)
            .filter(a -> a.isAuthenticated() && !"anonymousUser".equals(a.getName()))
            .map(Authentication::getName);
    }
}
