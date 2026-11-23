package cn.sky.ncm.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/**
 * 对应上游 request 返回的 { status, body, cookie }。
 *
 * @param redirect 非空时网关返回 302 跳转到这个地址（上游 redirectUrl）
 */
public record NcmResponse(int status, JsonNode body, List<String> cookies, String redirect) {

    public NcmResponse(int status, JsonNode body, List<String> cookies) {
        this(status, body, cookies, null);
    }

    public static NcmResponse error(int status, String msg) {
        var body = JsonNodeFactory.instance.objectNode();
        body.put("code", status);
        body.put("msg", msg);
        return new NcmResponse(status, body, List.of());
    }

    /** 上游 Promise.reject({ status, body: { code, message } }) 的写法。 */
    public static NcmResponse message(int status, String message) {
        var body = JsonNodeFactory.instance.objectNode();
        body.put("code", status);
        body.put("message", message);
        return new NcmResponse(status, body, List.of());
    }

    public static NcmResponse ok(JsonNode body, List<String> cookies) {
        return new NcmResponse(200, body, cookies == null ? List.of() : cookies);
    }

    public static NcmResponse redirect(String url, List<String> cookies) {
        return new NcmResponse(302, JsonNodeFactory.instance.textNode(""), cookies == null ? List.of() : cookies, url);
    }

    public NcmResponse withBody(JsonNode newBody) {
        return new NcmResponse(status, newBody, cookies, redirect);
    }

    public boolean ok() {
        return status == 200;
    }

    /** body 不是对象时返回空对象，方便 path() 取值。 */
    public ObjectNode bodyObject() {
        return body instanceof ObjectNode on ? on : JsonNodeFactory.instance.objectNode();
    }
}
