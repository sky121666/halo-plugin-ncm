package cn.sky.ncm.ai;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.sky.ncm.setting.NcmToolSettings;
import cn.sky.ncm.setting.ToolSettingsService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.core.user.service.RoleService;

class MusicAiSettingsTest {
    private final ToolSettingsService tools = mock(ToolSettingsService.class);
    private final RoleService roles = mock(RoleService.class);
    private final MusicAiSettings settings = new MusicAiSettings(tools, roles);
    private final UsernamePasswordAuthenticationToken viewer = UsernamePasswordAuthenticationToken.authenticated(
        "reader", "unused", List.of(new SimpleGrantedAuthority("ROLE_guest")));

    @Test
    void freshInstallDefaultsToSignedIn() {
        setAi(NcmToolSettings.Ai.DEFAULT);
        StepVerifier.create(settings.access(viewer)).expectNext(MusicAiSettings.Access.ALLOWED).verifyComplete();
        verifyNoInteractions(roles);
    }

    @Test
    void disabledOrUnreadableConfigurationRemainsClosed() {
        setAi(new NcmToolSettings.Ai(false, "signed-in", List.of()));
        StepVerifier.create(settings.access(viewer)).expectNext(MusicAiSettings.Access.DISABLED).verifyComplete();
        when(tools.current()).thenReturn(Mono.error(new IllegalStateException("setting unavailable")));
        StepVerifier.create(settings.access(viewer)).expectNext(MusicAiSettings.Access.DISABLED).verifyComplete();
        verifyNoInteractions(roles);
    }

    @Test
    void acceptsAnySelectedRoleAndAppliesRemovedAssignments() {
        setAi(new NcmToolSettings.Ai(true, "roles", List.of("editor", "guest")));
        when(roles.getRolesByUsername("reader")).thenReturn(Flux.just("guest"), Flux.just("editor"));

        StepVerifier.create(settings.access(viewer)).expectNext(MusicAiSettings.Access.ALLOWED).verifyComplete();
        StepVerifier.create(settings.access(viewer)).expectNext(MusicAiSettings.Access.DENIED).verifyComplete();
    }

    @Test
    void restrictsEmptyRoleListsAndDoesNotImplicitlyAuthorizeAdministrators() {
        setAi(new NcmToolSettings.Ai(true, "roles", List.of()));
        StepVerifier.create(settings.access(viewer)).expectNext(MusicAiSettings.Access.DENIED).verifyComplete();

        setAi(new NcmToolSettings.Ai(true, "roles", List.of("guest")));
        var administrator = UsernamePasswordAuthenticationToken.authenticated("admin", "unused",
            List.of(new SimpleGrantedAuthority("ROLE_super-role")));
        StepVerifier.create(settings.access(administrator)).expectNext(MusicAiSettings.Access.DENIED).verifyComplete();
        verifyNoInteractions(roles);
    }

    private void setAi(NcmToolSettings.Ai ai) {
        when(tools.current()).thenReturn(Mono.just(new NcmToolSettings(ai, null)));
    }
}
