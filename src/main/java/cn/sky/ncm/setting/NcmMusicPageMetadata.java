package cn.sky.ncm.setting;

import java.net.URI;
import org.springframework.web.server.ServerWebInputException;

/** Optional metadata for the independent music page. */
public record NcmMusicPageMetadata(String icon, String seoTitle, String shareImage, String iconSource) {
    private static final String SITE = "site";
    private static final String CUSTOM = "custom";
    public static final NcmMusicPageMetadata DEFAULT = new NcmMusicPageMetadata("", "", "");

    public NcmMusicPageMetadata(String icon, String seoTitle, String shareImage) {
        this(icon, seoTitle, shareImage, null);
    }

    public boolean usesCustomIcon() {
        return iconSource == null ? !safeImageUrl(icon).isEmpty() : CUSTOM.equals(iconSource);
    }

    NcmMusicPageMetadata withStoredDefaults(NcmMusicPageMetadata stored) {
        String source = iconSource != null ? iconSource : stored.iconSource();
        // 旧客户端清空图标时不会提交来源。
        if (iconSource == null && CUSTOM.equals(source) && icon != null && icon.isBlank()) {
            source = SITE;
        }
        return new NcmMusicPageMetadata(icon != null ? icon : stored.icon(),
            seoTitle != null ? seoTitle : stored.seoTitle(),
            shareImage != null ? shareImage : stored.shareImage(), source);
    }

    void validateForSave() {
        if (iconSource != null && !SITE.equals(iconSource) && !CUSTOM.equals(iconSource)) {
            throw new ServerWebInputException("音乐页图标来源无效");
        }
        if (CUSTOM.equals(iconSource) && (icon == null || icon.isBlank())) {
            throw new ServerWebInputException("请选择自定义图标");
        }
        validateImage(icon, "音乐页图标");
        validateImage(shareImage, "分享封面");
    }

    private static void validateImage(String value, String field) {
        if (value != null && !value.isBlank() && safeImageUrl(value).isEmpty()) {
            throw new ServerWebInputException(field + "须使用站内绝对路径或 HTTP(S) 图片地址");
        }
    }

    public static String safeImageUrl(String input) {
        if (input == null || input.length() > 2048 || input.chars().anyMatch(Character::isISOControl)) {
            return "";
        }
        String url = input.strip();
        try {
            URI uri = URI.create(url);
            if (url.startsWith("/") && !url.startsWith("//") && uri.getRawAuthority() == null) {
                return url;
            }
            if (("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null && uri.getRawUserInfo() == null) {
                return url;
            }
        } catch (IllegalArgumentException ignored) {
            // Invalid URI.
        }
        return "";
    }
}
