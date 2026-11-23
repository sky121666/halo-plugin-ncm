package cn.sky.ncm.setting;

import static java.util.Objects.requireNonNullElse;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.web.server.ServerWebInputException;

/** 原生 basic 分组的八项设置。 */
@Schema(requiredProperties = {"publicIdentity", "siteMaxLevel", "apiScope", "extraApis", "allowClientCookie",
    "cacheSeconds", "realIp", "randomCnIp"})
public record NcmApiAccessSettings(String publicIdentity, String siteMaxLevel, String apiScope, String extraApis,
                                   Boolean allowClientCookie, Integer cacheSeconds, String realIp, Boolean randomCnIp) {

    static NcmApiAccessSettings fromLegacy(BasicSetting legacy) {
        return new NcmApiAccessSettings(legacy.publicUsesSite() ? "site" : "anonymous", legacy.siteLevelCap(),
            legacy.allApisOpen() ? "all" : "common", requireNonNullElse(legacy.extraApis(), ""),
            legacy.clientCookieAllowed(), legacy.cacheTtl(), requireNonNullElse(legacy.realIp(), ""),
            legacy.useRandomCnIp());
    }

    BasicSetting toBasic() {
        return new BasicSetting(publicIdentity, "none", allowClientCookie, cacheSeconds, realIp, randomCnIp,
            apiScope, extraApis, siteMaxLevel);
    }

    void validateForSave() {
        if (Stream.of(publicIdentity, siteMaxLevel, apiScope, extraApis, allowClientCookie, cacheSeconds, realIp,
                randomCnIp).anyMatch(Objects::isNull)) {
            throw new ServerWebInputException("接口开放设置不完整");
        }
        if (!Set.of("site", "anonymous").contains(publicIdentity) || !Set.of("standard", "exhigh").contains(siteMaxLevel)
            || !Set.of("common", "all").contains(apiScope) || cacheSeconds < 0) {
            throw new ServerWebInputException("接口开放设置值无效");
        }
    }
}
