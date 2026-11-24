# 网易云音乐 · Halo 插件

为 Halo 提供独立音乐页、精选歌单、点唱台和个人曲库。音乐页由 Folia Web 适配，网易云 API 内置于插件的 Java 服务。

[项目源码](https://github.com/sky121666/halo-plugin-ncm) · [问题反馈](https://github.com/sky121666/halo-plugin-ncm/issues) · [构建方法](#构建) · [配置同步快速接入](docs/配置同步快速接入.md)

## 安装与使用

1. 在 JDK 21 和 Node.js 24 环境执行 `./gradlew build`，从 `build/libs/` 取得 `plugin-ncm-*.jar`。
2. 在 Halo 2.27 或更高版本的「插件」页面上传并启用。通过「工具 → 网易云音乐」配置并开启音乐页，然后访问 `/music`。
3. 在音乐首页或播放器的「音质」面板登录 Halo，再绑定网易云账号，即可访问个人曲库。

## 开源来源

| 项目 | 用途 | 许可证 |
| --- | --- | --- |
| [Folia Web](https://github.com/folia-ce/folia-web/tree/095e7f84738e1a366be2dc0fec3960cbac1793dd) | 音乐页、播放器和交互。本仓库保存对应补丁、Halo 适配代码和构建脚本 | [AGPL-3.0](licenses/folia-web.AGPL-3.0.txt) |
| [NeteaseCloudMusicApiEnhanced](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/tree/v4.41.1) | 网易云接口描述、请求协议和 Java 移植的来源 | [MIT](licenses/api-enhanced.MIT.txt) |
| [Halo](https://github.com/halo-dev/halo) | 插件运行平台和控制台组件 | [GPL-3.0](https://github.com/halo-dev/halo/blob/main/LICENSE) |
| [node-qrcode](https://github.com/soldair/node-qrcode/tree/v1.5.4) | 绑定弹窗的二维码渲染 | [MIT](licenses/node-qrcode.MIT.txt) |

本插件的自有后端与管理端代码采用 [MIT](LICENSE)。Folia Web 衍生前端按 AGPL-3.0 提供。JAR 中携带第三方许可文本，音乐页「设置 → 帮助 → 开源项目」可下载与当前构建对应的 Folia 补丁、上游版本和构建说明。

## 接口覆盖

| 状态 | 数量 | 说明 |
|---|---|---|
| ready | 372 | 由接口描述表自动执行（weapi 198 / eapi 159 / xeapi 15） |
| custom | 84 | Java 手写（登录、播放链接、上传、听歌打卡、动态聚合、调试工具等） |
| unsupported | 3 | 不移植，原因见下方“已知限制” |

**可用接口：456 / 459**，按等级分：公开 206、个人 134、写操作 88、登录 18、工具 10。

`GET /apis/api.ncm.halo.run/v1alpha1/meta/apis` 可以查看每个接口的等级和状态。

## 身份设计

| | 管理员 | 已登录的访客 | 未登录的访客 |
|---|---|---|---|
| 用谁的网易云身份 | 后台绑定的站长账号 | 她在个人中心绑定的自己的账号 | 游客（插件启动后自动注册） |
| 搜索、歌单、歌词、评论、榜单、歌手、专辑 | ✅ | ✅ | ✅ |
| 免费歌曲完整播放 | ✅ | ✅ | ✅ |
| 会员歌曲 | 站长是会员就能听 | 她是会员就能听 | ❌ 只有试听片段或拿不到链接 |
| 每日推荐、私人 FM、听歌记录、喜欢的歌、云盘 | 自己的 | 自己的 | ❌ |
| 点赞、收藏、建歌单、发评论 | 自己的账号 | 自己的账号 | ❌ |

- 管理员在后台扫码一次即可，前台调用个人接口时自动使用这个账号。
- 写操作只会用调用者自己的账号，绝不借用站长账号。
- 设置里可以选择“未登录访客使用站长账号”，**默认关闭**（违反网易云条款，且有风控风险）。

## 接口路径

| 路径 | 权限 | 用途 |
|---|---|---|
| `GET /apis/api.ncm.halo.run/v1alpha1/public/{name}` | 匿名 | 公开读取 |
| `GET /apis/api.ncm.halo.run/v1alpha1/personal/{name}` | 登录且已绑定 | 读取本人账号的数据 |
| `GET /apis/api.ncm.halo.run/v1alpha1/meta/apis` | 匿名 | 接口清单 |
| `GET /apis/uc.api.ncm.halo.run/v1alpha1/binding` | 登录 Halo | 我的绑定状态 |
| `POST /apis/uc.api.ncm.halo.run/v1alpha1/binding/qr-key` | 登录 Halo | 获取二维码 `{ key, qrurl }` |
| `GET /apis/uc.api.ncm.halo.run/v1alpha1/binding/qr-check?key=` | 登录 Halo | 查询扫码状态，803 表示等待网页确认 |
| `POST /apis/uc.api.ncm.halo.run/v1alpha1/binding/-/qr-confirm` | 登录 Halo | 确认并保存绑定 `{ key }` |
| `POST /apis/uc.api.ncm.halo.run/v1alpha1/binding/captcha` | 登录 Halo | 发短信验证码 `{ phone, ctcode }` |
| `POST /apis/uc.api.ncm.halo.run/v1alpha1/binding/cellphone` | 登录 Halo | 手机号登录并绑定 `{ phone, captcha 或 password, countrycode }` |
| `DELETE /apis/uc.api.ncm.halo.run/v1alpha1/binding` | 登录 Halo | 解绑 |
| `/apis/console.api.ncm.halo.run/v1alpha1/site-account`（GET / DELETE）、`/site-account/qr-key`、`/site-account/qr-check`、`/site-account/-/qr-confirm`、`/site-account/captcha`、`/site-account/cellphone` | 网易云音乐管理 | 站长账号绑定 |
| `GET /apis/console.api.ncm.halo.run/v1alpha1/calls/{name}` | 网易云接口调试 | 使用游客身份调试常用只读接口 |
| `GET /apis/console.api.ncm.halo.run/v1alpha1/guest-status/-` | 网易云音乐管理 | 读取游客会话摘要 |
| `GET/PUT /apis/console.api.ncm.halo.run/v1alpha1/api-access-settings/-` | Halo 插件管理 | 读取和保存接口设置。保存时校验配置版本 |
| `GET/PUT /apis/console.api.ncm.halo.run/v1alpha1/frontstage-settings/-` | Halo 插件管理 | 读取和保存音乐页设置。播放模式和音量由配置发布接口管理 |
| `GET /apis/console.api.ncm.halo.run/v1alpha1/device` | Halo 插件管理 | 读取设备、凭据和连接状态 |
| `POST /apis/console.api.ncm.halo.run/v1alpha1/device/refresh` | Halo 插件管理 | 重新获取 xeapi 公钥并注册游客 |
| `POST /apis/console.api.ncm.halo.run/v1alpha1/device/reset` | Halo 插件管理 | 重新生成设备身份 |

**对外开放的接口**。通用路径 `public/{name}` 和 `personal/{name}` 默认开放约 75 个常用只读接口，清单见 `api/OpenApis.java`。通过「接口管理 → 接口开放」调整范围，调用时继续校验 Halo 权限。`meta/apis` 的 `open` 表示对外开放状态，`debuggable` 表示能否用游客身份调试。音乐页和个人曲库使用插件专用接口。

**借用站长账号的限制**。在「接口管理 → 接口开放 → 公开访问身份」设置。访客借用站长账号时，下载接口返回 403，播放取址遵循设置的音质上限。站长本人按自己的账号权限播放。

`{name}` 是上游 module 的文件名，参数和上游一致。例如：

```
/apis/api.ncm.halo.run/v1alpha1/public/cloudsearch?keywords=晴天
/apis/api.ncm.halo.run/v1alpha1/public/playlist_track_all?id=24381616&limit=20
/apis/api.ncm.halo.run/v1alpha1/public/song_url_v1?id=186016&level=standard
/apis/api.ncm.halo.run/v1alpha1/public/lyric_new?id=186016
/apis/api.ncm.halo.run/v1alpha1/personal/recommend_songs
```

绑定、个人歌单、红心、收藏和后台写入请求需带 `X-NCM-Request: 1`。扫码会话校验发起人、绑定对象和有效期，网页确认后会话结束。个人音乐空间使用 UC API，歌单曲目路径为 `playlists/{id}/tracks`。接口调试由独立角色授权。

## 上传类接口

用 `multipart/form-data` 提交，文件字段名和上游一致：

| 接口 | 文件字段 | 说明 |
|---|---|---|
| `avatar_upload` | `imgFile` | 修改头像 |
| `playlist_cover_update` | `imgFile` | 修改歌单封面（另传 `id`） |
| `cloud` | `songFile` | 上传歌曲到云盘。标题、歌手、专辑先读 MP3 的 ID3 标签，也可以用 `song`、`artist`、`album` 参数指定 |
| `voice_upload` | `songFile`，可选 `imgFile` | 上传声音（播客），10MB 分片 |
| `cloud_upload_token` + `cloud_upload_complete` | 无 | 大文件推荐：先拿上传地址，前端直接传到网易云存储，再登记 |

```bash
curl -X POST -b 'SESSION=...' \
  -F songFile=@晴天.mp3 \
  https://你的站点/apis/api.ncm.halo.run/v1alpha1/personal/cloud
```

Halo 默认限制请求体大小，上传大文件前请确认反向代理和 Halo 的上传上限。

## 启动流程

第一次调用任意接口时，插件会自动：

1. 向 `/api/bsr/sk/get` 获取 xeapi 服务端公钥，并校验签名
2. 用 xeapi 调用 `/api/register/anonimous` 注册游客，拿到 `MUSIC_A`

失败不影响 weapi / eapi 接口，5 分钟后会自动重试，也可以在后台调用 `device/refresh` 手动重试。

> 和上游的差异：上游先注册游客、再取公钥，首次启动时游客注册会因为没有公钥而失败。这里改为先取公钥。

`song_url_v1` 走 xeapi，失败时自动改用 eapi 重试，返回体里带 `"fallback": "eapi"`。

## 可选的音乐 AI

网易云音乐插件内置歌曲 AI 配色和歌词分词，随主 JAR 安装。在「插件 → 网易云音乐 → 音乐 AI」管理总开关与使用范围。默认开启，所有已登录的 Halo 账号可用。切换到「仅指定角色」后，账号拥有任意一个所选角色即可使用。已有站点的开关选择继续有效。

音乐 AI 需要启用 [AI Foundation](https://github.com/halo-dev/plugin-ai-foundation)（`>=1.4.0 & <2.0.0`）并设置默认语言模型。音乐页在打开或切回标签页时检查角色权限和模型状态。未登录、权限不符或模型未就绪时隐藏生成入口，仍可使用手动配色、封面取色和原始歌词。

已安装旧「网易云音乐 AI 扩展」的站点可先停用旧扩展，再升级主插件。首次启动时，主插件会读取旧开关，优先使用主插件里已保存的值。核对开关后可卸载旧扩展。如热更新 AI Foundation 后 AI 入口未恢复，重载一次网易云音乐插件。

生成配色时会把歌名或最多 2,000 字的歌词发给站点配置的模型。明暗配色、关键词颜色和意象图标作为单曲主题保存在浏览器，重复播放可复用。封面提色也保存在浏览器的单独缓存中。Halo 配置同步保存外观、播放等偏好。分词只发送要处理的歌词行。

同站点的适配界面可调用 `/apis/api.ncm-ai.halo.run/v1alpha1` 下的接口。

| 方法和路径 | 请求 | 响应 |
|---|---|---|
| `GET /capabilities/-` | Halo 登录会话 | 对象中的 `available` 字段为 `true` 或 `false` |
| `POST /themes` | `{ "lyricsText": "...", "songTitle": "...", "isPureMusic": false }` | `light`、`dark` 两套配色 |
| `POST /segmentations` | `{ "lines": ["第一行", "第二行"] }` | `lines` 按输入对齐。空项沿用本地分词 |

POST 使用同源 JSON 请求并带上 `X-NCM-Request: 1`。服务端每次检查总开关、角色和模型。角色未获授权返回 `403`，总开关关闭或模型未就绪返回 `503`。个人每分钟最多 8 次，全站每分钟最多 30 次，同时最多处理 2 次生成，超出额度返回 `429`。

## 前端配置同步

主题或自定义音乐页可从[配置同步快速接入](docs/配置同步快速接入.md)复制客户端清单和读写示例。

Folia 设置即时保存在浏览器。登录 Halo 后，「存储与备份 → Halo 配置同步」中的个人同步默认开启，已有的开关选择优先。后续修改合并提交到当前 Halo 账号。换号会切换本地配置副本。离线修改在本机保留，版本冲突由用户选择云端或本机配置。

超级管理员修改可发布的设置后，可从设置窗口底部「保存为站点默认」。保存范围由配置字段清单决定。浏览器的歌词、封面、歌单和媒体缓存保存在本地。

在「存储与备份 → 缓存详情 → 歌曲缓存」可开关缓存、查看本浏览器已缓存的歌曲数量和占用空间，也可以清理。个人同步仅保存开关。重复播放时仍会校验当前账号的音源权限。试听、私人云盘、站长代播和需要会员权限的音源继续在线播放。

配置 API 的前缀为 `/apis/api.ncm.halo.run/v1alpha1`。

| 方法和路径 | Halo 权限 | 用途 |
|---|---|---|
| `GET /config-defaults/{clientId}` | 游客可读 | 读取该客户端已发布的默认配置 |
| `GET /my-configs/{clientId}` | 登录 | 读取本人的配置快照、当前账号标识和权限 |
| `PUT /my-configs/{clientId}` | 登录 | 保存本人的完整可同步配置 |
| `PUT /config-defaults/{clientId}` | 超级管理员 | 发布新的站点默认 |
| `POST /config-defaults/{clientId}/restore` | 超级管理员 | 用当前版本令牌恢复上一版 |

读取返回 `schemaVersion`、`revision`、`config`。个人读取将三项放在 `snapshot`，并提供 `profileKey`、`canPublishSite` 和 `canViewDiagnostics`。`config` 为空时沿用站点或客户端默认。写入提交同样三项，使用目标接口读取的 `revision`。

```json
{
  "schemaVersion": 1,
  "revision": "从目标接口读取的版本令牌",
  "config": {
    "playback": { "volume": 0.65, "loopMode": "all" },
    "visual": { "showSubtitleTranslation": true }
  }
}
```

写请求发送 `Content-Type: application/json` 和 `X-NCM-Request: 1`，浏览器使用同源 Halo 会话。请求体上限 64 KiB，未知字段或非法值返回 `400`，版本冲突返回 `409`。个人响应使用 `private, no-store`。诊断入口使用 Halo 的「网易云接口调试」权限，站点发布使用超级管理员权限。

Folia 使用 `folia` 客户端，其字段清单是 [foliaConfigPolicy.json](frontstage/folia/adapter/foliaConfigPolicy.json)。新适配端可随插件登记 `config-clients/{clientId}.json`，声明版本、分组、字段和 `personalOnly` 列表。设备、播放会话、外部服务凭据、素材和日志沿用各自存储。首次升级将原播放模式和音量迁入 `folia` 站点默认。两个标签页的待同步改动会分别保存在当前浏览器，恢复联网后按字段合并。同一字段取最后一次本机改动，服务端版本发生冲突时由用户选择。

清除个人云端配置复用个人写入接口，提交 `config: {}` 并使用个人配置的当前 `revision`。开启同步的设备重新应用站点默认。发布不同的站点默认时会保留上一版。`hasPrevious` 为 `true` 时，超管可以提交 `{ "schemaVersion": 1, "revision": "当前站点版本" }` 恢复。历史内容保留在后台。

## OpenAPI 与前端 API Client

三组自定义接口都用 `SpringdocRouteBuilder` 声明了 OpenAPI 描述（稳定的 operationId、tag、参数、请求体和响应类型），请求和响应都有明确的 DTO（`cn.sky.ncm.endpoint.dto.Ncm*`），请求体在服务端显式校验，不只靠文档约束。

| tag | 前端客户端类 | 接口 |
|---|---|---|
| `api.ncm.halo.run/v1alpha1/Ncm` | `ApiNcmHaloRunV1alpha1NcmApi` | 公开 / 个人接口调用、接口清单 |
| `console.api.ncm.halo.run/v1alpha1/SiteAccount` | `ConsoleApiNcmHaloRunV1alpha1SiteAccountApi` | 站长账号绑定 |
| `console.api.ncm.halo.run/v1alpha1/Device` | `ConsoleApiNcmHaloRunV1alpha1DeviceApi` | 设备状态 |
| `console.api.ncm.halo.run/v1alpha1/Debug` | `ConsoleApiNcmHaloRunV1alpha1DebugApi` | 调试台 |
| `console.api.ncm.halo.run/v1alpha1/FrontstageSettings` | `ConsoleApiNcmHaloRunV1alpha1FrontstageSettingsApi` | 音乐页设置 |
| `uc.api.ncm.halo.run/v1alpha1/Binding` | `UcApiNcmHaloRunV1alpha1BindingApi` | 个人中心绑定 |

后台和个人中心使用 `./gradlew generateApiClient` 生成到 `ui/src/api/generated/` 的客户端和模型。Folia 配置同步通过同源 Fetch 接入上述契约。

> 网易云接口本身（`public/{name}`、`personal/{name}`、`calls/{name}`）的参数因接口而异，OpenAPI 里只声明了路径参数 `name`，其余参数用 axios 的 `params` 或请求体传入，返回值声明为任意 JSON。

`haloServer` 启动后，可以在 `http://localhost:8090/swagger-ui.html` 里选择分组「Extension API for plugin-ncm」查看文档。

## 主题模板（ncmFinder）

插件给 Thymeleaf 主题提供了模板变量 `ncmFinder`（自插件 **0.1.0** 起），主题可以在服务端渲染时直接拿到歌曲、歌单、歌词等数据，不用自己写前端请求。

**先检查插件是否可用**，没装或停用插件时主题会自动隐藏相关内容：

```html
<th:block th:if="${pluginFinder.available('plugin-ncm', '>=0.1.0')}">
  <!-- 使用 ncmFinder 的内容 -->
</th:block>
```

### 约定

- **ID 一律用字符串**：`ncmFinder.song('186016')`。网易云的 ID 可能超过 int 范围，写成数字会出错。
- **不会让页面报错**：参数无效、网易云出错或超时（6 秒）时，返回单个对象的方法得到 `null`，返回列表的方法得到空列表。
- **有缓存**。结果默认缓存 600 秒，可在插件「网络与限流」调整。同一份数据用 `th:with` 读取一次。
- **身份**。公开数据使用「接口开放」设置的网易云身份。`playable` 根据该身份的曲目权限预估播放状态。
- `limit` 最大 200。

### 公开数据

| 方法 | 返回 | 说明 |
|---|---|---|
| `song(id)` | `SongVo` | 单首歌曲 |
| `songs(ids)` | `List<SongVo>` | 多首歌曲，`ids` 用英文逗号分隔，按传入顺序返回 |
| `playlist(id)` / `playlist(id, limit)` | `PlaylistVo` | 歌单详情和前 limit 首歌（默认 50）；limit 为 0 时只要歌单信息 |
| `playlistTracks(id, offset, limit)` | `List<SongVo>` | 歌单里从 offset 开始的 limit 首歌（默认 50，最多 200），用于分页 |
| `search(keyword, type, page, size)` | `SearchResultVo` | 搜索；type 为 song（默认）/ playlist / album / artist，size 默认 30（最多 100），最多到第 1000 条；关键词为空或超过 100 字时为 null |
| `suggest(keyword)` | `SuggestVo` | 搜索联想：`keyword`、`groups`（`type`、`label`、`items`：`type`、`id`、`name`、`sub`、`coverUrl`、`path`），每组最多 5 项 |
| `hotKeywords(limit)` | `List<HotKeywordVo>` | 热搜词（默认 10，最多 30） |
| `album(id)` | `AlbumVo` | 专辑详情和全部歌曲 |
| `artist(id)` / `artist(id, limit)` | `ArtistVo` | 歌手详情和热门歌曲（默认 20） |
| `lyric(id)` | `SearchResultVo` | `keyword`、`type`、`page`、`size`、`total`、`songs`、`playlists`、`albums`、`artists`（只有和 type 对应的列表有内容；歌单 / 专辑 / 歌手不含歌曲）、`empty`（本页没有结果） |
| `HotKeywordVo` | `keyword`、`description`、`score`、`iconUrl` |
| `LyricVo` | 歌词，已经按时间拆成行并合并译文 |
| `playUrl(id)` / `playUrl(id, level)` | `String` | 插件的播放地址 `/music/play/{id}?level=`（302 跳转到音频），直接用作 `<audio>` 的 `src`；每次请求时按访客身份取音频（见“播放地址”）；level 可选 standard / exhigh / lossless / hires / jyeffect / vivid / jymaster / sky |
| `thumbnail(url, size)` | `String` | 网易云图片的缩略图地址（边长 16~1024 像素） |

### 站长数据

在「工具 → 网易云音乐 → 账号与游客」绑定站长账号后，到「音乐页设置」选择要公开的内容。开关关闭、账号未绑定或读取设置失败时返回空结果。

| 方法 | 返回 | 设置开关（默认） |
|---|---|---|
| `siteProfile()` | `UserVo` | 展示站长资料（开） |
| `sitePlaylists()` / `sitePlaylists(limit)` | `List<PlaylistSummaryVo>` | 展示站长歌单（开）；只返回网易云上公开的歌单，默认 30 个 |
| `siteRecentSongs()` / `(limit)` | `List<PlayRecordVo>` | 最近播放（关），默认 20 首 |
| `siteWeekRank()` / `(limit)` | `List<PlayRecordVo>` | 听歌排行（关），最近一周，默认前 10 |
| `siteAllRank()` / `(limit)` | `List<PlayRecordVo>` | 听歌排行（关），全部时间，默认前 10 |
| `siteDailyRecommend()` / `(limit)` | `List<SongVo>` | 每日推荐（关），默认 30 首 |

### 返回字段

| 类型 | 字段 |
|---|---|
| `SongVo` | `id`、`name`、`aliases`、`translations`、`artists`（`id`、`name`）、`artistNames`、`album`（`id`、`name`、`coverUrl`）、`coverUrl`、`durationMs`、`durationText`（如 `4:29`）、`fee`、`vip`、`playable`（游客或站长是否有完整播放权限，**不要据此禁用播放**）、`availability`（预估：`full` / `trial` / `unavailable` / null，只用于提示）、`mvId`、`url`（网易云网页）、`playUrl` |
| `PlaylistVo` | `id`、`name`、`coverUrl`、`description`、`tags`、`trackCount`、`playCount`、`creator`（`userId`、`nickname`、`avatarUrl`）、`tracks`、`url` |
| `PlaylistSummaryVo` | `id`、`name`、`coverUrl`、`trackCount`、`playCount`、`own`（站长自己创建）、`likedSongs`（我喜欢的音乐）、`url` |
| `AlbumVo` | `id`、`name`、`coverUrl`、`description`、`company`、`publishTime`、`publishDate`（如 `2003-07-31`）、`artists`、`artistNames`、`size`、`tracks`、`url` |
| `ArtistVo` | `id`、`name`、`aliases`、`coverUrl`、`briefDesc`、`albumSize`、`musicSize`、`mvSize`、`hotSongs`、`url` |
| `LyricVo` | `songId`、`lines`（`timeMs`、`timeText`、`text`、`translation`）、`lyric`、`translatedLyric`、`hasTranslation`、`instrumental` |
| `UserVo` | `userId`、`nickname`、`avatarUrl`、`backgroundUrl`、`signature`、`level`、`listenSongs`、`vip`、`follows`、`followeds`、`playlistCount`、`url` |
| `PlayRecordVo` | `song`、`playCount`、`score`（排行有值）、`playedAt`（最近播放有值） |

所有图片地址都已经改成 https，避免 https 站点出现混合内容。

### 示例

歌单卡片：

```html
<section th:with="pl = ${ncmFinder.playlist('24381616', 10)}" th:if="${pl != null}">
  <img th:src="${ncmFinder.thumbnail(pl.coverUrl, 200)}" th:alt="${pl.name}" />
  <h3 th:text="${pl.name}">歌单名</h3>
  <ol>
    <li th:each="s : ${pl.tracks}">
      <span th:text="|${s.name} - ${s.artistNames}|">歌名 - 歌手</span>
      <span th:text="${s.durationText}">0:00</span>
      <audio th:if="${s.playable}" controls preload="none" th:src="${s.playUrl}"></audio>
      <span th:unless="${s.playable}">会员歌曲</span>
    </li>
  </ol>
</section>
```

站长的歌单墙：

```html
<ul th:with="lists = ${ncmFinder.sitePlaylists(12)}" th:unless="${#lists.isEmpty(lists)}">
  <li th:each="p : ${lists}">
    <a th:href="${p.url}" target="_blank" rel="noopener">
      <img th:src="${ncmFinder.thumbnail(p.coverUrl, 160)}" th:alt="${p.name}" loading="lazy" />
      <span th:text="${p.name}"></span>
    </a>
  </li>
</ul>
```

歌词（配合前端脚本按时间高亮）：

```html
<ol class="lyric" th:with="ly = ${ncmFinder.lyric('186016')}" th:if="${ly != null and !ly.instrumental}">
  <li th:each="l : ${ly.lines}" th:attr="data-time=${l.timeMs}">
    <span th:text="${l.text}"></span>
    <small th:if="${l.translation != null}" th:text="${l.translation}"></small>
  </li>
</ol>
```

> Finder 只在服务端渲染时使用。需要在浏览器里翻页、搜索或播放下一首时，请调用公开接口 `/apis/api.ncm.halo.run/v1alpha1/public/{name}`。

## 音乐前台

插件将固定版本的 Folia Web 随 JAR 提供。首页、搜索、个人曲库和音乐详情共用同一个 Folia 播放器。公共链接可直接分享和刷新。

| 路径 | 页面与接口 |
|---|---|
| `/music` | Folia 首页，包含歌单、推荐、探索和专辑。加 `format=json` 读取已发布的站长内容 |
| `/music/search?q=&type=` | 点唱台。JSON 搜索支持 `song`、`playlist`、`album`、`artist` 和 `page` |
| `/music/library` | 个人曲库。登录 Halo 并绑定网易云后，可管理歌单、查看喜欢的歌和播放记录 |
| `/music/playlist/{id}` | 歌单。JSON 用 `limit` 和 `offset` 分批读取曲目 |
| `/music/album/{id}` | 专辑 |
| `/music/artist/{id}` | 歌手 |
| `/music/song/{id}` | 歌曲。使用 Folia 曲目控件点播、加入队列或打开关联专辑和歌手 |

详情 HTML 提供标题、canonical 和相应的音乐结构化数据。访问不存在的资源返回 404。上游暂时不可用时返回 503，页面提供重试入口。四类详情均可加 `format=json` 获取数据。

「探索」提供网易云发现内容和本站精选。精选顺序在「歌单发布」管理。「站长内容」控制公开歌单、最近播放、排行和推荐的展示，以及公开接口提供的站长资料。「音乐页面」设置标题、简介、歌单首批曲目数、搜索和联想。首批曲目数默认 50，用于歌单详情的首次加载。

搜索联想使用 `GET /music/search/suggest?q=`。返回的分组包含单曲、歌单、专辑或歌手条目，条目的 `path` 为站内直达链接。搜索和联想分别限流。

歌单、个性化推荐和收藏专辑使用当前 Halo 用户绑定的网易云账号。首页与播放器的音质面板可在站内弹窗完成登录和绑定。已绑定时，从首页账号入口打开个人曲库，管理歌单和查看播放记录。

「我创建的」可新建公开或私密歌单。进入自己创建的歌单后，可从播放队列或搜索结果批量添加曲目，也可以移除曲目和删除歌单。点唱台、单曲页和播放面板共用「加入歌单」选择器。收藏的歌单可以取消收藏；喜欢的歌通过红心管理。

歌单详情的播放与添加在同一操作区，删除与移除从相应的操作菜单进入。添加曲目时可展开已选列表并撤销选择。提交结果未确认时，使用「查看最新歌单」回读后再决定下一步。

曲库按内容显示加载、空态和错误。最近播放的歌单读取失败时，可单独重试该分组。喜欢的歌遇到暂时性刷新故障时，可继续播放已加载的曲目。收藏和红心仅在确认后更新。提交结果未确认时，核对该歌单或曲目的状态后即可继续操作。

## Folia 播放器

播放器布局、歌词、快捷键和随机播放使用 Folia 原生功能。超管在音乐页「存储与备份 → Halo 配置同步」发布默认循环模式、音量等偏好。「音乐页设置 → 播放行为」控制回到音乐页时的自动续播。访客的本机调节由浏览器保存。旧版随机模式按列表循环读取，可用 Folia 的随机播放操作打乱队列。

适配源码和许可证可从音乐页「设置 → 帮助 → 开源项目」下载。

从个人曲库加入的曲目只保存在当前 Folia 文档，切换音乐区内的页面时继续播放。刷新或离开文档会清除私人队列，公共队列可按播放器设置恢复。

**播放地址** `GET /music/play/{id}?level=`：默认 302 跳转到音频（不缓存）；加 `format=json` 返回 `{ "id", "status", "url", "trial": { "start", "end" } | 无, "level", "br", "identity", "message" }`。

- 播放时按请求解析身份。已绑定的用户使用本人的网易云账号，站长本人可使用自己在后台绑定的站长账号，其他访客使用「接口开放」配置的公开身份。
- `status`：`ok`（200）、`unavailable` 没有可用音源，会说明是会员歌曲、付费专辑还是没有版权（404）、`service` 网易云或插件暂时不可用（503）、`rate_limited`（429）。`identity`：`user` / `site` / `guest`。
- 取址缓存按身份、绑定版本、曲目和音质区分。凭据与版本来自同一次 Secret 读取。换绑后，新请求使用新版本，旧请求即使晚到也只写回旧版本的缓存。可播放结果缓存 10 分钟，明确无音源的结果缓存 2 分钟。`fresh=1` 跳过已存的缓存结果。
- 播放和歌单抽查共用取址判定，同时检查响应状态、上游业务码和曲目信息。上游业务码 `502` 或响应缺少曲目信息时返回 `service`，限流返回 `rate_limited`。两者在抽查中显示「待确认」，可重试，且不缓存为不可播。登录用户按用户名限流，游客按 IP 限流。
- 旧的 `/apis/api.ncm.halo.run/v1alpha1/public/song_url_v1_302` 仍然可用（固定用公开身份）。

歌曲行根据实际取址显示试听、可用性和服务错误，播放控制交给音乐区的 Folia 实例。试播进度使用接口返回的试听范围。歌词由 `GET /music/lyric/{id}` 提供。

**文章音乐卡片**使用站内详情作为播放入口，歌单和专辑卡片保留曲目预览。音乐页关闭时使用网易云链接。主题可在 Halo 菜单登记 `/music`，也可使用 [ncmFinder](#主题模板ncmfinder) 提供的公开数据。

## 后台管理

| 位置 | 页签 | 管理内容 | 所需权限 |
|---|---|---|---|
| 工具 → 网易云音乐 | 精选歌单 | 预览试听、编辑展示信息、保存草稿、发布、下架和排序 | 网易云音乐管理 |
| 工具 → 网易云音乐 | 音乐页设置 | 页面与搜索、站长公开内容、自动续播 | Halo 插件管理 |
| 工具 → 网易云音乐 | 账号与游客 | 绑定站长网易云账号，读取游客注册状态与最近初始化尝试 | 网易云音乐管理 |
| 插件 → 网易云音乐 | 维护 | 刷新设备、凭据与代理状态，重新初始化游客或重置设备身份 | Halo 插件管理 |
| 插件 → 网易云音乐 | 接口管理 | 通用接口范围、公开访问身份、请求兼容和游客只读调试 | Halo 插件查看。接口开放需要 Halo 插件管理，接口调试需要网易云接口调试 |
| 插件 → 网易云音乐 | 网络与限流 | 网易云代理、访客 IP、请求额度、音乐页与主题查询缓存、上传 | Halo 插件管理 |
| 插件 → 网易云音乐 | 音乐 AI | 启用歌曲配色和歌词分词 | Halo 插件管理 |

工具页右上角的「插件详情」打开「管理入口」。该页按角色提供音乐页工具、插件设置与维护的直达链接，并可返回工具页。

通用公开接口缓存与兼容请求可从「网络与限流」跳到「接口开放」对应分区。音乐页设置与接口设置各自保存并校验版本，只读调试使用独立额度并返回脱敏结果。

同时具备账号管理与插件管理权限时，可从「账号与游客」和「维护」页顶部互相跳转。「刷新状态」读取已知状态。重新初始化与设备重置在「维护操作」中执行，重置需要二次确认。

「精选歌单」和「音乐页设置」提供音乐页查看入口。具备两页权限时可从页头互相跳转。编辑展示信息只保存本站使用的标题和简介，移除歌单只处理本站精选记录。

### Halo 接入边界

- 个人账号在「个人中心 → 网易云音乐」（`/uc/ncm`）绑定或解绑。打开个人曲库会进入 Folia 的 `/music/library`。个人曲库使用 `/apis/uc.api.ncm.halo.run/v1alpha1/me/-/` 下的接口。
- 音乐页从插件的发布记录和授权的站长内容读取数据。实际取址由 `/music/play/{id}` 按请求身份处理。
- 主题模板通过 `ncmFinder` 读取公开音乐数据。技术参数由 Halo Setting 管理，工具页使用 Halo 共享组件和生成的 API Client。

## 构建

目标版本：**Halo 2.27**（`plugin.yaml` 的 `spec.requires` 为 `>=2.27.0`）。依赖与官方脚手架 `create-halo-plugin` 一致：

| 项目 | 版本 |
|---|---|
| Halo 依赖平台 | `run.halo.tools.platform:plugin:2.27.0` |
| DevTools Gradle 插件 | `run.halo.plugin.devtools` 0.9.0 |
| Gradle | 9.4.0 |
| JDK | 21 |
| Node.js / pnpm | 24 / 10 |
| 控制台和个人中心 | `@halo-dev/*` 2.27.0、Vite 8、Vue 3.5 |
| 音乐页 | Folia Web，固定提交见 [upstream.json](frontstage/folia/upstream.json) |

项目包含 Gradle Wrapper 和 API Client。构建会下载并校验固定版本的 Folia Web 源码，应用适配补丁并随 JAR 打包。

```bash
git clone https://github.com/sky121666/halo-plugin-ncm.git
cd halo-plugin-ncm
./gradlew build
```

后端 API 契约变更后，重新生成 `ui/src/api/generated/`。默认由 DevTools 启动临时 Halo 容器读取 OpenAPI 文档，需要 Docker。请使用独立构建目录或把历史插件包保存在 `build/libs/` 之外，避免临时容器同时加载同名插件。也可在 `build.gradle` 的 `haloPlugin.openApi` 中配置 `useExistingServer = true`，从已加载插件的 Halo 读取文档。

```bash
./gradlew generateApiClient -x test
```

生成会重写专用客户端目录。确认产物包含当前接口后，使用 `./gradlew build` 运行完整检查。

构建插件会同时构建 `ui/` 前端（Gradle 的 node 插件会自动执行 `pnpm install`），前端产物会完整复制到插件包的 `ui/` 目录：

```bash
corepack enable          # 没装 pnpm 时执行一次
./gradlew build          # 产物在 build/libs/
./gradlew haloServer     # 启动一个装好插件的 Halo 2.27 开发实例（需要 Docker）
```

使用 Halo DevTools 在独立端口启动开发实例并自动重载。

```bash
./gradlew watch -Pport=8092
```

`watch` 会启动开发实例，监听插件源码和 `frontstage/folia/`。需要手动重载时，可用 `./gradlew haloServer -Pport=8092` 启动开发实例，然后执行 `./gradlew reloadPlugin -Pport=8092`。控制台和个人中心的 Vue 界面还可以使用 `ui/` 下的 `pnpm dev`。完整检查运行 `./gradlew build`。

> Halo 2.23 起升级到 Spring Boot 4，默认用 Jackson 3 序列化。插件内部仍用 Jackson 2 处理网易云的数据，所有接口在返回前都先自己序列化成 JSON 字符串，避免两套 Jackson 混用导致返回内容错乱。

## 验证加密是否和上游一致（重要）

```bash
git clone https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced /tmp/api-enhanced
(cd /tmp/api-enhanced && pnpm install)
node tools/gen-vectors.js /tmp/api-enhanced > src/test/resources/vectors.json
./gradlew test
```

对比项：weapi、eapi、linuxapi、xeapi 的 C 和 R、xeapi 签名、游客 username 编码。xeapi 的 S 含临时密钥，无法固定，靠自洽测试（模拟服务端完整解出 C、S、R）验证。

## 同步上游

```bash
(cd /tmp/api-enhanced && git pull)
python3 tools/convert.py /tmp/api-enhanced/module src/main/resources/ncm-apis.json
```

转换器只接受“结构可证明等价”的模块；已有的等级（tier）和手写标记会保留，新增模块会打印出来，请人工复核等级。

## 持久化与安全

**设备身份持久化**：设备 ID、WNMCID、NMTID、游客 token、xeapi 公钥和会话保存在 Halo 自定义资源 `NcmDevice/default` 里，插件重启后直接恢复，不会每次都重新注册游客。xeapi 公钥超过 24 小时自动重新获取。

**凭据存在 Halo Secret 里**：按 Halo 插件安全规范，凭据不放在自定义模型里。

| 凭据 | Halo Secret | 自定义模型里只留 |
|---|---|---|
| 站长账号的网易云 Cookie | `ncm-account-site` | `NcmAccount/site`：昵称、头像、会员状态 |
| 用户 alice 的网易云 Cookie | `ncm-account-user-alice` | `NcmAccount/user-alice`：同上 |
| 游客 token、xeapi sk、xeapi 会话密钥 | `ncm-device-default` | `NcmDevice/default`：设备 ID、公钥、会话 ID 等非敏感字段 |

- 这些 Secret 都带有标签 `plugin.halo.run/plugin-name=plugin-ncm` 和备注，在 Halo 后台的密钥列表里能看到，**请不要手动修改**。
- 账号和设备凭据只读写带这个标签的 Secret。账号凭据的 Secret 名称由账号名推导，`NcmAccount` 中的名称仅作记录。
- 解绑时会同时删除对应的 Secret。卸载插件不会自动删除，需要的话可以在密钥列表里按上面的名称手动删除。
- 「网易云音乐管理」角色通过插件提供的后台接口操作。已有旧角色引用继承新版范围，接口调试单独授权。
- **从旧版本升级**：之前直接存在 `NcmAccount.spec.cookie`、`NcmDevice.spec` 里的凭据，在第一次读取时会自动转存到 Secret，并从原资源中清空，不需要重新绑定。

**Secret 里的值再加密一层**：Halo 的 Secret 在数据库里只是普通 JSON，并不加密。所以插件写入前会用 AES-256-GCM 再加密每个值（格式 `enc:v1:...`），单独泄露数据库备份时看不到明文。

加密密钥和数据库分开存放，按优先级：

1. 环境变量 `NCM_SECRET_KEY`（任意字符串）
2. 密钥文件：环境变量 `NCM_SECRET_FILE` 指定的路径，否则 `{HALO_WORK_DIR 或 ~/.halo2}/plugin-ncm/secret.key`，第一次启动自动生成
3. 以上都不可用时只放在内存里，插件重启后需要重新绑定账号。插件「维护」页会给出提示。

> ⚠️ 备份时请把密钥文件和数据库一起备份。密钥丢失后，Secret 里的值无法解密，已绑定的账号需要重新绑定。

在「网络与限流」填写网易云代理地址。需要认证时选择含 `username`、`password` 的 Halo Secret。旧版本带认证的地址会在首次使用时迁入加密 Secret，其他配置字段按原值保存。

**限流**：

| 接口 | 维度 | 默认 |
|---|---|---|
| 通用公开接口、歌词、音乐卡片 | 各自按 IP 计数 | 每分钟 120 次 |
| 通用个人及写入接口、个人曲库 | 各自按 Halo 用户计数 | 每分钟 120 次 |
| 后台只读调试 | 每个操作者 | 每分钟 30 次 |

超过额度返回 429。搜索和联想按 IP 使用独立额度。游客取址按 IP 使用公开请求额度的 2 倍，登录用户取址按账号使用个人请求额度的 2 倍。

**访客 IP 识别**在「网络与限流」配置，代码见 `security/ClientIp.java`。

- Halo 只读取可信代理提供的 `X-Forwarded-For` 或 `X-Real-IP`。其他请求使用直接连接的地址。默认信任本机、内网、链路本地和 IPv6 唯一本地地址。通过公网代理直接连接 Halo 时，在「可信代理 IP 或网段」填写该代理地址。
- `X-Forwarded-For` 从**右往左**取第「反向代理层数」个地址。仅经 Nginx 时填 1，经 CDN 和 Nginx 时填 2。
- 没有 `X-Forwarded-For` 时用 `X-Real-IP`。请求头里的值必须是 IP 字面量。
- 「使用代理转发的访客 IP」默认打开。关闭时，同一个反向代理后的访客按代理 IP 共用公开请求额度。

**上传限制**：只有写操作等级的接口接收文件，其他接口带文件会返回 400。单个文件默认不超过 100MB（设置里可以改，最大 1024MB），超过返回 413。Halo 和反向代理本身也有上传大小限制，需要一起调整。

**不暴露的参数**：上游允许在请求里传 `cookie`、`proxy`、`realIP`，插件全部忽略，避免访客借插件冒用账号或让服务器访问任意地址。

## 已知限制

- 不移植的 3 个接口：
  - `register_checktoken_v2`：需要在浏览器环境里运行易盾 SDK。用到 v2 token 的接口（`comment`、`playlist_subscribe`、`middle_play_do_lottery`）改用 v3，需要实测服务端是否接受。
  - `register_neapikey`：neapi 配置要解析混淆的 Lua 字节码，目前没有接口默认使用 neapi。
  - `song_url_match`：依赖第三方音源解灰，涉及版权风险。`song_url_v1` 同样不支持 `unblock=true`。
- 和上游的小差异：
  - 二维码类接口（`login_qr_create`、`verify_getQr`）不生成 `qrimg` 图片，只返回 `qrurl`，由前端自己渲染。
  - `cloud` 用简单的 ID3 解析代替 music-metadata，只认 MP3 的标题、歌手、专辑；其他格式用文件名。
  - `scrobble_v1` 的日志压缩用 gzip（上游在 Node 22.15+ 用 zstd，旧版本同样用 gzip）。
  - `inner_version` 返回的是移植时的上游版本号。
- `/music` 页面：
  - 连续获取播放地址受「网络与限流」的公开请求或个人请求额度约束。
  - 游客身份下会员歌曲只能试听或不能播放，页面会提示并跳过。
  - 单曲联想可能没有封面。歌曲、歌单、专辑和歌手详情提供结构化数据。
- 等级划分（public / personal / write / auth / admin）是按接口名推断再人工修正的，见 `ncm-apis.json`，可以自行调整。

## 合规提醒

这是非官方接口。把站长会员账号共享给所有访客，违反网易云的服务条款，同一账号被大量 IP 调用也可能触发风控甚至封号。建议只用在个人站点、小范围使用。
