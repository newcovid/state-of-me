using System.Text.Json.Nodes;

namespace StateOfMe;

public static class ClientPresentation
{
    public static string CollectionSummary(Settings settings)
    {
        var parts = new List<string>();
        if (settings.AppMode == "blacklist") parts.Add("前台应用（排除 " + settings.Apps.Count + " 项）");
        else if (settings.AppMode == "whitelist" && settings.Apps.Count > 0) parts.Add("前台应用（仅 " + settings.Apps.Count + " 项）");
        if (settings.Music && settings.Players.Count > 0) parts.Add("音乐与歌词");
        if (settings.Presence) parts.Add("活跃状态");
        if (settings.Device) parts.Add("电量与电源");
        if (settings.Network) parts.Add("网络");
        if (settings.Audio) parts.Add("音量");
        if (settings.Resources) parts.Add("系统资源");
        if (settings.Display) parts.Add("显示环境");
        if (settings.WhatPulse) parts.Add("WhatPulse");
        return parts.Count == 0 ? "尚未选择要采集的信息。请前往采集页选择并保存，也可以只设置临时状态。" : "已保存的采集范围：" + string.Join("、", parts) + "。";
    }

    public static string Statistics(Settings settings, JsonNode? stats)
    {
        if (!settings.WhatPulse) return "未启用 WhatPulse 统计。需要时可在采集页开启。";
        if (!settings.Enabled) return "同步已暂停；恢复同步后更新 WhatPulse 统计。";
        if (stats == null) return "正在等待 WhatPulse 数据。若持续没有结果，请查看采集页的 WhatPulse 连接状态。";
        var scope = stats["scope"]?.ToString() == "since_pulse" ? "自上次 Pulse 以来" : "当前统计周期";
        return "WhatPulse：按键 " + (stats["keys"]?.ToString() ?? "未提供") + " 次，点击 " + (stats["clicks"]?.ToString() ?? "未提供") + " 次。" + scope + "的累计量，并非今日用量。";
    }
    static readonly Dictionary<string, string> PulseLabels = new()
    {
        ["words"] = "未 Pulse 词数",
        ["distance_inches"] = "未 Pulse 鼠标移动距离（英寸）",
        ["keys_formatted"] = "未 Pulse 按键次数（格式化）",
        ["account_keys_formatted"] = "账号累计按键次数（格式化）",
        ["account_keys"] = "账号累计按键次数",
        ["clicks_formatted"] = "未 Pulse 点击次数（格式化）",
        ["account_clicks_formatted"] = "账号累计点击次数（格式化）",
        ["account_clicks"] = "账号累计点击次数",
        ["scrolls_formatted"] = "未 Pulse 滚动次数（格式化）",
        ["account_scrolls_formatted"] = "账号累计滚动次数（格式化）",
        ["account_scrolls"] = "账号累计滚动次数",
        ["words_formatted"] = "未 Pulse 词数（格式化）",
        ["account_words_formatted"] = "账号累计词数（格式化）",
        ["account_words"] = "账号累计词数",
        ["download_formatted"] = "未 Pulse 下载流量（格式化）",
        ["account_download_formatted"] = "账号累计下载流量（格式化）",
        ["account_download_mb"] = "账号累计下载流量（MB，原接口单位）",
        ["upload_formatted"] = "未 Pulse 上传流量（格式化）",
        ["account_upload_formatted"] = "账号累计上传流量（格式化）",
        ["account_upload_mb"] = "账号累计上传流量（MB，原接口单位）",
        ["uptime_formatted"] = "未 Pulse 运行秒数（格式化）",
        ["account_uptime_formatted"] = "账号累计运行秒数（格式化）",
        ["account_uptime"] = "账号累计运行秒数",
        ["distance_formatted"] = "未 Pulse 鼠标移动距离（格式化）",
        ["account_distance_formatted"] = "账号累计鼠标移动距离（格式化）",
        ["account_distance_miles"] = "账号累计鼠标移动距离（英里）",
        ["account_rank_keys"] = "账号按键次数排名",
        ["account_rank_keys_formatted"] = "账号按键次数排名（格式化）",
        ["account_rank_clicks"] = "账号点击次数排名",
        ["account_rank_clicks_formatted"] = "账号点击次数排名（格式化）",
        ["account_rank_scrolls"] = "账号滚动次数排名",
        ["account_rank_scrolls_formatted"] = "账号滚动次数排名（格式化）",
        ["account_rank_distance"] = "账号鼠标移动距离排名",
        ["account_rank_distance_formatted"] = "账号鼠标移动距离排名（格式化）",
        ["account_rank_download"] = "账号下载流量排名",
        ["account_rank_download_formatted"] = "账号下载流量排名（格式化）",
        ["account_rank_upload"] = "账号上传流量排名",
        ["account_rank_upload_formatted"] = "账号上传流量排名（格式化）",
        ["account_rank_uptime"] = "账号运行秒数排名",
        ["account_rank_uptime_formatted"] = "账号运行秒数排名（格式化）",
        ["realtime_keys"] = "WhatPulse 实时按键次数／秒（原接口文本）",
        ["realtime_clicks"] = "WhatPulse 实时点击次数／秒（原接口文本）",
        ["realtime_download"] = "WhatPulse 实时下载流量速率（原接口文本）",
        ["realtime_upload"] = "WhatPulse 实时上传流量速率（原接口文本）",
        ["whatpulse_idle_seconds"] = "WhatPulse 缺少输入秒数",
        ["whatpulse_last_input"] = "WhatPulse 最近输入时间（本地时间）",
        ["profiles"] = "WhatPulse Profile 列表",
        ["profiles_truncated"] = "Profile 列表已截断",
        ["account_rank_words"] = "账号词数排名",
        ["account_rank_words_formatted"] = "账号词数排名（格式化）",
        ["source"] = "统计来源",
        ["scope"] = "本机累计范围",
        ["sampled_at"] = "采样时间（Unix 秒）",
        ["keys"] = "未 Pulse 按键次数",
        ["clicks"] = "未 Pulse 点击次数",
        ["scrolls"] = "未 Pulse 滚动次数",
        ["download"] = "未 Pulse 下载字节数",
        ["upload"] = "未 Pulse 上传字节数",
        ["uptime"] = "未 Pulse 运行秒数",
        ["keys_per_second"] = "本次采样平均按键／秒",
        ["clicks_per_second"] = "本次采样平均点击／秒",
        ["download_bytes_per_second"] = "本次采样平均下载字节／秒",
        ["upload_bytes_per_second"] = "本次采样平均上传字节／秒",
        ["sample_seconds"] = "平均速率采样窗口（秒）",
    };
    public static string WhatPulseDetails(JsonNode? stats, int? sectionIndex = null)
    {
        if (stats is not JsonObject data) return "尚无数据。请查看采集页的 WhatPulse 连接状态。";
        var sections = new (string Title, Func<string, bool> Includes)[]
        {
            ("本机未 Pulse 累计", k => !k.StartsWith("account_") && !k.StartsWith("realtime_") && !k.StartsWith("whatpulse_") && !k.StartsWith("profiles") && !k.EndsWith("per_second") && k != "sample_seconds"),
            ("WhatPulse 账号累计与排名（随 Pulse 更新，可能包含其他电脑）", k => k.StartsWith("account_")),
            ("实时速率（WhatPulse 原始格式）", k => k.StartsWith("realtime_")),
            ("本次采样的平均速率", k => k.EndsWith("per_second") || k == "sample_seconds"),
            ("空闲与 Profile", k => k.StartsWith("whatpulse_") || k.StartsWith("profiles"))
        };
        var lines = new List<string>();
        foreach (var section in sectionIndex.HasValue ? sections.Skip(sectionIndex.Value).Take(1) : sections)
        {
            var entries = data.Where(p => p.Value != null && section.Includes(p.Key)).ToList();
            if (entries.Count == 0) continue;
            lines.Add(section.Title);
            foreach (var pair in entries)
            {
                if (pair.Key == "profiles" && pair.Value is JsonArray profiles)
                {
                    lines.Add(profiles.Count == 0 ? "暂无 Profile" : string.Join("\n", profiles.Select(p =>
                        (p?["name"]?.ToString() ?? "未命名") + " · ID " + p?["id"] + (p?["active"]?.GetValue<bool>() == true ? " · 当前启用" : " · 未启用") + "\n  创建：" + p?["created_at"] + "；更新：" + p?["updated_at"])));
                }
                else lines.Add((PulseLabels.GetValueOrDefault(pair.Key, pair.Key)) + "：" + (pair.Key == "scope" ? "自上次 Pulse 以来" : pair.Value!.ToString()));
            }
            lines.Add("");
        }
        return string.Join("\n", lines);
    }
}
