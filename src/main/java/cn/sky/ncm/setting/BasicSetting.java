package cn.sky.ncm.setting;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import cn.sky.ncm.api.OpenApis;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 插件设置「基本设置」分组，对应 resources/extensions/settings.yaml。
 *
 * @param cacheSeconds      公开接口缓存秒数，0 为不缓存
 * @param apiScope          通用接口（public / personal 路径）开放范围：common 常用只读接口（默认）/ all 全部
 * @param extraApis         在 common 之外额外开放的接口名
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BasicSetting(Integer cacheSeconds, String realIp, Boolean randomCnIp, String apiScope,
                           String extraApis) {

    public static final BasicSetting DEFAULT =
        new BasicSetting(300, "", false, "common", "");
    public static final BasicSetting CLOSED =
        new BasicSetting(0, "", false, "closed", "");

    static final Pattern API_NAME = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    /** 是否对外开放全部接口（apiScope = all）。默认只开放常用的只读接口，见 {@link OpenApis#COMMON}。 */
    public boolean allApisOpen() {
        return "all".equals(apiScope);
    }

    /** 站长额外开放的接口名，英文逗号、空格或换行分隔，最多 200 个。 */
    public Set<String> extraApiSet() {
        if (extraApis == null || extraApis.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(extraApis.split("[,，\\s]+"))
            .map(String::trim)
            .filter(s -> API_NAME.matcher(s).matches())
            .limit(200)
            .collect(Collectors.toUnmodifiableSet());
    }

    /** 这个接口能否通过 public / personal 路径调用。 */
    public boolean apiOpen(String name) {
        return !"closed".equals(apiScope) && (allApisOpen() || OpenApis.COMMON.contains(name) || extraApiSet().contains(name));
    }

    public int cacheTtl() {
        return cacheSeconds == null ? 300 : Math.max(0, cacheSeconds);
    }

    public boolean useRandomCnIp() {
        return Boolean.TRUE.equals(randomCnIp);
    }
}
