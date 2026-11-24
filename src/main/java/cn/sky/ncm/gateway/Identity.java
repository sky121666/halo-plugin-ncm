package cn.sky.ncm.gateway;

import java.util.Map;

/**
 * 本次请求用谁的网易云身份。
 *
 * @param kind anonymous（游客）/ site（站长）/ user（访客自己绑定的账号）
 */
public record Identity(String kind, Map<String, String> cookie) {

    public static Identity anonymous() {
        return new Identity("anonymous", Map.of());
    }
}
