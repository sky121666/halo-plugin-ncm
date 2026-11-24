package cn.sky.ncm.ai;

import cn.sky.ncm.setting.NcmToolSettings;
import cn.sky.ncm.setting.ToolSettingsService;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.core.user.service.RoleService;

@Component
public class MusicAiSettings {
    private final ToolSettingsService settings;
    private final RoleService roles;

    MusicAiSettings(ToolSettingsService settings, RoleService roles) {
        this.settings = settings;
        this.roles = roles;
    }

    enum Access { ALLOWED, DENIED, DISABLED }

    Mono<Access> access(Authentication viewer) {
        return settings.current().flatMap(config -> access(config.ai(), viewer))
            .onErrorReturn(Access.DISABLED);
    }

    private Mono<Access> access(NcmToolSettings.Ai ai, Authentication viewer) {
        if (ai == null || !Boolean.TRUE.equals(ai.enabled())) return Mono.just(Access.DISABLED);
        if ("signed-in".equals(ai.audience())) return Mono.just(Access.ALLOWED);
        if (!"roles".equals(ai.audience()) || ai.allowedRoles() == null || ai.allowedRoles().isEmpty()) {
            return Mono.just(Access.DENIED);
        }

        var authorities = viewer.getAuthorities().stream().map(GrantedAuthority::getAuthority)
            .collect(Collectors.toSet());
        Set<String> matchingRoles = ai.allowedRoles().stream()
            .filter(role -> authorities.contains("ROLE_" + role)).collect(Collectors.toSet());
        if (matchingRoles.isEmpty()) return Mono.just(Access.DENIED);
        // 实时核对角色绑定，尊重已经撤销的权限与令牌的授权范围。
        return roles.getRolesByUsername(viewer.getName()).any(matchingRoles::contains)
            .map(matches -> matches ? Access.ALLOWED : Access.DENIED)
            .onErrorReturn(Access.DENIED);
    }
}
