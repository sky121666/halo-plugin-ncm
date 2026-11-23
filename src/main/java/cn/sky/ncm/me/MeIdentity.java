package cn.sky.ncm.me;

import java.util.Map;

/**
 * 个人音乐空间一次请求所用的网易云身份。
 *
 * @param username       Halo 用户名
 * @param cookie         本人绑定账号的网易云 Cookie（只在服务端使用，不会返回给浏览器）
 * @param uid            网易云 UID；绑定时没记下来的旧数据为 null，用到时再通过 user_account 查询
 * @param viaSiteAccount true 表示本人没有单独绑定，用的是他在后台绑定的站长账号
 */
public record MeIdentity(String username, Map<String, String> cookie, String uid, boolean viaSiteAccount) {

    public MeIdentity withUid(String newUid) {
        return new MeIdentity(username, cookie, newUid, viaSiteAccount);
    }

    @Override
    public String toString() {
        // 不把 Cookie 打进日志
        return "MeIdentity[username=" + username + ", uid=" + uid + ", viaSiteAccount=" + viaSiteAccount + "]";
    }
}
