package cn.sky.ncm.account;

import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * 绑定的网易云账号。
 * <ul>
 *   <li>name = "site"：站长账号（后台绑定）</li>
 *   <li>name = "user-{用户名}"：Halo 注册用户自己的账号（个人中心绑定）</li>
 * </ul>
 * 网易云 Cookie 存在 Halo Secret「ncm-account-{name}」里，这里只记 Secret 的名字。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "ncm.halo.run", version = "v1alpha1", kind = "NcmAccount",
    plural = "ncmaccounts", singular = "ncmaccount")
public class NcmAccount extends AbstractExtension {

    private Spec spec;

    private Status status;

    @Data
    public static class Spec {
        /** Halo 用户名；站长账号为绑定它的管理员用户名（旧数据可能是 "__site__"）。 */
        private String owner;

        /** 保存 Cookie 的 Halo Secret 名称。 */
        private String secretName;

        /**
         * 旧版本直接保存在这里的 Cookie（明文或 enc:v1 加密）。
         * 只用于升级迁移：第一次读取时会转存到 Secret 并清空。
         *
         * @deprecated 改用 {@link #secretName}
         */
        @Deprecated
        private String cookie;
    }

    @Data
    public static class Status {
        private Long userId;
        private String nickname;
        private String avatarUrl;
        private Integer vipType;
        private String boundAt;
    }
}
