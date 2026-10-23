# 0.5 Android 与博客组件

Android：四个区域（状态、连接、分享、歌词），8dp 间距基准、20dp 页面边距、16sp 输入内容、至少 48dp 操作按钮、系统深浅色、字体缩放和表单 labelFor。按钮保留触摸反馈，密钥遮挡；状态页显示用户可理解的状态，不直接暴露内部歌词状态枚举。现有凭据/允许列表/授权保留，新安装不再默认指定 Poweramp。

设计依据：已读取 frontend-design skill，并参考 Android 官方可访问性规范 https://developer.android.com/guide/topics/ui/accessibility/views/apps-views 。这是原生 Views 实现，不宣称已使用 Material 组件库或通过全部应用质量认证。

Halo：在当前主题公共布局 header 后接入独立 Web Component；首页和文章页均可见。组件代码和片段可供其他主题及博客复用。公开投影仅 music，不公开应用、歌词、令牌或 QQ 绑定。Halo 官方扩展文档：https://docs.halo.run/developer-guide/theme/template-tag 。

验收：APK 构建与签名通过；14 项 Python 合约测试通过；首页和文章页返回 200 且包含组件；首页浏览器截图确认。Android 真机布局、大字体和长时采集仍需用户验收，未以编译结果替代视觉检查。

维护：当前 Halo 接入为主题模板改动，主题覆盖升级可能丢失，应重应用 integrations/halo/embed.html；备份路径记录在私有部署记录。后续可迁移为 Halo 注入或独立插件。
