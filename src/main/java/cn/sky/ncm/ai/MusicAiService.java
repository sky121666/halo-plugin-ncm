package cn.sky.ncm.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.extensionpoint.ExtensionGetter;

@Service
class MusicAiService {
    private static final Logger LOG = LoggerFactory.getLogger(MusicAiService.class);
    private static final String FOUNDATION_ID = "ai-foundation";
    private static final int MAX_LYRIC_LENGTH = 12_000;
    static final int MAX_LINES = 100;
    private static final int MAX_LINE_LENGTH = 240;
    private static final int MAX_TITLE_LENGTH = 160;
    private static final int MAX_THEME_SOURCE_LENGTH = 2000;
    static final int MAX_NAME_LENGTH = 32;
    static final int MAX_THEME_WORDS = 20;
    static final int MAX_THEME_ICONS = 5;
    private static final int MAX_THEME_DESCRIPTION_LENGTH = 30;
    private static final Pattern HEX_COLOR = Pattern.compile("#[a-fA-F0-9]{6}");
    private static final Pattern LUCIDE_NAME = Pattern.compile("[A-Z][A-Za-z0-9]{0,63}");

    private final MusicAiSettings settings;
    private final PluginWrapper plugin;
    private final String versionRange;
    private final ExtensionGetter extensions;
    private final MusicAiBudget budget;

    MusicAiService(MusicAiSettings settings, PluginWrapper plugin, ExtensionGetter extensions, MusicAiBudget budget) {
        this.settings = settings;
        this.plugin = plugin;
        this.versionRange = plugin.getDescriptor().getDependencies().stream()
            .filter(dependency -> FOUNDATION_ID.equals(dependency.getPluginId()) && dependency.isOptional())
            .findFirst()
            .orElseThrow()
            .getPluginVersionSupport();
        this.extensions = extensions;
        this.budget = budget;
    }

    Mono<Boolean> available(Authentication viewer) {
        return settings.access(viewer)
            .filter(MusicAiSettings.Access.ALLOWED::equals)
            .flatMap(ignored -> foundation())
            .flatMap(FoundationMusicAi::available)
            .defaultIfEmpty(false);
    }

    Mono<ThemePair> theme(Authentication viewer, ThemeInput input) {
        return Mono.defer(() -> {
            String title = input.songTitle() == null ? "" : input.songTitle().strip();
            String lyrics = input.lyricsText() == null ? "" : input.lyricsText().strip();
            if (lyrics.length() > MAX_LYRIC_LENGTH || title.length() > MAX_TITLE_LENGTH
                || (lyrics.isEmpty() && title.isEmpty())) {
                return Mono.error(invalid("请输入歌名或歌词，歌词最长 12000 字"));
            }
            String source = (input.isPureMusic() || lyrics.isEmpty()) && !title.isEmpty() ? title : lyrics;
            int end = source.offsetByCodePoints(0, Math.min(MAX_THEME_SOURCE_LENGTH,
                source.codePointCount(0, source.length())));
            String snippet = source.substring(0, end);
            return generate(viewer, ai -> ai.theme(viewer.getName(), title, snippet, input.isPureMusic())).map(output -> {
                if (!(output instanceof Map<?, ?> pair)) {
                    throw invalidOutput();
                }
                var words = themeWords(pair.get("wordColors"), snippet);
                var icons = themeIcons(pair.get("lyricsIcons"));
                return new ThemePair(palette(pair.get("light"), words, icons), palette(pair.get("dark"), words, icons));
            });
        });
    }

    Mono<Segmentation> segment(Authentication viewer, SegmentationInput input) {
        return Mono.defer(() -> {
            List<String> lines = input.lines();
            if (lines == null || lines.isEmpty() || lines.size() > MAX_LINES
                || lines.stream().anyMatch(line -> line == null || line.isBlank() || line.length() > MAX_LINE_LENGTH
                    || line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0)
                || lines.stream().mapToInt(String::length).sum() > MAX_LYRIC_LENGTH) {
                return Mono.error(invalid("一次可处理 1 至 100 行歌词，每行最多 240 字，总长度不超过 12000 字"));
            }
            return generate(viewer, ai -> ai.segment(viewer.getName(), lines)).map(output -> segments(output, lines));
        });
    }

    private Mono<FoundationMusicAi> foundation() {
        return Mono.fromSupplier(() -> {
            var manager = plugin.getPluginManager();
            var provider = manager.getPlugin(FOUNDATION_ID);
            if (provider == null || provider.getPluginState() != PluginState.STARTED
                || !manager.getVersionManager().checkVersionConstraint(
                    provider.getDescriptor().getVersion(), versionRange)) {
                return null;
            }
            try {
                // The optional SDK belongs to the running Foundation plugin's class loader.
                // After a provider update, a stale linkage stays unavailable until NCM is reloaded.
                if (FoundationMusicAi.isLinkedTo(provider.getPluginClassLoader())) {
                    return new FoundationMusicAi(extensions, budget);
                }
            } catch (LinkageError error) {
                LOG.debug("音乐 AI SDK 未就绪", error);
            }
            return null;
        });
    }

    private Mono<Object> generate(Authentication viewer, Function<FoundationMusicAi, Mono<Object>> request) {
        return settings.access(viewer).flatMap(access -> switch (access) {
            case ALLOWED -> foundation().switchIfEmpty(Mono.error(MusicAiService::unavailable)).flatMap(request);
            case DISABLED -> Mono.error(unavailable());
            case DENIED -> Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号无法使用音乐 AI"));
        });
    }

    private static Palette palette(Object value, List<ThemeWordColor> words, List<String> icons) {
        if (!(value instanceof Map<?, ?> source) || !(source.get("name") instanceof String name)
            || name.isBlank() || name.length() > MAX_NAME_LENGTH || name.chars().anyMatch(Character::isISOControl)) {
            throw invalidOutput();
        }
        String description = source.get("description") instanceof String text ? text.strip() : "";
        if (description.length() > MAX_THEME_DESCRIPTION_LENGTH || description.chars().anyMatch(Character::isISOControl)) {
            description = "";
        }
        return new Palette(name.strip(), color(source.get("backgroundColor")), color(source.get("primaryColor")),
            color(source.get("accentColor")), color(source.get("secondaryColor")), "AI", description, words, icons);
    }

    private static List<ThemeWordColor> themeWords(Object value, String snippet) {
        if (!(value instanceof List<?> entries)) {
            return List.of();
        }
        var source = snippet.toLowerCase(Locale.ROOT);
        var words = new LinkedHashMap<String, ThemeWordColor>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> row) || !(row.get("word") instanceof String raw)
                || !(row.get("color") instanceof String color) || !HEX_COLOR.matcher(color).matches()) {
                continue;
            }
            String word = raw.strip();
            String key = word.toLowerCase(Locale.ROOT);
            if (!word.isEmpty() && word.length() <= MAX_TITLE_LENGTH
                && !word.chars().anyMatch(Character::isISOControl) && source.contains(key)) {
                words.putIfAbsent(key, new ThemeWordColor(word, color));
                if (words.size() == MAX_THEME_WORDS) {
                    break;
                }
            }
        }
        return List.copyOf(words.values());
    }

    private static List<String> themeIcons(Object value) {
        if (!(value instanceof List<?> entries)) {
            return List.of();
        }
        return entries.stream().filter(String.class::isInstance).map(String.class::cast)
            .map(String::strip).filter(icon -> LUCIDE_NAME.matcher(icon).matches())
            .distinct().limit(MAX_THEME_ICONS).toList();
    }

    private static String color(Object value) {
        if (!(value instanceof String color) || !HEX_COLOR.matcher(color).matches()) {
            throw invalidOutput();
        }
        return color;
    }

    private static Segmentation segments(Object output, List<String> lines) {
        if (!(output instanceof Map<?, ?> result) || !(result.get("lines") instanceof List<?> rows)
            || rows.size() != lines.size()) {
            throw invalidOutput();
        }
        List<List<String>> validated = new ArrayList<>(lines.size());
        boolean hasMatch = false;
        for (int i = 0; i < lines.size(); i++) {
            List<String> row = matchingRow(rows.get(i), lines.get(i));
            validated.add(row);
            hasMatch |= row != null;
        }
        if (!hasMatch) {
            throw invalidOutput();
        }
        // Null rows tell Folia to keep that line on its local word segmentation.
        return new Segmentation(validated);
    }

    private static List<String> matchingRow(Object value, String line) {
        if (!(value instanceof List<?> pieces) || pieces.isEmpty() || pieces.size() > line.length()) {
            return null;
        }
        int offset = 0;
        List<String> matched = new ArrayList<>(pieces.size());
        for (Object piece : pieces) {
            if (!(piece instanceof String text) || text.isEmpty() || !line.startsWith(text, offset)) {
                return null;
            }
            offset += text.length();
            matched.add(text);
        }
        return offset == line.length() ? matched : null;
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "音乐 AI 暂不可用");
    }

    static ResponseStatusException invalidOutput() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 返回的内容格式有误，请重试");
    }

    record ThemeInput(String lyricsText, String songTitle, boolean isPureMusic) {}
    record SegmentationInput(List<String> lines) {}
    static final class SegmentationTruncatedException extends RuntimeException {}
    record ThemeWordColor(String word, String color) {}
    record Palette(String name, String backgroundColor, String primaryColor, String accentColor, String secondaryColor,
                   String provider, String description, List<ThemeWordColor> wordColors, List<String> lyricsIcons) {}
    record ThemePair(Palette light, Palette dark) {}
    record Segmentation(List<List<String>> lines) {}
}
