# WhatPulse 完整本地 API 接入（0.17）

2026-10-25。Windows 和后端 0.17，Android 无需更换包即可通过动态服务端目录管理新增分享字段。本文描述本地 Client API，不宣称已接入 WhatPulse 网站的历史查询。

## 数据与时间范围

每约 20 秒 GET 本机 `/v1/all-stats`，当前客户端聚合返回 account-totals、unpulsed、realtime、idle、profiles。旧版本遗漏某类别时尝试对应只读 GET；整个聚合端点 404 时回退独立端点。缺失/非法字段省略，失败后退避至最多 5 分钟，不复用上次失败前的快照。不会 POST pulse、open-window 或 profiles/activate。

- 无 account_ 前缀的累计计数属于当前电脑自上次 Pulse 以来；scope=since_pulse 仅描述这部分。
- account_ 是账号累计及排名，随 Pulse 更新，可能包含其他电脑，不可跨设备相加。account_download_mb/account_upload_mb 保留官方接口 MB 单位；本机 download/upload 是字节。
- realtime_ 保留 WhatPulse 原始文本、单位和本地化小数分隔符，通常为其短窗口平均。与 State of Me 相邻采样计算的 *_per_second 分开。
- distance_inches 是英寸，account_distance_miles 是英里。*_formatted 原样保留展示值，不将它们解析后冒充精确原始值。
- whatpulse_last_input 是原始本地 ISO 时间，可能没有时区，禁止默认解释为 UTC。
- profiles 保留 id/name/active/created_at/updated_at。最多 128 项，超出时 profiles_truncated=true；字符串最多 128 字符。Profile 是配置档案，不代表前台应用。
- 首次采样、计数回退、间隔超过 60 秒不产生差值速率。原接口负排名等无效数值省略，保留可用的格式化文本；缺失不是零。

客户端本地 API 响应上限 256 KiB。后端实时事件上限 256 KiB，其余管理请求仍为 16 KiB；历史使用统计上传维持 1 MiB。新增字段默认不加入任何公开或群授权。现有字段授权不扩大。账号私有查询能读取已上传的完整快照，群和博客按字段过滤。

本机已确认 `/v1/all-stats` 没有按日/7日/年度查询能力；不从一个累计快照伪造这些时间段，也不抓取用户私有数据库。

## 完整字段目录

以下 64 项都支持上传、私有查询、独立受众授权和网页展示；单个快照按接口可用性省略部分字段。profiles 是一个整体授权字段，包含上述五个属性。

| 字段 | 名称 |
| --- | --- |
| `usage_summary.source` | 统计来源 |
| `usage_summary.scope` | 统计范围 |
| `usage_summary.sampled_at` | 统计采样时间 |
| `usage_summary.keys` | 按键次数 |
| `usage_summary.clicks` | 点击次数 |
| `usage_summary.scrolls` | 滚动次数 |
| `usage_summary.download` | 下载字节数 |
| `usage_summary.upload` | 上传字节数 |
| `usage_summary.uptime` | 运行时长 |
| `usage_summary.keys_per_second` | 每秒按键 |
| `usage_summary.clicks_per_second` | 每秒点击 |
| `usage_summary.download_bytes_per_second` | 下载速率 |
| `usage_summary.upload_bytes_per_second` | 上传速率 |
| `usage_summary.sample_seconds` | 速率采样窗口 |
| `usage_summary.words` | 未 Pulse 词数 |
| `usage_summary.distance_inches` | 未 Pulse 鼠标移动距离（英寸） |
| `usage_summary.keys_formatted` | 未 Pulse 按键次数（格式化） |
| `usage_summary.account_keys_formatted` | 账号累计按键次数（格式化） |
| `usage_summary.account_keys` | 账号累计按键次数 |
| `usage_summary.clicks_formatted` | 未 Pulse 点击次数（格式化） |
| `usage_summary.account_clicks_formatted` | 账号累计点击次数（格式化） |
| `usage_summary.account_clicks` | 账号累计点击次数 |
| `usage_summary.scrolls_formatted` | 未 Pulse 滚动次数（格式化） |
| `usage_summary.account_scrolls_formatted` | 账号累计滚动次数（格式化） |
| `usage_summary.account_scrolls` | 账号累计滚动次数 |
| `usage_summary.words_formatted` | 未 Pulse 词数（格式化） |
| `usage_summary.account_words_formatted` | 账号累计词数（格式化） |
| `usage_summary.account_words` | 账号累计词数 |
| `usage_summary.download_formatted` | 未 Pulse 下载流量（格式化） |
| `usage_summary.account_download_formatted` | 账号累计下载流量（格式化） |
| `usage_summary.account_download_mb` | 账号累计下载流量（MB，原接口单位） |
| `usage_summary.upload_formatted` | 未 Pulse 上传流量（格式化） |
| `usage_summary.account_upload_formatted` | 账号累计上传流量（格式化） |
| `usage_summary.account_upload_mb` | 账号累计上传流量（MB，原接口单位） |
| `usage_summary.uptime_formatted` | 未 Pulse 运行秒数（格式化） |
| `usage_summary.account_uptime_formatted` | 账号累计运行秒数（格式化） |
| `usage_summary.account_uptime` | 账号累计运行秒数 |
| `usage_summary.distance_formatted` | 未 Pulse 鼠标移动距离（格式化） |
| `usage_summary.account_distance_formatted` | 账号累计鼠标移动距离（格式化） |
| `usage_summary.account_distance_miles` | 账号累计鼠标移动距离（英里） |
| `usage_summary.account_rank_words` | 账号词数排名 |
| `usage_summary.account_rank_words_formatted` | 账号词数排名（格式化） |
| `usage_summary.account_rank_keys` | 账号按键次数排名 |
| `usage_summary.account_rank_keys_formatted` | 账号按键次数排名（格式化） |
| `usage_summary.account_rank_clicks` | 账号点击次数排名 |
| `usage_summary.account_rank_clicks_formatted` | 账号点击次数排名（格式化） |
| `usage_summary.account_rank_scrolls` | 账号滚动次数排名 |
| `usage_summary.account_rank_scrolls_formatted` | 账号滚动次数排名（格式化） |
| `usage_summary.account_rank_distance` | 账号鼠标移动距离排名 |
| `usage_summary.account_rank_distance_formatted` | 账号鼠标移动距离排名（格式化） |
| `usage_summary.account_rank_download` | 账号下载流量排名 |
| `usage_summary.account_rank_download_formatted` | 账号下载流量排名（格式化） |
| `usage_summary.account_rank_upload` | 账号上传流量排名 |
| `usage_summary.account_rank_upload_formatted` | 账号上传流量排名（格式化） |
| `usage_summary.account_rank_uptime` | 账号运行秒数排名 |
| `usage_summary.account_rank_uptime_formatted` | 账号运行秒数排名（格式化） |
| `usage_summary.realtime_keys` | WhatPulse 实时按键次数／秒（原接口文本） |
| `usage_summary.realtime_clicks` | WhatPulse 实时点击次数／秒（原接口文本） |
| `usage_summary.realtime_download` | WhatPulse 实时下载流量速率（原接口文本） |
| `usage_summary.realtime_upload` | WhatPulse 实时上传流量速率（原接口文本） |
| `usage_summary.whatpulse_idle_seconds` | WhatPulse 缺少输入秒数 |
| `usage_summary.whatpulse_last_input` | WhatPulse 最近输入时间（本地时间） |
| `usage_summary.profiles` | WhatPulse Profile 列表 |
| `usage_summary.profiles_truncated` | Profile 列表已截断 |

## 验证

已验证真实本地 API → Windows 归一化 → 后端 Pydantic 校验；当次首个快照有 58 项，首次差值速率尚不可计算，词数原始排名无效被省略。单元测试覆盖五类数据、单位区分、本地化格式、Profile、缺失撤回、非法数值、重置速率与逐字段公开隔离。生产部署另记录实际上传及客户端运行结果。

参考：[WhatPulse Client API](https://whatpulse.org/help/api/client-api)，以及安装客户端的 `/whatpulse-api-spec.json`。

## 生产部署与实机结果

2026-10-25 已部署后端与 AstrBot 0.17.0，Windows 0.17.0 在副屏运行。线上健康检查正常，账号字段目录中有 64 项 WhatPulse 字段。真实桌面上传已读到五类新增数据，客户端原有采集设置保留；公开/群授权与修订表的部署前后摘要一致。AstrBot 日志确认加载 v0.17.0，未向真实 QQ 群发送测试消息。

代码、数据库与代理备份：`/srv/backups/state-of-me-017-20261025-whatpulse`。Caddy 仅增加 `/presence/v1/events` 的 256 KiB 路由；账号等其他请求维持原限额。回滚时同时回退 Windows 客户端、后端代码与插件；本次无数据库迁移，不应为代码回滚覆盖后续账号数据。
