package cn.sky.ncm.api;

import java.util.List;
import java.util.Map;

/**
 * 一个接口的声明式描述，来自 resources/ncm-apis.json（由 tools/convert.py 从上游 module/*.js 生成）。
 *
 * @param tier       public / personal / write / auth / admin
 * @param status     ready（描述可直接执行）/ custom（Java 手写）/ manual（待手写）
 * @param pre        调用前对 query 的赋值，按顺序执行，例如 [["t", "query.t == 1 ? 'sub' : 'unsub'"]]
 * @param post       返回值处理：renameAvatarImgId（把 avatarImgId_str 改成 avatarImgIdStr）
 */
public record ApiDef(String name, String tier, String status, String uri, String crypto,
                     String checkToken, Map<String, String> fields, List<String[]> pre, String post) {

    public boolean executable() {
        return "ready".equals(status) || "custom".equals(status);
    }
}
