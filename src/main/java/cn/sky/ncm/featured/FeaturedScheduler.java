package cn.sky.ncm.featured;

import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 歌单发布的定时任务：插件启动 3 分钟后第一次（归集旧设置并核对全部歌单），之后每天一次。插件停止时停掉。
 * <p>
 * 启动后延迟执行，是因为创建 Bean 时 NcmPlugin.start() 还没有注册 NcmFeaturedPlaylist 模型，设备身份也可能还没准备好。
 */
@Slf4j
@Component
public class FeaturedScheduler implements InitializingBean, DisposableBean {

    static final Duration FIRST = Duration.ofMinutes(3);
    static final Duration EVERY = Duration.ofDays(1);

    private final FeaturedService service;
    private volatile Disposable task;

    public FeaturedScheduler(FeaturedService service) {
        this.service = service;
    }

    @Override
    public void afterPropertiesSet() {
        task = Flux.interval(FIRST, EVERY)
            .onBackpressureDrop()
            .concatMap(tick -> service.checkAll()
                .doOnNext(n -> log.info("[ncm] 已重新核对 {} 个发布的歌单", n))
                .onErrorResume(e -> {
                    log.warn("[ncm] 定时核对歌单失败: {}", e.toString());
                    return Mono.empty();
                }))
            .subscribe();
    }

    @Override
    public void destroy() {
        Disposable t = task;
        task = null;
        if (t != null) {
            t.dispose();
        }
    }
}
