package cn.sky.ncm.endpoint;

import static cn.sky.ncm.endpoint.EndpointSupport.apiName;
import static cn.sky.ncm.endpoint.EndpointSupport.apiNameParam;
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
import static cn.sky.ncm.endpoint.EndpointSupport.returnsAny;
import static cn.sky.ncm.endpoint.EndpointSupport.returnsNothing;
import static cn.sky.ncm.endpoint.EndpointSupport.username;
import static cn.sky.ncm.endpoint.EndpointSupport.writeHeader;
import static cn.sky.ncm.endpoint.EndpointSupport.writeUsername;

import cn.sky.ncm.account.AccountService;
import cn.sky.ncm.account.PhoneLoginService;
import cn.sky.ncm.account.QrLoginService;
import cn.sky.ncm.bootstrap.NcmBootstrap;
import cn.sky.ncm.endpoint.dto.NcmAccountView;
import cn.sky.ncm.endpoint.dto.NcmCaptchaRequest;
import cn.sky.ncm.endpoint.dto.NcmCellphoneLoginRequest;
import cn.sky.ncm.endpoint.dto.NcmDeviceStatus;
import cn.sky.ncm.endpoint.dto.NcmGuestStatus;
import cn.sky.ncm.endpoint.dto.NcmLoginResult;
import cn.sky.ncm.endpoint.dto.NcmQrConfirmRequest;
import cn.sky.ncm.endpoint.dto.NcmQrKey;
import cn.sky.ncm.gateway.NcmGateway;
import java.time.Instant;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

/**
 * 后台。站长与游客摘要、维护详情、只读调试分别授权。
 * <pre>
 * GET      /apis/console.api.ncm.halo.run/v1alpha1/site-account                站长账号状态
 * POST     /apis/console.api.ncm.halo.run/v1alpha1/site-account/qr-key         获取二维码
 * GET      /apis/console.api.ncm.halo.run/v1alpha1/site-account/qr-check?key=  查询扫码状态
 * POST     /apis/console.api.ncm.halo.run/v1alpha1/site-account/-/qr-confirm  确认并保存绑定
 * POST     /apis/console.api.ncm.halo.run/v1alpha1/site-account/captcha        发短信验证码
 * POST     /apis/console.api.ncm.halo.run/v1alpha1/site-account/cellphone      手机号登录并保存为站长账号
 * DELETE   /apis/console.api.ncm.halo.run/v1alpha1/site-account                解绑
 * GET      /apis/console.api.ncm.halo.run/v1alpha1/calls/{name}                用游客身份调试常用只读接口
 * GET      /apis/console.api.ncm.halo.run/v1alpha1/guest-status/-              游客状态摘要
 * GET      /apis/console.api.ncm.halo.run/v1alpha1/device                      设备状态
 * POST     /apis/console.api.ncm.halo.run/v1alpha1/device/refresh              重新获取 xeapi 公钥并注册游客
 * POST     /apis/console.api.ncm.halo.run/v1alpha1/device/reset                换一个全新的设备身份
 * </pre>
 */
@Component
public class NcmConsoleEndpoint implements CustomEndpoint {

    static final String TAG_ACCOUNT = "console.api.ncm.halo.run/v1alpha1/SiteAccount";
    static final String TAG_GUEST = "console.api.ncm.halo.run/v1alpha1/GuestStatus";
    static final String TAG_DEVICE = "console.api.ncm.halo.run/v1alpha1/Device";
    static final String TAG_DEBUG = "console.api.ncm.halo.run/v1alpha1/Debug";

    private final AccountService accounts;
    private final QrLoginService qrLogin;
    private final NcmGateway gateway;
    private final NcmBootstrap bootstrap;
    private final PhoneLoginService phoneLogin;

    public NcmConsoleEndpoint(AccountService accounts, QrLoginService qrLogin, NcmGateway gateway,
                              NcmBootstrap bootstrap, PhoneLoginService phoneLogin) {
        this.accounts = accounts;
        this.qrLogin = qrLogin;
        this.gateway = gateway;
        this.bootstrap = bootstrap;
        this.phoneLogin = phoneLogin;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return SpringdocRouteBuilder.route()
            // ---------- 站长账号 ----------
            .GET("site-account", this::siteAccount, b -> returns(
                op(b, "GetNcmSiteAccount", TAG_ACCOUNT, "获取站长网易云账号（不含 Cookie）"), NcmAccountView.class))
            .POST("site-account/qr-key", r -> privateOk(writeUsername(r)
                    .flatMap(u -> qrLogin.createQr(AccountService.SITE, u)), NcmQrKey.class), b -> returns(
                writeHeader(op(b, "CreateNcmSiteAccountQrKey", TAG_ACCOUNT, "获取扫码登录二维码")), NcmQrKey.class))
            .GET("site-account/qr-check", this::check, b -> returns(qrKeyParam(
                op(b, "CheckNcmSiteAccountQr", TAG_ACCOUNT, "查询扫码状态，803 表示等待确认绑定")),
                NcmLoginResult.class))
            .POST("site-account/-/qr-confirm", this::confirm, b -> returns(writeHeader(jsonBody(
                op(b, "ConfirmNcmSiteAccountQr", TAG_ACCOUNT, "确认并保存站长账号的扫码绑定"), NcmQrConfirmRequest.class)),
                NcmLoginResult.class))
            .POST("site-account/captcha", r -> privateOk(writeUsername(r).then(captchaBody(r))
                    .flatMap(phoneLogin::sendCaptcha), NcmLoginResult.class),
                b -> returns(writeHeader(jsonBody(op(b, "SendNcmSiteAccountCaptcha", TAG_ACCOUNT, "发送短信验证码"),
                    NcmCaptchaRequest.class)), NcmLoginResult.class))
            .POST("site-account/cellphone", this::cellphone, b -> returns(writeHeader(jsonBody(
                op(b, "BindNcmSiteAccountByCellphone", TAG_ACCOUNT, "用手机号（验证码或密码）登录网易云，并保存为站长账号"),
                NcmCellphoneLoginRequest.class)), NcmLoginResult.class))
            .DELETE("site-account", r -> noContent(writeUsername(r).then(Mono.defer(() -> accounts.delete(AccountService.SITE)))),
                b -> returnsNothing(writeHeader(op(b, "DeleteNcmSiteAccount", TAG_ACCOUNT, "解绑站长网易云账号"))))
            // ---------- 设备 ----------
            .GET("guest-status/-", this::guestStatus, b -> returns(
                op(b, "GetNcmGuestStatus", TAG_GUEST, "读取游客会话摘要"), NcmGuestStatus.class))
            .GET("device", r -> privateOk(Mono.fromSupplier(bootstrap::status), NcmDeviceStatus.class),
                b -> returns(op(b, "GetNcmDeviceStatus", TAG_DEVICE, "获取设备身份与运行状态"), NcmDeviceStatus.class))
            .POST("device/refresh", r -> privateOk(writeUsername(r).then(Mono.defer(bootstrap::refresh)), NcmDeviceStatus.class),
                b -> returns(writeHeader(op(b, "RefreshNcmDevice", TAG_DEVICE, "刷新游客设备")), NcmDeviceStatus.class))
            .POST("device/reset", r -> privateOk(writeUsername(r).then(Mono.defer(bootstrap::reset)), NcmDeviceStatus.class),
                b -> returns(writeHeader(op(b, "ResetNcmDevice", TAG_DEVICE, "重新生成游客设备")), NcmDeviceStatus.class))
            // ---------- 调试 ----------
            .GET("calls/{name}", this::call, b -> returnsAny(apiNameParam(
                op(b, "DebugCallNcmApi", TAG_DEBUG, "使用游客身份调试常用只读接口"))))
            .build();
    }

    Mono<ServerResponse> guestStatus(ServerRequest r) {
        return privateOk(Mono.fromSupplier(() -> {
            var status = bootstrap.status();
            String state = status.path("anonymousReady").asBoolean() ? "registered"
                : status.path("lastError").asText("").isBlank() && status.path("storeError").asText("").isBlank()
                    ? "pending" : "failed";
            String attempt = status.path("lastAttempt").asText(Instant.EPOCH.toString());
            return new NcmGuestStatus(state, Instant.EPOCH.toString().equals(attempt) ? null : attempt);
        }), NcmGuestStatus.class);
    }

    Mono<ServerResponse> siteAccount(ServerRequest r) {
        return privateOk(accounts.find(AccountService.SITE)
            .flatMap(accounts::bindingView)
            .defaultIfEmpty(AccountService.view(null)), NcmAccountView.class);
    }

    Mono<ServerResponse> check(ServerRequest r) {
        String key = qrKey(r);
        return privateOk(username().flatMap(owner -> qrLogin.check(key, AccountService.SITE, owner)), NcmLoginResult.class);
    }

    Mono<ServerResponse> confirm(ServerRequest r) {
        return privateOk(writeUsername(r).flatMap(owner -> qrConfirmKey(r)
            .flatMap(key -> qrLogin.confirm(key, AccountService.SITE, owner))), NcmLoginResult.class);
    }

    Mono<ServerResponse> cellphone(ServerRequest r) {
        return privateOk(writeUsername(r).flatMap(owner -> cellphoneBody(r)
            .flatMap(p -> phoneLogin.login(p, AccountService.SITE, owner))), NcmLoginResult.class);
    }

    Mono<ServerResponse> call(ServerRequest r) {
        apiName(r);
        return username().flatMap(operator -> gateway.debug(r, operator));
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("console.api.ncm.halo.run/v1alpha1");
    }
}
