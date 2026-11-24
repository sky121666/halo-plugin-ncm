package cn.sky.ncm.setting;

import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

@Component
public class SettingService {

    static final Duration CACHE = Duration.ofSeconds(10);

    private final ReactiveSettingFetcher fetcher;
    private final FrontstageSettingsService frontstage;
    private final ApiAccessSettingsService access;
    private final ClientConfigService preferences;
    private volatile Mono<AdvancedSetting> advanced;
    private volatile long advancedAt;

    public SettingService(ReactiveSettingFetcher fetcher, FrontstageSettingsService frontstage,
                          ApiAccessSettingsService access, ClientConfigService preferences) {
        this.fetcher = fetcher;
        this.frontstage = frontstage;
        this.access = access;
        this.preferences = preferences;
    }

    public Mono<BasicSetting> basic() {
        return access.current().onErrorReturn(BasicSetting.CLOSED);
    }

    public Mono<PlayerSetting> player() {
        return frontstage.current().flatMap(page -> preferences.foliaPlayer(page.resume()))
            .onErrorReturn(PlayerSetting.DEFAULT);
    }

    public Mono<PageSetting> page() {
        return Mono.zip(frontstage.current(), advanced(), NcmFrontstageSettings::page)
            .onErrorReturn(PageSetting.CLOSED);
    }

    public Mono<FinderSetting> finder() {
        return Mono.zip(frontstage.current(), advanced(), NcmFrontstageSettings::finder)
            .onErrorReturn(FinderSetting.CLOSED);
    }

    /** 旧推荐歌单归集完成后，发布列表由精选歌单资源管理。 */
    public Mono<List<String>> legacyFeaturedIds() {
        return fetcher.fetch("page", PageSetting.class)
            .defaultIfEmpty(PageSetting.DEFAULT)
            .map(PageSetting::featuredIds);
    }

    /** 网络与限流设置，缓存 10 秒。出站连接独立校验代理设置。 */
    public Mono<AdvancedSetting> advanced() {
        long now = System.currentTimeMillis();
        Mono<AdvancedSetting> m = advanced;
        if (m == null || now - advancedAt > CACHE.toMillis()) {
            m = fetcher.fetch("advanced", AdvancedSetting.class)
                .defaultIfEmpty(AdvancedSetting.DEFAULT)
                .onErrorReturn(AdvancedSetting.DEFAULT)
                .cache();
            advanced = m;
            advancedAt = now;
        }
        return m;
    }
}
