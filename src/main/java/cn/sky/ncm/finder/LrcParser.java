package cn.sky.ncm.finder;

import cn.sky.ncm.finder.vo.LyricLineVo;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** LRC 歌词解析：支持一行多个时间标签，跳过 [ti:] 等元信息和网易云的 JSON 元数据行，并按时间合并译文。 */
final class LrcParser {

    static final Pattern TAG = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");

    private LrcParser() {
    }

    /** 时间（毫秒）→ 歌词，时间相同的只保留第一行。 */
    static TreeMap<Long, String> parse(String lrc) {
        TreeMap<Long, String> out = new TreeMap<>();
        if (lrc == null || lrc.isBlank()) {
            return out;
        }
        for (String raw : lrc.split("\\r?\\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("{")) {
                continue;
            }
            Matcher m = TAG.matcher(line);
            List<Long> times = new ArrayList<>();
            int end = 0;
            while (m.find() && m.start() == end) {
                times.add(toMillis(m.group(1), m.group(2), m.group(3)));
                end = m.end();
            }
            if (times.isEmpty()) {
                continue;
            }
            String text = line.substring(end).strip();
            for (Long t : times) {
                out.putIfAbsent(t, text);
            }
        }
        return out;
    }

    static List<LyricLineVo> merge(String lrc, String translated) {
        TreeMap<Long, String> main = parse(lrc);
        Map<Long, String> trans = parse(translated);
        List<LyricLineVo> lines = new ArrayList<>(main.size());
        main.forEach((t, text) -> {
            String tr = trans.get(t);
            lines.add(LyricLineVo.builder()
                .timeMs(t)
                .timeText(format(t))
                .text(text)
                .translation(tr == null || tr.isBlank() ? null : tr)
                .build());
        });
        return lines;
    }

    static long toMillis(String min, String sec, String frac) {
        long ms = 0;
        if (frac != null) {
            ms = switch (frac.length()) {
                case 1 -> Long.parseLong(frac) * 100;
                case 2 -> Long.parseLong(frac) * 10;
                default -> Long.parseLong(frac);
            };
        }
        return Long.parseLong(min) * 60_000 + Long.parseLong(sec) * 1000 + ms;
    }

    static String format(long ms) {
        return String.format("%02d:%02d.%02d", ms / 60_000, (ms / 1000) % 60, (ms % 1000) / 10);
    }
}
