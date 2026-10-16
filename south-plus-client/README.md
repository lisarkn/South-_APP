# SouthPlusClient — 南+ 第三方 Android 客户端


社区第三方客户端（非官方，与南+官方无关）。技术方案见 [../South+第三方Android客户端开发方案.md](../South+第三方Android客户端开发方案.md)。

当前状态：**v0.3.3**（「晨雾 Mist」主题：冷调雾灰中性 + 青碧强调、浅深双模式，见 [design/THEME-PROPOSAL.md](../design/THEME-PROPOSAL.md)；搜索修复：站点搜索只在表单 POST 时执行，改为 POST 起查 + sid 模板翻页并校准 `search_page` 选择器；论坛任务修复：解析 `startjob(cid)`，新增「申请任务/领取奖励」按钮，客户端代为执行 `actions=job/job2` AJAX 并刷新列表——均经真机账号端到端验证）。v0.3.2：暖黑罗盘针 adaptive 图标；首页最新/最近回复 feed + 子版块行内嵌套；连接检测 NetworkOnMainThreadException 修复。提供 debug 与启用 R8/资源压缩的 release APK。线程、图片、阅读页、缓存及数据库迁移的改动与验证边界见 [性能优化记录](docs/performance-0.2.3.md)。v0.2.2 的真机校准记录保留在下文。

## 网络连接修复

网络与连接现统一为「当前连接 / 镜像 / DNS 解析」：

- 「检测当前连接」请求当前镜像的真实首页；镜像与 DoH 检测都检查 GET 响应及论坛版块链接，区分 DNS、连接、TLS、超时、HTTP、验证页和维护页。测试不改变配置；「使用最快可用镜像」只选择本次成功加载首页的结果。
- DoH 使用一个持久开关，选择立即生效并在重启后保留。关闭时使用系统 DNS；开启时只使用该 DoH 服务的答案，失败明确报错，不混入系统解析。DNS 内不再做串行 TLS 探活；正常 HTTPS 请求验证证书与域名，并尝试后续 IP。
- 切换 DNS 时，请求绑定新解析器，避免复用旧配置的连接；连接检测使用独立连接池，可取消。读取失败支持相对及绝对站内链接切换镜像，全部失败保留原选择；POST 表单只提交一次。
- DoH 不是代理，无法解决目标线路阻断或站点故障。自定义 DoH 域名的初始解析使用系统 DNS。检测覆盖应用内论坛请求；系统 WebView、外部浏览器与 DownloadManager 使用系统的网络解析。

验证：单元测试、debug APK 构建与 Android Lint；开发机首页 GET 已成功，设备网络及 UI 真机复测待连接 Android 设备后完成。

## 真机排障指南（v0.2.1 新增）

若出现「加载不出来」：打开 **设置 → 网络与连接 → 网络诊断**，里面是最近 60 条记录（HTTP 状态码/大小/耗时/镜像/挑战判定 + PARSE 解析产出条数）。对照处理：

| 现象 | 含义 | 处理 |
| --- | --- | --- |
| 无 HTTP 记录 / IO失败 | 请求根本没到站点（TLS 指纹被 CF 拦、DNS 等） | 换镜像；或反馈日志 |
| HTTP 200 + `挑战:CLOUDFLARE` | 真 CF 盾（站点在 CF 后面，正常页不会误判） | 点重试过盾 |
| HTTP 200 + `挑战:NO_PERMISSION` | 受限版块，游客/未认证账号不可见 | 登录后访问 |
| HTTP 200 + `PARSE ... =0` | 选择器未命中真实皮肤结构 | 复制「最近页面HTML」→ 把对应页的 CSS 结构反馈给开发，改 `selectors.yaml` 即可，无需发版 |
| 全部正常但仍空白 | UI 渲染问题 | 复制全部日志反馈 |

首页/版块/阅读页在「HTTP 200 但解析为 0 条」时不再白屏，会显示明确的解析失败提示 + 「用浏览器打开」/「应用内打开原页」按钮。

## 本次针对真机的修复（v0.2.2，真机抓包校准轮）

1. **CF JSD 注入误判（根因）**：南+全站在 Cloudflare 后面，**每个正常页面**都被注入 JSD 脚本（`/cdn-cgi/challenge-platform/...`、`__CF$cv$params`），旧检测把 `challenge-platform` 当挑战特征 → 所有页面被判为 CF 盾、首页永远显示「站点防护验证中」。已移除正常页特征，只保留挑战页专属特征（Just a moment / cf-chl / jsql_vc / __cf_chl_opt 等）。
2. **加载成功却永远转圈（首页+阅读页）**：`State(page = p)` 漏传 `loading = false`（默认 true），数据到手后 UI 永远停在加载中。旧版本因 CF 误判从未走到成功分支，该潜伏 bug 首次暴露。已改为 `copy(loading = false, ...)`。
3. **选择器真机校准**（`selectors.yaml`，开发机直连抓包验证）：
   - 首页：分区容器实为 `div.t[id^=t_]>table`（非 `table.t`）；描述 `[id^=desc_]`；认证标记 `th:containsOwn(认证论坛)`；在线信息 `td:contains(在线用户)`。校准后 4 分区 / 81 版块全部分组渲染（此前走平铺兜底）。
   - 版块列表：主题锚 `a[id^=a_ajax_]` 优先（行首「打开新窗口」图标链接同为 read.php href 且无文本，会令整行被丢弃）；计数 `td:has(span.s8)`；最后回复 `a[href*='-page-e-']`；置顶 `img[src*='headtopic']`（旧 `img[src*='top']` 会误中 topichot/topiclock）；面包屑 `#breadcrumbs a[href*='thread.php?fid']`。
   - 阅读页：楼层实为 `tr.tr1:has(th[id^=td_])`（外层是 `div.t5.t2`，非 `table.t5`）；正文 `[id^=read_]`；作者 `a[href*='u.php']:has(strong)`（头像锚无文本会令楼层被丢）；时间 `.tiptop span.gray`。真机验证 31/31 楼层完整解析。
4. **权限拒绝页识别**：受限版块对游客返回 200 + 独立错误页（31KB），真实文案有两种——「对不起,本版块为认证版块,您没有权限查看此版块的内容!」和「本版块为正规版块,只有注册会员才能进入!」，均已加入 NO_PERMISSION 特征，现在会提示「需注册/认证会员，请先登录」而非「未解析出帖子列表」。
5. **一键去登录**：版块页/阅读页遇到无权限或未登录（NO_PERMISSION / NOT_LOGGED_IN）时，错误面板直接提供「去登录」按钮跳转登录页；登录成功返回后点「重试」即可加载内容。
6. **「图墙模式」版块修复**（2026-09-30）：图墙 fid（102–106、216–231…）是母版块别名，`thread.php?fid-103` 302 到 `thread_new.php?fid-44`。该新引擎页静态 HTML **只渲染置顶帖**（正常帖走页内 AJAX），且 `fid-N-page-M` 的 302 会丢页码跳回第 1 页 → 症状即「只剩置顶帖、加载更多全是重复」。修复：`HtmlGateway.Ok.finalUrl` 改为 302 落地 URL（原实现返回请求 URL，重写检测永不触发）；`ForumRepository.board` 检测落地为 `thread_new.php` 时改抓母版块经典 `thread.php` 列表（内容与图墙本就重复），翻页随母版块页码走。回归测试 `ThreadNewWallTest`（真实抓包 fixture：thread_new 仅 4 置顶 vs 经典页 50 帖）。

> ✅ v0.2.2 起首页/列表/阅读三页选择器已完成真机抓包校准并端到端验证；若站点改版，直接改 `app/src/main/assets/selectors.yaml` 即可修复（无需改代码）。搜索/短消息/收藏等登录态页面仍待登录后校准。

## v0.2.1 真机修复记录（历史）

1. **挑战误判收紧**：游客浏览公开帖时页面常带「您尚未登录」文案，旧逻辑会整页误判为需登录；现在 NOT_LOGGED_IN 必须同时出现登录表单（password 输入框）或 302 落到 login.php；NO_PERMISSION 只保留错误页专属长短语；index.php 抓取关闭登录检测。
2. **解析通用兜底**：分区表/列表行/楼层选择器整体不命中时，自动降级为「全页扫描版块链接 / 帖子链接 / 最内层表格」，保证改版皮肤也能给出可用内容并留 PARSE 日志。
3. **崩溃隐患**：置顶帖重复出现导致的 LazyColumn 重复 key 崩溃（解析去重 + 位置 key）；分区重名 key 冲突。
4. **可见性**：DiagLog 诊断日志 + 网络诊断屏 + 复制原始 HTML（校准选择器的关键通道）。

## 技术栈

Kotlin 2.0 / Jetpack Compose (Material 3) / OkHttp / JSoup / Coil / Room / DataStore / Navigation-Compose / Custom Tabs，minSdk 26 · targetSdk 35 · AGP 8.7.3 · Gradle 8.9。

## 已实施模块（对照方案）

| 方案章节 | 实现 | 位置 |
| --- | --- | --- |
| §3.3 限速硬约束 | 论坛域串行 + 1.5–3.0s 随机抖动；站外图独立车道（并发 2、短间隔） | `core/network/RateLimitInterceptor.kt` |
| §6.1.1 请求管线 | UA/Referer/Accept 拦截器 + 限速 + 图床 per-host 覆写 | `core/network/ForumHttpClient.kt` |
| §2.2 / §6.1.2 多镜像 | 内置 6 镜像 + 远程清单合并；读取失败在 45 秒总预算内逐个尝试；首页 GET 检测与手动选用 | `core/network/MirrorManager.kt` |
| 首页 feed（v0.3.1） | 「最新发表」= rss.php 全站最新 200 帖（游客可见、90s 内存缓存，失败静默隐藏）；「最近回复」= index.php 各版块最后回复汇总按时间排序；子版块收进主版块行内小链接，不再平铺 | `core/parser/RssParser.kt`、`core/parser/PageParsers.kt IndexParser`、`ui/home/HomeScreen.kt` |
| §6.1.3 挑战识别 | CF 盾 / 未登录 / 无权限 / 维护 四类检测；CF 弹 WebView 过盾后 Cookie 回灌并重试 | `core/network/ChallengeDetector.kt`、`core/webview/WebViewActivity.kt` |
| §2.4 / §6.1 会话 | 持久化 CookieJar（`*_winduser`）、退出清空、静默重登（记住密码时） | `core/network/PersistentCookieJar.kt`、`core/session/SessionManager.kt` |
| §6.2 登录 | 通用表单镜像（回填全部原始隐藏域）+ 验证码图点击刷新 + 密码预处理配置驱动（none/md5/md5_upper）+ 凭据 EncryptedSharedPreferences | `core/parser/FormParser.kt`、`core/session/SessionManager.kt`、`ui/login/` |
| §6.3 选择器配置化 | 全部选择器/正则集中于 `assets/selectors.yaml`（候选列表按序尝试）+ schema_version 缓存失效 + 远程热修（默认关闭） | `core/config/`、`assets/selectors.yaml` |
| §6.4.1–3 内容 | 楼层流 ContentBlock（文本/图/引用/付费墙/附件/资源卡）、表情识别、引用折叠、分页 | `core/parser/ReadParser.kt`、`ui/reader/` |
| §6.4.4 LinkRouter | 站内跨镜像改写路由、magnet/ed2k/thunder 交系统、外链 Custom Tabs | `core/links/LinkRouter.kt`、`core/links/LinkOpener.kt` |
| §6.4.5 资源卡 | 网盘链接+提取码识别（host 库与正则可配置热修）、打开前自动复制提取码 | `core/links/ResourceExtractor.kt`、`ui/components/Common.kt` |
| §6.4.6 图床 | 直链内联、移动网络点击加载、站外图模糊开关、pixiv 等 Referer 覆写、独立限速 | `assets/selectors.yaml image_hosts`、`ui/reader/` |
| §6.5 互动 | 回复/编辑（表单镜像提交 + 编辑回填）、收藏加/删/列表、短消息收/发/读、轻量 BBCode 工具条 | `core/data/ForumRepository.kt`、`ui/{reader,favor,pm}/` |
| §6.6 付费贴 | PaidWall 卡片（售价+余额文案）→ 二次确认 → 镜像购买表单提交 → 强刷解锁正文 | `ui/reader/ReaderScreen.kt` |
| §6.7 下载 | 系统 DownloadManager + 会话 Cookie/Referer 头、种子 MIME、`Downloads/SouthPlus/` | `core/download/AttachmentDownloader.kt` |
| §6.9 存储 | Room（页面缓存 LRU 500MB/历史/已读/屏蔽）+ DataStore（设置）+ 加密凭据 | `core/database/`、`core/settings/` |
| §6.10 设置 | 深色模式、字号、低流量、镜像选择/自定义/测速、屏蔽规则（用户/关键词）、清缓存、更新检查、免责声明 | `ui/settings/` |
| §10 测试 | 解析快照测试（6 份合成 fixture）、限速时序（MockWebServer）、镜像故障切换、挑战分类、CookieJar 往返、LinkRouter/资源卡、版本比较 | `app/src/test/` |

UI 共 10 屏：首页 / 版块列表 / 阅读器 / 搜索 / 登录 / 私信 / 收藏 / 历史 / 用户空间 / 设置，底部 4 Tab（消息含未读角标）。

## 与方案的偏差（记录在案）

1. **单模块工程**：方案 §4.3 的 Gradle 多模块简化为 `:app` 单模块 + 包边界（`core/*` 与 `ui/*` 严格分层），便于快速迭代；后续可按包边界平移成多模块。
2. **Hilt → ServiceLocator**：`AppGraph` 手工装配，接口不变，规模变大后再引入 Hilt。
3. **暂未实施**（方案 Phase 3）：消息轮询 WorkManager、签到探测、全屏图集查看器。
4. **fixture 为合成页面**：环境不可直连站点，快照测试基于 PHPWind 8.x 通用结构合成，真机校准后应替换为真实抓包样本（方案附录 B）。

## 本机环境（WSL2）

| 组件 | 位置 |
| --- | --- |
| Android SDK | `/opt/android-sdk`（platform-tools、platforms;android-35、build-tools;35.0.0） |
| Gradle 8.9 | `/opt/gradle/gradle-8.9` |
| 环境变量 | `/etc/profile.d/android-sdk.sh`（ANDROID_HOME + PATH） |

构建源全部走国内镜像（本机直连 mavenCentral / services.gradle.org 超时）：

- Maven 依赖：阿里云 `maven.aliyun.com`（google / public / gradle-plugin 三仓，`settings.gradle.kts` 已配置，官方源兜底）
- Gradle 发行版：腾讯镜像（`gradle/wrapper/gradle-wrapper.properties`，CI 可改回官方地址）

## 构建与测试

```bash
cd south-plus-client
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease  # app/build/outputs/apk/release/app-release.apk
./gradlew :app:testDebugUnitTest # 54 个单元测试
python3 scripts/check-db-migration.py # v1→v2 SQL 迁移检查
```

## 真机调试（WSL2 无 /dev/kvm，不跑模拟器）

- **ADB over WiFi（推荐）**：手机与 Windows 同网 → 手机开"无线调试" → `adb connect <手机IP>:<端口>` → `gradle installDebug`
- **USB**：Windows 侧 `usbipd` 转发，或直接在 Windows 侧 Android Studio 打开本目录构建

## 首次真机跑通后的 Phase 0 校准清单（方案附录 B 摘要）

1. 登录：确认表单字段全名、验证码 img URL、密码是否 JS 预处理（改 `login_page.password_transform`）；
2. 逐项核对 `selectors.yaml`：index/thread/read/login/pm/favor 的候选选择器是否命中，未命中就**追加候选**（按序尝试机制保证不破坏已有规则）；
3. 确认各镜像 Cookie 是否互通（`PersistentCookieJar` 按 domain 归档，必要时逐镜像登录）；
4. 用小号验证回复/购买/私信的真实 POST 参数（表单镜像策略应原样携带隐藏域 token）；
5. 抓一份真实 HTML 替换 `app/src/test/resources/fixtures/` 中的合成样本，跑 `gradle :app:testDebugUnitTest` 建立真实回归基线。

## 目录速览

```
app/src/main/java/net/southplus/client/
├─ AppGraph.kt               # 手工 DI 装配 + 远程配置低频校验
├─ MainActivity.kt           # 入口（Compose Navigation）
├─ core/model/               # 领域模型（Board/Thread/Post/ContentBlock…）
├─ core/network/             # ForumHttpClient / RateLimit / MirrorManager / ChallengeDetector / HtmlGateway / CookieJar
├─ core/parser/              # 配置化解析器（Index/ThreadList/Read/Login/Pm/Favor/User + 通用表单镜像）
├─ core/config/              # SelectorYaml（极简 YAML 子集）/ SelectorConfig / RemoteConfig
├─ core/database/            # Room（缓存/历史/已读/屏蔽）
├─ core/session/             # 登录、静默重登、加密凭据
├─ core/data/                # ForumRepository（缓存/屏蔽/已读编排）
├─ core/links/               # LinkRouter / LinkOpener / ResourceExtractor
├─ core/download/            # DownloadManager 附件下载
├─ core/webview/             # 过盾/兜底 WebView（Cookie 双向同步）
├─ core/update/              # GitHub Releases 更新检查
└─ ui/                       # 10 屏 + 公共组件（HtmlText 链接路由、资源卡、验证码）
app/src/main/assets/         # selectors.yaml（选择器热修）、mirrors.json（镜像清单）
app/src/test/                # 单元测试 + HTML fixtures
```
