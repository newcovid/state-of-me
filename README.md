# State of Me

自托管的个人实时状态发布工具。免 Root Android + Windows → 独立 HTTP API → 网页 / AstrBot。

**0.1 开发预览**：当前快照、白名单过滤、设备写入与受众只读凭据分离、45 秒失联过期，公开访问默认关闭。

## 能力

- Android 8+：通知访问授权后读取标准 MediaSession 的歌曲、歌手、播放/暂停及进度；可选 UsageStats 分享白名单前台应用。常驻通知、一键暂停，不读取通知正文，不要求 Root、LSPosed 或词幕。
- 歌词：选择本地 UTF-8 LRC 并绑定精确歌曲名，只发送当前句。无歌词时明确未提供。不自动读取 Poweramp 私有歌词库。
- Windows WPF：白名单前台进程，不读取窗口标题；锁屏/空闲 3 分钟隐藏应用；关闭窗口停止。
- FastAPI + SQLite：按受众投影、重放/乱序保护、隐身和 TTL。网页每 5 秒刷新，令牌仅在页面内存。AstrBot 以真实群事件绑定只读权限。

## 开始

[安装与测试](docs/QUICKSTART.md) · [开发计划](docs/PLAN.md) · [隐私和限制](docs/PRIVACY.md)

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
