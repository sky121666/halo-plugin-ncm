package cn.sky.ncm.me;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.sky.ncm.account.AccountService;
import cn.sky.ncm.account.NcmAccount;
import cn.sky.ncm.api.ApiDef;
import cn.sky.ncm.api.ApiExecutor;
import cn.sky.ncm.api.ApiRegistry;
import cn.sky.ncm.client.NcmResponse;
import cn.sky.ncm.client.RequestOptions;
import cn.sky.ncm.page.PlayService;
import cn.sky.ncm.security.SecretBox;
import cn.sky.ncm.security.SecretStore;
import cn.sky.ncm.setting.AdvancedSetting;
import cn.sky.ncm.setting.SettingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;
import java.time.Instant;
import run.halo.app.core.extension.User;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

/** 个人音乐空间测试共用的夹具：真实的 AccountService / ApiRegistry / LikeCache，网易云调用用 mock 的 ApiExecutor。 */
public class MeFixture {

    static final ObjectMapper MAPPER = new ObjectMapper();

    public final ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
    public final SecretStore secrets = mock(SecretStore.class);
    public final AccountService accounts = new AccountService(client, mock(SecretBox.class), secrets);
    public final ApiRegistry registry = new ApiRegistry();
    public final ApiExecutor executor = mock(ApiExecutor.class);
    public final SettingService settings = mock(SettingService.class);
    public final LikeCache likeCache = new LikeCache();
    public final PlayService plays = mock(PlayService.class);
    public final MeService service = new MeService(registry, executor, accounts, settings, likeCache, plays);

    public static User testUser(String name) {
        var user = new User();
        var metadata = new Metadata();
        metadata.setName(name);
        metadata.setCreationTimestamp(Instant.parse("2026-11-01T00:00:00Z"));
        user.setMetadata(metadata);
        return user;
    }

    public MeFixture() {
        when(settings.advanced()).thenReturn(Mono.just(AdvancedSetting.DEFAULT));
        when(client.fetch(org.mockito.ArgumentMatchers.eq(User.class), any())).thenAnswer(
            request -> Mono.just(testUser(request.getArgument(1))));
        // 没有特别说明的账号都不存在
        when(client.fetch(org.mockito.ArgumentMatchers.eq(NcmAccount.class), any())).thenReturn(Mono.empty());
    }

    /** 本人在个人中心绑定了网易云账号。 */
    public void givenPersonalAccount(String username, long ncmUid) {
        givenAccount(AccountService.userAccountName(username), username, ncmUid);
    }

    /** 后台绑定的站长账号，owner 为绑定它的管理员。 */
    public void givenSiteAccount(String owner, long ncmUid) {
        givenAccount(AccountService.SITE, owner, ncmUid);
    }

    void givenAccount(String name, String owner, long ncmUid) {
        var account = new NcmAccount();
        var metadata = new Metadata();
        metadata.setName(name);
        metadata.setCreationTimestamp(Instant.parse("2026-11-02T00:00:00Z"));
        account.setMetadata(metadata);
        var spec = new NcmAccount.Spec();
        spec.setOwner(owner);
        account.setSpec(spec);
        var status = new NcmAccount.Status();
        status.setUserId(ncmUid);
        account.setStatus(status);
        when(client.fetch(NcmAccount.class, name)).thenReturn(Mono.just(account));
        when(secrets.readVersionedValue(AccountService.secretNameOf(name), "account", "cookie"))
            .thenReturn(Mono.just(new SecretStore.VersionedValue("MUSIC_U=fixture-" + name, "revision-" + name)));
    }

    /** 让某个网易云接口返回给定的 JSON。 */
    public void givenApi(String name, String json) {
        try {
            var body = MAPPER.readTree(json);
            int code = body.path("code").asInt(200);
            when(executor.execute(api(name), anyMap(), any(RequestOptions.class)))
                .thenReturn(Mono.just(new NcmResponse(code, body, List.of())));
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static ApiDef api(String name) {
        return argThat(d -> d != null && name.equals(d.name()));
    }

    public MeIdentity identity(String username, String uid) {
        return new MeIdentity(username, Map.of("MUSIC_U", "fixture"), uid, false);
    }
}
