package cn.sky.ncm.api.more;

import static cn.sky.ncm.api.more.Support.blank;
import static cn.sky.ncm.api.more.Support.map;
import static cn.sky.ncm.api.more.Support.or;

import cn.sky.ncm.api.ApiGroup;
import cn.sky.ncm.api.UploadFile;
import cn.sky.ncm.client.Js;
import cn.sky.ncm.client.NcmClient;
import cn.sky.ncm.client.NcmHttp;
import cn.sky.ncm.client.NcmResponse;
import cn.sky.ncm.client.RequestOptions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 上传类接口：头像、歌单封面、云盘、声音（播客）。
 * 文件用 multipart/form-data 上传，字段名与上游一致：songFile、imgFile。
 */
@Component
public class UploadApis implements ApiGroup {

    static final String CLOUD_BUCKET = "jd-musicrep-privatecloud-audio-public";
    static final int VOICE_BLOCK = 10 * 1024 * 1024;
    static final Pattern UPLOAD_ID = Pattern.compile("<UploadId>([^<]+)</UploadId>");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final NcmClient client;
    private final NcmHttp http;

    public UploadApis(NcmClient client, NcmHttp http) {
        this.client = client;
        this.http = http;
    }

    WebClient web() {
        return http.current();
    }

    @Override
    public Set<String> names() {
        return Set.of("avatar_upload", "playlist_cover_update", "cloud", "cloud_import", "cloud_upload_token",
            "cloud_upload_complete", "voice_upload");
    }

    @Override
    public Mono<NcmResponse> handle(String name, Map<String, String> q, Map<String, UploadFile> files,
                                    RequestOptions opt) {
        return switch (name) {
            case "avatar_upload" -> withImage(q, files, opt, (info, o) ->
                client.request("/api/user/avatar/upload/v1", map("imgid", info.path("imgId").asText()), o.withCrypto("")));
            case "playlist_cover_update" -> withImage(q, files, opt, (info, o) ->
                client.request("/api/playlist/cover/update",
                    map("id", q.get("id"), "coverImgId", info.path("imgId").asText()), o.withCrypto("weapi")));
            case "cloud" -> cloud(q, files.get("songFile"), opt);
            case "cloud_import" -> cloudImport(q, opt);
            case "cloud_upload_token" -> cloudUploadToken(q, opt);
            case "cloud_upload_complete" -> cloudUploadComplete(q, opt);
            case "voice_upload" -> voiceUpload(q, files, opt);
            default -> Mono.just(NcmResponse.error(501, "接口 " + name + " 尚未移植"));
        };
    }

    interface AfterImage {
        Mono<NcmResponse> apply(ObjectNode uploadInfo, RequestOptions opt);
    }

    /** avatar_upload / playlist_cover_update：先传图，再调用接口，返回 { ...uploadInfo, ...res.body }。 */
    Mono<NcmResponse> withImage(Map<String, String> q, Map<String, UploadFile> files, RequestOptions opt,
                                AfterImage then) {
        UploadFile img = files.get("imgFile");
        if (img == null) {
            return Mono.just(NcmResponse.error(400, "imgFile is required"));
        }
        return uploadImage(img, opt).flatMap(info -> then.apply(info, opt).map(res -> {
            if (!res.ok()) {
                return res;
            }
            ObjectNode data = info.deepCopy();
            data.setAll(res.bodyObject());
            ObjectNode b = JSON.objectNode();
            b.put("code", 200);
            b.set("data", data);
            return NcmResponse.ok(b, List.of());
        })).onErrorResume(UploadError.class, e -> Mono.just(e.response));
    }

    /** plugins/upload.js：上传图片到 NOS，返回 { url_pre, imgId }。 */
    Mono<ObjectNode> uploadImage(UploadFile img, RequestOptions opt) {
        Map<String, Object> data = map(
            "bucket", "yyimgs",
            "ext", "jpg",
            "filename", img.name(),
            "local", false,
            "nos_product", 0L,
            "return_body", "{\"code\":200,\"size\":\"$(ObjectSize)\"}",
            "type", "other");
        return client.request("/api/nos/token/alloc", data, opt.withCrypto("weapi")).flatMap(res -> {
            JsonNode result = res.bodyObject().path("result");
            if (!res.ok() || result.path("objectKey").asText("").isEmpty()) {
                return Mono.error(new UploadError(fail(500, "获取上传token失败", res.body())));
            }
            String objectKey = result.path("objectKey").asText();
            return web().post()
                .uri(URI.create("https://nosup-hz1.127.net/yyimgs/" + objectKey + "?offset=0&complete=true&version=1.0"))
                .header("x-nos-token", result.path("token").asText())
                .header(HttpHeaders.CONTENT_TYPE, or(img.mimetype(), "image/jpeg"))
                .bodyValue(img.data())
                .retrieve().toBodilessEntity()
                .timeout(Duration.ofMinutes(2))
                .onErrorMap(e -> new UploadError(NcmResponse.error(500, "图片上传失败: " + e.getMessage())))
                .map(x -> {
                    ObjectNode info = JSON.objectNode();
                    info.put("url_pre", "https://p1.music.126.net/" + objectKey);
                    info.set("imgId", result.path("docId"));
                    return info;
                });
        });
    }

    /** module/cloud.js：上传歌曲到云盘。 */
    Mono<NcmResponse> cloud(Map<String, String> q, UploadFile song, RequestOptions opt) {
        if (song == null) {
            return Mono.just(NcmResponse.error(500, "请上传音乐文件"));
        }
        String ext = song.extension();
        String filename = UploadFile.sanitize(song.name());
        String md5 = song.md5();
        long size = song.size();
        RequestOptions o = opt.withCrypto("");
        return client.request("/api/cloud/upload/check", map(
            "bitrate", "999000", "ext", "", "length", size, "md5", md5, "songId", "0", "version", 1L), o)
            .flatMap(check -> {
                if (!check.ok()) {
                    return Mono.just(check);
                }
                Map<String, String> meta = Id3.read(song.data());
                String songName = or(q.get("song"), meta.getOrDefault("title", ""));
                String album = or(q.get("album"), meta.getOrDefault("album", ""));
                String artist = or(q.get("artist"), meta.getOrDefault("artist", ""));
                return client.request("/api/nos/token/alloc", map(
                    "bucket", "", "ext", ext, "filename", filename, "local", false, "nos_product", 3L,
                    "type", "audio", "md5", md5), o).flatMap(token -> {
                        String resourceId = token.bodyObject().path("result").path("resourceId").asText("");
                        if (!token.ok() || resourceId.isEmpty()) {
                            return Mono.just(fail(500, "获取上传token失败", token.body()));
                        }
                        Mono<Void> upload = check.bodyObject().path("needUpload").asBoolean(false)
                            ? uploadSong(song, md5, ext, filename, opt) : Mono.empty();
                        return upload.then(Mono.defer(() -> client.request("/api/upload/cloud/info/v2", map(
                            "md5", md5,
                            "songid", check.bodyObject().get("songId"),
                            "filename", song.name(),
                            "song", or(songName, filename),
                            "album", or(album, "未知专辑"),
                            "artist", or(artist, "未知艺术家"),
                            "bitrate", "999000",
                            "resourceId", resourceId), o)))
                            .flatMap(info -> publish(info, o, pub -> {
                                ObjectNode b = check.bodyObject().deepCopy();
                                b.setAll(pub.bodyObject());
                                return new NcmResponse(200, b, check.cookies());
                            }));
                    });
            })
            .onErrorResume(UploadError.class, e -> Mono.just(e.response));
    }

    /** plugins/songUpload.js：申请云盘桶的上传 token，查询上传节点，上传文件。 */
    Mono<Void> uploadSong(UploadFile song, String md5, String ext, String filename, RequestOptions opt) {
        return cloudToken(md5, ext, filename, opt).flatMap(t -> lbs().flatMap(server -> web().post()
            .uri(URI.create(server + "/" + CLOUD_BUCKET + "/" + t.objectKey().replace("/", "%2F")
                + "?offset=0&complete=true&version=1.0"))
            .header("x-nos-token", t.token())
            .header("Content-MD5", md5)
            .header(HttpHeaders.CONTENT_TYPE, or(song.mimetype(), "audio/mpeg"))
            .bodyValue(song.data())
            .retrieve().toBodilessEntity()
            .timeout(Duration.ofMinutes(5))
            .onErrorMap(e -> !(e instanceof UploadError),
                e -> new UploadError(NcmResponse.error(500, "文件上传失败: " + e.getMessage())))
            .then()));
    }

    record NosToken(String token, String objectKey, String resourceId, JsonNode raw) {
    }

    Mono<NosToken> cloudToken(String md5, String ext, String filename, RequestOptions opt) {
        return client.request("/api/nos/token/alloc", map(
            "bucket", CLOUD_BUCKET, "ext", ext, "filename", filename, "local", false, "nos_product", 3L,
            "type", "audio", "md5", md5), opt.withCrypto("weapi")).flatMap(res -> {
                JsonNode r = res.bodyObject().path("result");
                if (!res.ok() || r.path("objectKey").asText("").isEmpty()) {
                    return Mono.error(new UploadError(fail(500, "获取上传token失败", res.body())));
                }
                return Mono.just(new NosToken(r.path("token").asText(), r.path("objectKey").asText(),
                    r.path("resourceId").asText(), res.body()));
            });
    }

    /** 查询云盘桶的上传节点（wanproxy LBS）。 */
    Mono<String> lbs() {
        return web().get().uri(URI.create("https://wanproxy.127.net/lbs?version=1.0&bucketname=" + CLOUD_BUCKET))
            .retrieve().bodyToMono(String.class)
            .timeout(Duration.ofSeconds(10))
            .onErrorMap(e -> new UploadError(fail(500, "获取上传服务器地址失败", JSON.textNode(String.valueOf(e.getMessage())))))
            .flatMap(text -> {
                JsonNode n;
                try {
                    n = MAPPER.readTree(text);
                } catch (Exception e) {
                    n = JSON.textNode(text);
                }
                String server = n.path("upload").path(0).asText("");
                return server.isEmpty()
                    ? Mono.error(new UploadError(fail(500, "获取上传服务器地址无效", n)))
                    : Mono.just(server);
            });
    }

    /** cloud_upload_complete 和 cloud 的最后两步：info/v2 → pub/v2。 */
    Mono<NcmResponse> publish(NcmResponse info, RequestOptions o, java.util.function.Function<NcmResponse, NcmResponse> done) {
        int code = info.bodyObject().path("code").asInt();
        if (code != 200) {
            ObjectNode b = JSON.objectNode();
            b.put("code", code == 0 ? 500 : code);
            b.put("msg", or(info.bodyObject().path("msg").asText(""), "上传云盘信息失败"));
            b.set("detail", info.body());
            return Mono.just(new NcmResponse(info.status() == 0 ? 500 : info.status(), b, info.cookies()));
        }
        return client.request("/api/cloud/pub/v2", map("songid", info.bodyObject().get("songId")), o)
            .map(pub -> pub.ok() ? done.apply(pub) : pub);
    }

    /** module/cloud_import.js：按 md5 秒传导入云盘（不需要上传文件）。 */
    Mono<NcmResponse> cloudImport(Map<String, String> q, RequestOptions opt) {
        Object id = or(q.get("id"), (Object) (-2L));
        String artist = or(q.get("artist"), "未知");
        String album = or(q.get("album"), "未知");
        RequestOptions o = opt.withCrypto("");
        String songs = Js.json(List.of(map("md5", q.get("md5"), "songId", id, "bitrate", q.get("bitrate"),
            "fileSize", q.get("fileSize"))));
        return client.request("/api/cloud/upload/check/v2", map("uploadType", 0L, "songs", songs), o).flatMap(res -> {
            JsonNode songId = res.bodyObject().path("data").path(0).get("songId");
            if (!res.ok() || songId == null) {
                return Mono.just(res);
            }
            String importSongs = Js.json(List.of(map(
                "songId", MAPPER.convertValue(songId, Object.class),
                "bitrate", q.get("bitrate"),
                "song", q.get("song"),
                "artist", artist,
                "album", album,
                "fileName", Js.string(q.get("song")) + "." + Js.string(q.get("fileType")))));
            return client.request("/api/cloud/user/song/import", map("uploadType", 0L, "songs", importSongs), o);
        });
    }

    /** module/cloud_upload_token.js：只拿上传地址和 token，由前端直接把文件传到 NOS。 */
    Mono<NcmResponse> cloudUploadToken(Map<String, String> q, RequestOptions opt) {
        String md5 = q.get("md5");
        String fileSize = q.get("fileSize");
        String filename = q.get("filename");
        if (blank(md5) || blank(fileSize) || blank(filename)) {
            return Mono.just(NcmResponse.error(400, "缺少必要参数: md5, fileSize, filename"));
        }
        String bitrate = q.get("bitrate") != null ? q.get("bitrate") : "999000";
        String ext = filename.contains(".") ? filename.substring(filename.lastIndexOf('.') + 1) : "mp3";
        return client.request("/api/cloud/upload/check", map(
            "bitrate", bitrate, "ext", "", "length", fileSize, "md5", md5, "songId", "0", "version", 1L),
            opt.withCrypto("")).flatMap(check -> {
                if (!check.ok()) {
                    return Mono.just(check);
                }
                return cloudToken(md5, ext, UploadFile.sanitize(filename), opt).flatMap(t -> lbs().map(server -> {
                    ObjectNode d = JSON.objectNode();
                    d.set("needUpload", check.bodyObject().get("needUpload"));
                    d.set("songId", check.bodyObject().get("songId"));
                    d.put("uploadToken", t.token());
                    d.put("objectKey", t.objectKey());
                    d.put("resourceId", t.resourceId());
                    d.put("uploadUrl", server + "/" + CLOUD_BUCKET + "/" + t.objectKey().replace("/", "%2F")
                        + "?offset=0&complete=true&version=1.0");
                    d.put("bucket", CLOUD_BUCKET);
                    d.put("md5", md5);
                    d.put("fileSize", fileSize);
                    d.put("filename", filename);
                    ObjectNode b = JSON.objectNode();
                    b.put("code", 200);
                    b.set("data", d);
                    return new NcmResponse(200, b, check.cookies());
                }));
            })
            .onErrorResume(UploadError.class, e -> Mono.just(e.response));
    }

    /** module/cloud_upload_complete.js：前端传完文件后，登记到云盘并发布。 */
    Mono<NcmResponse> cloudUploadComplete(Map<String, String> q, RequestOptions opt) {
        String songId = q.get("songId");
        String resourceId = q.get("resourceId");
        String md5 = q.get("md5");
        String filename = q.get("filename");
        if (blank(songId) || blank(resourceId) || blank(md5) || blank(filename)) {
            return Mono.just(NcmResponse.error(400, "缺少必要参数: songId, resourceId, md5, filename"));
        }
        RequestOptions o = opt.withCrypto("");
        String songName = or(q.get("song"), filename.replaceFirst("\\.[^.]+$", ""));
        return client.request("/api/upload/cloud/info/v2", map(
            "md5", md5,
            "songid", songId,
            "filename", filename,
            "song", songName,
            "album", or(q.get("album"), "未知专辑"),
            "artist", or(q.get("artist"), "未知艺术家"),
            "bitrate", q.get("bitrate") != null ? q.get("bitrate") : "999000",
            "resourceId", resourceId), o)
            .flatMap(info -> publish(info, o, pub -> {
                ObjectNode d = JSON.objectNode();
                d.set("songId", info.bodyObject().get("songId"));
                d.setAll(pub.bodyObject());
                ObjectNode b = JSON.objectNode();
                b.put("code", 200);
                b.set("data", d);
                return new NcmResponse(200, b, info.cookies());
            }));
    }

    /** module/voice_upload.js：上传声音（播客节目），NOS 分片上传。 */
    Mono<NcmResponse> voiceUpload(Map<String, String> q, Map<String, UploadFile> files, RequestOptions opt) {
        UploadFile song = files.get("songFile");
        if (song == null) {
            return Mono.just(NcmResponse.error(500, "请上传音频文件"));
        }
        String ext = song.extension();
        String filename = or(q.get("songName"),
            song.name().replace("." + ext, "").replaceAll("\\s", "").replace('.', '_'));
        String mime = or(song.mimetype(), "audio/mpeg");
        Mono<String> cover = files.get("imgFile") != null
            ? uploadImage(files.get("imgFile"), opt).map(i -> i.path("imgId").asText())
            : Mono.justOrEmpty(q.get("coverImgId")).defaultIfEmpty("");
        return cover.flatMap(coverImgId -> client.request("/api/nos/token/alloc", map(
            "bucket", "ymusic", "ext", ext, "filename", filename, "local", false, "nos_product", 0L, "type", "other"),
            opt.withCrypto("weapi")).flatMap(tokenRes -> {
                JsonNode r = tokenRes.bodyObject().path("result");
                if (!tokenRes.ok() || r.path("objectKey").asText("").isEmpty()) {
                    return Mono.just(fail(500, "获取上传token失败", tokenRes.body()));
                }
                String objectKey = r.path("objectKey").asText().replace("/", "%2F");
                String token = r.path("token").asText();
                JsonNode docId = r.get("docId");
                String baseUrl = "https://ymusic.nos-hz.163yun.com/" + objectKey;
                return web().post().uri(URI.create(baseUrl + "?uploads"))
                    .header("x-nos-token", token)
                    .header("X-Nos-Meta-Content-Type", mime)
                    .retrieve().bodyToMono(String.class)
                    .flatMap(xml -> {
                        Matcher m = UPLOAD_ID.matcher(xml);
                        if (!m.find()) {
                            return Mono.error(new UploadError(fail(500, "初始化分片上传失败", JSON.textNode(xml))));
                        }
                        String uploadId = m.group(1);
                        List<byte[]> chunks = new ArrayList<>();
                        for (int off = 0; off < song.data().length; off += VOICE_BLOCK) {
                            chunks.add(Arrays.copyOfRange(song.data(), off, Math.min(off + VOICE_BLOCK, song.data().length)));
                        }
                        return Flux.range(0, chunks.size())
                            .concatMap(i -> web().put()
                                .uri(URI.create(baseUrl + "?partNumber=" + (i + 1) + "&uploadId=" + uploadId))
                                .header("x-nos-token", token)
                                .header(HttpHeaders.CONTENT_TYPE, mime)
                                .bodyValue(chunks.get(i))
                                .retrieve().toBodilessEntity()
                                .map(resp -> String.valueOf(resp.getHeaders().getETag())))
                            .collectList()
                            .flatMap(etags -> {
                                var sb = new StringBuilder("<CompleteMultipartUpload>");
                                for (int i = 0; i < etags.size(); i++) {
                                    sb.append("<Part><PartNumber>").append(i + 1).append("</PartNumber><ETag>")
                                        .append(etags.get(i)).append("</ETag></Part>");
                                }
                                sb.append("</CompleteMultipartUpload>");
                                return web().post().uri(URI.create(baseUrl + "?uploadId=" + uploadId))
                                    .header(HttpHeaders.CONTENT_TYPE, "text/plain;charset=UTF-8")
                                    .header("X-Nos-Meta-Content-Type", mime)
                                    .header("x-nos-token", token)
                                    .bodyValue(sb.toString())
                                    .retrieve().toBodilessEntity();
                            });
                    })
                    .timeout(Duration.ofMinutes(10))
                    .onErrorMap(e -> !(e instanceof UploadError),
                        e -> new UploadError(NcmResponse.error(500, "文件上传失败: " + e.getMessage())))
                    .then(Mono.defer(() -> {
                        String voiceData = Js.json(List.of(map(
                            "name", filename,
                            "autoPublish", "1".equals(q.get("autoPublish")),
                            "autoPublishText", or(q.get("autoPublishText"), ""),
                            "description", q.get("description"),
                            "voiceListId", q.get("voiceListId"),
                            "coverImgId", coverImgId.isEmpty() ? null : coverImgId,
                            "dfsId", docId == null ? null : MAPPER.convertValue(docId, Object.class),
                            "categoryId", q.get("categoryId"),
                            "secondCategoryId", q.get("secondCategoryId"),
                            "composedSongs", blank(q.get("composedSongs")) ? List.of()
                                : Arrays.asList(q.get("composedSongs").split(",", -1)),
                            "privacy", "1".equals(q.get("privacy")),
                            "publishTime", or(q.get("publishTime"), (Object) 0L),
                            "orderNo", or(q.get("orderNo"), (Object) 1L))));
                        RequestOptions o = opt.withCrypto("").withHeaders(Map.of("x-nos-token", token));
                        return client.request("/api/voice/workbench/voice/batch/upload/preCheck",
                                map("dupkey", dupkey(), "voiceData", voiceData), o)
                            .flatMap(pre -> !pre.ok() ? Mono.just(pre)
                                : client.request("/api/voice/workbench/voice/batch/upload/v2",
                                    map("dupkey", dupkey(), "voiceData", voiceData), o).map(res -> {
                                        if (!res.ok()) {
                                            return res;
                                        }
                                        ObjectNode b = JSON.objectNode();
                                        b.put("code", 200);
                                        JsonNode d = res.bodyObject().get("data");
                                        if (d != null) {
                                            b.set("data", d);
                                        }
                                        return NcmResponse.ok(b, List.of());
                                    }));
                    }));
            }))
            .onErrorResume(UploadError.class, e -> Mono.just(e.response));
    }

    /** 对应 createDupkey（类 UUID v4）。 */
    static String dupkey() {
        String hex = "0123456789abcdef";
        char[] s = new char[36];
        for (int i = 0; i < 36; i++) {
            s[i] = hex.charAt(Support.RANDOM.nextInt(16));
        }
        s[14] = '4';
        int v = Character.isDigit(s[19]) ? s[19] - '0' : 0;
        s[19] = hex.charAt((v & 0x3) | 0x8);
        s[8] = s[13] = s[18] = s[23] = '-';
        return new String(s);
    }

    static NcmResponse fail(int code, String msg, JsonNode detail) {
        ObjectNode b = JSON.objectNode();
        b.put("code", code);
        b.put("msg", msg);
        if (detail != null) {
            b.set("detail", detail);
        }
        return new NcmResponse(code, b, List.of());
    }

    /** 上传流程中途失败时携带要返回给调用方的响应。 */
    static final class UploadError extends RuntimeException {
        final NcmResponse response;

        UploadError(NcmResponse response) {
            super(response.bodyObject().path("msg").asText("upload error"), null, false, false);
            this.response = response;
        }
    }
}
