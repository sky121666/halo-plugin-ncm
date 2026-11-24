package cn.sky.ncm;

import cn.sky.ncm.account.NcmAccount;
import cn.sky.ncm.ai.MusicAiSettings;
import cn.sky.ncm.bootstrap.NcmBootstrap;
import cn.sky.ncm.device.NcmDevice;
import cn.sky.ncm.featured.FeaturedService;
import cn.sky.ncm.featured.NcmFeaturedPlaylist;
import cn.sky.ncm.setting.ApiAccessSettingsService;
import cn.sky.ncm.setting.ClientConfigService;
import cn.sky.ncm.setting.FrontstageSettingsService;
import cn.sky.ncm.setting.NcmClientConfig;
import java.time.Duration;
import org.springframework.stereotype.Component;
import run.halo.app.extension.SchemeManager;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

@Component
public class NcmPlugin extends BasePlugin {

    private final SchemeManager schemeManager;
    private final FrontstageSettingsService frontstageSettings;
    private final ApiAccessSettingsService apiAccessSettings;
    private final NcmBootstrap bootstrap;
    private final FeaturedService featuredPlaylists;
    private final ClientConfigService preferences;
    private final MusicAiSettings musicAiSettings;

    public NcmPlugin(PluginContext pluginContext, SchemeManager schemeManager,
                     FrontstageSettingsService frontstageSettings, ApiAccessSettingsService apiAccessSettings,
                     NcmBootstrap bootstrap, FeaturedService featuredPlaylists, ClientConfigService preferences,
                     MusicAiSettings musicAiSettings) {
        super(pluginContext);
        this.schemeManager = schemeManager;
        this.frontstageSettings = frontstageSettings;
        this.apiAccessSettings = apiAccessSettings;
        this.bootstrap = bootstrap;
        this.featuredPlaylists = featuredPlaylists;
        this.preferences = preferences;
        this.musicAiSettings = musicAiSettings;
    }

    @Override
    public void start() {
        apiAccessSettings.initialize().then(frontstageSettings.initialize()).block(Duration.ofSeconds(15));
        musicAiSettings.migrate().block();
        schemeManager.register(NcmAccount.class);
        schemeManager.register(NcmDevice.class);
        schemeManager.register(NcmFeaturedPlaylist.class);
        schemeManager.register(NcmClientConfig.class);
        frontstageSettings.legacyPlayer().flatMap(preferences::migrateFoliaDefaults).block(Duration.ofSeconds(10));
        bootstrap.restoreStoredState().block(Duration.ofSeconds(10));
        featuredPlaylists.ensureMigrated().block(Duration.ofSeconds(10));
    }

    @Override
    public void stop() {
        schemeManager.unregister(schemeManager.get(NcmAccount.class));
        schemeManager.unregister(schemeManager.get(NcmDevice.class));
        schemeManager.unregister(schemeManager.get(NcmFeaturedPlaylist.class));
        schemeManager.unregister(schemeManager.get(NcmClientConfig.class));
    }
}
