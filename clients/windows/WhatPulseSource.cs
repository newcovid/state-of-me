using System.Globalization;
using System.IO;
using System.Net.Http;
using System.Text.Json.Nodes;
namespace StateOfMe;
public sealed class WhatPulseSource : IDisposable
{
    readonly HttpClient client = new(new HttpClientHandler { UseProxy = false, AllowAutoRedirect = false }) { Timeout = TimeSpan.FromSeconds(2) };
    long last = -20000, retry, previousAt; JsonObject? previous; JsonObject? cached; int failures;
    public string Status { get; private set; } = "未连接";
    public async Task<JsonObject?> Read(bool enabled, int port)
    {
        if (!enabled)
        {
            cached = null;
            Status = "未启用";
            return null;
        }
        var now = Environment.TickCount64;
        if (now < retry)
            return null;
        if (now - last < 20000)
            return cached?.DeepClone() as JsonObject;
        last = now;
        try
        {
            JsonNode? data;
            try { data = await ReadJson(port, "/v1/all-stats"); }
            catch (HttpRequestException ex) when (ex.StatusCode == System.Net.HttpStatusCode.NotFound)
            { data = new JsonObject { ["unpulsed"] = await ReadJson(port, "/v1/unpulsed") }; }
            if (data is not JsonObject) throw new IOException("WhatPulse returned an invalid snapshot");
            // Older API versions omit optional sections from the aggregate endpoint.
            foreach (var pair in new[] { ("account-totals", "/v1/account-totals"), ("unpulsed", "/v1/unpulsed"), ("realtime", "/v1/realtime"), ("idle", "/v1/idle"), ("profiles", "/v1/profiles") })
                if (data[pair.Item1] == null)
                    try { var section = await ReadJson(port, pair.Item2); data[pair.Item1] = (pair.Item1 == "profiles" ? section?["profiles"] : section)?.DeepClone(); } catch { }
            if (data["unpulsed"] == null && data["account-totals"] == null && data["realtime"] == null && data["idle"] == null && data["profiles"] == null) throw new IOException("No WhatPulse data available");
            cached = SummarizeAll(data, previous, (now - previousAt) / 1000.0, DateTimeOffset.UtcNow.ToUnixTimeSeconds());
            previous = (JsonObject)cached.DeepClone();
            previousAt = now;
            Status = "已连接 · 20 秒采样 · " + new[] { "account-totals", "unpulsed", "realtime", "idle", "profiles" }.Count(k => data[k] != null) + "/5 类数据可用";
            failures = 0;
            retry = 0;
            return cached.DeepClone() as JsonObject;
        }
        catch { cached = null; previous = null; retry = now + Math.Min(300000, 5000L << Math.Min(failures++, 6)); Status = "暂不可用，自动重试"; return null; }
    }
    async Task<JsonNode?> ReadJson(int port, string path)
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(3));
        using var response = await client.GetAsync($"http://127.0.0.1:{port}{path}", HttpCompletionOption.ResponseHeadersRead, timeout.Token);
        response.EnsureSuccessStatusCode();
        await using var stream = await response.Content.ReadAsStreamAsync(timeout.Token);
        var bytes = new byte[262145]; int count = 0;
        while (count < bytes.Length)
        {
            int n = await stream.ReadAsync(bytes.AsMemory(count), timeout.Token);
            if (n == 0) break;
            count += n;
        }
        if (count > 262144) throw new IOException("WhatPulse snapshot too large");
        return JsonNode.Parse(bytes.AsSpan(0, count));
    }
    static void CopyNumber(JsonNode? source, string key, JsonObject target, string output, bool integer = true)
    {
        if (source?[key] is not JsonValue raw) return;
        var text = raw.ToString();
        if (integer && long.TryParse(text, NumberStyles.Integer, CultureInfo.InvariantCulture, out var count) && count >= 0 && count <= 9007199254740991) target[output] = count;
        else if (!integer && double.TryParse(text, NumberStyles.Float, CultureInfo.InvariantCulture, out var value) && double.IsFinite(value) && value >= 0 && value <= 9007199254740991) target[output] = value;
    }
    static void CopyText(JsonNode? source, string key, JsonObject target, string output)
    {
        if (source?[key] is JsonValue raw && raw.TryGetValue<string>(out var value) && value.Length <= 128) target[output] = value;
    }
    public static JsonObject SummarizeAll(JsonNode data, JsonObject? previous, double seconds, long sampledAt)
    {
        var unpulsed = data["unpulsed"] as JsonObject;
        var result = Summarize(unpulsed ?? new JsonObject(), previous, seconds, sampledAt);
        CopyNumber(unpulsed, "words", result, "words");
        CopyNumber(unpulsed, "distance_inches", result, "distance_inches", false);
        foreach (var key in new[] { "keys", "clicks", "scrolls", "words", "download", "upload", "uptime", "distance" })
        {
            CopyText(unpulsed, key + "_formatted", result, key + "_formatted");
            CopyText(data["account-totals"], key + "_formatted", result, "account_" + key + "_formatted");
            if (key != "distance") CopyNumber(data["account-totals"], key, result, "account_" + key + (key is "download" or "upload" ? "_mb" : ""), key is not ("download" or "upload"));
        }
        CopyNumber(data["account-totals"], "distance_miles", result, "account_distance_miles", false);
        foreach (var key in new[] { "keys", "clicks", "scrolls", "words", "distance", "download", "upload", "uptime" })
        {
            CopyNumber(data["account-totals"]?["ranks"], "rank_" + key, result, "account_rank_" + key);
            CopyText(data["account-totals"]?["ranks"], "rank_" + key + "_formatted", result, "account_rank_" + key + "_formatted");
        }
        // Preserve locale/unit-bearing realtime values instead of guessing decimal separators or KB bases.
        foreach (var key in new[] { "keys", "clicks", "download", "upload" }) CopyText(data["realtime"], key, result, "realtime_" + key);
        CopyNumber(data["idle"], "idle_seconds", result, "whatpulse_idle_seconds");
        CopyText(data["idle"], "last_input", result, "whatpulse_last_input");
        if (data["profiles"] is JsonArray profiles)
        {
            var rows = new JsonArray();
            foreach (var item in profiles.Take(128).OfType<JsonObject>())
            {
                var row = new JsonObject();
                CopyNumber(item, "id", row, "id");
                foreach (var key in new[] { "name", "created_at", "updated_at" }) CopyText(item, key, row, key);
                if (item["active"] is JsonValue active && active.TryGetValue<bool>(out var enabled)) row["active"] = enabled;
                rows.Add(row);
            }
            result["profiles"] = rows;
            result["profiles_truncated"] = profiles.Count > 128;
        }
        return result;
    }
    public static JsonObject Summarize(JsonNode data, JsonObject? previous, double seconds, long sampledAt)
    {
        var value = new JsonObject { ["source"] = "whatpulse", ["scope"] = "since_pulse", ["sampled_at"] = sampledAt };
        foreach (var name in new[] { "keys", "clicks", "scrolls", "download", "upload", "uptime" })
            if (long.TryParse(data[name]?.ToString(), out var count) && count >= 0 && count <= 9007199254740991)
                value[name] = count;
        var reset = previous != null && new[] { "keys", "clicks", "scrolls", "download", "upload", "uptime" }.Any(name => long.TryParse(previous[name]?.ToString(), out var oldValue) && long.TryParse(value[name]?.ToString(), out var newValue) && newValue < oldValue);
        if (previous != null && !reset && seconds > 0 && seconds <= 60)
        {
            value["sample_seconds"] = seconds;
            foreach (var pair in new[] { ("keys", "keys_per_second"), ("clicks", "clicks_per_second"), ("download", "download_bytes_per_second"), ("upload", "upload_bytes_per_second") })
                if (long.TryParse(previous[pair.Item1]?.ToString(), out var oldValue) && long.TryParse(value[pair.Item1]?.ToString(), out var newValue))
                    value[pair.Item2] = (newValue - oldValue) / seconds;
        }
        return value;
    }
    public void Reset()
    {
        last = -20000;
        retry = 0;
        cached = null;
        previous = null;
    }
    public void Dispose() => client.Dispose();
}
