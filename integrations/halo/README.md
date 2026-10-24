# Halo / 博客浮动状态组件（0.11）

在 Halo Console → 系统设置 → 代码注入 → 页脚，添加以下完整片段。先在账号页开启所需账号的公开分享，把 `alice` 换成实际账号名：

```html
<state-of-me-status endpoint="/presence/v1/public/users/alice"></state-of-me-status>
<script defer src="/presence/widget.js?v=0.11.0"></script>
```

`endpoint` 必须写在 `state-of-me-status` 元素上，不能写在 script 上。元素先存在，脚本就会复用它；只放 script 会自动创建默认组件，读取 `/presence/v1/public/status`（旧站点范围，不聚合新注册账号）。

每个博客域名必须把同源 `/presence/*` 去掉 `/presence` 前缀后代理到 State of Me。跨域 URL 被组件主动拒绝，因此不能只把脚本 URL 改成另一域名；同源代理配置见 [快速入门](../../docs/QUICKSTART.md)。组件不发送凭据或 Cookie。只通过公开接口取数，不要往网页填任何设备、只读或机器人密钥。

组件默认右下方收起，支持拖动、方向键移动、展开/收起、歌词折叠和恢复位置，跟随博客明暗与文字颜色。基础信息可以包含应用名称、电量、充电/锁屏、电脑空闲和手动状态，具体以账号公开范围和设备实际上传为准。音乐只在正在播放时展示。歌词窗口最多 16 行、未来 60 秒；页面连续推进，5 秒校准，超过有效期清空。

这使用 Halo 自带代码注入扩展点，**不是独立 Java 插件**。配置保存在 Halo 系统设置，升级主题不会覆盖它；更换主题需要新主题支持 `halo:footer`。不要同时保留旧模板插入片段。其他博客只要能插入 HTML 并配置同源代理，也可复用同一组件。

localStorage 仅保存界面偏好（展开、歌词、位置），不保存歌词或个人状态；存储不可用时仍可操作。不同域名分别记忆，窗口缩小时自动限制位置。组件没有远程操作播放器的能力。
