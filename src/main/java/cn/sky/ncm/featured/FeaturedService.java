package cn.sky.ncm.featured;

import cn.sky.ncm.client.NcmResponse;
import cn.sky.ncm.finder.vo.PlaylistVo;
import cn.sky.ncm.finder.vo.SongVo;
import cn.sky.ncm.finder.vo.UserBriefVo;
import cn.sky.ncm.gateway.Identity;
import cn.sky.ncm.security.SecretStore;
import cn.sky.ncm.setting.SettingService;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * 歌单发布：增删改查、发布 / 下架、排序、核对、旧设置归集，以及 /music 首页和歌单详情页取数。
 * 定时核对由 {@link FeaturedScheduler} 负责，这里不启动任何定时任务。
 * <p>
 * 公开规则：只有已发布（spec.published）并且游客能公开访问的歌单才会出现在 /music。
 * 发布只看本次核对的结果：本次核对超时、限流或出错时，即使上一次是“可公开”，也只能保留草稿。
 */
@Slf4j
@Component
public class FeaturedService {

    static final String MARKER_CONFIG_MAP = "plugin-ncm-featured";
    static final String MARKER_KEY = "migratedLegacyIds";
    static final int MAX_HOME = 24;
    static final int PROBE_TRACKS = 10;
    static final int MAX_TITLE = 80;
    static final int MAX_DESCRIPTION = 500;
    static final Duration HOME_OK_TTL = Duration.ofMinutes(10);
    static final Duration HOME_ERROR_TTL = Duration.ofSeconds(30);
    static final Duration BETWEEN_CHECKS = Duration.ofSeconds(2);

    /** 一次核对的完整结果。guestSongs / siteSongs 为 null 表示没取到（或该身份看不到这个歌单）。 */
    public record CheckResult(FeaturedCheck.Access access, List<SongVo> guestSongs, List<SongVo> siteSongs,
                              NcmFeaturedPlaylist.Stats guest, NcmFeaturedPlaylist.Stats site, boolean siteBound) {
    }

    /** 抽样实测一首歌（游客身份）：full / trial / unavailable / pending。 */
    public record Probe(String songId, String result) {
    }

    public record Preview(String playlistId, CheckResult result, List<Probe> probes, NcmFeaturedPlaylist existing) {
    }

    public record HomePlaylists(List<PlaylistVo> playlists, boolean unavailable) {
    }

    private record HomePlaylist(PlaylistVo playlist, boolean unavailable) {
    }

    record Cached(FeaturedCheck.Access access, long expireAt) {
    }

    private final ReactiveExtensionClient client;
    private final FeaturedRemote remote;
    private final SettingService settings;
    private final Map<String, Cached> homeCache = new ConcurrentHashMap<>();
    private volatile Mono<Void> migrated;

    public FeaturedService(ReactiveExtensionClient client, FeaturedRemote remote, SettingService settings) {
        this.client = client;
        this.remote = remote;
        this.settings = settings;
    }

    // ---------- 查询 ----------

    static boolean alive(NcmFeaturedPlaylist p) {
        return p != null && p.getMetadata() != null && p.getMetadata().getDeletionTimestamp() == null
            && p.getSpec() != null && FeaturedInput.validId(p.getSpec().getPlaylistId());
    }

    static boolean published(NcmFeaturedPlaylist p) {
        return alive(p) && Boolean.TRUE.equals(p.getSpec().getPublished());
    }

    /** 已发布并且最近一次确认可公开（访客能看到）。 */
    static boolean visible(NcmFeaturedPlaylist p) {
        return published(p) && p.getStatus() != null && FeaturedCheck.OK.equals(p.getStatus().getState());
    }

    Mono<List<NcmFeaturedPlaylist>> all() {
        return client.list(NcmFeaturedPlaylist.class, FeaturedService::alive, FeaturedOrder.COMPARATOR).collectList();
    }

    /** 后台列表：先归集旧设置，再按顺序返回全部（草稿、已下架、失效的都在内）。 */
    public Mono<List<NcmFeaturedPlaylist>> list() {
        return ensureMigrated().then(Mono.defer(this::all));
    }

    public Flux<NcmFeaturedPlaylist> publishedPlaylists() {
        return list().flatMapIterable(rows -> rows).filter(FeaturedService::visible);
    }

    public Mono<NcmFeaturedPlaylist> publishedPlaylist(String playlistId) {
        return Mono.justOrEmpty(playlistId).filter(FeaturedInput::validId)
            .flatMap(id -> client.fetch(NcmFeaturedPlaylist.class,
                NcmFeaturedPlaylist.nameOf(FeaturedInput.trimZeros(id))))
            .filter(FeaturedService::visible);
    }

    public Mono<NcmFeaturedPlaylist> get(String name) {
        return client.fetch(NcmFeaturedPlaylist.class, name).filter(FeaturedService::alive)
            .switchIfEmpty(Mono.error(FeaturedService::notFound));
    }

    // ---------- 新建、编辑、发布、下架、删除、排序 ----------

    /**
     * 新建并核对一次（只核对这一次）。publish 为 true 且本次核对确认可公开时直接发布；
     * 否则保存为草稿，publish 为 true 时再返回 409 说明为什么不能发布（草稿已经保存）。
     */
    public Mono<NcmFeaturedPlaylist> create(String playlistId, String title, String description, boolean publish) {
        String raw = playlistId == null ? null : playlistId.strip();
        if (!FeaturedInput.validId(raw)) {
            return Mono.error(badRequest("歌单 ID 不正确"));
        }
        String id = FeaturedInput.trimZeros(raw);
        String name = NcmFeaturedPlaylist.nameOf(id);
        String t;
        String d;
        try {
            t = clean(title, MAX_TITLE, "标题");
            d = clean(description, MAX_DESCRIPTION, "简介");
        } catch (ResponseStatusException e) {
            return Mono.error(e);
        }
        return ensureMigrated()
            .then(client.fetch(NcmFeaturedPlaylist.class, name))
            .flatMap(existing -> Mono.<NcmFeaturedPlaylist>error(conflict(alive(existing)
                ? "这个歌单已经添加过了" : "这个歌单刚被删除，请稍后再添加")))
            .switchIfEmpty(Mono.defer(() -> Mono.zip(all(), inspect(id)).flatMap(tu -> {
                CheckResult r = tu.getT2();
                boolean ok = r.access().ok();
                NcmFeaturedPlaylist p = newRecord(id, FeaturedOrder.maxPriority(tu.getT1()) + FeaturedMigration.STEP, publish && ok);
                p.getSpec().setTitle(t);
                p.getSpec().setDescription(d);
                applyStatus(p, r, Instant.now());
                Mono<NcmFeaturedPlaylist> saved = client.create(p)
                    .onErrorResume(e -> !(e instanceof ResponseStatusException),
                        e -> client.fetch(NcmFeaturedPlaylist.class, name).hasElement()
                            .flatMap(exists -> Mono.<NcmFeaturedPlaylist>error(exists ? conflict("这个歌单已经添加过了") : e)));
                if (publish && !ok) {
                    return saved.then(Mono.<NcmFeaturedPlaylist>error(conflict("已保存为草稿，但还不能发布："
                        + publishBlocked(r.access().state(), r.access().message()))));
                }
                return saved;
            })))
            .doOnNext(p -> homeCache.remove(id));
    }

    /** 修改显示标题和简介（留空则用网易云的）。 */
    public Mono<NcmFeaturedPlaylist> updateText(String name, String title, String description) {
        String t;
        String d;
        try {
            t = clean(title, MAX_TITLE, "标题");
            d = clean(description, MAX_DESCRIPTION, "简介");
        } catch (ResponseStatusException e) {
            return Mono.error(e);
        }
        return updateSpec(name, s -> {
            s.setTitle(t);
            s.setDescription(d);
        });
    }

    /**
     * 发布前核对一次，只依据本次结果：确认可公开才发布；私密、已删除、超时、限流都拒绝（409），保持草稿。
     * 下架不需要核对。
     */
    public Mono<NcmFeaturedPlaylist> setPublished(String name, boolean publish) {
        if (!publish) {
            return updateSpec(name, s -> s.setPublished(false));
        }
        return get(name).flatMap(p -> inspect(p.getSpec().getPlaylistId()))
            .flatMap(r -> writeStatus(name, r).flatMap(saved -> r.access().ok()
                ? updateSpec(name, s -> s.setPublished(true))
                : Mono.<NcmFeaturedPlaylist>error(conflict(publishBlocked(r.access().state(), r.access().message())))));
    }

    static String publishBlocked(String state, String detail) {
        if (FeaturedCheck.PRIVATE.equals(state)) {
            return "这是私密歌单，或者访客没有权限访问，不能发布。请在网易云里把歌单设为公开后重新核对";
        }
        if (FeaturedCheck.MISSING.equals(state)) {
            return "歌单不存在或已被删除，不能发布";
        }
        return "这次没能确认歌单是否公开" + (detail == null ? "" : "（" + detail + "）") + "，已保留为草稿，请稍后再发布";
    }

    public Mono<Void> delete(String name) {
        return get(name).flatMap(p -> {
            homeCache.remove(p.getSpec().getPlaylistId());
            return client.delete(p);
        }).then();
    }

    /** 调整顺序：names 是新顺序下的全部歌单名字。和服务器上的列表不一致时（别处刚增删过）返回 409，让站长刷新。 */
    public Mono<List<NcmFeaturedPlaylist>> reorder(List<String> names) {
        return all().flatMap(list -> {
            List<String> current = list.stream().map(FeaturedOrder::name).toList();
            Map<String, Integer> next = FeaturedOrder.renumber(current, names);
            if (next == null) {
                return Mono.error(conflict("歌单列表已经变化，请刷新后再调整顺序"));
            }
            return Flux.fromIterable(list)
                .filter(p -> !Objects.equals(p.getSpec().getPriority(), next.get(FeaturedOrder.name(p))))
                .concatMap(p -> updateSpec(FeaturedOrder.name(p), s -> s.setPriority(next.get(FeaturedOrder.name(p)))))
                .then(Mono.defer(this::all));
        });
    }

    Mono<NcmFeaturedPlaylist> updateSpec(String name, Consumer<NcmFeaturedPlaylist.Spec> change) {
        return Mono.defer(() -> get(name).flatMap(p -> {
                change.accept(p.getSpec());
                return client.update(p);
            }))
            .retryWhen(Retry.max(3).filter(e -> !(e instanceof ResponseStatusException)))
            .doOnNext(p -> homeCache.remove(p.getSpec().getPlaylistId()));
    }

    // ---------- 核对 ----------

    /** 立即核对一个歌单，并把结果写进 status。 */
    public Mono<NcmFeaturedPlaylist> check(String name) {
        return get(name).flatMap(p -> inspect(p.getSpec().getPlaylistId()))
            .flatMap(r -> writeStatus(name, r));
    }

    /** 依次核对全部歌单（定时任务调用），先归集旧设置。单个失败不影响其他。返回核对成功的数量。 */
    public Mono<Integer> checkAll() {
        return ensureMigrated().then(Mono.defer(this::all))
            .flatMapMany(Flux::fromIterable)
            .concatMap(p -> check(FeaturedOrder.name(p))
                .onErrorResume(e -> {
                    log.debug("[ncm] 核对歌单 {} 失败: {}", FeaturedOrder.name(p), e.toString());
                    return Mono.empty();
                })
                .delayElement(BETWEEN_CHECKS))
            .count()
            .map(Long::intValue);
    }

    /** 用游客和站长两种身份取歌单信息和曲目版权信息。不会抛错：失败的部分记为“待确认”。 */
    Mono<CheckResult> inspect(String id) {
        Mono<Optional<NcmResponse>> guestDetail = opt(remote.detail(id, Identity.anonymous()));
        return remote.site().map(Optional::of).defaultIfEmpty(Optional.empty()).flatMap(site -> {
            Mono<Optional<NcmResponse>> siteDetail = site.isPresent()
                ? opt(remote.detail(id, site.get())) : Mono.just(Optional.<NcmResponse>empty());
            return Mono.zip(guestDetail, siteDetail).flatMap(t -> {
                FeaturedCheck.Access access = FeaturedCheck.access(t.getT1().orElse(null), t.getT2().orElse(null));
                int count = access.playlist() == null || access.playlist().getTrackCount() == null
                    ? 0 : access.playlist().getTrackCount();
                boolean siteSees = site.isPresent() && access.playlist() != null;
                Mono<Optional<List<SongVo>>> guestSongs = access.ok()
                    ? songs(remote.tracks(id, Identity.anonymous()), count) : Mono.just(Optional.<List<SongVo>>empty());
                Mono<Optional<List<SongVo>>> siteSongs = siteSees
                    ? songs(remote.tracks(id, site.get()), count) : Mono.just(Optional.<List<SongVo>>empty());
                return Mono.zip(guestSongs, siteSongs).map(u -> new CheckResult(access,
                    u.getT1().orElse(null), u.getT2().orElse(null),
                    access.ok() ? FeaturedCheck.stats(u.getT1().orElse(null), count) : null,
                    siteSees ? FeaturedCheck.stats(u.getT2().orElse(null), count) : null,
                    site.isPresent()));
            });
        });
    }

    static Mono<Optional<NcmResponse>> opt(Mono<NcmResponse> m) {
        return m.map(Optional::of)
            .defaultIfEmpty(Optional.empty())
            .onErrorResume(e -> {
                log.debug("[ncm] 歌单发布请求网易云失败: {}", e.toString());
                return Mono.just(Optional.empty());
            });
    }

    /** 曲目：业务失败、空壳返回都算没取到（统计里全部为待确认）。 */
    static Mono<Optional<List<SongVo>>> songs(Mono<NcmResponse> m, int trackCount) {
        return opt(m).map(r -> r.map(x -> FeaturedCheck.songsOf(x, trackCount)));
    }

    Mono<NcmFeaturedPlaylist> writeStatus(String name, CheckResult r) {
        return Mono.defer(() -> get(name).flatMap(p -> {
                applyStatus(p, r, Instant.now());
                return client.update(p);
            }))
            .retryWhen(Retry.max(3).filter(e -> !(e instanceof ResponseStatusException)))
            .doOnNext(p -> homeCache.remove(p.getSpec().getPlaylistId()));
    }

    /**
     * 把核对结果写进 status。核对失败（网络、限流等）时保留上一次确认的状态和统计，只记下错误，
     * 避免一次网络抖动就让已发布的歌单下线（首页还会用游客身份再确认）。发布不看这里，见 {@link #setPublished}。
     */
    static void applyStatus(NcmFeaturedPlaylist p, CheckResult r, Instant now) {
        NcmFeaturedPlaylist.Status old = p.getStatus();
        NcmFeaturedPlaylist.Status s = new NcmFeaturedPlaylist.Status();
        FeaturedCheck.Access a = r.access();
        if (FeaturedCheck.ERROR.equals(a.state())) {
            if (old != null) {
                s.setState(old.getState());
                s.setName(old.getName());
                s.setCreator(old.getCreator());
                s.setCoverUrl(old.getCoverUrl());
                s.setTrackCount(old.getTrackCount());
                s.setGuest(old.getGuest());
                s.setSite(old.getSite());
                s.setMissing(old.getMissing());
            }
            if (s.getState() == null || FeaturedCheck.UNCHECKED.equals(s.getState())) {
                s.setState(FeaturedCheck.ERROR);
                s.setMissing(false);
            }
        } else {
            s.setState(a.state());
            PlaylistVo pl = a.playlist();
            if (pl != null) {
                s.setName(pl.getName());
                s.setCreator(pl.getCreator() == null ? null : pl.getCreator().getNickname());
                s.setCoverUrl(pl.getCoverUrl());
                s.setTrackCount(pl.getTrackCount());
            } else if (old != null) {
                s.setName(old.getName());
                s.setCreator(old.getCreator());
                s.setCoverUrl(old.getCoverUrl());
                s.setTrackCount(old.getTrackCount());
            }
            s.setGuest(r.guest());
            s.setSite(r.site());
            s.setMissing(!a.ok());
        }
        s.setLastError(a.message());
        s.setCheckedAt(now.toString());
        p.setStatus(s);
    }

    // ---------- 预览、识别 ----------

    /** 预览一个歌单（不保存）；probe 为 true 时用游客身份实测前 10 首能不能播。 */
    public Mono<Preview> preview(String playlistId, boolean probe) {
        String raw = playlistId == null ? null : playlistId.strip();
        if (!FeaturedInput.validId(raw)) {
            return Mono.error(badRequest("歌单 ID 不正确"));
        }
        String id = FeaturedInput.trimZeros(raw);
        Mono<Optional<NcmFeaturedPlaylist>> existing = client.fetch(NcmFeaturedPlaylist.class,
                NcmFeaturedPlaylist.nameOf(id))
            .filter(FeaturedService::alive)
            .map(Optional::of)
            .defaultIfEmpty(Optional.empty())
            .onErrorReturn(Optional.empty());
        return Mono.zip(inspect(id), existing).flatMap(t -> {
            CheckResult r = t.getT1();
            NcmFeaturedPlaylist ex = t.getT2().orElse(null);
            if (!probe || r.guestSongs() == null || r.guestSongs().isEmpty()) {
                return Mono.just(new Preview(id, r, List.of(), ex));
            }
            return Flux.fromIterable(r.guestSongs()).take(PROBE_TRACKS)
                .flatMapSequential(s -> remote.probe(s.getId())
                    .map(su -> new Probe(s.getId(), FeaturedCheck.measured(su)))
                    .defaultIfEmpty(new Probe(s.getId(), "pending"))
                    .onErrorResume(e -> Mono.just(new Probe(s.getId(), "pending"))), 2)
                .collectList()
                .map(list -> new Preview(id, r, list, ex));
        });
    }

    /** 识别粘贴的内容；短链交给 {@link FeaturedRemote#expandShort}（只请求 163cn.tv 一次，校验跳转地址）。不会抛错。 */
    public Mono<FeaturedInput.Parsed> resolve(String input) {
        FeaturedInput.Parsed p = FeaturedInput.parse(input);
        if (p.kind() != FeaturedInput.Kind.SHORT) {
            return Mono.just(p);
        }
        return remote.expandShort(p.shortCode())
            .defaultIfEmpty(FeaturedInput.Parsed.error("short_failed", "暂时无法展开短链，请粘贴完整的歌单链接"))
            .onErrorResume(e -> Mono.just(FeaturedInput.Parsed.error("short_failed",
                "暂时无法展开短链，请稍后再试，或者粘贴完整的歌单链接")));
    }

    // ---------- /music 首页、歌单详情页 ----------

    /** 首页显示的歌单：已发布、并且游客能公开访问，顺序和后台一致。不会抛错。 */
    public Flux<PlaylistVo> forHome() {
        return homePlaylists(false).flatMapIterable(HomePlaylists::playlists);
    }

    /** 重试时跳过暂时故障的缓存，已核对的歌单继续使用缓存。 */
    public Mono<HomePlaylists> homePlaylists(boolean retry) {
        return ensureMigrated()
            .thenMany(Flux.defer(() -> all().flatMapMany(Flux::fromIterable)))
            .filter(FeaturedService::published)
            .take(MAX_HOME)
            .flatMapSequential(p -> homePlaylist(p, retry), 4)
            .collectList()
            .map(rows -> new HomePlaylists(rows.stream().map(HomePlaylist::playlist).filter(Objects::nonNull).toList(),
                rows.stream().anyMatch(HomePlaylist::unavailable)))
            .onErrorResume(e -> {
                log.warn("[ncm] 读取发布的歌单失败: {}", e.toString());
                return Mono.just(new HomePlaylists(List.of(), true));
            });
    }

    private Mono<HomePlaylist> homePlaylist(NcmFeaturedPlaylist p, boolean retry) {
        String id = p.getSpec().getPlaylistId();
        return guestAccess(id, retry).map(a -> {
            if (a.ok()) {
                return new HomePlaylist(overlay(p, a.playlist()), false);
            }
            // 网易云暂时出错：沿用最近一次核对结果，只有当时确认可公开的才显示
            boolean unavailable = FeaturedCheck.ERROR.equals(a.state());
            PlaylistVo fallback = unavailable && visible(p) ? overlay(p, fromStatus(id, p.getStatus())) : null;
            return new HomePlaylist(fallback, unavailable);
        }).onErrorReturn(new HomePlaylist(null, true));
    }

    /**
     * 歌单详情页用：这个歌单在歌单发布里已发布并且确认可公开时，套上站长填的标题和简介；
     * 草稿、已下架、私密、失效的不套（访客看到的就是网易云原样）。不会抛错。
     */
    public Mono<PlaylistVo> decorate(String playlistId, PlaylistVo base) {
        if (base == null || !FeaturedInput.validId(playlistId)) {
            return Mono.justOrEmpty(base);
        }
        return client.fetch(NcmFeaturedPlaylist.class, NcmFeaturedPlaylist.nameOf(FeaturedInput.trimZeros(playlistId)))
            .filter(FeaturedService::visible)
            .map(p -> overlay(p, base))
            .defaultIfEmpty(base)
            .onErrorReturn(base);
    }

    Mono<FeaturedCheck.Access> guestAccess(String id, boolean retry) {
        long now = System.currentTimeMillis();
        Cached c = homeCache.get(id);
        if (c != null && c.expireAt() > now && !(retry && FeaturedCheck.ERROR.equals(c.access().state()))) {
            return Mono.just(c.access());
        }
        return opt(remote.detail(id, Identity.anonymous()))
            .map(r -> FeaturedCheck.access(r.orElse(null), null))
            .doOnNext(a -> {
                if (homeCache.size() > 500) {
                    homeCache.clear();
                }
                long ttl = FeaturedCheck.ERROR.equals(a.state()) ? HOME_ERROR_TTL.toMillis() : HOME_OK_TTL.toMillis();
                homeCache.put(id, new Cached(a, System.currentTimeMillis() + ttl));
            });
    }

    /** 站长填的标题、简介优先；其余字段（包括曲目）保持网易云的。 */
    static PlaylistVo overlay(NcmFeaturedPlaylist p, PlaylistVo base) {
        NcmFeaturedPlaylist.Spec s = p.getSpec();
        return PlaylistVo.builder()
            .id(base.getId() != null ? base.getId() : s.getPlaylistId())
            .name(s.getTitle() != null && !s.getTitle().isBlank() ? s.getTitle() : base.getName())
            .coverUrl(base.getCoverUrl())
            .description(s.getDescription() != null && !s.getDescription().isBlank()
                ? s.getDescription() : base.getDescription())
            .tags(base.getTags() == null ? List.of() : base.getTags())
            .trackCount(base.getTrackCount())
            .playCount(base.getPlayCount())
            .creator(base.getCreator())
            .tracks(base.getTracks() == null ? List.of() : base.getTracks())
            .url(base.getUrl() != null ? base.getUrl() : "https://music.163.com/#/playlist?id=" + s.getPlaylistId())
            .build();
    }

    static PlaylistVo fromStatus(String id, NcmFeaturedPlaylist.Status st) {
        return PlaylistVo.builder()
            .id(id)
            .name(st.getName())
            .coverUrl(st.getCoverUrl())
            .tags(List.of())
            .trackCount(st.getTrackCount())
            .creator(st.getCreator() == null ? null : UserBriefVo.builder().nickname(st.getCreator()).build())
            .tracks(List.of())
            .url("https://music.163.com/#/playlist?id=" + id)
            .build();
    }

    // ---------- 旧设置归集 ----------

    /** 每个进程只成功归集一次；失败时下次再试。不会抛错。 */
    public Mono<Void> ensureMigrated() {
        Mono<Void> m = migrated;
        if (m == null) {
            m = Mono.defer(this::migrate)
                .doOnError(e -> {
                    log.warn("[ncm] 归集旧的推荐歌单设置失败，稍后重试: {}", e.toString());
                    migrated = null;
                })
                .onErrorResume(e -> Mono.empty())
                .cache();
            migrated = m;
        }
        return m;
    }

    Mono<Void> migrate() {
        return settings.legacyFeaturedIds().flatMap(ids -> ids.isEmpty() ? Mono.<Void>empty()
            : Mono.zip(readMarker(), client.list(NcmFeaturedPlaylist.class, x -> true, FeaturedOrder.COMPARATOR)
                    .collectList())
                .flatMap(t -> {
                    List<NcmFeaturedPlaylist> records = t.getT2();
                    Set<String> existingIds = records.stream()
                        .filter(x -> x.getSpec() != null && x.getSpec().getPlaylistId() != null)
                        .map(x -> x.getSpec().getPlaylistId())
                        .collect(Collectors.toSet());
                    int max = FeaturedOrder.maxPriority(records.stream().filter(FeaturedService::alive).toList());
                    FeaturedMigration.Plan plan = FeaturedMigration.plan(ids, t.getT1(), existingIds, max);
                    if (!plan.changed()) {
                        return Mono.<Void>empty();
                    }
                    return Flux.fromIterable(plan.create())
                        .concatMap(item -> client.create(newRecord(item.playlistId(), item.priority(), true))
                            .onErrorResume(e -> client.fetch(NcmFeaturedPlaylist.class,
                                    NcmFeaturedPlaylist.nameOf(item.playlistId()))
                                .hasElement()
                                .flatMap(exists -> exists ? Mono.<NcmFeaturedPlaylist>empty()
                                    : Mono.<NcmFeaturedPlaylist>error(e))))
                        .then(writeMarker(plan.marker()))
                        .doOnSuccess(v -> {
                            if (!plan.create().isEmpty()) {
                                log.info("[ncm] 已把旧设置里的 {} 个推荐歌单归集到「歌单发布」", plan.create().size());
                            }
                        });
                }));
    }

    Mono<Set<String>> readMarker() {
        return client.fetch(ConfigMap.class, MARKER_CONFIG_MAP)
            .flatMap(cm -> {
                if (!SecretStore.ownedConfigMap(cm)) {
                    return Mono.<Set<String>>error(new IllegalStateException(
                        "ConfigMap " + MARKER_CONFIG_MAP + " 不是本插件创建的"));
                }
                return Mono.just(parseMarker(cm.getData() == null ? null : cm.getData().get(MARKER_KEY)));
            })
            .defaultIfEmpty(Set.of());
    }

    static Set<String> parseMarker(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(","))
            .map(String::strip)
            .filter(FeaturedInput::validId)
            .map(FeaturedInput::trimZeros)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    Mono<Void> writeMarker(Set<String> marker) {
        String value = String.join(",", new TreeSet<>(marker));
        return Mono.defer(() -> client.fetch(ConfigMap.class, MARKER_CONFIG_MAP)
                .flatMap(cm -> {
                    if (!SecretStore.ownedConfigMap(cm)) {
                        return Mono.<ConfigMap>error(new IllegalStateException(
                            "ConfigMap " + MARKER_CONFIG_MAP + " 不是本插件创建的，已拒绝覆盖"));
                    }
                    Map<String, String> data = cm.getData() == null ? new HashMap<>() : new HashMap<>(cm.getData());
                    data.put(MARKER_KEY, value);
                    cm.setData(data);
                    return client.update(cm);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    ConfigMap cm = new ConfigMap();
                    Metadata m = new Metadata();
                    m.setName(MARKER_CONFIG_MAP);
                    m.setLabels(new HashMap<>(Map.of(SecretStore.LABEL_PLUGIN, SecretStore.PLUGIN_NAME)));
                    cm.setMetadata(m);
                    cm.setData(new HashMap<>(Map.of(MARKER_KEY, value)));
                    return client.create(cm);
                })))
            .retryWhen(Retry.max(2).filter(e -> !(e instanceof IllegalStateException)))
            .then();
    }

    // ---------- 工具 ----------

    static NcmFeaturedPlaylist newRecord(String playlistId, int priority, boolean published) {
        NcmFeaturedPlaylist p = new NcmFeaturedPlaylist();
        Metadata m = new Metadata();
        m.setName(NcmFeaturedPlaylist.nameOf(playlistId));
        m.setLabels(new HashMap<>(Map.of(SecretStore.LABEL_PLUGIN, SecretStore.PLUGIN_NAME)));
        p.setMetadata(m);
        NcmFeaturedPlaylist.Spec s = new NcmFeaturedPlaylist.Spec();
        s.setPlaylistId(playlistId);
        s.setPriority(priority);
        s.setPublished(published);
        s.setHideUnavailable(false);
        p.setSpec(s);
        NcmFeaturedPlaylist.Status st = new NcmFeaturedPlaylist.Status();
        st.setState(FeaturedCheck.UNCHECKED);
        st.setMissing(false);
        p.setStatus(st);
        return p;
    }

    /** 去掉首尾空白和控制字符（保留换行）；空串为 null（使用网易云的）；超长报 400。 */
    static String clean(String value, int max, String label) {
        if (value == null) {
            return null;
        }
        String v = value.replaceAll("[\\p{Cntrl}&&[^\\n]]", "").strip();
        if (v.isEmpty()) {
            return null;
        }
        if (v.codePointCount(0, v.length()) > max) {
            throw badRequest(label + "最多 " + max + " 个字");
        }
        return v;
    }

    static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "没有这个歌单，可能已经被删除，请刷新");
    }
}
