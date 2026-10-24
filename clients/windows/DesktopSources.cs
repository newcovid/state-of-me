using System.Diagnostics;
using System.IO;
using System.Net.NetworkInformation;
using System.Runtime.InteropServices;
using System.Text.Json.Nodes;
using Microsoft.Win32;
using NAudio.CoreAudioApi;
using Windows.Media.Control;
namespace StateOfMe;
public sealed class DesktopSources : IDisposable
{
    [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
    [DllImport("user32.dll")] static extern bool GetLastInputInfo(ref LastInput input);
    [DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr h, out Rect r);
    [DllImport("user32.dll")] static extern IntPtr GetWindow(IntPtr h, uint command);
    [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr h);
    [DllImport("user32.dll")] static extern bool IsIconic(IntPtr h);
    [DllImport("user32.dll")] static extern bool IsZoomed(IntPtr h);
    [DllImport("user32.dll", EntryPoint="GetWindowLongPtrW")] static extern IntPtr GetWindowLongPtr(IntPtr h, int index);
    [DllImport("dwmapi.dll")] static extern int DwmGetWindowAttribute(IntPtr h, int attribute, out int value, int size);
    [DllImport("user32.dll")] static extern IntPtr OpenInputDesktop(uint flags, bool inherit, uint access);
    [DllImport("user32.dll")] static extern bool CloseDesktop(IntPtr h);
    [DllImport("user32.dll")] static extern bool SwitchDesktop(IntPtr h);
    [StructLayout(LayoutKind.Sequential)]
    struct Rect
    {
        public int Left, Top, Right, Bottom;
    }
    [StructLayout(LayoutKind.Sequential)]
    struct LastInput
    {
        public uint size, tick;
    }
    delegate void WinEvent(IntPtr hook, uint evt, IntPtr window, int obj, int child, uint thread, uint time);
    [DllImport("user32.dll")] static extern IntPtr SetWinEventHook(uint min, uint max, IntPtr mod, WinEvent callback, uint pid, uint tid, uint flags);
    [DllImport("user32.dll")] static extern bool UnhookWinEvent(IntPtr hook);
    [DllImport("kernel32.dll")] static extern bool GetSystemTimes(out long idle, out long kernel, out long user);
    [DllImport("kernel32.dll")] static extern bool GlobalMemoryStatusEx(ref Memory data);
    [StructLayout(LayoutKind.Sequential)]
    struct Memory
    {
        public uint size, load; public ulong total, available, page, availablePage, virtualTotal, virtualAvailable, extended;
    }
    [StructLayout(LayoutKind.Sequential)]
    struct PowerStatus
    {
        public byte ac, flags, percent, saver; public uint remaining, full;
    }
    [DllImport("kernel32.dll")] static extern bool GetSystemPowerStatus(out PowerStatus status);
    [DllImport("user32.dll")] static extern IntPtr RegisterPowerSettingNotification(IntPtr hwnd, ref Guid guid, uint flags);
    [DllImport("user32.dll")] static extern bool UnregisterPowerSettingNotification(IntPtr handle);
    readonly List<IntPtr> powerHandles = new(); System.Windows.Interop.HwndSource? windowSource; bool? screenOn;
    public void AttachWindow(IntPtr hwnd)
    {
        windowSource = System.Windows.Interop.HwndSource.FromHwnd(hwnd);
        windowSource?.AddHook(WindowMessage);
        foreach (var value in new[] { "6fe69556-704a-47a0-8f24-c28d936fda47", "e00958c0-c213-4ace-ac77-fecced2eeea5", "a7ad8041-b45a-4cae-87a3-eecbb468a9e1" })
        {
            var guid = Guid.Parse(value);
            var handle = RegisterPowerSettingNotification(hwnd, ref guid, 0);
            if (handle != IntPtr.Zero)
                powerHandles.Add(handle);
        }
    }
    IntPtr WindowMessage(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        if (msg == 0x218 && wParam.ToInt64() == 0x8013)
        {
            var guid = Marshal.PtrToStructure<Guid>(lParam);
            if (guid == Guid.Parse("6fe69556-704a-47a0-8f24-c28d936fda47"))
                screenOn = Marshal.ReadInt32(lParam, 20) != 0;
            contextAt = -60000;
            changed();
        }
        return IntPtr.Zero;
    }
    readonly WinEvent callback; readonly IntPtr hook;
    readonly Action changed;
    readonly List<GlobalSystemMediaTransportControlsSession> subscribed = new();
    readonly Dictionary<GlobalSystemMediaTransportControlsSession, GlobalSystemMediaTransportControlsSessionMediaProperties> mediaCache = new();
    long storageAt = -300000, storageFree, storageTotal;
    GlobalSystemMediaTransportControlsSessionManager? manager;
    bool locked, disposed; long appSince; string current = "", currentLabel = "";
    long lastIdle, lastKernel, lastUser, contextAt = -60000; JsonObject context = new();
    public string MediaCapability { get; private set; } = "initializing";
    public string AppStatus { get; private set; } = "等待采集";
    static int? VisibleRank(IntPtr window)
    {
        int rank=0;var visited=new HashSet<IntPtr>();
        for(var h=GetWindow(window,3);h!=IntPtr.Zero;h=GetWindow(h,3))
        {
            if(!visited.Add(h)||visited.Count>256)return null;
            if(IsWindowVisible(h)&&!IsIconic(h)&&DwmGetWindowAttribute(h,14,out var cloaked,4)==0&&cloaked==0)rank++;
        }
        return rank;
    }
    public DesktopSources(Action onChanged)
    {
        changed = onChanged;
        callback = (_, _, _, _, _, _, _) => changed();
        hook = SetWinEventHook(3, 3, IntPtr.Zero, callback, 0, 0, 0);
        SystemEvents.SessionSwitch += Session;
        SystemEvents.PowerModeChanged += Power;
        NetworkChange.NetworkAddressChanged += NetworkChanged;
    }
    void Session(object sender, SessionSwitchEventArgs e)
    {
        if (e.Reason == SessionSwitchReason.SessionLock)
            locked = true;
        if (e.Reason == SessionSwitchReason.SessionUnlock)
            locked = false;
        changed();
    }
    void Power(object sender, PowerModeChangedEventArgs e)
    {
        contextAt = -60000;
        changed();
    }
    void NetworkChanged(object? sender, EventArgs e)
    {
        contextAt = -60000;
        changed();
    }
    public async Task Initialize()
    {
        try
        {
            manager = await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
            manager.SessionsChanged += Sessions;
            RefreshSessions();
            MediaCapability = "available";
        }
        catch { MediaCapability = "unavailable"; }
    }
    void Sessions(GlobalSystemMediaTransportControlsSessionManager sender, SessionsChangedEventArgs args)
    {
        RefreshSessions();
        changed();
    }
    void RefreshSessions()
    {
        lock (subscribed)
        {
            foreach (var s in subscribed)
            {
                s.PlaybackInfoChanged -= Playback;
                s.MediaPropertiesChanged -= Properties;
                s.TimelinePropertiesChanged -= Timeline;
            }
            subscribed.Clear();
            mediaCache.Clear();
            if (manager == null || disposed)
                return;
            foreach (var s in manager.GetSessions())
            {
                subscribed.Add(s);
                s.PlaybackInfoChanged += Playback;
                s.MediaPropertiesChanged += Properties;
                s.TimelinePropertiesChanged += Timeline;
            }
        }
    }
    void Playback(GlobalSystemMediaTransportControlsSession s, PlaybackInfoChangedEventArgs e) => changed();
    void Properties(GlobalSystemMediaTransportControlsSession s, MediaPropertiesChangedEventArgs e)
    {
        lock (subscribed)
            mediaCache.Remove(s);
        changed();
    }
    void Timeline(GlobalSystemMediaTransportControlsSession s, TimelinePropertiesChangedEventArgs e) => changed();
    public void Invalidate()
    {
        contextAt = -60000;
    }
    public string[] Players()
    {
        lock (subscribed)
            return subscribed.Select(s => s.SourceAppUserModelId).Distinct().ToArray();
    }
    public static string PlayerLabel(string id)
    {
        try
        {
            var name = Windows.ApplicationModel.AppInfo.GetFromAppUserModelId(id)?.DisplayInfo?.DisplayName;
            if (!string.IsNullOrWhiteSpace(name)) return name;
        }
        catch { }
        return id;
    }
    public static List<(string Id, string Label)> Applications()
    {
        var found = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var p in Process.GetProcesses())
            using (p)
            {
                try
                {
                    if (p.MainWindowHandle == IntPtr.Zero)
                        continue;
                    var id = p.ProcessName;
                    var label = p.MainModule?.FileVersionInfo.FileDescription;
                    found[id] = string.IsNullOrWhiteSpace(label) ? id : label;
                }
                catch { }
            }
        return found.OrderBy(x => x.Value).Select(x => (x.Key, x.Value)).ToList();
    }
    public JsonObject Collect(Settings cfg)
    {
        var result = new JsonObject();
        var now = Environment.TickCount64;
        var input = new LastInput { size = (uint)Marshal.SizeOf<LastInput>() };
        long? idle = GetLastInputInfo(ref input) ? unchecked((uint)Environment.TickCount - input.tick) : null;
        var desktop = OpenInputDesktop(0, false, 0x100);
        bool? secured = desktop == IntPtr.Zero ? null : !SwitchDesktop(desktop);
        if (desktop != IntPtr.Zero)
            CloseDesktop(desktop);
        bool isLocked = locked || secured == true;
        bool active = !isLocked && secured == false && screenOn != false && idle.HasValue && idle < cfg.IdleMinutes * 60000L;
        string id = "", label = "";
        var hwnd = GetForegroundWindow();
        AppStatus = cfg.AppMode == "off" ? "应用采集已关闭" : isLocked ? "已锁屏，隐藏前台应用" : secured != false ? "无法确认桌面可访问状态" : screenOn == false ? "屏幕已关闭，隐藏前台应用" : "未取得前台窗口";
        if (!isLocked && secured == false && screenOn != false && cfg.AppMode != "off")
            try
            {
                GetWindowThreadProcessId(hwnd, out var pid);
                using var p = Process.GetProcessById((int)pid);
                id = p.ProcessName;
                if (!cfg.Permitted(id))
                {
                    AppStatus = cfg.AppMode == "blacklist" ? "前台应用被黑名单隐藏" : "前台应用未加入白名单";
                    id = "";
                }
                else
                {
                    label = id;
                    try { label = id == current ? currentLabel : p.MainModule?.FileVersionInfo.FileDescription ?? id; } catch { }
                    AppStatus = "前台应用已采集";
                }
            }
            catch { id = ""; AppStatus = "前台进程暂不可读取"; }
        if (id != current)
        {
            current = id;
            currentLabel = label;
            appSince = now;
        }
        if (id != "")
        {
            result["app"] = id;
            if (cfg.AppAliases.TryGetValue(id, out var alias) && !string.IsNullOrWhiteSpace(alias))
                label = alias;
            result["app_details"] = new JsonObject { ["label"] = label.Length > 120 ? label[..120] : label, ["active_for_ms"] = Math.Min(3600000, now - appSince) };
            var details=(JsonObject)result["app_details"]!;
            details["foreground"]=true;
            details["input_idle"]=idle.HasValue ? (bool?)(idle >= cfg.IdleMinutes*60000L) : null;
            details["z_order"]=VisibleRank(hwnd);
            details["always_on_top"]=(GetWindowLongPtr(hwnd,-20).ToInt64() & 8)!=0;
            details["maximized"]=IsZoomed(hwnd);
            if(GetWindowRect(hwnd,out var rect))
            {
                var bounds=System.Windows.Forms.Screen.FromHandle(hwnd).Bounds;
                details["fullscreen"]=rect.Left<=bounds.Left&&rect.Top<=bounds.Top&&rect.Right>=bounds.Right&&rect.Bottom>=bounds.Bottom;
            }
        }
        if (cfg.Presence)
            result["presence"] = new JsonObject { ["state"] = isLocked ? "locked" : idle == null || secured == null ? "unknown" : active ? "active" : "idle", ["idle_seconds"] = idle / 1000 };
        if (cfg.ManualUntil > DateTimeOffset.UtcNow.ToUnixTimeSeconds() && !string.IsNullOrWhiteSpace(cfg.ManualText))
            result["manual_status"] = new JsonObject { ["text"] = cfg.ManualText, ["expires_at"] = cfg.ManualUntil };
        if (cfg.Device)
        {
            var power = System.Windows.Forms.SystemInformation.PowerStatus;
            bool? battery=power.BatteryChargeStatus==System.Windows.Forms.BatteryChargeStatus.Unknown?null:!power.BatteryChargeStatus.HasFlag(System.Windows.Forms.BatteryChargeStatus.NoSystemBattery);
            result["device_state"] = new JsonObject { ["screen_on"] = screenOn, ["power_save"] = GetSystemPowerStatus(out var nativePower) ? (bool?)(nativePower.saver == 1) : null, ["locked"] = locked ? true : secured, ["battery_present"] = battery, ["battery_percent"] = battery==true&&power.BatteryLifePercent>=0 ? (int?)Math.Clamp((int)(power.BatteryLifePercent * 100), 0, 100) : null, ["charging"] = battery==true ? (bool?)power.BatteryChargeStatus.HasFlag(System.Windows.Forms.BatteryChargeStatus.Charging) : null, ["ac_connected"] = power.PowerLineStatus == System.Windows.Forms.PowerLineStatus.Unknown ? null : (bool?)(power.PowerLineStatus == System.Windows.Forms.PowerLineStatus.Online) };
        }
        if (now - contextAt >= 20000)
        {
            context = new();
            contextAt = now;
            if (cfg.Network)
                try
                {
                    var adapters = NetworkInterface.GetAllNetworkInterfaces().Where(a => a.OperationalStatus == OperationalStatus.Up && a.NetworkInterfaceType != NetworkInterfaceType.Loopback).ToArray();
                    var types = adapters.Select(a => a.NetworkInterfaceType switch { NetworkInterfaceType.Wireless80211 => "wifi", NetworkInterfaceType.Ethernet => "ethernet", NetworkInterfaceType.Ppp or NetworkInterfaceType.Tunnel => "vpn", _ => "other" }).Distinct().ToArray();
                    var profile = Windows.Networking.Connectivity.NetworkInformation.GetInternetConnectionProfile();
                    context["network"] = new JsonObject { ["metered"] = profile == null ? null : (bool?)(profile.GetConnectionCost().NetworkCostType is Windows.Networking.Connectivity.NetworkCostType.Fixed or Windows.Networking.Connectivity.NetworkCostType.Variable), ["validated"] = profile == null ? null : (bool?)(profile.GetNetworkConnectivityLevel() == Windows.Networking.Connectivity.NetworkConnectivityLevel.InternetAccess), ["connected"] = NetworkInterface.GetIsNetworkAvailable(), ["transports"] = new JsonArray(types.Select(x => (JsonNode?)JsonValue.Create(x)).ToArray()) };
                }
                catch { }
            if (cfg.Audio)
                try
                {
                    using var e = new MMDeviceEnumerator();
                    using var d = e.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
                    var form=Convert.ToInt32(d.Properties[PropertyKeys.PKEY_AudioEndpoint_FormFactor].Value);
                    var activeSessions=0;var sessions=d.AudioSessionManager.Sessions;for(int n=0;n<sessions.Count;n++){using var session=sessions[n];if(session.State==NAudio.CoreAudioApi.Interfaces.AudioSessionState.AudioSessionStateActive)activeSessions++;}
                    context["audio"] = new JsonObject { ["default_output_form"]=form switch{1=>"speaker",3=>"headphones",5=>"headset",2=>"line",8 or 9=>"digital",_=>"other"},["active_output_sessions"]=Math.Min(activeSessions,4096), ["music_volume_percent"] = (int)(d.AudioEndpointVolume.MasterVolumeLevelScalar * 100), ["media_muted"] = d.AudioEndpointVolume.Mute, ["connected_output_types"] = new JsonArray(Convert.ToInt32(d.Properties[PropertyKeys.PKEY_AudioEndpoint_FormFactor].Value) switch { 1 => "speaker", 9 => "hdmi", _ => "other" }) };
                }
                catch { }
            if (cfg.Resources)
                try
                {
                    var m = new Memory { size = (uint)Marshal.SizeOf<Memory>() };
                    if (GlobalMemoryStatusEx(ref m))
                    {
                        var r = new JsonObject { ["available_memory_mib"] = (long)(m.available / 1048576), ["total_memory_mib"] = (long)(m.total / 1048576), ["low_memory"] = m.load >= 90 };
                        if (now - storageAt >= 300000)
                        {
                            var disk = new DriveInfo(Path.GetPathRoot(Environment.SystemDirectory)!);
                            storageFree = disk.AvailableFreeSpace / 1048576;
                            storageTotal = disk.TotalSize / 1048576;
                            storageAt = now;
                        }
                        r["available_storage_mib"] = storageFree;
                        r["total_storage_mib"] = storageTotal;
                        if (GetSystemTimes(out var i, out var k, out var u))
                        {
                            var total = k - lastKernel + u - lastUser;
                            if (lastKernel != 0 && total > 0)
                                r["cpu_percent"] = Math.Clamp(100.0 * (total - (i - lastIdle)) / total, 0, 100);
                            lastIdle = i;
                            lastKernel = k;
                            lastUser = u;
                        }
                        context["resources"] = r;
                    }
                }
                catch { }
        }
        foreach (var (key, enabled) in new[] { ("network", cfg.Network), ("audio", cfg.Audio), ("resources", cfg.Resources) })
            if (enabled && context[key] != null)
                result[key] = context[key]!.DeepClone();
        if (cfg.Display)
        {
            var display = new JsonObject { ["monitor_count"] = System.Windows.Forms.Screen.AllScreens.Length, ["remote_session"] = System.Windows.Forms.SystemInformation.TerminalServerSession };
            if (GetWindowRect(hwnd, out var r))
            {
                var b = System.Windows.Forms.Screen.FromHandle(hwnd).Bounds;
                display["fullscreen"] = r.Left <= b.Left && r.Top <= b.Top && r.Right >= b.Right && r.Bottom >= b.Bottom;
            }
            result["display"] = display;
        }
        return result;
    }
    public async Task<JsonObject?> Music(Settings cfg)
    {
        if (!cfg.Music)
            return null;
        GlobalSystemMediaTransportControlsSession[] sessions;
        lock (subscribed)
            sessions = subscribed.ToArray();
        foreach (var s in sessions.OrderByDescending(s => s.SourceAppUserModelId == cfg.PreferredPlayer).ThenByDescending(s => s.SourceAppUserModelId == manager?.GetCurrentSession()?.SourceAppUserModelId))
        {
            try
            {
                if (!cfg.Players.Contains(s.SourceAppUserModelId, StringComparer.OrdinalIgnoreCase))
                    continue;
                var playback = s.GetPlaybackInfo();
                if (playback.PlaybackStatus != GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing)
                    continue;
                GlobalSystemMediaTransportControlsSessionMediaProperties? meta;
                lock (subscribed)
                    mediaCache.TryGetValue(s, out meta);
                if (meta == null)
                {
                    meta = await s.TryGetMediaPropertiesAsync().AsTask().WaitAsync(TimeSpan.FromSeconds(3));
                    lock (subscribed)
                        mediaCache[s] = meta;
                }
                if (string.IsNullOrWhiteSpace(meta.Title))
                    continue;
                var timeline = s.GetTimelineProperties();
                double rate = playback.PlaybackRate ?? 1;
                long duration = (long)timeline.EndTime.TotalMilliseconds;
                long pos = (long)timeline.Position.TotalMilliseconds;
                var delta = (DateTimeOffset.UtcNow - timeline.LastUpdatedTime).TotalMilliseconds;
                if (delta >= 0 && timeline.LastUpdatedTime.Year > 2000)
                    pos += (long)(delta * rate);
                pos = Math.Clamp(pos, 0, duration > 0 ? duration : 604800000);
                string Trim(string x) => x.Length > 200 ? x[..200] : x;
                return new JsonObject { ["media_kind"] = playback.PlaybackType == Windows.Media.MediaPlaybackType.Music ? "music" : playback.PlaybackType == Windows.Media.MediaPlaybackType.Video ? "video" : "unknown", ["position_known"] = duration > 0 && timeline.LastUpdatedTime.Year > 2000, ["title"] = Trim(meta.Title), ["artist"] = Trim(meta.Artist), ["album"] = Trim(meta.AlbumTitle), ["player"] = Trim(s.SourceAppUserModelId), ["playing"] = true, ["playback_status"] = "playing", ["position_ms"] = pos, ["duration_ms"] = Math.Clamp(duration, 0, 604800000), ["playback_rate"] = Math.Clamp(rate, 0, 8), ["track_id"] = Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(meta.Title + "\n" + meta.Artist + "\n" + meta.AlbumTitle))) };
            }
            catch { }
        }
        return null;
    }
    public void Dispose()
    {
        disposed = true;
        windowSource?.RemoveHook(WindowMessage);
        foreach (var h in powerHandles)
            UnregisterPowerSettingNotification(h);
        if (hook != IntPtr.Zero)
            UnhookWinEvent(hook);
        SystemEvents.SessionSwitch -= Session;
        SystemEvents.PowerModeChanged -= Power;
        NetworkChange.NetworkAddressChanged -= NetworkChanged;
        if (manager != null)
            manager.SessionsChanged -= Sessions;
        RefreshSessions();
    }
}
