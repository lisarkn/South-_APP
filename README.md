# South+ 第三方 Android 客户端

为南+（South Plus，`south-plus.net` / `blue-plus.net`）开发的社区第三方 Android 客户端。

> ⚠️ **非官方声明**：本项目为社区第三方工具，与南+官方无任何隶属或合作关系。不修改站点数据、不绕过任何权限体系；所有请求由用户设备以用户自己的账号发起，凭据仅存本地。请在遵守站点规则与当地法律法规的前提下使用。

## 项目简介

南+是一个基于 PHPWind 深度改版引擎（Pu!mdHd）的大型中文 ACG 综合论坛，**不提供任何官方 API**，Web 界面为纯桌面时代设计，手机浏览体验差。本项目通过 Kotlin + Jetpack Compose 开发原生 Android 客户端：OkHttp 直连论坛站点，用 JSoup 将服务端渲染的 HTML 解析为结构化数据，实现浏览、登录、互动、付费贴购买、附件下载的全功能移动端体验，**不含任何中转服务器**。

## 仓库结构

| 路径 | 内容 |
| --- | --- |
| [`south-plus-client/`](south-plus-client/) | Android 客户端工程（Kotlin 2.0 / Jetpack Compose / Material 3），开发与构建细节见其 [README](south-plus-client/README.md) |
| [`South+第三方Android客户端开发方案.md`](South+第三方Android客户端开发方案.md) | 完整技术开发方案：站点调研、架构设计、模块详细设计、里程碑排期、测试与发布策略 |
| `design/` | UI 主题提案与预览（「晨雾 Mist」主题，未入库） |

## 功能范围

- **浏览**：首页分区、版块帖子列表、帖子阅读（楼层/图片/表情/引用/资源卡）、搜索
- **账号**：登录（含验证码）、会话保活、多镜像共享会话
- **互动**：回复、发新主题、编辑、收藏、短消息收发
- **交易**：付费贴购买（余额 + 确认流程）
- **下载**：帖子附件/种子下载（系统 DownloadManager）
- **辅助**：多镜像竞速切换、Cloudflare 过盾、深色模式、低流量模式、屏蔽规则、离线缓存

明确不做：注册引导、任何绕过权限/认证/付费的能力、iOS / 桌面端、自建中转服务器。

## 技术栈与构建

Kotlin 2.0 · Jetpack Compose (Material 3) · OkHttp · JSoup · Coil · Room · DataStore · Navigation-Compose，minSdk 26 · targetSdk 35。

```bash
cd south-plus-client
./gradlew :app:assembleDebug      # debug APK
./gradlew :app:assembleRelease    # release APK（R8 + 资源压缩）
./gradlew :app:testDebugUnitTest  # 单元测试
```

环境要求与国内镜像配置、真机调试方法详见 [south-plus-client/README.md](south-plus-client/README.md)。

## 文档导航

- **开发方案**（含接口逆向、合规分析、架构与模块设计）：[South+第三方Android客户端开发方案.md](South+第三方Android客户端开发方案.md)
- **客户端实现状态、模块对照表、真机排障指南**：[south-plus-client/README.md](south-plus-client/README.md)
- **主题设计提案**：[design/THEME-PROPOSAL.md](design/THEME-PROPOSAL.md)
- **性能优化记录**：[south-plus-client/docs/performance-0.2.3.md](south-plus-client/docs/performance-0.2.3.md)

## 许可与免责

- 项目仅供学习交流，请自行承担使用风险；因使用本客户端产生的账号问题与站点规则冲突，与项目作者无关。
- 站点改版可能导致客户端功能失效，选择器热修与配置化解析机制可降低发版频率。
