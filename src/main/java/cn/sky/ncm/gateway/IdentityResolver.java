package cn.sky.ncm.gateway;

import cn.sky.ncm.account.AccountService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * public 接口使用游客身份。
 * personal / write 接口使用当前 Halo 用户的绑定，后台绑定也仅供绑定人本人使用。
 */
@Component
public class IdentityResolver {

    private final AccountService accounts;

    public IdentityResolver(AccountService accounts) {
        this.accounts = accounts;
    }

    public Mono<Identity> resolve(String tier) {
        return switch (tier) {
            case "public" -> Mono.just(Identity.anonymous());
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

    /** 后台精选歌单核验使用。 */
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
