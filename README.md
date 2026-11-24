# 网抑云 · Halo 插件

网抑云为 Halo 提供独立的网易云音乐页。访客可以浏览公开内容、搜索和试听，登录 Halo 并绑定自己的网易云账号后可使用个人曲库。音乐页由 Folia Web 适配。博客主题可以接入站点小播放器、文章音乐卡片和公开音乐数据。

[项目源码](https://github.com/sky121666/halo-plugin-ncm) · [版本与下载](https://github.com/sky121666/halo-plugin-ncm/releases) · [问题反馈](https://github.com/sky121666/halo-plugin-ncm/issues) · [从源码构建](#构建与开发)

## 安装与快速开始

1. 准备 Halo 2.27.0 或更高版本。从版本页取得插件 JAR，或按下文从源码构建。
2. 在 Halo 的「插件」上传并启用网抑云。在「工具 → 网抑云 → 音乐首页」设置名称、图标、搜索和播放，打开 `/music` 验证。可在 Halo 菜单添加这个地址。
3. 在「精选歌单」粘贴网易云歌单链接、预览游客可播放情况，再发布。发布顺序用于音乐页的「探索」。
4. 在音乐首页或播放器的「音质」面板打开登录弹窗，登录 Halo，再扫码绑定网易云。绑定和解绑也可在「个人中心 → 网抑云」操作。

### 谁能做什么

| 身份 | 点歌与浏览 | 曲库与配置 |
| --- | --- | --- |
| 游客 | 以网易云游客身份搜索、浏览公开内容和获取音源。歌曲可完整播放、试听或暂不可播，以网易云返回为准 | 保存在当前浏览器的偏好 |
| 已登录 Halo，尚未绑定 | 继续以网易云游客身份播放 | 可以同步自己的播放器偏好，绑定后可打开个人曲库 |
| 已绑定网易云 | 音乐页与小播放器按本人网易云权限播放 | 推荐、私人 FM、我的歌单、喜欢的歌、收藏、最近播放、排行与云盘按本人权限读取 |
| 有站长绑定的 Halo 用户 | 本人可在音乐页使用自己在后台完成的绑定。也可以另行绑定个人账号，个人绑定优先 | 有对应 Halo 角色才可管理站点内容和插件。站点默认偏好由超级管理员发布 |

公开接口、主题公开数据和游客取址固定使用网易云游客身份。启用更多接口只改变可调用范围。站长在后台绑定的账号用于本人播放、个人曲库和受控的后台歌单核验。

同一 Halo 账户在前台、个人中心和后台使用同一个登录用户标识。网易云绑定以 Halo 服务器认证的用户标识定位，修改显示名称不影响绑定。参见 [Halo 用户与权限](https://docs.halo.run/guide/use/users)。

## 前台使用

| 页面 | 用途 |
| --- | --- |
| `/music` | 歌单、推荐、探索、专辑和 Folia 播放器。游客可通过「浏览公开音乐」进入探索 |
| `/music/search` | 点唱台，支持歌曲、歌单、专辑和歌手搜索。后台可单独打开联想 |
| `/music/library` | 个人曲库。可新建或删除自己的歌单，批量增删曲目，查看喜欢的歌和播放记录 |
| `/music/playlist/{id}`、`/music/album/{id}`、`/music/artist/{id}`、`/music/song/{id}` | 可直接访问和分享的 Folia 详情 |
| `/uc/ncm` | Halo 个人中心的账号绑定、绑定状态和解绑 |

「探索」可展示本站精选、网易云允许游客查看的站长歌单和排行。歌曲、歌单、专辑和歌手的详情提供标题、canonical 与结构化数据。搜索和个人曲库设置 `noindex`。站点地址、分享标题、图标和封面来自 Halo 与音乐页设置。

播放页保留 Folia 的歌词、音量、播放模式、控制面板和快捷键。进入个人曲库可管理红心与收藏。公共曲目可按播放器设置恢复队列与位置，私人曲目留在当前 Folia 会话。

## 后台管理

| 入口 | 用途 |
| --- | --- |
| 工具 → 网抑云 → 音乐首页 | 页面标识、SEO 与分享、搜索联想、探索内容、每批曲目数和自动续播 |
| 工具 → 网抑云 → 精选歌单 | 核验游客访问、试听抽查、设置展示标题与简介、保存草稿、发布和排序 |
| 工具 → 网抑云 → 账号与游客 | 绑定站长网易云、读取游客会话状态 |
| 工具 → 网抑云 → 工具设置 | 站点小播放器、音乐 AI 和角色范围 |
| 插件 → 网抑云 → 管理入口 | 依权限直达产品设置、接口与维护 |
| 插件 → 网抑云 → 维护 | 查看设备、凭据与代理状态，重新初始化游客或重置设备 |
| 插件 → 网抑云 → 接口管理 | 通用 API 的开放范围、缓存、请求兼容与游客只读调试 |
| 插件 → 网抑云 → 网络与限流 | 出站代理、可信反代、请求额度、主题查询缓存和上传大小 |

「网抑云管理」用于站长绑定与精选歌单。「网抑云接口调试」用于游客只读调试，也控制有权限用户的音乐页开发者入口。技术设置使用 Halo 插件管理权限。超级管理员可在音乐页快速打开后台设置。

### 开启站点小播放器

1. 确认音乐页已开启，并发布一张游客可访问的精选歌单。
2. 在该歌单点击「配置小播放器」，或从「工具设置 → 站点小播放器」直接配置。启用后保存歌单和默认悬浮位置。
3. 在博客首页打开黑胶按钮。点击封面、歌名或歌手可以进入当前歌曲的歌词页。

首次安装时小播放器处于关闭状态。首次展开载入所选歌单的前 100 首。展开面板本身只加载队列，使用 Swup 接入的主题到需要播放时再加载 Folia。访客可以拖动位置，登录时使用自己的位置设置。整页跳转可恢复公开队列和进度，自动播放依赖浏览器授权。接入 Swup 4 的主题可共用同一播放实例。

主题挂载位置、明暗跟随、CSS 变量和 Swup 接入见 [站点小播放器接入规范](docs/站点小播放器接入.md)。

### 音乐 AI

在「工具设置 → 音乐 AI」配置总开关和允许的 Halo 角色。功能依赖已启用且有默认语言模型的 AI Foundation，支持版本范围为 `>=1.4.0 <2.0.0`。满足条件的已登录用户在音乐页可使用 AI 歌词分词和歌曲配色。

- **分词**在「设置 → 歌词 → 分词」启用自动处理。也可通过快捷操作进入「歌词分词调整」。
- **配色**在「设置 → 外观 → 歌曲主题」选择 AI 推断，或在播放页「控制」面板生成当首歌的配色。
- 分词发送需处理的歌词行。配色发送歌名或最多 2,000 字的歌词给站点配置的模型。生成结果留在浏览器。

旧音乐 AI 扩展的设置可迁入主插件，主插件已保存的选择优先。升级后可在「工具设置 → 音乐 AI」核对开关和角色。

### 偏好与缓存

- Folia 偏好即时应用。Halo 登录用户的个人同步默认开启，已保存的开关选择优先。联网后同步外观、播放、歌词等允许的字段。
- 超级管理员更改可发布设置后，在设置窗口底部选择「保存为站点默认」。默认配置供游客与沿用站点默认的用户读取，也可恢复上一版。
- 歌曲缓存可在「存储与备份 → 缓存详情」开启、统计和清理。歌词、封面、媒体、分析数据和播放会话由浏览器管理。Halo 保存配置字段，歌曲缓存的开关属于配置。
- 音频缓存复用前会核验可用性，游客试听、私人曲目和需要会员权限的曲目继续在线取址。开启自动过渡时的缓存提示与缓存详情控制同一个开关。

## 给主题和应用开发者

### 主题模板与文章卡片

Halo 主题可用 `ncmFinder` 提供的 Thymeleaf 数据。启用插件时才能使用该变量。ID 按字符串传递，列表可按接口传入分页参数。

```html
<th:block th:if="${pluginFinder.available('plugin-ncm')}">
  <th:block th:with="song=${ncmFinder.song('186016')}">
    <a th:if="${song != null}" th:href="${'/music/song/' + song.id}" th:text="${song.name}"></a>
  </th:block>
</th:block>
```

| 数据 | 方法 |
| --- | --- |
| 歌曲与歌词 | `song(id)`、`songs(ids)`、`lyric(id)`、`playUrl(id, level)` |
| 歌单、专辑、歌手 | `playlist(id, limit)`、`playlistTracks(id, offset, limit)`、`album(id)`、`artist(id, limit)` |
| 搜索 | `search(keyword, type, page, size)`、`suggest(keyword)`、`hotKeywords(limit)` |
| 站长已公开的数据 | `siteProfile()`、`sitePlaylists(limit)`、`siteWeekRank(limit)`、`siteAllRank(limit)` |
| 图片 | `thumbnail(url, size)` |

`limit` 通常最多 200。Finder 结果默认缓存 600 秒，读取失败时主题可按空值处理。`playable` 和 `availability` 用于提示，点击时的真实取址决定能播放到哪个版本。详情数据结构与默认值见 [NcmFinder 契约](src/main/java/cn/sky/ncm/finder/NcmFinder.java) 和 [返回模型](src/main/java/cn/sky/ncm/finder/vo)。

在 Halo 编辑器的「插入」菜单可添加歌曲、专辑和歌单卡片。音乐页关闭时，卡片可打开对应的网易云页面。

### HTTP 接口

| 前缀或路径 | 用途 |
| --- | --- |
| `/apis/api.ncm.halo.run/v1alpha1/public/{name}` | 游客公开读取，支持范围由「接口开放」控制 |
| `/apis/api.ncm.halo.run/v1alpha1/personal/{name}` | 本人网易云绑定的读取或授权写入 |
| `/apis/api.ncm.halo.run/v1alpha1/meta/apis` | 接口清单及 `open`、`debuggable` 等字段 |
| `/apis/uc.api.ncm.halo.run/v1alpha1/binding` | 本人的绑定状态、扫码、手机绑定及解绑 |
| `/apis/uc.api.ncm.halo.run/v1alpha1/me/-/` | 个人曲库、收藏、搜索推荐、最近播放和云盘。歌单曲目由 `playlists/{id}/tracks` 处理 |
| `/apis/console.api.ncm.halo.run/v1alpha1/` | 站长绑定、设备、精选发布、调试、音乐页、接口开放和工具设置 |
| `/apis/api.ncm-ai.halo.run/v1alpha1/` | 登录用户的 `GET capabilities/-`、`POST themes` 和 `POST segmentations` |
| `/music/play/{id}`、`/music/lyric/{id}`、`/music/search/suggest` | 同源播放取址、歌词和搜索联想 |
| `/music/card/{type}/{id}` | 文章音乐卡片读取 |

接口目录含 459 个移植条目，其中 456 个可执行。常用只读清单与实际授权见 [OpenApis](src/main/java/cn/sky/ncm/api/OpenApis.java) 和 [Halo 角色模板](src/main/resources/extensions/role-templates.yaml)。调试台使用游客身份、独立额度和脱敏响应。写操作使用 Halo 同源会话，并要求相应接口方法及 `X-NCM-Request: 1`。

`GET /music/play/{id}?format=json&level=standard` 返回 `status`、`url`、`identity`、`level`、`br` 和可选试听范围。试听起止是毫秒。`identity` 为 `guest` 或 `user`。省略 `format=json` 时成功取址会返回 302。音源缺失、上游故障和限流分别用 404、503、429 表示。请求的 `fresh=1` 可在音频加载失败后跳过已有的取址缓存。

公开详情可加 `format=json`，歌单曲目支持 `limit` 和 `offset`。探索首页的站点精选数据可从 `/music?format=json` 读取。另有播放器、歌曲、歌单等响应形状定义，详见 [同源页面路由](src/main/java/cn/sky/ncm/page/MusicPageRouter.java) 和 [播放器路由](src/main/java/cn/sky/ncm/page/PlayerRouter.java)。

控制台与个人中心的 TypeScript 客户端位于 `ui/src/api/generated/`。运行中的 Halo 可通过 Swagger UI 查看 **Extension API for plugin-ncm**。动态接口参数随网易云接口而异，调用时按实际接口传递。

### 配置 API

| 方法 | 权限 |
| --- | --- |
| `GET /config-defaults/{clientId}` | 公开读取已发布默认 |
| `GET /my-configs/{clientId}`、`PUT /my-configs/{clientId}` | 当前 Halo 账户的配置 |
| `PUT /config-defaults/{clientId}`、`POST /config-defaults/{clientId}/restore` | 超级管理员发布或恢复站点默认 |

上表前缀为 `/apis/api.ncm.halo.run/v1alpha1`。写入携带 `schemaVersion`、从读取接口获得的 `revision`、可保存的 `config`。版本冲突返回 409。Folia 客户端标识为 `folia`，小播放器为 `mini-player`。字段清单、同源请求示例和新客户端登记方法见 [配置同步快速接入](docs/配置同步快速接入.md)。

## 安全与运行

- **游客身份**由插件初始化并定期刷新。先取 xeapi 公钥，再注册网易云游客。公开音乐数据和接口按游客权限读取。
- **账号凭据**加密后保存在 Halo Secret。服务器依据当前 Halo 登录会话决定能读取哪些绑定。站长帐号在后台完成绑定，个人账号在音乐页或个人中心完成绑定。
- **加密密钥**可通过 `NCM_SECRET_KEY` 或 `NCM_SECRET_FILE` 配置。默认持久化到 `{Halo 工作目录}/plugin-ncm/secret.key`。备份数据库时一并保管密钥。密钥丢失后需要重新绑定。
- **出站代理**在「网络与限流」配置。需要认证时选择存有代理用户名和密码的 Halo Secret。可信反代识别关系到站点入口的访客 IP 计算。
- **请求额度**按路由组计数。公开通用 API 默认每 IP 每分钟 120 次，个人与写入默认每 Halo 账号每分钟 120 次，搜索和联想分别为 20 和 60 次。播放取址用公开或个人额度的两倍，后台游客只读调试每操作者每分钟 30 次。首页及部分详情目前依赖缓存和上游访问控制，相关额度覆盖见 [路由实现](src/main/java/cn/sky/ncm/page/MusicPageRouter.java)。
- **上传额度**由「网络与限流」「缓存与上传」管理，单文件默认上限为 100 MB。通用上传接口要求本人绑定、开放对应写接口和 `multipart/form-data`。可用操作与参数见 [上传接口](src/main/java/cn/sky/ncm/api/more/UploadApis.java)。
- **升级兼容**支持旧账号、设备和代理凭据迁入 Secret。旧的公开访问身份、站长代播音质和调用方 Cookie 设置在新版读取时会忽略。最近播放和个性化推荐留在绑定用户的个人功能里，旧主题的对应 Finder 方法返回空列表。

## 构建与开发

需要 JDK 21、Node.js 24 和 pnpm 10。仓库包含 Gradle 9.4.0 Wrapper 和生成的 API Client。

```bash
git clone https://github.com/sky121666/halo-plugin-ncm.git
cd halo-plugin-ncm
./gradlew build
```

产物在 `build/libs/plugin-ncm-*.jar`。构建包括 Java 和控制台测试、Vue 类型检查、Folia 适配测试及静态资源打包。Folia 的固定提交与源码校验值见 [upstream.json](frontstage/folia/upstream.json)，适配代码在 `frontstage/folia/adapter/`，小播放器在 `frontstage/mini-player/`。

本地开发可按下列入口操作：

| 任务 | 命令 |
| --- | --- |
| 运行 Java 测试 | `./gradlew test` |
| Vue 单元测试与类型检查 | 在 `ui/` 运行 `pnpm test:unit --run` 和 `pnpm type-check` |
| 使用 Halo DevTools 监听源码 | `./gradlew watch -Pport=8092`，需要 Docker |
| 从运行的 Halo 生成客户端 | 按 `build.gradle` 的 `haloPlugin.openApi` 设置使用已有服务，执行 `./gradlew generateApiClient -x test` |
| 对照上游加密结果 | 克隆上游指定版本并安装依赖后，执行 `node tools/gen-vectors.js /path/to/api-enhanced > src/test/resources/vectors.json`，再执行 `./gradlew test` |

同步上游模块前固定版本，再用 `python3 tools/convert.py /path/to/api-enhanced/module src/main/resources/ncm-apis.json` 更新描述表并复核新增接口等级。构建与 API Client 的具体配置见 [build.gradle](build.gradle)。

## 兼容范围与已知限制

- 游客的试听和完整播放由网易云返回。部分曲目受版权、音质或会员限制。
- 普通主题的整页导航会重新建立媒体实例。会话恢复和自动续播受浏览器策略及主题接入方式影响。
- `register_checktoken_v2`、`register_neapikey`、`song_url_match` 三个上游接口未移植。部分 v2 验证令牌调用沿用 v3，听歌打卡压缩当前使用 gzip。
- AI 功能取决于 AI Foundation 和配置的模型。客户端能力探测返回可用时显示相应入口。
- 接口限流是单 Halo 进程内的独立计数。手机号绑定与短信发送依赖网易云侧限制。

## 开源来源与许可

| 项目 | 用途 | 许可证 |
| --- | --- | --- |
| [Folia Web](https://github.com/folia-ce/folia-web/tree/095e7f84738e1a366be2dc0fec3960cbac1793dd) | 音乐页、播放器、歌词与交互 | [AGPL-3.0](licenses/folia-web.AGPL-3.0.txt) |
| [NeteaseCloudMusicApiEnhanced](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/tree/v4.41.1) | 网易云请求协议、接口描述与 Java 移植参考 | [MIT](licenses/api-enhanced.MIT.txt) |
| [Halo](https://github.com/halo-dev/halo) | 插件运行平台、开发工具与组件 | [GPL-3.0](https://github.com/halo-dev/halo/blob/main/LICENSE) |
| [node-qrcode](https://github.com/soldair/node-qrcode/tree/v1.5.4) | 绑定弹窗的二维码渲染 | [MIT](licenses/node-qrcode.MIT.txt) |

插件自有后端与管理端使用 [MIT](LICENSE)。Folia Web 衍生前端按 AGPL-3.0 提供。插件包内含第三方许可证，音乐页「设置 → 帮助 → 开源项目」可下载对应版本的前端适配源码、补丁和构建说明。网抑云是社区适配插件，服务内容与账号权限由网易云提供。
