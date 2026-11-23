package cn.sky.ncm.api.more;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 极简 ID3v2 读取（只取 TIT2 标题、TPE1 歌手、TALB 专辑），代替上游的 music-metadata。
 * 解析失败时返回空 Map，调用方改用文件名，与上游的降级行为一致。
 */
final class Id3 {

    private Id3() {
    }

    static Map<String, String> read(byte[] data) {
        Map<String, String> out = new HashMap<>();
        try {
            if (data.length < 10 || data[0] != 'I' || data[1] != 'D' || data[2] != '3') {
                return out;
            }
            int version = data[3];
            int size = syncsafe(data, 6);
            int pos = 10;
            if ((data[5] & 0x40) != 0) {
                pos += version == 4 ? syncsafe(data, 10) : be32(data, 10) + 4;
            }
            int end = Math.min(data.length, 10 + size);
            while (pos + 10 <= end) {
                String id = new String(data, pos, 4, StandardCharsets.ISO_8859_1);
                if (id.charAt(0) == 0) {
                    break;
                }
                int len = version == 4 ? syncsafe(data, pos + 4) : be32(data, pos + 4);
                int body = pos + 10;
                if (len <= 0 || body + len > end) {
                    break;
                }
                String key = switch (id) {
                    case "TIT2" -> "title";
                    case "TPE1" -> "artist";
                    case "TALB" -> "album";
                    default -> null;
                };
                if (key != null) {
                    String v = text(data, body, len);
                    if (!v.isEmpty()) {
                        out.put(key, v);
                    }
                }
                pos = body + len;
            }
        } catch (RuntimeException e) {
            out.clear();
        }
        return out;
    }

    static String text(byte[] d, int off, int len) {
        int enc = d[off];
        Charset cs = switch (enc) {
            case 1 -> StandardCharsets.UTF_16;
            case 2 -> StandardCharsets.UTF_16BE;
            case 3 -> StandardCharsets.UTF_8;
            default -> StandardCharsets.ISO_8859_1;
        };
        String s = new String(d, off + 1, len - 1, cs);
        int nul = s.indexOf('\u0000');
        return (nul >= 0 ? s.substring(0, nul) : s).trim();
    }

    static int syncsafe(byte[] d, int i) {
        return ((d[i] & 0x7f) << 21) | ((d[i + 1] & 0x7f) << 14) | ((d[i + 2] & 0x7f) << 7) | (d[i + 3] & 0x7f);
    }

    static int be32(byte[] d, int i) {
        return ((d[i] & 0xff) << 24) | ((d[i + 1] & 0xff) << 16) | ((d[i + 2] & 0xff) << 8) | (d[i + 3] & 0xff);
    }
}
