using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Windows.Threading;
namespace StateOfMe;
public sealed class SyncEngine : IDisposable
{
    readonly HttpClient http = new(new HttpClientHandler { AllowAutoRedirect = false }) { Timeout = TimeSpan.FromSeconds(8) };
    readonly Dispatcher dispatcher; readonly DispatcherTimer poll, eventTimer, lyricTimer;
    readonly PublishPolicy policy = new(); readonly WhatPulseSource whatpulse = new(); readonly LyricsIndex lyrics = new();
    public DesktopSources Sources
    {
        get;
    }
    public Settings Config
    {
        get; set;
    }
    bool busy, disposed, pending; long lastAttempt = -2000; long lyricDue = long.MaxValue; JsonObject? lastMusic;
    public long Collections
    {
        get; private set;
    }
    public long Uploads
    {
        get; private set;
    }
    public long Skipped
    {
        get; private set;
    }
    public long Bytes
    {
        get; private set;
    }
    public JsonObject Preview { get; private set; } = new();
    public string Status { get; private set; } = "已暂停";
    public string WhatPulseStatus => whatpulse.Status;
    public string LyricStatus => lyrics.Status;
    public event Action? Updated;
    public SyncEngine(Settings settings, Dispatcher d)
    {
        Config = settings;
        dispatcher = d;
        Sources = new DesktopSources(Request);
        poll = new DispatcherTimer(TimeSpan.FromSeconds(5), DispatcherPriority.Background, async (_, _) => await Tick(), d);
        eventTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(1200) };
        eventTimer.Tick += async (_, _) => { eventTimer.Stop(); await Tick(); };
        lyricTimer=new DispatcherTimer();lyricTimer.Tick+=async(_,_)=>{lyricTimer.Stop();await Tick();};
    }
    public Task RefreshLyrics() => lyrics.Index(Config.MusicDirectory, true);
    public async Task Initialize()
    {
        await Sources.Initialize();
        await lyrics.Index(Config.MusicDirectory);
        await Tick();
    }
    public void Request()
    {
        if (disposed)
            return;
        dispatcher.BeginInvoke(() => { if (!eventTimer.IsEnabled) eventTimer.Start(); });
    }
    public async Task Configure(Settings settings)
    {
        Config = settings;
        Sources.Invalidate();
        policy.Reset();
        whatpulse.Reset();
        await lyrics.Index(settings.MusicDirectory);
        await Tick();
    }
    static string Fingerprint(JsonObject data)
    {
        var copy = (JsonObject)data.DeepClone();
        copy.Remove("seq");
        copy.Remove("observed_at");
        if (copy["music"] is JsonObject m)
        {
            m.Remove("position_ms");
            m.Remove("lyric_window");
        }
        if (copy["app_details"] is JsonObject a)
            a.Remove("active_for_ms");
        if (copy["presence"] is JsonObject p)
            p.Remove("idle_seconds");
        copy.Remove("resources");
        copy.Remove("usage_summary");
        return copy.ToJsonString();
    }
    bool Seek(JsonObject? music, long now)
    {
        if (music == null || lastMusic == null || music["position_known"]?.GetValue<bool>() == false)
            return false;
        var rate = lastMusic["playback_rate"]!.GetValue<double>();
        var expected = lastMusic["position_ms"]!.GetValue<long>() + (now - policy.LastSuccess) * rate;
        var duration = lastMusic["duration_ms"]!.GetValue<long>();
        if (duration > 0)
            expected = Math.Min(duration, expected);
        return Math.Abs(music["position_ms"]!.GetValue<long>() - expected) > 1500;
    }
    public async Task Tick()
    {
        if(disposed)return;
        if(busy){pending=true;return;}
        busy = true;
        try
        {
            var now = Environment.TickCount64;
            var cfg = Config;
            if (now - lastAttempt < 1200 || now < policy.RetryAt)
                return;
            Collections++;
            var data = cfg.Enabled ? Sources.Collect(cfg) : new JsonObject();
            if (cfg.Enabled)
            {
                var stats = await whatpulse.Read(cfg.WhatPulse, cfg.WhatPulsePort);
                if (stats != null)data["usage_summary"] = stats;
                var music = await Sources.Music(cfg);
                if (music != null)
                {
                    if (music["media_kind"]?.ToString() == "music" && music["position_known"]?.GetValue<bool>() != false)
                        lyrics.Enrich(music);
                    data["music"] = music;
                }

            }
            data["hidden"] = !cfg.Enabled;
            Preview = (JsonObject)data.DeepClone();
            if (!cfg.Enabled)
            {
                data = new JsonObject { ["hidden"] = true };
                Preview = new JsonObject { ["hidden"] = true };
            }
            Updated?.Invoke();
            if (string.IsNullOrWhiteSpace(cfg.Endpoint) || cfg.Token.Length < 24 || string.IsNullOrWhiteSpace(cfg.Account))
            {
                Status = "请在设置页登录并连接本机";
                return;
            }
            var key = Fingerprint(data);
            if (!policy.Due(now, key, now >= lyricDue || Seek(data["music"] as JsonObject, now)))
            {
                Skipped++;
                return;
            }
            if (!cfg.Enabled && policy.Key == key)
            {
                Skipped++;
                return;
            }
            if (!Uri.TryCreate(cfg.Endpoint, UriKind.Absolute, out var uri) || uri.Scheme != "https" || !string.IsNullOrEmpty(uri.UserInfo))
            {
                Status = "连接地址必须是 HTTPS";
                return;
            }
            if (!ReferenceEquals(cfg, Config))
                return;
            lastAttempt = now;
            cfg.Sequence = Math.Max(DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), cfg.Sequence + 1);
            cfg.Save();
            data["seq"] = cfg.Sequence;
            data["observed_at"] = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() / 1000.0;
            var json = data.ToJsonString();
            if (Encoding.UTF8.GetByteCount(json) > 16384)
            {
                Status = "快照超过大小限制";
                policy.Fail(now);
                return;
            }
            using var req = new HttpRequestMessage(HttpMethod.Post, cfg.Endpoint.TrimEnd('/') + "/v1/events");
            req.Headers.Authorization = new("Bearer", cfg.Token);
            req.Content = new StringContent(json, Encoding.UTF8, "application/json");
            Uploads++;
            Bytes += Encoding.UTF8.GetByteCount(json);
            try
            {
                using var response = await http.SendAsync(req);
                if (!response.IsSuccessStatusCode)
                {
                    if (response.StatusCode == System.Net.HttpStatusCode.Unauthorized) { cfg.Enabled = false; cfg.ConnectionInvalid = true; cfg.Save(); Status = "连接已失效，请断开旧设备后重新连接"; return; }
                    Status = $"同步失败 HTTP {(int)response.StatusCode}，自动退避";
                    policy.Fail(Environment.TickCount64);
                    return;
                }
                policy.Success(now, key);
                lastMusic = data["music"]?.DeepClone() as JsonObject;
                lyricDue = long.MaxValue;
                lyricTimer.Stop();
                if (lastMusic?["lyric_window"] is JsonObject w)
                {
                    var remaining = w["end_ms"]!.GetValue<long>() - lastMusic["position_ms"]!.GetValue<long>();
                    double rate = lastMusic["playback_rate"]!.GetValue<double>();
                    if (rate > 0)
                        lyricDue = now + (long)Math.Max(1200, remaining / rate - 5000);
                }
                if(lyricDue!=long.MaxValue){lyricTimer.Interval=TimeSpan.FromMilliseconds(Math.Max(1200,lyricDue-Environment.TickCount64));lyricTimer.Start();}
                Status = cfg.Enabled ? $"已同步 {DateTime.Now:HH:mm:ss}" : "已暂停，服务端已清空";
            }
            catch { Status = "连接失败，自动退避；旧状态会过期"; policy.Fail(Environment.TickCount64); }
        }
        catch { Status = "采集失败，稍后重试"; }
        finally { busy = false; Updated?.Invoke();if(pending){pending=false;Request();} }
    }
    public async Task Pause()
    {
        Config.Enabled = false;
        Config.Save();
        policy.Reset();
        while (busy)
            await Task.Delay(50);
        lastAttempt = -2000;
        await Tick();
    }
    public void Dispose()
    {
        disposed = true;
        poll.Stop();
        eventTimer.Stop();
        lyricTimer.Stop();
        Sources.Dispose();
        whatpulse.Dispose();
        http.Dispose();
    }
}
