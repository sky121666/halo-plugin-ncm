package cn.sky.ncm.setting;

import static java.util.Objects.requireNonNullElse;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.web.server.ServerWebInputException;

/** 通用接口的开放范围与请求设置。 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(requiredProperties = {"apiScope", "extraApis", "cacheSeconds", "realIp", "randomCnIp"})
public record NcmApiAccessSettings(String apiScope, String extraApis, Integer cacheSeconds, String realIp,
                                   Boolean randomCnIp) {

    static NcmApiAccessSettings fromLegacy(BasicSetting legacy) {
        return new NcmApiAccessSettings(legacy.allApisOpen() ? "all" : "common",
            requireNonNullElse(legacy.extraApis(), ""), legacy.cacheTtl(), requireNonNullElse(legacy.realIp(), ""),
            legacy.useRandomCnIp());
    }

    BasicSetting toBasic() {
        return new BasicSetting(cacheSeconds, realIp, randomCnIp, apiScope, extraApis);
    }

    void validateForSave() {
        if (Stream.of(apiScope, extraApis, cacheSeconds, realIp, randomCnIp).anyMatch(Objects::isNull)) {
            throw new ServerWebInputException("接口开放设置不完整");
        }
        if (!Set.of("common", "all").contains(apiScope) || cacheSeconds < 0) {
            throw new ServerWebInputException("接口开放设置值无效");
        }
    }
}
