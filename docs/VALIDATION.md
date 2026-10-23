# 0.1 验证记录

0.2 补充：11 组 Python 测试覆盖服务端与真实事件身份解析、群主转让及 QQ 接口失败；Java 标签测试覆盖 MP3 USLT UTF-8/UTF-16、SYLT 毫秒时间戳、FLAC、M4A、LRC offset 与曲目强匹配。用户设备 MP3 样本本地提取到内嵌同步歌词，未上传样本。系统目录授权及整库索引匹配仍需用户在手机上确认授权后体验。

## 已验证

- 服务端 4 组自动测试：读写权限隔离、重放、隐藏清空、敏感应用过滤、歌词受众隔离、规则变更撤回、TTL、默认关闭公开状态、非法事件。
- WPF .NET 8 编译和发布包生成。
- Android SDK 原生构建，APK v2/v3 签名验证。
- Android 16 实机安装，标准通知访问 + MediaSession 取得 Poweramp 音乐并通过 HTTPS 写入独立服务；只读 API 返回手机音乐字段。未使用 Root。暂停后查询中设备状态清空。
- 独立服务 HTTPS health 200；无凭据私有查询 401；公共状态默认 404。
- AstrBot 插件加载成功，通过真实部署的适配器函数调用只读服务，未发送群测试消息。

## 待用户体验

歌曲播放/暂停/拖动/换曲、实际 LRC 匹配与时间偏移；Windows 前台切换/锁屏；Android UsageStats 与分屏行为；ROM 后台与耗电；真实 QQ 群交互。当前客户端采样 5 秒，歌词可能落后数秒，不是逐字实时字幕。

窗口 UI、探针和短时成功不代表全设备兼容或长期稳定。以最终发布附件的构建结果为准，预览签名不等于正式生产发行。

## 0.3 歌词窗口验证

- 13 项 Python 合约/身份测试通过：权限投影、窗口撤回、暂停锚点、格式限制以及原有身份检查。
- Java 标签解析、时间轴排序/offset/重复时间戳测试通过；Android APK 构建与 v2/v3 签名验证通过。
- Node 播放计算测试通过：速度、暂停、时长上限、时间轴边界和缓存撤回。
- 本地浏览器合成播放验证：轮询间进度和歌词继续推进；停止测试服务后旧卡片清空。
- 服务端健康接口和主站访问正常。Android 0.3 覆盖安装成功。
- 尚未确认真实歌曲整段播放、手动跳转、暂停后的体感；安装更新后请重新打开客户端并开启采集。当前版本仍存在约 0–10 秒加网络耗时的控制同步延迟。

## 0.4 验证

Python 13 项合约/适配测试通过；Node 播放测试通过；Java 限流时间测试通过；Android 构建及 v2/v3 签名验证通过。浏览器合成播放下检查桌面和 390px iframe 布局、连接设置和歌词上下文。实际暂停/跳转耗时、多播放器、后台省电与审美满意度需要用户验证。


## 0.7 context preview (2026-10-24)

- 20 local Python contract/identity/adapter tests passed; JS playback contracts passed.
- Android 0.7.0 compiled, signature verified, and installed over 0.6 on the connected phone.
- Backend health reports 0.7.0; AstrBot startup confirms state_of_me v0.7.0 loaded.
- Backups were taken before replacing backend/config/plugin; unrelated containers remained running.
- Public projection configuration is unchanged. New Android category switches default off.
- Live hardware values and group replies need user testing after reopening/enabling sharing; no QQ messages were sent during verification.
- See ANDROID-CAPABILITIES.md for category coverage, API limitations and opt-in configuration.


## 0.8 UX preview (2026-10-24)

23 Python tests, Java AppFilter cases, JS playback checks, signed Android build. Installed final APK on the connected phone; inspected home/sharing/lyrics/picker screenshots. Backend health 0.8.0 and adapter redeployed with backup. Scope/filter and UX acceptance boundaries are documented in UX-0.8.md. Private app-list screenshots are not published. No actual group message was sent.


## 0.8.1 current-activity fix

29 Python tests and JS playback checks pass; APK compiled, signed and installed. Backend health and AstrBot plugin load report 0.8.1. No group test messages were sent. Current-activity semantics and timing limits are in PLAYBACK-API.md.


## 0.8.2 public status widget

30 Python tests, playback JS tests and widget signal contracts pass. Browser fixture verified playing-to-paused removal without losing basic state; live blog battery/charging and light/dark appearance verified. Public category subfield scopes exclude diagnostics and application duration. See PUBLIC-WIDGET-0.8.2.md.
