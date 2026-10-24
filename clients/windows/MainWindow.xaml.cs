using System.Collections.ObjectModel;
using System.ComponentModel;
using System.IO;
using System.Net.Http;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Data;
using Microsoft.Win32;
using Wpf.Ui.Appearance;
namespace StateOfMe;
public class AppChoice
{
    public string Id { get; set; } = ""; public string Label { get; set; } = ""; public bool Selected
    {
        get; set;
    }
    public string Alias { get; set; } = ""; public System.Windows.Media.ImageSource? Icon
    {
        get; set;
    }
}
public partial class MainWindow : Wpf.Ui.Controls.FluentWindow
{
    readonly Settings settings = Settings.Load(); readonly SyncEngine engine;
    readonly ObservableCollection<AppChoice> apps = new(), players = new();
    readonly System.Windows.Forms.NotifyIcon tray; bool exiting; string directory = ""; CancellationTokenSource? hideTimer;
    public MainWindow()
    {
        InitializeComponent();
        SystemThemeWatcher.Watch(this);
        Endpoint.Text = settings.Endpoint;
        Token.Password = settings.Token;
        FilterMode.SelectedIndex = settings.AppMode == "off" ? 0 : settings.AppMode == "blacklist" ? 2 : 1;
        SharePresence.IsChecked = settings.Presence;
        ShareDevice.IsChecked = settings.Device;
        ShareNetwork.IsChecked = settings.Network;
        ShareAudio.IsChecked = settings.Audio;
        ShareResources.IsChecked = settings.Resources;
        ShareDisplay.IsChecked = settings.Display;
        ShareWhatPulse.IsChecked = settings.WhatPulse;
        ShareMusic.IsChecked = settings.Music;
        AutoStart.IsChecked = settings.AutoStart;
        PulsePort.Value = settings.WhatPulsePort;
        IdleMinutes.Value = settings.IdleMinutes;
        Manual.Text = settings.ManualUntil > DateTimeOffset.UtcNow.ToUnixTimeSeconds() ? settings.ManualText : "";
        directory = settings.MusicDirectory;
        DirectoryLabel.Text = directory;
        var args = Environment.GetCommandLineArgs();
        if (args.Length == 3 && args[1] == "--config")
            try
            {
                var cfg = JsonDocument.Parse(File.ReadAllText(args[2]));
                Endpoint.Text = cfg.RootElement.GetProperty("url").GetString() ?? "";
                Token.Password = cfg.RootElement.GetProperty("token").GetString() ?? "";
            }
            catch { }
        foreach (var id in settings.Apps)
            apps.Add(new()
            {
                Id = id,
                Label = id,
                Alias = settings.AppAliases.GetValueOrDefault(id, ""),
                Selected = true
            });
        foreach (var id in settings.Players)
            players.Add(new()
            {
                Id = id,
                Label = id,
                Selected = true
            });
        AppList.ItemsSource = apps;
        PlayerList.ItemsSource = players;
        engine = new(settings, Dispatcher);
        engine.Updated += UpdateView;
        IsVisibleChanged += (_, _) => { if (IsVisible) UpdateView(); };
        tray = new()
        {
            Text = "State of Me",
            Icon = System.Drawing.SystemIcons.Application,
            Visible = true
        };
        var menu = new System.Windows.Forms.ContextMenuStrip();
        menu.Items.Add("打开", null, (_, _) => Dispatcher.Invoke(Open));
        menu.Items.Add("暂停分享", null, async (_, _) => await engine.Pause());
        menu.Items.Add("恢复分享", null, (_, _) => Dispatcher.Invoke(() => { settings.Enabled = true; settings.Save(); engine.Request(); }));
        menu.Items.Add("退出", null, async (_, _) => await Exit());
        tray.ContextMenuStrip = menu;
        tray.DoubleClick += (_, _) => Dispatcher.Invoke(Open);
        Closing += (_, e) => { if (!exiting) { e.Cancel = true; Hide(); } };
        Loaded += async (_, _) => { engine.Sources.AttachWindow(new System.Windows.Interop.WindowInteropHelper(this).Handle); await engine.Initialize(); RefreshApps(this, new RoutedEventArgs()); RefreshPlayers(this, new RoutedEventArgs()); };
    }
    void Open()
    {
        Show();
        WindowState = WindowState.Normal;
        Activate();
    }
    async Task Exit()
    {
        exiting = true;
        hideTimer?.Cancel();
        await engine.Pause();
        engine.Dispose();
        tray.Dispose();
        System.Windows.Application.Current.Shutdown();
    }
    void UpdateView()
    {
        if (!IsVisible)
            return;
        StatusText.Text = engine.Status;
        Summary.Text = settings.Enabled ? (engine.Preview["app_details"]?["label"]?.ToString() ?? engine.Preview["music"]?["title"]?.ToString() ?? "等待允许分享的活动") : "分享已暂停";
        Counters.Text = $"采集 {engine.Collections} 次    上传 {engine.Uploads} 次    去重 {engine.Skipped} 次";
        Preview.Text = engine.Preview.ToJsonString(new JsonSerializerOptions { WriteIndented = true, Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping });
        Health.Text = $"媒体接口：{engine.Sources.MediaCapability}；请求正文累计 {engine.Bytes} 字节。受众权限以服务端为准。";
        PulseStatus.Text = engine.WhatPulseStatus;
        LyricsStatus.Text = engine.LyricStatus;
    }
    async void Save(object sender, RoutedEventArgs e)
    {
        if (!Uri.TryCreate(Endpoint.Text.Trim(), UriKind.Absolute, out var u) || u.Scheme != "https" || !string.IsNullOrEmpty(u.UserInfo) || Token.Password.Length < 24)
        {
            StatusText.Text = "请填写 HTTPS 服务地址和有效写入令牌";
            return;
        }
        hideTimer?.Cancel();
        settings.Endpoint = Endpoint.Text.Trim().TrimEnd('/');
        settings.Token = Token.Password;
        settings.AppMode = FilterMode.SelectedIndex switch
        {
            0 => "off",
            2 => "blacklist",
            _ => "whitelist"
        };
        settings.Apps = apps.Where(x => x.Selected).Select(x => x.Id).ToList();
        settings.AppAliases = apps.Where(x => !string.IsNullOrWhiteSpace(x.Alias)).ToDictionary(x => x.Id, x => x.Alias.Trim());
        settings.Players = players.Where(x => x.Selected).Select(x => x.Id).ToList();
        settings.PreferredPlayer = PreferredPlayer.SelectedItem as string ?? "";
        settings.Presence = SharePresence.IsChecked == true;
        settings.Device = ShareDevice.IsChecked == true;
        settings.Network = ShareNetwork.IsChecked == true;
        settings.Audio = ShareAudio.IsChecked == true;
        settings.Resources = ShareResources.IsChecked == true;
        settings.Display = ShareDisplay.IsChecked == true;
        settings.WhatPulse = ShareWhatPulse.IsChecked == true;
        settings.Music = ShareMusic.IsChecked == true;
        settings.MusicDirectory = directory;
        settings.IdleMinutes = (int)(IdleMinutes.Value ?? 3);
        settings.WhatPulsePort = (int)(PulsePort.Value ?? 3490);
        settings.ManualText = Manual.Text.Trim();
        settings.ManualUntil = DateTimeOffset.UtcNow.AddHours(1).ToUnixTimeSeconds();
        settings.Enabled = true;
        settings.AutoStart = AutoStart.IsChecked == true;
        try
        {
            using var key = Registry.CurrentUser.CreateSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run");
            if (settings.AutoStart)
                key.SetValue("StateOfMe", "\"" + Environment.ProcessPath + "\"");
            else
                key.DeleteValue("StateOfMe", false);
            settings.Save();
            await engine.Configure(settings);
        }
        catch { StatusText.Text = "保存失败，请检查配置目录权限"; }
    }
    async void Pause(object sender, RoutedEventArgs e)
    {
        hideTimer?.Cancel();
        await engine.Pause();
    }
    async void HideBriefly(object sender, RoutedEventArgs e)
    {
        hideTimer?.Cancel();
        hideTimer = new();
        var cancel = hideTimer.Token;
        await engine.Pause();
        StatusText.Text = "已隐身，将在 15 分钟后恢复";
        try
        {
            await Task.Delay(TimeSpan.FromMinutes(15), cancel);
            settings.Enabled = true;
            settings.Save();
            engine.Request();
        }
        catch (OperationCanceledException) { }
    }
    void RefreshApps(object sender, RoutedEventArgs e)
    {
        foreach (var item in DesktopSources.Applications())
        {
            var old = apps.FirstOrDefault(x => x.Id.Equals(item.Id, StringComparison.OrdinalIgnoreCase));
            if (old == null)
            {
                old = new()
                {
                    Id = item.Id,
                    Label = item.Label
                };
                apps.Add(old);
            }
            try
            {
                using var proc = System.Diagnostics.Process.GetProcessesByName(item.Id).FirstOrDefault();
                var path = proc?.MainModule?.FileName;
                if (path != null)
                {
                    using var icon = System.Drawing.Icon.ExtractAssociatedIcon(path);
                    if (icon != null)
                    {
                        var image = System.Windows.Interop.Imaging.CreateBitmapSourceFromHIcon(icon.Handle, Int32Rect.Empty, System.Windows.Media.Imaging.BitmapSizeOptions.FromWidthAndHeight(24, 24));
                        image.Freeze();
                        old.Icon = image;
                    }
                }
            }
            catch { }
        }
        CollectionViewSource.GetDefaultView(apps).Refresh();
    }
    void SearchChanged(object sender, TextChangedEventArgs e)
    {
        if (AppList == null)
            return;
        CollectionViewSource.GetDefaultView(apps).Filter = x => x is AppChoice a && (a.Label.Contains(Search.Text, StringComparison.OrdinalIgnoreCase) || a.Id.Contains(Search.Text, StringComparison.OrdinalIgnoreCase));
    }
    void RefreshPlayers(object sender, RoutedEventArgs e)
    {
        foreach (var id in engine.Sources.Players())
            if (!players.Any(x => x.Id == id))
                players.Add(new()
                {
                    Id = id,
                    Label = id
                });
        PreferredPlayer.ItemsSource = new[] { "" }.Concat(players.Select(p => p.Id)).ToArray();
        PreferredPlayer.SelectedItem = settings.PreferredPlayer;
    }
    void ChooseDirectory(object sender, RoutedEventArgs e)
    {
        var picker = new OpenFolderDialog { Title = "选择音乐目录" };
        if (picker.ShowDialog(this) == true)
        {
            directory = picker.FolderName;
            DirectoryLabel.Text = directory;
        }
    }
    async void RefreshLyrics(object sender, RoutedEventArgs e)
    {
        await engine.RefreshLyrics();
        UpdateView();
    }
    void ClearDirectory(object sender, RoutedEventArgs e)
    {
        directory = "";
        DirectoryLabel.Text = "未选择音乐目录";
    }
    async void AudiencePreview(object sender, RoutedEventArgs e)
    {
        try
        {
            if (!Uri.TryCreate(Endpoint.Text.Trim(), UriKind.Absolute, out var address) || address.Scheme != "https" || !string.IsNullOrEmpty(address.UserInfo))
                throw new InvalidOperationException();
            using var http = new HttpClient(new HttpClientHandler { AllowAutoRedirect = false }) { Timeout = TimeSpan.FromSeconds(8) };
            using var req = new HttpRequestMessage(HttpMethod.Get, Endpoint.Text.TrimEnd('/') + (ReadToken.Password.Length > 0 ? "/v1/status" : "/v1/public/status"));
            if (ReadToken.Password.Length > 0)
                req.Headers.Authorization = new("Bearer", ReadToken.Password);
            using var response = await http.SendAsync(req);
            response.EnsureSuccessStatusCode();
            var text = await response.Content.ReadAsStringAsync();
            var window = new Window { Title = "受众可见内容", Width = 600, Height = 480, Owner = this, Content = new TextBox { Text = JsonSerializer.Serialize(JsonDocument.Parse(text), new JsonSerializerOptions { WriteIndented = true, Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping }), IsReadOnly = true, TextWrapping = TextWrapping.Wrap, VerticalScrollBarVisibility = ScrollBarVisibility.Auto } };
            window.ShowDialog();
        }
        catch { StatusText.Text = "无法读取受众预览，请检查地址与只读权限"; }
    }
}
