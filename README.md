# State of Me

自托管的个人实时状态发布工具。免 Root Android + Windows → 独立 HTTP API → 网页 / AstrBot。

**0.8 开发预览**：当前快照、白名单过滤、设备写入与受众只读凭据分离、45 秒失联过期，公开访问默认关闭。

## 能力

- Android 8+：通知访问授权后读取标准 MediaSession 的歌曲、歌手、播放/暂停及进度；可选 UsageStats 分享按名称多选的前台应用，支持白名单、黑名单与关闭。常驻通知、一键暂停，不读取通知正文，不要求 Root、LSPosed 或词幕。
- 歌词：授权音乐目录，在本机索引 MP3/FLAC/M4A 的内嵌歌词，按曲名、歌手、专辑与时长严格匹配。同步标签发送当前句及最多 16 行、未来 60 秒的歌词窗口，读者需单独获得窗口权限；纯文本不猜进度。不访问 Poweramp 私有数据库。
- Windows WPF：白名单前台进程，不读取窗口标题；锁屏/空闲 3 分钟隐藏应用；关闭窗口停止。
- FastAPI + SQLite：按受众投影、重放/乱序保护、隐身和 TTL。Android 播放事件触发更新并保留心跳；网页每 5 秒校准，页面内存缓存歌词并连续推进进度，令牌仅在页面内存。AstrBot 以真实群事件绑定只读权限和设备主人身份；“我”取实际发送者，“群主”实时查询 QQ 角色，未绑定他人不返回主人状态。

## 开始

[安装与测试](docs/QUICKSTART.md) · [开发计划](docs/PLAN-0.6.md) · [隐私和限制](docs/PRIVACY.md) · [通用性审查](docs/GENERALITY.md) · [歌词窗口协议](docs/PLAYBACK-API.md)

```powershell
python -m pip install -r server/requirements.txt
python -m pip install httpx
python -m unittest server.test_app -v
dotnet build clients/windows
# 设置 ANDROID_HOME，需要 platform 35 / build-tools 36.0.0
./clients/android/build.ps1
```

Android 使用无外部库的原生 Java，JDK/Android SDK 可直接构建，不依赖 Gradle。APK 使用本地生成的开发预览签名；保留私钥以便后续更新，正式发行需要独立签名流程。

API：`POST /v1/events`、`GET /v1/status`、默认关闭的 `GET /v1/public/status`。机器人和博客都是适配器；机器人停机不影响采集和网页。

MIT License。贡献请附复现步骤和设备版本，不提交令牌、聊天记录、真实活动或私有配置。

Halo 常驻公开组件：[接入说明](integrations/halo/README.md)。Android 设置按状态、连接、分享、歌词分区，支持系统深浅色。

0.6：博客浮动歌词卡片、Android 输入法 Insets 修复、可选电量/充电/屏幕/锁屏/省电状态（独立受众权限），音乐专辑字段。
