# 安装与测试

## 服务端

1. 复制 `server/config.example.json` 到 `deploy/config.json`。给每台设备、每个受众生成不同的高熵令牌，至少 32 随机字节，例如 `python -c "import secrets; print(secrets.token_urlsafe(32))"`。示例占位符不可用于生产。
2. 配置设备 `apps`、`players` 白名单，歌词默认关闭，需要时设 `lyrics: true`。读者的 `fields` 独立控制 `app`、`music`、`lyric`。两层都允许才能看到。配置文件仅部署账户可读。
3. 创建数据目录并让 UID 10001 可写：Linux 上 `mkdir -p deploy/data && sudo chown 10001:10001 deploy/data`。
4. `docker compose -f deploy/compose.yaml up -d --build`，在已有 HTTPS 反向代理后接入 `127.0.0.1:8080`。反向代理限制请求体 16KB。不要开放明文写入端口。
5. 浏览器打开 HTTPS 地址，输入只读令牌。公开分享保持关闭，明确决定要公开哪些设备/字段后再改 `public`。

也可设置 `SOM_DB`、`SOM_CONFIG`，运行 `uvicorn server.app:app --host 127.0.0.1 --port 8080 --no-access-log`。本版没有设备管理 UI，编辑配置即可撤销令牌、修改白名单或 enabled；下一请求即生效。

## Android

安装 APK 后填写 HTTPS 地址和手机专用写入令牌。系统“通知访问”页面启用 State of Me，再点击保存开始。可选开启使用情况访问，填写需要分享的应用包名（逗号分隔）；空列表不分享应用。服务端也需允许同样包名。界面显示最近识别到的前台包，仅存本地，便于配置。

播放器默认 Poweramp 包名 `com.maxmpz.audioplayer`。其他播放器可在客户端和服务端两处添加。允许常驻通知；ROM 省电策略可能停止后台服务，需在系统设置允许后台运行。没有自动开机启动。

点击“授权音乐目录并索引内嵌歌词”，通过 Android 系统目录选择器选音乐目录。只授予读取权限，不需要 Root。等待索引完成，播放目录中的歌曲；按标题、歌手、专辑（播放器提供时）、时长±2秒匹配，多个匹配文件会拒绝选取。更改音乐库后重新索引。撤销按钮会释放目录权限并清除索引。

支持 MP3 ID3v2.3/v2.4 USLT、毫秒制 SYLT；FLAC Vorbis LYRICS/UNSYNCEDLYRICS/SYNCEDLYRICS；M4A/MP4 ©lyr。无时间轴只报告 plain，不发送完整歌词。少数压缩/全局 unsynchronization/extended-header ID3、其他封装尚不支持；不声称全部标签等价于 TagLib。原始 AAC 无标签时不能提取歌词。

可选手动 UTF-8 LRC，按填写标题绑定，属于用户显式选择的兜底，同名版本需自行确认。支持 LRC offset 毫秒偏移；逐字格式暂不支持。

## Windows

运行发行包 `StateOfMe.exe`，需要 .NET 8 Desktop Runtime；填写 HTTPS 地址、电脑写入令牌和进程允许列表，点击开始。名称不含 `.exe`，服务端需使用相同大小写名称。凭据不落盘，每次启动重新填写。窗口需保持运行，暂无托盘和自启动。

## AstrBot

复制 `integrations/astrbot` 为插件目录 `state_of_me`。在 AstrBot 数据目录放置仅服务账户可读的 `state-of-me.json`：

```json
{"url":"https://presence.example.com","groups":{"YOUR_GROUP_ID":{"token":"GROUP_READ_ONLY_TOKEN","owner_qq":"OWNER_QQ","owner_aliases":[]}}}
```

将 OWNER_QQ 替换为实际设备主人的数字 QQ 号，只放私有配置。“我”按当前事件发送者解析；“群主”实时查成员列表的 owner；支持真实 @、QQ 号及明确配置的主人别名。指代不清或多目标会要求明确，不用模型猜测。插件按群注入可信身份说明，不污染其他群人格。

重载插件，在目标群人格工具列表添加 `get_my_status`。工具通过真实事件取得群号，不接受模型指定群号。不会主动发送 QQ 消息；QQ 适配器需已登录在线。

## 首次验收

- 播放 Poweramp，网页出现曲名、歌手和播放状态；暂停、拖动、换曲更新。
- 开启允许应用，网页可见；切换不允许应用、锁屏或暂停后旧应用被移除。
- 手机音乐和电脑应用同时出现，互不覆盖。
- 授权音乐目录或导入匹配 LRC；给网页读者增加 lyric_window 字段权限（同时保留 music、lyric），检查缓存换句、暂停、前后拖动和换曲。更新 APK 后重新开启采集。
- 暂停分享，网页清空该设备；断网/关闭进程，45 秒后过期。
- 无令牌查询、读令牌写入、设备令牌读状态均应失败；公开接口默认 404。
- 群内询问主人状态，答案来自工具；离线应说明无法确认，不猜测。

这是功能闭环测试，不等于已通过长时间耗电、全部 ROM 或应用商店审核。
