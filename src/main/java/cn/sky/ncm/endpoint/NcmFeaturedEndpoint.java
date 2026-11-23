package cn.sky.ncm.endpoint;

import static cn.sky.ncm.endpoint.EndpointSupport.jsonBody;
import static cn.sky.ncm.endpoint.EndpointSupport.op;
import static cn.sky.ncm.endpoint.EndpointSupport.returns;
import static cn.sky.ncm.endpoint.EndpointSupport.returnsNothing;
import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;

import cn.sky.ncm.client.Js;
import cn.sky.ncm.endpoint.dto.NcmFeaturedItem;
import cn.sky.ncm.endpoint.dto.NcmFeaturedList;
import cn.sky.ncm.endpoint.dto.NcmFeaturedPreview;
import cn.sky.ncm.endpoint.dto.NcmFeaturedReorderRequest;
import cn.sky.ncm.endpoint.dto.NcmFeaturedResolveRequest;
import cn.sky.ncm.endpoint.dto.NcmFeaturedResolveResult;
import cn.sky.ncm.endpoint.dto.NcmFeaturedSaveRequest;
import cn.sky.ncm.featured.FeaturedCheck;
import cn.sky.ncm.featured.FeaturedService;
import cn.sky.ncm.featured.NcmFeaturedPlaylist;
import cn.sky.ncm.finder.FeaturedBridge;
import cn.sky.ncm.finder.vo.SongVo;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springdoc.core.fn.builders.operation.Builder;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

/**
 * 后台「歌单发布」（需要「网易云音乐管理」权限，沿用 Halo 后台的角色授权），前缀 /apis/console.api.ncm.halo.run/v1alpha1：
 * <pre>
 * GET    featured-playlists                        列表，按 /music 的顺序 → { items }
 * POST   featured-playlists                        添加 { playlistId, title?, description?, publish? } → Item
 * GET    featured-playlists/-/preview?id=&amp;probe=  预览（probe=true 时游客身份抽样实测前 10 首）→ Preview
 * POST   featured-playlists/-/resolve              识别 { input } → { ok, playlistId?, error?, message?, existing? }
 * POST   featured-playlists/-/reorder              排序 { names }（全部记录名，按新顺序）→ { items }
 * PUT    featured-playlists/{name}                 修改 { title, description } → Item
 * DELETE featured-playlists/{name}                 删除 → 204
 * POST   featured-playlists/{name}/publish         发布（按本次核对结果，不能确认可公开时 409）→ Item
 * POST   featured-playlists/{name}/unpublish       下架 → Item
 * POST   featured-playlists/{name}/check           立即重新核对 → Item
 * </pre>
 * 除 GET / HEAD 外都必须带请求头 X-NCM-Request: 1（防跨站请求伪造：跨站表单、链接、图片带不了自定义请求头），
 * 缺失或不等于 1 时返回 403。这里用本功能自己的常量，不依赖网关。
 */
@Component
public class NcmFeaturedEndpoint implements CustomEndpoint {

    /** 防跨站请求伪造用的请求头。 */
    static final String REQUEST_HEADER = "X-NCM-Request";
    static final String TAG = "console.api.ncm.halo.run/v1alpha1/FeaturedPlaylist";
    static final String BASE = "featured-playlists";
    static final Pattern NAME = Pattern.compile("^" + NcmFeaturedPlaylist.NAME_PREFIX + "\\d{1,20}$");
    static final int PREVIEW_TRACKS = 50;
    static final int MAX_REORDER = 500;

    private final FeaturedService service;

    public NcmFeaturedEndpoint(FeaturedService service) {
        this.service = service;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return SpringdocRouteBuilder.route()
            .GET(BASE, r -> list(), b -> returns(
                op(b, "ListNcmFeaturedPlaylists", TAG, "发布到 /music 的歌单，按显示顺序"), NcmFeaturedList.class))
            .POST(BASE, r -> guarded(r, () -> create(r)), b -> returns(jsonBody(
                op(b, "CreateNcmFeaturedPlaylist", TAG,
                    "添加歌单并核对一次；默认草稿。publish=true 但本次核对不能确认可公开时仍保存为草稿，并返回 409 说明原因"),
                NcmFeaturedSaveRequest.class), NcmFeaturedItem.class))
            .GET(BASE + "/-/preview", this::preview, b -> returns(probeParam(idParam(
                op(b, "PreviewNcmFeaturedPlaylist", TAG,
                    "预览歌单：能否公开、前 50 首，以及游客和站长账号两种视角的可播放预估"))), NcmFeaturedPreview.class))
            .POST(BASE + "/-/resolve", r -> guarded(r, () -> resolve(r)), b -> returns(jsonBody(
                op(b, "ResolveNcmFeaturedPlaylist", TAG,
                    "识别网易云歌单链接、分享文案、163cn.tv 短链或歌单 ID；专辑、单曲等链接会提示目前只支持歌单"),
                NcmFeaturedResolveRequest.class), NcmFeaturedResolveResult.class))
            .POST(BASE + "/-/reorder", r -> guarded(r, () -> reorder(r)), b -> returns(jsonBody(
                op(b, "ReorderNcmFeaturedPlaylists", TAG, "按给出的完整顺序重新排序；和服务器上的列表不一致时返回 409"),
                NcmFeaturedReorderRequest.class), NcmFeaturedList.class))
            .PUT(BASE + "/{name}", r -> guarded(r, () -> update(r)), b -> returns(jsonBody(nameParam(
                op(b, "UpdateNcmFeaturedPlaylist", TAG, "修改显示标题和简介，留空表示使用网易云的")),
                NcmFeaturedSaveRequest.class), NcmFeaturedItem.class))
            .DELETE(BASE + "/{name}", r -> guarded(r, () -> service.delete(name(r))
                    .then(ServerResponse.noContent().build())),
                b -> returnsNothing(nameParam(
                    op(b, "DeleteNcmFeaturedPlaylist", TAG, "删除记录（不影响网易云上的歌单）"))))
            .POST(BASE + "/{name}/publish", r -> guarded(r, () -> item(service.setPublished(name(r), true))),
                b -> returns(nameParam(op(b, "PublishNcmFeaturedPlaylist", TAG,
                    "发布：先重新核对，只按本次结果决定；超时、限流、私密、失效都返回 409 并保持草稿")), NcmFeaturedItem.class))
            .POST(BASE + "/{name}/unpublish", r -> guarded(r, () -> item(service.setPublished(name(r), false))),
                b -> returns(nameParam(op(b, "UnpublishNcmFeaturedPlaylist", TAG, "下架")), NcmFeaturedItem.class))
            .POST(BASE + "/{name}/check", r -> guarded(r, () -> item(service.check(name(r)))),
                b -> returns(nameParam(op(b, "CheckNcmFeaturedPlaylist", TAG,
                    "立即重新核对：能否公开、两种视角的可播放预估")), NcmFeaturedItem.class))
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("console.api.ncm.halo.run/v1alpha1");
    }

    // ---------- 处理 ----------

    Mono<ServerResponse> list() {
        return service.list().flatMap(NcmFeaturedEndpoint::itemsResponse);
    }

    Mono<ServerResponse> create(ServerRequest r) {
        return r.bodyToMono(NcmFeaturedSaveRequest.class)
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少请求体")))
            .flatMap(b -> item(service.create(b.getPlaylistId(), b.getTitle(), b.getDescription(),
                Boolean.TRUE.equals(b.getPublish()))));
    }

    Mono<ServerResponse> update(ServerRequest r) {
        String name = name(r);
        return r.bodyToMono(NcmFeaturedSaveRequest.class)
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少请求体")))
            .flatMap(b -> item(service.updateText(name, b.getTitle(), b.getDescription())));
    }

    Mono<ServerResponse> reorder(ServerRequest r) {
        return r.bodyToMono(NcmFeaturedReorderRequest.class)
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少请求体")))
            .flatMap(b -> {
                List<String> names = b.getNames() == null ? List.of() : b.getNames();
                if (names.size() > MAX_REORDER || names.stream().anyMatch(n -> n == null || !NAME.matcher(n).matches())) {
                    return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "names 不正确"));
                }
                return service.reorder(names);
            })
            .flatMap(NcmFeaturedEndpoint::itemsResponse);
    }

    Mono<ServerResponse> resolve(ServerRequest r) {
        return r.bodyToMono(NcmFeaturedResolveRequest.class)
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少请求体")))
            .flatMap(b -> service.resolve(b.getInput()))
            .flatMap(p -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("ok", p.ok());
                if (!p.ok()) {
                    body.put("error", p.error());
                    body.put("message", p.message());
                    return json(body);
                }
                body.put("playlistId", p.playlistId());
                // 已经添加过时带上记录名：Service 没有按 ID 查找的方法，用列表查询
                return service.list().flatMap(items -> {
                    items.stream()
                        .filter(x -> x.getSpec() != null && p.playlistId().equals(x.getSpec().getPlaylistId()))
                        .findFirst()
                        .ifPresent(x -> body.put("existing", x.getMetadata().getName()));
                    return json(body);
                });
            });
    }

    Mono<ServerResponse> preview(ServerRequest r) {
        String id = r.queryParam("id").map(String::trim).orElse("");
        boolean probe = r.queryParam("probe").map("true"::equalsIgnoreCase).orElse(false);
        return service.preview(id, probe).flatMap(pv -> json(previewBody(pv, pv.existing())));
    }

    static Mono<ServerResponse> item(Mono<NcmFeaturedPlaylist> p) {
        return p.flatMap(x -> json(itemBody(x)));
    }

    static Mono<ServerResponse> itemsResponse(List<NcmFeaturedPlaylist> items) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items.stream().map(NcmFeaturedEndpoint::itemBody).toList());
        return json(body);
    }

    /** 自己序列化成字符串（Halo 2.27 默认用 Jackson 3 编码，插件用 Jackson 2）。值为 null 的字段省略。 */
    static Mono<ServerResponse> json(Object body) {
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
            .header("Cache-Control", "no-store")
            .bodyValue(Js.json(body));
    }

    // ---------- 防跨站请求伪造 ----------

    /** 写操作（GET / HEAD 以外）先检查请求头 X-NCM-Request 等于 1，缺失或不对时返回 403。 */
    static Mono<ServerResponse> guarded(ServerRequest r, Supplier<Mono<ServerResponse>> handler) {
        HttpMethod m = r.method();
        if (HttpMethod.GET.equals(m) || HttpMethod.HEAD.equals(m)) {
            return Mono.defer(handler);
        }
        String h = r.headers().firstHeader(REQUEST_HEADER);
        if (!"1".equals(h == null ? null : h.trim())) {
            return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                "缺少请求头 " + REQUEST_HEADER + ": 1（防止跨站请求伪造）"));
        }
        return Mono.defer(handler);
    }

    static String name(ServerRequest r) {
        String name = r.pathVariable("name");
        if (!NAME.matcher(name).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "记录名无效");
        }
        return name;
    }

    static Builder nameParam(Builder b) {
        return b.parameter(parameterBuilder().name("name").in(ParameterIn.PATH).required(true)
            .description("记录名，格式为 playlist-{网易云歌单 ID}").implementation(String.class));
    }

    static Builder idParam(Builder b) {
        return b.parameter(parameterBuilder().name("id").in(ParameterIn.QUERY).required(true)
            .description("网易云歌单 ID").implementation(String.class));
    }

    static Builder probeParam(Builder b) {
        return b.parameter(parameterBuilder().name("probe").in(ParameterIn.QUERY).required(false)
            .description("为 true 时用游客身份真实取一次前 10 首的音频地址（抽样实测）").implementation(Boolean.class));
    }

    // ---------- 转换 ----------

    /** 列表里的一项（字段见 NcmFeaturedItem）。 */
    static Map<String, Object> itemBody(NcmFeaturedPlaylist p) {
        NcmFeaturedPlaylist.Spec s = p.getSpec() == null ? new NcmFeaturedPlaylist.Spec() : p.getSpec();
        NcmFeaturedPlaylist.Status st = p.getStatus() == null ? new NcmFeaturedPlaylist.Status() : p.getStatus();
        String state = st.getState() == null ? FeaturedCheck.UNCHECKED : st.getState();
        boolean published = Boolean.TRUE.equals(s.getPublished());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", p.getMetadata().getName());
        m.put("playlistId", s.getPlaylistId());
        m.put("title", s.getTitle());
        m.put("description", s.getDescription());
        m.put("displayTitle", !blank(s.getTitle()) ? s.getTitle()
            : !blank(st.getName()) ? st.getName() : "歌单 " + s.getPlaylistId());
        m.put("published", published);
        m.put("priority", s.getPriority());
        m.put("state", state);
        m.put("visible", published && FeaturedCheck.OK.equals(state));
        m.put("checkedAt", st.getCheckedAt());
        m.put("lastError", st.getLastError());
        m.put("neteaseName", st.getName());
        m.put("creator", st.getCreator());
        m.put("coverUrl", !blank(s.getCoverUrl()) ? s.getCoverUrl() : st.getCoverUrl());
        m.put("trackCount", st.getTrackCount());
        m.put("guest", statsBody(st.getGuest()));
        m.put("site", statsBody(st.getSite()));
        var created = p.getMetadata().getCreationTimestamp();
        m.put("createdAt", created == null ? null : created.toString());
        return m;
    }

    static Map<String, Object> statsBody(NcmFeaturedPlaylist.Stats s) {
        if (s == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("full", zero(s.getFull()));
        m.put("trial", zero(s.getTrial()));
        m.put("unavailable", zero(s.getUnavailable()));
        m.put("pending", zero(s.getPending()));
        m.put("counted", zero(s.getCounted()));
        m.put("total", zero(s.getTotal()));
        m.put("truncated", Boolean.TRUE.equals(s.getTruncated()));
        return m;
    }

    /** 预览（字段见 NcmFeaturedPreview）。 */
    static Map<String, Object> previewBody(FeaturedService.Preview pv, NcmFeaturedPlaylist existing) {
        FeaturedService.CheckResult r = pv.result();
        FeaturedCheck.Access a = r.access();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("playlistId", pv.playlistId());
        m.put("state", a.state());
        m.put("message", a.message());
        if (a.playlist() != null) {
            m.put("name", a.playlist().getName());
            m.put("creator", a.playlist().getCreator() == null ? null : a.playlist().getCreator().getNickname());
            m.put("coverUrl", a.playlist().getCoverUrl());
            m.put("description", a.playlist().getDescription());
            m.put("trackCount", a.playlist().getTrackCount());
        }
        m.put("siteBound", r.siteBound());
        m.put("guest", statsBody(r.guest()));
        m.put("site", statsBody(r.site()));
        List<SongVo> songs = r.guestSongs() != null ? r.guestSongs() : r.siteSongs();
        Map<String, SongVo> siteById = new HashMap<>();
        if (r.siteSongs() != null) {
            r.siteSongs().forEach(s -> siteById.put(s.getId(), s));
        }
        // 游客身份抽样实测结果：歌曲 ID → full / trial / unavailable / pending
        List<FeaturedService.Probe> probes = pv.probes() == null ? List.of() : pv.probes();
        Map<String, String> measured = new HashMap<>();
        probes.forEach(x -> measured.put(x.songId(), x.result()));
        List<Map<String, Object>> tracks = new ArrayList<>();
        if (songs != null) {
            for (SongVo s : songs) {
                if (tracks.size() >= PREVIEW_TRACKS) {
                    break;
                }
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("id", s.getId());
                t.put("name", s.getName());
                t.put("artists", s.getArtistNames());
                t.put("album", s.getAlbum() == null ? null : s.getAlbum().getName());
                t.put("duration", s.getDurationText());
                t.put("coverUrl", s.getCoverUrl() == null ? null : FeaturedBridge.thumbnail(s.getCoverUrl(), 80));
                // 游客看不到这个歌单时（私密），逐首的游客预估没有意义，记为待确认
                t.put("guest", r.guestSongs() == null ? "pending" : FeaturedCheck.estimate(s));
                t.put("site", r.siteBound() && r.siteSongs() != null ? FeaturedCheck.estimate(siteById.get(s.getId()))
                    : null);
                t.put("measured", measured.get(s.getId()));
                t.put("playUrl", s.getPlayUrl());
                tracks.add(t);
            }
        }
        m.put("tracks", tracks);
        m.put("probed", !probes.isEmpty());
        m.put("existing", existing == null ? null : itemBody(existing));
        return m;
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    static int zero(Integer v) {
        return v == null ? 0 : v;
    }
}
