# 内嵌歌词与身份绑定（0.2）

## 词幕 Poweramp 提供器参考

本次阅读用户本地 LyricProvider 源码版本 `60a4343682fbad36fdce42422ac91d7f3426ffce` 中的 `poweramp-music/.../xposed/PowerAmp.kt`。

流程为：监听 TRACK_CHANGED，缓存当前曲目路径，转换为 SAF URI，通过文件描述符调用 TagLib，查找 LYRICS 属性，以 LRC 解析器得到时间轴；挂接 MediaSession.setPlaybackState 后用播放位置/速度/单调时钟更新歌词位置。这些代码运行于 YukiHook/LSPosed 环境。

State of Me 复用这一设计思路，未复制该实现源码：普通应用使用 Android 标准 MediaSession；用户在系统文件选择器授权目录；本地严格匹配曲目；有界 Java 标签读取器提取内嵌歌词。无需注入 Poweramp 进程，也不读取其私有数据库。支持范围比 TagLib 窄，遇到不支持的格式明确降级，不猜测歌词。

参考：[LyricProvider Poweramp 源码](https://github.com/tomakino/LyricProvider/tree/master/poweramp-music)、[Poweramp 官方 API](https://github.com/maxmpz/powerampapi)。

## 测试与限制

- 合成 MP3 UTF-8/UTF-16、FLAC、M4A 标签回读与强匹配测试。
- 用户设备实际 MP3 样本：读取成功，包含同步时间戳；样本仅在本地忽略目录，未提交、未上传。
- 一个原始 AAC 样本无上述歌词标签，正确返回无歌词；不能将音频解码等同于歌词识别。
- 明确不支持的 ID3 变体、超限或损坏标签不会尝试猜偏移。索引多匹配拒绝选取。
- 目录授权、整库索引、不同歌曲播放匹配和 ROM 后台仍需实机体验；解析器的 JVM 样本测试不等于已验收所有 Android 目录提供者。

身份测试涵盖本人、其他人、实际 @、QQ 号、群主实时查验、群主转让、查询失败、多目标、模糊指代。单人服务仅允许返回所绑定主人的数据，其他主体返回 unbound；工具结果携带 subject_qq，供模型明确归属。
