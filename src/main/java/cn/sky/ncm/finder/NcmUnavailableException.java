package cn.sky.ncm.finder;

/**
 * 网易云或插件暂时不可用：请求出错、超时，或网易云返回了 200 / 404 以外的状态。
 * 和“没有内容”（网易云正常返回但没有数据，或返回 404）区分开，页面据此显示“服务暂时不可用”而不是“没有找到”。
 */
public class NcmUnavailableException extends RuntimeException {

    private final String api;

    public NcmUnavailableException(String api, String message) {
        super(message);
        this.api = api;
    }

    public NcmUnavailableException(String api, String message, Throwable cause) {
        super(message, cause);
        this.api = api;
    }

    public String api() {
        return api;
    }
}
