# 从零安装 State of Me 0.15

## 只使用别人已搭好的服务

获取维护者提供的 **0.15 开发预览** 安装包（公开发行包以 [GitHub Releases](https://github.com/newcovid/state-of-me/releases) 实际发布为准）：Android 安装 `state-of-me-0.15.0.apk`（Android 8+）；Windows 10/11 x64 下载并解压 `state-of-me-windows-0.15.0-win-x64.zip`，运行 `StateOfMe.exe`，自包含包无需另装 .NET。更新 Windows 前从旧版托盘正常退出，再运行新版本。

向维护者索取 HTTPS 服务地址，在客户端的账号入口填写地址，直接注册或登录。新注册先保存恢复码，再添加本机；已有账号登录自动登记或复用本人设备。选择本机应用、播放器和可选信息并开始分享后，再为群或公开访客选择分享字段。QQ 验证、找回密码和设备管理都可在客户端完成，网页是可选的另一管理入口。见 [多用户使用说明](MULTIUSER-0.15.md)。客户端默认不填写服务器。0.15 不再提供旧版短码设备配对入口，需将客户端、后端和插件一并升级。

## 自建服务：Linux + Docker Compose

前提：Git、Python 3、Docker Engine 和 Compose 插件；域名已解析到服务器，HTTPS 代理可访问 80/443。以下命令从仓库根目录执行。推荐首先使用独立域名，QQ 和博客均可稍后接入。

```sh
git clone https://github.com/newcovid/state-of-me.git
cd state-of-me
python3 deploy/init_config.py --url https://status.example.com
```

**将示例 URL 替换为自己的地址。** 已有 QQ 群时可添加 `--group 123456789`，多个群重复该参数。工具会生成：

- `deploy/config.json`：启用账号注册，公开默认关闭，自动填写正确 `origin`。
- 指定群时另生成 `deploy/astrbot-state-of-me.json`：与后端配套的独立机器人服务凭据。无需手工复制或生成群密钥。

工具拒绝覆盖已有配置。已有实例应保留账号数据库与配置，不要重初始化。生成文件不应提交 Git；工具只输出文件路径，不打印凭据。

让非 root 容器可以读取配置和写数据库：

```sh
mkdir -p deploy/data
sudo chown 10001:10001 deploy/data
sudo chown root:10001 deploy/config.json
sudo chmod 640 deploy/config.json
docker compose -f deploy/compose.yaml up -d --build
docker compose -f deploy/compose.yaml ps
curl --fail http://127.0.0.1:8080/health
```

应返回 `ok: true` 与版本号，容器随后显示 healthy。端口默认只绑定宿主机回环地址。8080 被占用时使用 `SOM_PORT=18080 docker compose -f deploy/compose.yaml up -d --build`，并同步修改代理目标。升级、重建时也必须传入同一个 `SOM_PORT`，否则会回到默认 8080；可在部署 shell 中 `export SOM_PORT=18080` 后统一执行后续命令。

### HTTPS：代理直接运行在宿主机

Caddy 独立域名示例（替换域名）：

```caddyfile
status.example.com {
    handle /v1/usage {
        request_body {
            max_size 1MiB
        }
        reverse_proxy 127.0.0.1:8080
    }
    handle {
        request_body {
            max_size 16KiB
        }
        reverse_proxy 127.0.0.1:8080
    }
}
```

若已有站点且要放在 `/presence/`，初始化时 URL 应为 `https://example.com/presence`，在该站点现有 Caddy 配置中加入以下路由。`handle_path` 会剥离 `/presence` 前缀：

```caddyfile
redir /presence /presence/ 308
handle_path /presence/* {
    handle /v1/usage {
        request_body {
            max_size 1MiB
        }
        reverse_proxy 127.0.0.1:8080
    }
    handle {
        request_body {
            max_size 16KiB
        }
        reverse_proxy 127.0.0.1:8080
    }
}
```

代理示例依据 Caddy 官方 [handle_path](https://caddyserver.com/docs/caddyfile/directives/handle_path) 与 [request_body](https://caddyserver.com/docs/caddyfile/directives/request_body) 文档。

后端没有硬编码部署前缀。客户端、AstrBot `url` 必须包含你实际使用的 `/presence`；`accounts.origin` 只包含 `https://example.com`，初始化工具已自动处理。不要缓存账号、设备登记或状态响应。`POST /v1/usage` 为历史统计批量上传，允许 1 MiB；其余请求限制 16 KiB。不要把整个站点的限额统一提高。

**如果 Caddy 本身在容器里**，`127.0.0.1` 是代理容器自己。请将代理和 State of Me 加入同一个 Docker 网络，代理到 `state-of-me:8080`，而非照抄宿主机目标。不要把明文写入端口直接开放到公网。

### 第一次验收

1. 在 Android 或 Windows 的原生账号入口填写服务地址，注册第一个账号并保存恢复码。
2. 添加本机，确认账号与设备列表；设备上传凭据由服务签发，无需用户复制。完成后默认暂停。
3. 选择至少一个应用或播放器并开始分享，客户端状态及可选网页账号页应显示自己的设备。
4. 新账号的公开链接应返回未开启公开；选择具体公开字段并保存后才可查看。
5. 第二个账号应看不到第一个账号的设备。网页撤销设备后，该客户端提示连接失效。

无 QQ 群时注册、设备和公开页面完全可用；账号页会说明暂未开放群绑定。

## Android 权限与歌词

应用和播放器都可按名称多选、白名单/黑名单或关闭。默认白名单为空，因此刚连接成功还不会分享应用和音乐。黑名单范围基于保存时读取的应用列表，安装新应用后重新打开列表保存。

“分享 → 所需权限”仅对未授权且需要的功能显示操作按钮；音乐通知访问、应用使用情况是 Android 特殊权限，需要进入系统页选择 State of Me。返回后自动显示已授权。运行通知支持系统权限弹窗，无法再次弹窗时引导系统设置。已授权项不会重复要求授权。厂商省电策略仍可能终止后台，必要时由用户在系统设置允许后台运行；无需 Root，无自动开机启动。

歌词页选择整个音乐文件夹，一次授予读取权限，本机索引内嵌歌词，音乐原文件不上传。支持 MP3 USLT/毫秒 SYLT、FLAC Vorbis 歌词、M4A ©lyr；严格匹配标题、歌手、专辑（若提供）、时长。同名重复歌曲不猜测，纯文本不伪造时间轴。音乐目录授权失效时会提示重新选择。

## Windows

原生账号登录并登记后在分享页选择应用，在媒体页选择播放器。可选托盘常驻、登录 Windows 时启动、活跃/空闲、电源、网络、声音、资源、屏幕和 WhatPulse。WhatPulse 未安装或 API 未启用不会阻止其他采集；仅开启该项时访问 `127.0.0.1` 的配置端口。凭据使用 Windows 当前用户 DPAPI 保存，不要求每次启动重新输入。

关闭窗口保留托盘运行；托盘“退出”会停止并清空分享。详细能力见 [桌面端说明](DESKTOP-0.10.md)，当前连接步骤以本页为准。

## AstrBot：可选 QQ 接入

本版支持 **aiocqhttp / OneBot QQ 平台**。先使 AstrBot 与 QQ 适配器正常收发消息，再复制 `integrations/astrbot` 到 AstrBot 的插件目录，文件夹名为 `state_of_me`。

初始化时指定 `--group` 后，把生成的 `deploy/astrbot-state-of-me.json` 复制到 AstrBot 实际数据目录，命名为 `state-of-me.json`；只让 AstrBot 服务账户读取。插件默认读取容器路径 `/AstrBot/data/state-of-me.json`。非默认安装路径通过 AstrBot 进程环境变量 `SOM_ADAPTER_CONFIG=/实际路径/state-of-me.json` 指定。宿主机路径与容器内路径不要混用。

配套配置的结构如下，**SAME_SECRET 两处必须相同；不要使用字面占位符部署**。初始化工具已代你完成：

后端 `config.json` 中：

```json
"accounts": {
  "registration": true,
  "max_users": 100,
  "origin": "https://status.example.com",
  "bot_groups": {"123456789": {"token": "SAME_SECRET"}}
}
```

AstrBot `state-of-me.json`：

```json
{
  "url": "https://status.example.com",
  "groups": {
    "123456789": {"bot_token": "SAME_SECRET"}
  }
}
```

重载插件；若人格使用工具白名单，在目标群人格启用 `get_my_status` 和 `get_usage_history`。真实“我”、@对象和群主由插件/QQ API 解析。群友在客户端或网页的“QQ 群分享”输入群号并生成 `/状态绑定 XXXXXXXX`，在相应群发送，再回到同一管理入口刷新、核对真实 QQ 和群号并确认。新群确认后范围为空，必须选择字段并保存后机器人才可读取。`/状态绑定` 使用精确匹配，可直接发送，无需额外唤醒前缀。客户端和网页均允许输入群号，服务端仍校验此群已配置接入。

添加或移除群需同步维护两份私有配置。不要把机器人服务凭据提供给群友；他们只需要自己的账号与一次性 QQ 验证命令。其他平台尚需另做身份适配。

## 博客与升级维护

Halo 或其他博客见 [完整组件嵌入示例](../integrations/halo/README.md)，可以只展示指定账号已经公开的状态。

0.15 为开发预览架构升级，不支持旧设备短码配对、旧大类权限请求和旧客户端自动兼容。维护者应同步更新后端、客户端、AstrBot 和卡片脚本；既有实例先备份并在副本验证，不能把升级当作数据迁移保证。

升级前备份配置和数据库。小型部署可选择短暂停机备份，保证 SQLite 一致性：

```sh
backup="som-backup-$(date +%Y%m%d-%H%M%S)"
mkdir -m 700 "$backup"
docker compose -f deploy/compose.yaml stop state-of-me
sudo cp -a deploy/config.json deploy/data "$backup/"
# 如已接入机器人，另行私密备份 AstrBot 的 state-of-me.json。
git pull --ff-only
docker compose -f deploy/compose.yaml up -d --build
curl --fail http://127.0.0.1:8080/health
```

需要不中断采集时，改用 SQLite online backup API，不要只复制正在写入的 `state.db` 而遗漏 WAL。回滚前保留升级后新增账号的数据；不能无检查覆盖成旧数据库。

这是开发预览：不含邮箱/OAuth、自助删号、管理后台、Linux/macOS 采集器，也未完成所有手机厂商的权限与耗电测试。
