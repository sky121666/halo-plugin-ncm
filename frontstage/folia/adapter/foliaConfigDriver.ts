import i18n, { applyAppLanguagePreference, readStoredAppLanguagePreference } from '../i18n/config';
import { buildVisualSettingsConfig } from '../services/obs/visualSettingsConfig';
import { applyImportedConfig, type ConfigThemeControls } from '../components/modal/settings/pages/storage/visualConfigTransfer';
import { useAudioSettingsStore, type AudioSettingsState } from '../stores/useAudioSettingsStore';
import { useAutomixSettingsStore, type AutomixSettingsState } from '../stores/useAutomixSettingsStore';
import { useGridViewSettingsStore, type GridViewSettingsState } from '../stores/useGridViewSettingsStore';
import { useHomeLayoutSettingsStore, type HomeLayoutSettingsState } from '../stores/useHomeLayoutSettingsStore';
import { useInteractionSettingsStore, type InteractionSettingsState } from '../stores/useInteractionSettingsStore';
import { useLatticeSettingsStore, type LatticeSettingsState } from '../stores/useLatticeSettingsStore';
import { useLibraryWallLookStore } from '../stores/useLibraryWallLookStore';
import { useLyricSettingsStore, type LyricSettingsState } from '../stores/useLyricSettingsStore';
import { MOTION_SURFACE_IDS, useMotionSettingsStore } from '../stores/useMotionSettingsStore';
import { usePlaybackEntryViewStore } from '../stores/usePlaybackEntryViewStore';
import { usePlaybackStore } from '../stores/usePlaybackStore';
import { usePlayerChromeSettingsStore, type PlayerChromeSettingsState } from '../stores/usePlayerChromeSettingsStore';
import { useScratchpadSettingsStore } from '../stores/useScratchpadSettingsStore';
import { useStageSettingsStore } from '../stores/useStageSettingsStore';
import { useThemeSettingsStore } from '../stores/useThemeSettingsStore';
import { useTypographySettingsStore } from '../stores/useTypographySettingsStore';
import { useVideoLayerSettingsStore } from '../stores/useVideoLayerSettingsStore';
import { useVisualizerSettingsStore } from '../stores/useVisualizerSettingsStore';
import type { DualTheme, ThemeMode } from '../types';
import { legacyColorPreference, musicColorPreference } from './appearance';
import { selectConfig, type ConfigDocument, type ConfigGroup, type ConfigValue } from './haloConfigDocument';

export interface ConfigDriver {
    capture(): ConfigDocument;
    restore(config: ConfigDocument): Promise<void>;
    subscribe(change: () => void): () => void;
}

export type FoliaThemeDriver = ConfigThemeControls & {
    restoreProfileTheme: (mode: ThemeMode, palette: DualTheme | null) => void;
};

const persistedStores = [
    useAudioSettingsStore, useAutomixSettingsStore, useGridViewSettingsStore, useHomeLayoutSettingsStore,
    useInteractionSettingsStore, usePlaybackEntryViewStore, useVideoLayerSettingsStore,
    useLatticeSettingsStore, useLibraryWallLookStore, useLyricSettingsStore, useMotionSettingsStore,
    usePlayerChromeSettingsStore, useScratchpadSettingsStore, useStageSettingsStore, useThemeSettingsStore,
    useTypographySettingsStore, useVisualizerSettingsStore,
];

function applyFields<T>(group: ConfigGroup | undefined, setters: { [K in keyof T]?: (value: T[K]) => unknown }) {
    for (const [key, setter] of Object.entries(setters) as [string, (value: ConfigValue) => unknown][]) {
        if (group && key in group) setter(group[key]);
    }
}

export function createFoliaConfigDriver(getTheme: () => FoliaThemeDriver): ConfigDriver {
    return {
        capture() {
            const theme = getTheme();
            const colors = useThemeSettingsStore.getState();
            return selectConfig({
                visual: buildVisualSettingsConfig(),
                appearance: {
                    mode: theme.bgMode,
                    palette: theme.customTheme ?? null,
                    colorScheme: colors.colorSchemePreference,
                },
                playback: { ...useAudioSettingsStore.getState(), ...useAutomixSettingsStore.getState(),
                    replayGainMode: usePlaybackStore.getState().replayGainMode },
                player: { ...usePlayerChromeSettingsStore.getState(), ...useLatticeSettingsStore.getState(),
                    ...usePlaybackEntryViewStore.getState(), ...useVideoLayerSettingsStore.getState() },
                lyrics: useLyricSettingsStore.getState(),
                home: { ...useHomeLayoutSettingsStore.getState(), ...useGridViewSettingsStore.getState(),
                    showHandle: useScratchpadSettingsStore.getState().showHandle },
                motion: { ...useMotionSettingsStore.getState(),
                    disableHomeDynamicBackground: colors.disableHomeDynamicBackground,
                    glowBlurQuantize: useVisualizerSettingsStore.getState().glowBlurQuantize },
                interaction: useInteractionSettingsStore.getState(),
                general: { language: readStoredAppLanguagePreference() },
            });
        },
        async restore(config) {
            const theme = getTheme();
            const colors = useThemeSettingsStore.getState();
            const appearance = config.appearance;
            const preference = musicColorPreference(appearance?.colorScheme)
                ?? legacyColorPreference(config.visual?.followSystemTheme, appearance?.daylight);
            if (preference) colors.setColorSchemePreference(preference);
            if (appearance && ('mode' in appearance || 'palette' in appearance)) {
                theme.restoreProfileTheme((appearance.mode ?? theme.bgMode) as ThemeMode,
                    ('palette' in appearance ? appearance.palette : theme.customTheme) as DualTheme | null);
            }

            const visual = config.visual;
            if (visual) {
                const keys = Object.keys(visual).filter(key => key !== 'followSystemTheme');
                applyImportedConfig(visual, keys, theme);
            }

            const audio = useAudioSettingsStore.getState();
            applyFields<AudioSettingsState>(config.playback, {
                volume: audio.handleSetVolume,
                loopMode: audio.handleSetLoopMode,
                audioQuality: audio.setAudioQuality,
                queueAddBehavior: audio.handleSetQueueAddBehavior,
                enableMediaCache: audio.handleToggleMediaCache,
                playbackFadeEnabled: audio.handleTogglePlaybackFade,
                autoSkipUnplayable: audio.handleToggleAutoSkipUnplayable,
                shuffleRoundNoRepeat: audio.handleToggleShuffleRoundNoRepeat,
                audioEqualizerSettings: audio.handleSetAudioEqualizerSettings,
            });
            const mix = useAutomixSettingsStore.getState();
            applyFields<AutomixSettingsState>(config.playback, {
                automixEnabled: mix.handleToggleAutomix,
                transitionMode: mix.handleSetTransitionMode,
                crossfadeMaxSec: mix.handleSetCrossfadeMaxSec,
                transitionPerformance: mix.handleToggleTransitionPerformance,
                transitionAnimation: mix.handleToggleTransitionAnimation,
                transitionAnimationCard: mix.handleToggleTransitionAnimationCard,
            });
            applyFields<ReturnType<typeof usePlaybackStore.getState>>(config.playback, {
                replayGainMode: usePlaybackStore.getState().setReplayGainMode,
            });
            const chrome = usePlayerChromeSettingsStore.getState();
            applyFields<PlayerChromeSettingsState>(config.player, {
                transparentPlayerBackground: chrome.setTransparentPlayerBackgroundFromSystem,
                autoHidePlayerChrome: chrome.handleToggleAutoHidePlayerChrome,
                autoHideCursorWithPlayerChrome: chrome.handleToggleAutoHideCursorWithPlayerChrome,
                hideCursorWhenIdle: chrome.handleToggleHideCursorWhenIdle,
                hidePlayerProgressBar: chrome.handleToggleHidePlayerProgressBar,
                hidePlayerRightPanelButton: chrome.handleToggleHidePlayerRightPanelButton,
                alwaysShowPlayerBackButton: chrome.handleToggleAlwaysShowPlayerBackButton,
                alwaysShowTrackSwitchButtons: chrome.handleToggleAlwaysShowTrackSwitchButtons,
                showOpenPanelCloseButton: chrome.handleToggleOpenPanelCloseButton,
            });
            const wall = useLatticeSettingsStore.getState();
            applyFields<LatticeSettingsState>(config.player, {
                latticeWallForm: wall.handleSetLatticeWallForm,
                latticeRadioHideUpcoming: wall.handleToggleLatticeRadioHideUpcoming,
                latticeVignette: wall.handleToggleLatticeVignette,
                latticePosterTintEnabled: wall.handleToggleLatticePosterTint,
                latticePosterTintUseCustomColor: wall.handleToggleLatticePosterTintCustomColor,
                latticePosterTintColor: wall.handleSetLatticePosterTintColor,
                latticePosterTintIntensity: wall.handleSetLatticePosterTintIntensity,
            });
            applyFields<ReturnType<typeof usePlaybackEntryViewStore.getState>>(config.player, {
                playbackEntryView: usePlaybackEntryViewStore.getState().setPlaybackEntryView,
            });
            const video = useVideoLayerSettingsStore.getState();
            applyFields<ReturnType<typeof useVideoLayerSettingsStore.getState>>(config.player, {
                videoLayerOpacity: video.setVideoLayerOpacity,
                videoLayerFit: video.setVideoLayerFit,
            });
            const lyrics = useLyricSettingsStore.getState();
            applyFields<LyricSettingsState>(config.lyrics, {
                autoUseBestLyric: lyrics.handleToggleAutoUseBestLyric,
                keepProviderWordTiming: lyrics.handleToggleKeepProviderWordTiming,
                lyricStaffPolicy: lyrics.handleSetLyricStaffPolicy,
                lyricStaffMinDwellSeconds: lyrics.handleSetLyricStaffMinDwellSeconds,
                lyricStaffAbsorbMode: lyrics.handleSetLyricStaffAbsorbMode,
            });
            const home = useHomeLayoutSettingsStore.getState();
            applyFields<HomeLayoutSettingsState>(config.home, {
                homeLayoutStyle: home.handleSetHomeLayoutStyle,
                grid3dCardStyle: home.handleSetGrid3dCardStyle,
                showHomeTabPlaylist: home.handleToggleHomeTabPlaylist,
                showHomeTabRadio: home.handleToggleHomeTabRadio,
                showHomeTabAlbums: home.handleToggleHomeTabAlbums,
                showHomeTabExplore: home.handleToggleHomeTabExplore,
                rememberHomeCardPosition: home.handleToggleRememberHomeCardPosition,
            });
            const grid = useGridViewSettingsStore.getState();
            applyFields<GridViewSettingsState>(config.home, {
                gridViewFullBleedCover: grid.handleToggleGridViewFullBleedCover,
                gridViewSquareCards: grid.handleToggleGridViewSquareCards,
                gridViewMinCardScale: grid.handleSetGridViewMinCardScale,
                gridViewMinCardOpacity: grid.handleSetGridViewMinCardOpacity,
            });
            if (typeof config.home?.showHandle === 'boolean') {
                useScratchpadSettingsStore.getState().setShowHandle(config.home.showHandle);
            }
            const interaction = useInteractionSettingsStore.getState();
            applyFields<InteractionSettingsState>(config.interaction, {
                gridActionButtonSlideTarget: interaction.setGridActionButtonSlideTarget,
                gridCommandPaletteHotkey: interaction.handleToggleGridCommandPaletteHotkey,
                queuePaletteKeepOpen: interaction.setQueuePaletteKeepOpen,
                visualizerPickerKeepOpen: interaction.setVisualizerPickerKeepOpen,
                customShortcutLetter: interaction.setCustomShortcutLetter,
                customShortcutCommandId: interaction.setCustomShortcutCommandId,
            });
            const motion = useMotionSettingsStore.getState();
            if (typeof config.motion?.disableHomeDynamicBackground === 'boolean') {
                colors.handleToggleDisableHomeDynamicBackground(config.motion.disableHomeDynamicBackground);
            }
            if (typeof config.motion?.glowBlurQuantize === 'boolean') {
                useVisualizerSettingsStore.getState().handleToggleGlowBlurQuantize(config.motion.glowBlurQuantize);
            }
            if (typeof config.motion?.followSystemReducedMotion === 'boolean') {
                motion.handleToggleFollowSystemReducedMotion(config.motion.followSystemReducedMotion);
            }
            const surfaces = config.motion?.reducedMotionSurfaces as Record<string, boolean> | undefined;
            if (surfaces) {
                for (const id of MOTION_SURFACE_IDS) {
                    if (typeof surfaces[id] === 'boolean') motion.handleToggleReducedMotionSurface(id, surfaces[id]);
                }
            }
            const language = config.general?.language;
            if (typeof language === 'string' && language !== readStoredAppLanguagePreference()) {
                await applyAppLanguagePreference(language as ReturnType<typeof readStoredAppLanguagePreference>);
            }
        },
        subscribe(change) {
            const cleanups = persistedStores.map(store => store.subscribe(change));
            cleanups.push(usePlaybackStore.subscribe((current, previous) => {
                if (current.replayGainMode !== previous.replayGainMode) change();
            }));
            i18n.on('languageChanged', change);
            return () => {
                cleanups.forEach(cleanup => cleanup());
                i18n.off('languageChanged', change);
            };
        },
    };
}
