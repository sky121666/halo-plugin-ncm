package cn.sky.ncm.endpoint;

import cn.sky.ncm.security.NcmRequestSecurity;
import static cn.sky.ncm.endpoint.EndpointSupport.captchaBody;
import static cn.sky.ncm.endpoint.EndpointSupport.cellphoneBody;
import static cn.sky.ncm.endpoint.EndpointSupport.jsonBody;
import static cn.sky.ncm.endpoint.EndpointSupport.noContent;
import static cn.sky.ncm.endpoint.EndpointSupport.op;
import static cn.sky.ncm.endpoint.EndpointSupport.privateOk;
import static cn.sky.ncm.endpoint.EndpointSupport.qrConfirmKey;
import static cn.sky.ncm.endpoint.EndpointSupport.qrKey;
import static cn.sky.ncm.endpoint.EndpointSupport.qrKeyParam;
import static cn.sky.ncm.endpoint.EndpointSupport.returns;
import static cn.sky.ncm.endpoint.EndpointSupport.returnsNothing;
import static cn.sky.ncm.endpoint.EndpointSupport.username;
import static cn.sky.ncm.endpoint.EndpointSupport.writeHeader;
import static cn.sky.ncm.endpoint.EndpointSupport.writeUsername;

import cn.sky.ncm.account.AccountService;
import cn.sky.ncm.account.PhoneLoginService;
import cn.sky.ncm.account.QrLoginService;
import cn.sky.ncm.endpoint.dto.NcmAccountView;
import cn.sky.ncm.endpoint.dto.NcmCaptchaRequest;
import cn.sky.ncm.endpoint.dto.NcmCellphoneLoginRequest;
import cn.sky.ncm.endpoint.dto.NcmLoginResult;
import cn.sky.ncm.endpoint.dto.NcmQrConfirmRequest;
import cn.sky.ncm.endpoint.dto.NcmQrKey;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

/**
 * 个人中心：Halo 注册用户绑定自己的网易云账号。
 * <pre>
 * GET    /apis/uc.api.ncm.halo.run/v1alpha1/binding                 绑定状态（不含 Cookie）
 * POST   /apis/uc.api.ncm.halo.run/v1alpha1/binding/qr-key          获取二维码
 * GET    /apis/uc.api.ncm.halo.run/v1alpha1/binding/qr-check?key=   轮询扫码状态
 * POST   /apis/uc.api.ncm.halo.run/v1alpha1/binding/-/qr-confirm  确认并保存绑定
 * POST   /apis/uc.api.ncm.halo.run/v1alpha1/binding/captcha         发短信验证码
 * POST   /apis/uc.api.ncm.halo.run/v1alpha1/binding/cellphone       手机号登录并绑定
 * DELETE /apis/uc.api.ncm.halo.run/v1alpha1/binding                 解绑
 * </pre>
 */
@Component
public class NcmUcEndpoint implements CustomEndpoint {

    static final String TAG = "uc.api.ncm.halo.run/v1alpha1/Binding";

    private final AccountService accounts;
    private final QrLoginService qrLogin;
    private final PhoneLoginService phoneLogin;

    public NcmUcEndpoint(AccountService accounts, QrLoginService qrLogin, PhoneLoginService phoneLogin) {
        this.accounts = accounts;
        this.qrLogin = qrLogin;
        this.phoneLogin = phoneLogin;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return SpringdocRouteBuilder.route()
            .GET("binding", this::status, b -> returns(
                op(b, "GetMyNcmBinding", TAG, "获取个人绑定，未单独绑定时返回本人在后台绑定的站长账号"),
                NcmAccountView.class))
            .POST("binding/qr-key", r -> privateOk(writeUsername(r)
                    .flatMap(u -> qrLogin.createQr(AccountService.userAccountName(u), u)), NcmQrKey.class), b -> returns(
                writeHeader(op(b, "CreateMyNcmBindingQrKey", TAG, "获取扫码登录二维码")), NcmQrKey.class))
            .GET("binding/qr-check", this::check, b -> returns(qrKeyParam(
                op(b, "CheckMyNcmBindingQr", TAG, "查询扫码状态，803 表示等待确认绑定")), NcmLoginResult.class))
            .POST("binding/-/qr-confirm", this::confirm, b -> returns(writeHeader(jsonBody(
                op(b, "ConfirmMyNcmBindingQr", TAG, "确认并保存当前用户的扫码绑定"), NcmQrConfirmRequest.class)),
                NcmLoginResult.class))
            .POST("binding/captcha", r -> privateOk(writeUsername(r).flatMap(u -> captchaBody(r).flatMap(p -> phoneLogin.sendCaptcha(p, u, r))),
                NcmLoginResult.class), b -> returns(jsonBody(
                writeHeader(op(b, "SendMyNcmBindingCaptcha", TAG, "发送短信验证码")), NcmCaptchaRequest.class),
                NcmLoginResult.class))
            .POST("binding/cellphone", this::cellphone, b -> returns(writeHeader(jsonBody(
                op(b, "BindMyNcmAccountByCellphone", TAG, "用手机号（验证码或密码）登录网易云，并绑定为当前用户的账号"),
                NcmCellphoneLoginRequest.class)), NcmLoginResult.class))
            .DELETE("binding", r -> noContent(writeUsername(r).flatMap(u -> accounts.revoke(AccountService.userAccountName(u), u))),
                b -> returnsNothing(writeHeader(op(b, "DeleteMyNcmBinding", TAG, "解绑当前用户保存的网易云账号"))))
            .build()
            .filter(NcmRequestSecurity::privateEndpoint);
    }

    Mono<ServerResponse> status(ServerRequest r) {
        return privateOk(username().flatMap(accounts::binding)
            .map(AccountService::bindingView).defaultIfEmpty(AccountService.view(null)), NcmAccountView.class);
    }

    Mono<ServerResponse> check(ServerRequest r) {
        String key = qrKey(r);
        return privateOk(username().flatMap(u -> qrLogin.check(key, AccountService.userAccountName(u), u)),
            NcmLoginResult.class);
    }

    Mono<ServerResponse> confirm(ServerRequest r) {
        return privateOk(writeUsername(r).flatMap(u -> qrConfirmKey(r)
            .flatMap(key -> qrLogin.confirm(key, AccountService.userAccountName(u), u))), NcmLoginResult.class);
    }

    Mono<ServerResponse> cellphone(ServerRequest r) {
        return privateOk(writeUsername(r).flatMap(u -> cellphoneBody(r)
            .flatMap(p -> phoneLogin.login(p, AccountService.userAccountName(u), u, r))), NcmLoginResult.class);
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("uc.api.ncm.halo.run/v1alpha1");
    }
}
