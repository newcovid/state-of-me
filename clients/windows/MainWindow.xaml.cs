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
    readonly Settings settings = Settings.Load(); readonly SyncEngine engine; readonly AccountWorkspace account;
    readonly ObservableCollection<AppChoice> apps = new(), players = new();
    readonly System.Windows.Forms.NotifyIcon tray; bool exiting, initialized; string directory = ""; CancellationTokenSource? hideTimer;
    public MainWindow()
    {
        InitializeComponent();
        SystemThemeWatcher.Watch(this);
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
        DirectoryLabel.Text = directory.Length == 0 ? "未选择音乐目录（可选）" : directory;
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
        var configuredToken = settings.Token; var configuredEndpoint = settings.Endpoint;
        account = new AccountWorkspace(this, settings, async () => { if (configuredToken != settings.Token || configuredEndpoint != settings.Endpoint) { await engine.Configure(settings); configuredToken = settings.Token; configuredEndpoint = settings.Endpoint; } ConnectionView(); UpdateView(); }, () => ShowPage("Settings"), () => ShowPage("Collection"));
        account.StateChanged += () => { ConnectionView(); UpdateView(); };
        AccountHost.Content = account.SettingsContent; SharingPage.Content = account.SharingContent; ConnectionView(); ShowPage("Overview");
        IsVisibleChanged += (_, _) => { if (IsVisible) UpdateView(); };
        tray = new()
        {
            Text = "State of Me",
            Icon = System.Drawing.SystemIcons.Application,
            Visible = true
        };
        var menu = new System.Windows.Forms.ContextMenuStrip();
        menu.Items.Add("打开", null, (_, _) => Dispatcher.Invoke(Open));
        menu.Items.Add("暂停同步", null, async (_, _) => await engine.Pause());
        menu.Items.Add("开始同步", null, (_, _) => Dispatcher.Invoke(() => StartSync(this, new RoutedEventArgs())));
        menu.Items.Add("退出", null, async (_, _) => await Exit());
        tray.ContextMenuStrip = menu;
        tray.DoubleClick += (_, _) => Dispatcher.Invoke(Open);
        Closing += (_, e) => { if (!exiting) { e.Cancel = true; if (account.CanClose()) Hide(); else ShowPage("Settings"); } };
        Loaded += async (_, _) => { if (initialized) return; initialized = true; RefreshEnabledStartup(); engine.Sources.AttachWindow(new System.Windows.Interop.WindowInteropHelper(this).Handle); await engine.Initialize(); await account.InitializeAsync(); RefreshApps(this, new RoutedEventArgs()); RefreshPlayers(this, new RoutedEventArgs()); };
    }
    void Open()
    {
        Show();
        WindowState = WindowState.Normal;
        Activate();
    }
    async Task Exit()
    {
        if (!account.CanClose()) { ShowPage("Settings"); return; }
        exiting = true;
        hideTimer?.Cancel();
        await engine.Pause();
        engine.Dispose();
        tray.Dispose();
        System.Windows.Application.Current.Shutdown();
    }
    void Navigate(object sender, RoutedEventArgs e)
    {
        if (sender is Wpf.Ui.Controls.NavigationViewItem item) ShowPage(item.Tag?.ToString() ?? "Overview");
    }
    void ShowPage(string page)
    {
        OverviewPage.Visibility = page == "Overview" ? Visibility.Visible : Visibility.Collapsed;
        CollectionPage.Visibility = page == "Collection" ? Visibility.Visible : Visibility.Collapsed;
        SharingPage.Visibility = page == "Sharing" ? Visibility.Visible : Visibility.Collapsed;
        SettingsPage.Visibility = page == "Settings" ? Visibility.Visible : Visibility.Collapsed;
        OverviewNav.IsActive = page == "Overview"; CollectionNav.IsActive = page == "Collection";
        SharingNav.IsActive = page == "Sharing"; SettingsNav.IsActive = page == "Settings";
        PageTitle.Text = page switch { "Collection" => "采集", "Sharing" => "分享", "Settings" => "设置", _ => "概览" };
    }
    void OpenCollection(object sender, RoutedEventArgs e) => ShowPage("Collection");
    void ConnectionView()
    {
        bool connected = settings.Token.Length >= 24 && !string.IsNullOrWhiteSpace(settings.Account);
        AccountLabel.Text = settings.ConnectionInvalid ? "设备连接已失效，请断开后重新登录添加本机" : connected ? "设备同步连接：" + settings.Account + "。" + (settings.AccountSession.Length >= 24 ? "管理会话已登录。" : "管理会话未登录；在上方登录即可管理分享，无需断开同步连接。") : "尚未连接设备，请在上方登录或注册。";
        DisconnectButton.IsEnabled = connected;
    }
    static async Task<JsonElement> AccountRequest(string endpoint, string path, object body, CancellationToken cancellation, string token = "")
    {
        using var http = new HttpClient(new HttpClientHandler { AllowAutoRedirect = false }) { Timeout = TimeSpan.FromSeconds(10) };
        using var request = new HttpRequestMessage(HttpMethod.Post, endpoint + path) { Content = new StringContent(JsonSerializer.Serialize(body), System.Text.Encoding.UTF8, "application/json") };
        if (token.Length > 0) request.Headers.Authorization = new("Bearer", token);
        using var response = await http.SendAsync(request, cancellation);
        response.EnsureSuccessStatusCode();
        return JsonDocument.Parse(await response.Content.ReadAsStringAsync(cancellation)).RootElement.Clone();
    }
    async void DisconnectAccount(object sender, RoutedEventArgs e)
    {
        if (System.Windows.MessageBox.Show(this, "停止同步并断开设备，清空本机采集选择、手动状态和歌词目录。音乐文件会保留。是否继续？", "断开此设备", MessageBoxButton.YesNo) != MessageBoxResult.Yes) return;
        if (!account.CanDisconnect) { ConnectionNotice.Text = account.IsBusy ? "请等待账号请求完成后再断开。" : "请先保存上方新的恢复码，再断开本机。"; return; }
        hideTimer?.Cancel(); DisconnectButton.IsEnabled = false;
        await engine.Pause();
        try { await AccountRequest(settings.Endpoint, "/v1/device/disconnect", new { }, CancellationToken.None, settings.Token); ConnectionNotice.Text = "已断开设备。"; }
        catch { ConnectionNotice.Text = "本机已断开，服务器暂不可达。旧状态到期后会消失，请在账号页移除此设备。"; }
        var clean = new Settings { Endpoint = settings.Endpoint, AutoStart = settings.AutoStart };
        foreach (var property in typeof(Settings).GetProperties().Where(x => x.CanWrite)) property.SetValue(settings, property.GetValue(clean));
        settings.Save(); apps.Clear(); players.Clear(); Manual.Text = ""; directory = ""; DirectoryLabel.Text = ""; FilterMode.SelectedIndex = 1; PreferredPlayer.ItemsSource = null;
        foreach (var check in new[] { SharePresence, ShareDevice, ShareNetwork, ShareAudio, ShareResources, ShareDisplay, ShareWhatPulse, ShareMusic }) check.IsChecked = false;
        await engine.Configure(settings); account.ResetConnection(); ConnectionView();
    }
    void UpdateView()
    {
        if (!IsVisible)
            return;
        StatusText.Text = string.IsNullOrWhiteSpace(settings.Account) || settings.Token.Length < 24 ? "尚未连接账号，请在设置页登录" : engine.Status;
        if (settings.ConnectionInvalid) ConnectionView();
        Summary.Text = settings.Enabled ? (engine.Preview["app_details"]?["label"]?.ToString() ?? engine.Preview["music"]?["title"]?.ToString() ?? engine.Sources.AppStatus) : "同步已暂停";
        if (settings.Token.Length < 24 || string.IsNullOrWhiteSpace(settings.Account)) Summary.Text = "连接账号，开始同步这台电脑的状态。";
        Counters.Text = $"采集 {engine.Collections} 次    上传 {engine.Uploads} 次    去重 {engine.Skipped} 次";
        Preview.Text = engine.Preview.ToJsonString(new JsonSerializerOptions { WriteIndented = true, Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping });
        Health.Text = $"前台应用：{engine.Sources.AppStatus}；媒体接口：{engine.Sources.MediaCapability}；请求正文累计 {engine.Bytes} 字节。受众权限以服务端为准。";
        bool connected = settings.Token.Length >= 24 && !string.IsNullOrWhiteSpace(settings.Account) && !settings.ConnectionInvalid;
        StartButton.Content = !connected ? "连接账号" : settings.Enabled ? "正在同步" : "开始同步";
        StartButton.IsEnabled = !connected || !settings.Enabled; PauseSyncButton.IsEnabled = connected && settings.Enabled; TimedPauseButton.IsEnabled = connected && settings.Enabled;
        var media = engine.Preview["music"];
        MediaSummary.Text = media?["playing"]?.GetValue<bool>() == true ? "正在播放：" + (media["title"]?.ToString() ?? "媒体") + (media["artist"] == null ? "" : " · " + media["artist"]) : "";
        MediaSummary.Visibility = MediaSummary.Text.Length > 0 ? Visibility.Visible : Visibility.Collapsed;
        CollectionSummary.Text = ClientPresentation.CollectionSummary(settings);
        var stats = engine.Preview["usage_summary"];
        Statistics.Text = ClientPresentation.Statistics(settings, stats);
        PulseStatus.Text = engine.WhatPulseStatus;
        LyricsStatus.Text = directory != settings.MusicDirectory ? "所选目录尚未保存；保存采集设置后自动索引。" : engine.LyricStatus;
    }
    async void SaveCollection(object sender, RoutedEventArgs e)
    {
        var draft = new CollectionOptions();
        draft.AppMode = FilterMode.SelectedIndex switch
        {
            0 => "off",
            2 => "blacklist",
            _ => "whitelist"
        };
        draft.Apps = apps.Where(x => x.Selected).Select(x => x.Id).ToList();
        draft.AppAliases = apps.Where(x => !string.IsNullOrWhiteSpace(x.Alias)).ToDictionary(x => x.Id, x => x.Alias.Trim());
        draft.Players = players.Where(x => x.Selected).Select(x => x.Id).ToList();
        draft.PreferredPlayer = PreferredPlayer.SelectedValue as string ?? "";
        draft.Presence = SharePresence.IsChecked == true;
        draft.Device = ShareDevice.IsChecked == true;
        draft.Network = ShareNetwork.IsChecked == true;
        draft.Audio = ShareAudio.IsChecked == true;
        draft.Resources = ShareResources.IsChecked == true;
        draft.Display = ShareDisplay.IsChecked == true;
        draft.WhatPulse = ShareWhatPulse.IsChecked == true;
        draft.Music = ShareMusic.IsChecked == true;
        draft.MusicDirectory = directory;
        draft.IdleMinutes = (int)(IdleMinutes.Value ?? 3);
        draft.WhatPulsePort = (int)(PulsePort.Value ?? 3490);
        try { draft.ApplyTo(settings); settings.Save(); await engine.Configure(settings); CollectionNotice.Text = "采集设置已保存。同步状态见窗口底部。"; }
        catch { CollectionNotice.Text = "保存失败，请检查配置目录权限。"; }
    }
    void StartSync(object sender, RoutedEventArgs e)
    {
        if (settings.Token.Length < 24 || string.IsNullOrWhiteSpace(settings.Account) || settings.ConnectionInvalid) { StatusText.Text = "请先在设置页登录并连接本机。"; ShowPage("Settings"); return; }
        try { hideTimer?.Cancel(); settings.Enabled = true; settings.Save(); engine.Request(); UpdateView(); }
        catch { settings.Enabled = false; StatusText.Text = "无法保存同步状态，请检查配置目录权限。"; }
    }
    void SaveManual(object sender, RoutedEventArgs e)
    {
        settings.ManualText = Manual.Text.Trim(); settings.ManualUntil = settings.ManualText.Length > 0 ? DateTimeOffset.UtcNow.AddHours(1).ToUnixTimeSeconds() : 0;
        try { settings.Save(); if (settings.Enabled) engine.Request(); StatusText.Text = settings.Enabled ? "临时状态已保存，将随当前状态同步。" : "临时状态已保存，同步仍处于暂停状态。"; }
        catch { StatusText.Text = "临时状态保存失败，请检查配置目录权限。"; }
    }
    void RefreshEnabledStartup()
    {
        if (!settings.AutoStart) return;
        try { using var key = Registry.CurrentUser.CreateSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run"); key.SetValue("StateOfMe", "\"" + Environment.ProcessPath + "\""); }
        catch { StartupNotice.Text = "已启用开机启动，但更新程序路径失败。可重新保存启动设置。"; }
    }
    void SaveStartup(object sender, RoutedEventArgs e)
    {
        try {
            using var key = Registry.CurrentUser.CreateSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run");
            bool enabled = AutoStart.IsChecked == true;
            if (enabled) key.SetValue("StateOfMe", "\"" + Environment.ProcessPath + "\""); else key.DeleteValue("StateOfMe", false);
            settings.AutoStart = enabled; settings.Save(); StartupNotice.Text = "启动设置已保存。";
        } catch { StartupNotice.Text = "启动设置保存失败，请检查当前用户的权限。"; }
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
        StatusText.Text = "已暂停同步，将在 15 分钟后恢复";
        try
        {
            await Task.Delay(TimeSpan.FromMinutes(15), cancel);
            StartSync(this, new RoutedEventArgs());
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
            old.Label = item.Label;
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
        AppList.Items.Refresh();
        UpdateAppSearchHint();
    }
    void SearchChanged(object sender, TextChangedEventArgs e)
    {
        if (AppList == null)
            return;
        CollectionViewSource.GetDefaultView(apps).Filter = x => x is AppChoice a && (a.Label.Contains(Search.Text, StringComparison.OrdinalIgnoreCase) || a.Id.Contains(Search.Text, StringComparison.OrdinalIgnoreCase));
        UpdateAppSearchHint();
    }
    void UpdateAppSearchHint()
    {
        if (AppSearchHint == null) return;
        AppSearchHint.Text = CollectionViewSource.GetDefaultView(apps).IsEmpty ? "没有匹配的应用。先打开目标应用，再点击刷新应用；也可以更换搜索词。" : "列表来自正在运行的应用与已保存的选择。右侧可填写展示别名。";
    }
    void RefreshPlayers(object sender, RoutedEventArgs e)
    {
        var preferred = PreferredPlayer.SelectedValue as string ?? settings.PreferredPlayer;
        foreach (var id in engine.Sources.Players())
            if (!players.Any(x => x.Id == id))
                players.Add(new()
                {
                    Id = id,
                    Label = id
                });
        foreach (var player in players) player.Label = DesktopSources.PlayerLabel(player.Id);
        PlayerList.Items.Refresh();
        PreferredPlayer.ItemsSource = new[] { new AppChoice { Id = "", Label = "自动选择正在播放的播放器" } }.Concat(players).ToArray();
        PreferredPlayer.SelectedValue = preferred;
        PlayerHint.Text = players.Count == 0 ? "尚未发现播放器。请先打开播放器并播放一首歌，再刷新列表。" : "勾选要采集的播放器；多个播放器同时播放时，优先使用下方选择。";
    }
    void ChooseDirectory(object sender, RoutedEventArgs e)
    {
        var picker = new OpenFolderDialog { Title = "选择音乐目录" };
        if (picker.ShowDialog(this) == true)
        {
            directory = picker.FolderName;
            DirectoryLabel.Text = directory.Length == 0 ? "未选择音乐目录（可选）" : directory;
        }
    }
    async void RefreshLyrics(object sender, RoutedEventArgs e)
    {
        if (directory != settings.MusicDirectory) { LyricsStatus.Text = "目录选择尚未保存，请先保存采集设置；保存会自动索引新目录。"; return; }
        await engine.RefreshLyrics();
        UpdateView();
    }
    void ClearDirectory(object sender, RoutedEventArgs e)
    {
        directory = "";
        DirectoryLabel.Text = "未选择音乐目录";
    }
}
