using System.IO;
using System.Text.Json.Nodes;
namespace StateOfMe;
public partial class App : System.Windows.Application
{
    Mutex? instance; EventWaitHandle? showEvent; RegisteredWaitHandle? showWait;
    protected override async void OnStartup(System.Windows.StartupEventArgs e)
    {
        if (e.Args.Length == 2 && (e.Args[0] == "--probe" || e.Args[0] == "--probe-foreground"))
        {
            ShutdownMode = System.Windows.ShutdownMode.OnExplicitShutdown;
            try
            {
                using var sources = new DesktopSources(() => { });
                await sources.Initialize();
                var cfg = new Settings { AppMode = e.Args[0] == "--probe-foreground" ? "blacklist" : "off", Presence = true, Device = true, Network = true, Audio = true, Resources = true, Display = true };
                var data = sources.Collect(cfg);
                using var pulse = new WhatPulseSource();
                data["usage_summary"] = await pulse.Read(true, 3490);
                data["app_capture_status"] = sources.AppStatus;
                data["seq"] = 1;
                data["observed_at"] = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
                File.WriteAllText(Path.GetFullPath(e.Args[1]), data.ToJsonString());
                Shutdown(0);
            }
            catch { Shutdown(1); }
            return;
        }
        instance = new Mutex(true, "Local\\StateOfMe.Desktop", out bool first);
        if (!first)
        {
            try
            {
                using var existing = EventWaitHandle.OpenExisting("Local\\StateOfMe.Show");
                existing.Set();
            }
            catch { }
            Shutdown();
            return;
        }
        base.OnStartup(e);
        var window = new MainWindow();
        window.Show();
        showEvent = new EventWaitHandle(false, EventResetMode.AutoReset, "Local\\StateOfMe.Show");
        showWait = ThreadPool.RegisterWaitForSingleObject(showEvent, (_, _) => Dispatcher.BeginInvoke(() => { window.Show(); window.WindowState = System.Windows.WindowState.Normal; window.Activate(); }), null, -1, false);
    }
    protected override void OnExit(System.Windows.ExitEventArgs e)
    {
        showWait?.Unregister(null);
        showEvent?.Dispose();
        instance?.Dispose();
        base.OnExit(e);
    }
}
