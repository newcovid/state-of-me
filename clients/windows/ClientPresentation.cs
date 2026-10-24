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
}
