# State of Me

自托管的个人实时状态发布工具。免 Root Android + Windows → 独立 HTTP API → 网页 / AstrBot。

**0.15 开发预览**：当前快照、白名单过滤、账号与设备隔离、群分享与公开分享独立授权、45 秒失联过期，公开访问默认关闭。

## 能力

- Android 8+：通知访问授权后读取标准 MediaSession 的歌曲、歌手、播放/暂停及进度；可选 UsageStats 分享按名称多选的前台应用，支持白名单、黑名单与关闭。常驻通知、一键暂停，不读取通知正文，不要求 Root、LSPosed 或词幕。
- 歌词：授权音乐目录，在本机索引 MP3/FLAC/M4A 的内嵌歌词，按曲名、歌手、专辑与时长严格匹配。同步标签发送当前句及最多 16 行、未来 60 秒的歌词窗口，读者需单独获得窗口权限；纯文本不猜进度。不访问 Poweramp 私有数据库。
- Windows：WPF UI Fluent 组件、托盘、应用名称多选过滤、标准媒体与目录歌词、电源/空闲/网络/音量/资源，以及可选 WhatPulse 统计；事件合并与 20 秒保活。详见[桌面端使用与验证](docs/DESKTOP-0.10.md)。
- FastAPI + SQLite：按受众投影、重放/乱序保护、隐身和 TTL。Android 播放事件触发更新并保留心跳；网页每 5 秒校准，页面内存缓存歌词并连续推进进度。AstrBot 以真实群事件识别查询对象并校验分享权限；“我”取实际发送者，“群主”实时查询 QQ 角色，未绑定他人不返回主人状态。

- Android 使用统计：屏幕交互／解锁使用时长、解锁次数、应用排行、7／30 天趋势；系统低频补算与独立私有历史接口，群查询与公开卡片分别按汇总、应用排行、小时分布授权。详见 [统计口径与部署](docs/ANDROID-USAGE-0.14.md)。

## 多用户账号

手机和电脑直接在原生客户端注册或登录，自动登记本机，无需网页配对或手填密钥。客户端和网页管理同一账号下的设备与分享范围；账号会话与采集凭据分离。QQ 验证以真实群消息发送者为准，由本人在客户端或网页确认后，为每个群选择字段并保存。公开卡片另选字段，默认全部关闭；媒体、应用、设备状态、诊断与历史统计均可按需公开。见 [0.15 多用户与部署架构](docs/MULTIUSER-0.15.md)。

## 开始

[下载 Android / Windows 开发预览](https://github.com/newcovid/state-of-me/releases)；已有服务只需安装客户端并连接账号。自行搭建请从下方“安装与测试”开始，可用初始化工具生成配套配置。

[0.15 账号与分享](docs/MULTIUSER-0.15.md) · [安装与测试](docs/QUICKSTART.md) · [开发计划](docs/PLAN-0.6.md) · [隐私和限制](docs/PRIVACY.md) · [通用性审查](docs/GENERALITY.md) · [歌词窗口协议](docs/PLAYBACK-API.md)

```powershell
python -m pip install -r server/requirements.txt
python -m pip install httpx
python -m unittest server.test_app -v
dotnet build clients/windows
# 设置 JAVA_HOME（JDK 21）和 ANDROID_HOME，需要 platform 37.0 / build-tools 37.0.0
./clients/android/build.ps1
```

Android 界面使用 Kotlin/Compose 与 Miuix 0.9.4 组件库，采集和歌词模块保留 Java，通过 Gradle Wrapper 构建。详见[组件与构建说明](docs/ANDROID-UI.md)。APK 使用本地生成的开发预览签名；保留私钥以便后续更新，正式发行需要独立签名流程。

Android 0.9.1 使用事件更新、状态去重与 20 秒保活，并支持失败退避、增量应用查询和歌词窗口提前补充。详见[同步策略与验证](docs/ANDROID-SYNC-0.9.1.md)。

桌面端后续开发范围、采集能力矩阵和分阶段验收见[桌面端开发规划](docs/DESKTOP-PLAN.md)。已实现范围与体验入口见 [0.10 桌面端说明](docs/DESKTOP-0.10.md)。

API：`POST /v1/events`、登录后可读的 `GET /v1/accounts/status`、按群授权的 `GET /v1/bot/status/{qq}`、默认关闭的 `GET /v1/public/users/{name}`。机器人和博客都是适配器；机器人停机不影响采集和网页。

MIT License。贡献请附复现步骤和设备版本，不提交令牌、聊天记录、真实活动或私有配置。

Halo 常驻公开组件：[接入说明](integrations/halo/README.md) · [0.15 全字段卡片说明](docs/PUBLIC-WIDGET-0.15.md)。Android 设置按状态、连接、分享、歌词分区，支持系统深浅色。

0.6：博客浮动歌词卡片、Android 输入法 Insets 修复、可选电量/充电/屏幕/锁屏/省电状态（独立受众权限），音乐专辑字段。
