# 0.15 统一账号与分享架构

## 目标与实施顺序

1. 后端统一字段目录与确切叶字段投影，删除旧授权兼容入口，部署时备份并清空旧分享授权；增加账号级 scope_revision 防并发覆盖。
2. 原生账号会话与设备上传凭据隔离，注册／登录后自动登记本机；保留旧设备设置，禁止跨账号接管。
3. Android miuix 和 Windows WPF UI 原生账号／设备／QQ／公开／群授权管理；账号退出与停止采集分别表达。
4. 网页使用同一字段目录和版本校验；公开预览读取真正匿名接口。
5. 博客卡片支持所有当前字段、多设备及历史汇总，分类折叠，默认收起与主题跟随继续保留；未授权字段不由前端推断补齐。
6. AstrBot 按真实消息身份和群 scope 查询；历史与实时工具均复用投影，公开权限不扩大群权限。
7. 回归、全新授权初始化、新部署、实机与桌面验证；备份后部署，发布内部分发包。

## 审查安排

- 第一轮：独立账号安全审查、独立新手流程审查，已完成。重点采纳凭据隔离、默认不授权、并发版本、元数据选择、历史与实时分离。
- 第二轮：安全代理复审后端及两端会话；父代理交叉检查各端流程和字段完整性。
- 第三轮：独立复审修复后的初始化／撤权／账号切换和测试证据，再确认发布边界。

## 原生接口约定

- POST `/v1/accounts/login` 或 `register`：`{name,password,client:"native"}`，返回 `name,access_token,expires_in`，注册额外返回 recovery。
- 独立账号会话表，30 天有效，无自动续期；只保存 token 哈希。原生端 Keystore／DPAPI 加密，绑定完整 HTTPS 服务地址。
- 管理 API 接受账号 Bearer；有 Authorization 就不得回退 Cookie；网页 Cookie 写操作保持 CSRF／Origin 校验。
- POST `/v1/accounts/devices/enroll`：`{label,platform,existing_token,enrollment_id}`，旧 token 只能复用同一账号设备；无效或他人旧 token 返回409。新登记返回 `token,device,account,label`。
- GET info 返回 `scopes:[{key,label,group,description}]`；GET me 返回 `public_fields,group_scopes,scope_revision`。
- POST public `{fields,expected_revision}`；POST groups/scope `{group_id,fields,expected_revision}`；旧 revision 返回409，客户端保留草稿。
- 公开／群“全选”保存当时的确切字段集合，不存通配符；新增字段默认关闭。历史 summary/apps 分开；设备标签可选。

## 用户流程

选择服务 → 原生注册／登录 → 自动登记设备 → 本机采集选择及系统权限 → 开始分享。

可选 QQ 验证：客户端生成一次性命令 → QQ 群发送 → 回客户端确认真实身份 → 选择向本群分享的范围。无需网页往返。

账号级分享标明“影响本账号所有设备”；本机采集／上传仍按设备配置。普通群友不需要部署服务；维护者教程与普通用户教程分开。

设备登记使用按账号隔离的请求 ID 做重试幂等，成功后客户端清除请求 ID。撤销后的 ID 永久失效，重新登记产生新的随机凭据。旧配对码 API 与旧整类授权接口已删除。

## 已完成的审查与验证

三轮独立审查及发布前并发复核完成。修复登记撤销后凭据复活、旧入口绕过版本、账号快照范围/版本不一致、恢复密码并发发行旧会话、Android 离线退出和历史小时分布遗漏。原生写操作在事务内再次鉴权。后端与机器人69项回归通过，另有Web/Windows/Android专项验证。

真实部署先在线备份并短暂停止State of Me，清空旧受众范围，删除废弃pairings/policy_migrations表，再启动0.15。服务健康后单独重启AstrBot；其他服务未改动。客户端首次进入账号管理需要正常登录，设备上传凭据不具备账号管理能力。
