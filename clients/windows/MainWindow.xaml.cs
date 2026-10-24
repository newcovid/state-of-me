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
        ConnectionView();
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
    void ConnectionView()
    {
        bool connected = settings.Token.Length >= 24 && !string.IsNullOrWhiteSpace(settings.Account);
        AccountLabel.Text = settings.ConnectionInvalid ? "设备连接已失效，请断开后重新登录添加本机" : connected ? "✓ 本机连接到 " + settings.Account + (settings.AccountSession.Length >= 24 ? " · 账号管理已登录" : " · 登录后可直接管理分享范围") : "在客户端注册或登录，自动添加本机，无需填写密钥。";
        ConnectButton.IsEnabled = true; DisconnectButton.IsEnabled = connected; Endpoint.IsReadOnly = connected;
    }
    void ManageAccount(object sender, RoutedEventArgs e)
    {
        if (settings.Token.Length < 24) { try { settings.Endpoint = AccountApi.NormalizeEndpoint(Endpoint.Text); } catch (InvalidOperationException ex) { PairStatus.Text = ex.Message; return; } }
        var window = new AccountWindow(settings, async () => { await engine.Configure(settings); ConnectionView(); UpdateView(); }) { Owner = this };
        window.ShowDialog(); Endpoint.Text = settings.Endpoint; ConnectionView(); UpdateView();
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
    void ConnectAccount(object sender, RoutedEventArgs e) => ManageAccount(sender, e);
    async void DisconnectAccount(object sender, RoutedEventArgs e)
    {
        if (System.Windows.MessageBox.Show(this, "停止分享并断开设备，清空本机分享选择、手动状态和歌词目录。音乐文件会保留。是否继续？", "断开此设备", MessageBoxButton.YesNo) != MessageBoxResult.Yes) return;
        hideTimer?.Cancel(); DisconnectButton.IsEnabled = false;
        await engine.Pause();
        try { await AccountRequest(settings.Endpoint, "/v1/device/disconnect", new { }, CancellationToken.None, settings.Token); PairStatus.Text = "已断开设备。"; }
        catch { PairStatus.Text = "本机已断开，服务器暂不可达。旧状态到期后会消失，请在账号页移除此设备。"; }
        var clean = new Settings { Endpoint = settings.Endpoint, AutoStart = settings.AutoStart };
        foreach (var property in typeof(Settings).GetProperties().Where(x => x.CanWrite)) property.SetValue(settings, property.GetValue(clean));
        settings.Save(); apps.Clear(); players.Clear(); Manual.Text = ""; directory = ""; DirectoryLabel.Text = ""; FilterMode.SelectedIndex = 1; PreferredPlayer.ItemsSource = null;
        foreach (var check in new[] { SharePresence, ShareDevice, ShareNetwork, ShareAudio, ShareResources, ShareDisplay, ShareWhatPulse, ShareMusic }) check.IsChecked = false;
        await engine.Configure(settings); ConnectionView();
    }
    void UpdateView()
    {
        if (!IsVisible)
            return;
        StatusText.Text = string.IsNullOrWhiteSpace(settings.Account) || settings.Token.Length < 24 ? "尚未连接账号，请在账号与设备页登录" : engine.Status;
        if (settings.ConnectionInvalid) ConnectionView();
        Summary.Text = settings.Enabled ? (engine.Preview["app_details"]?["label"]?.ToString() ?? engine.Preview["music"]?["title"]?.ToString() ?? engine.Sources.AppStatus) : "分享已暂停";
        Counters.Text = $"采集 {engine.Collections} 次    上传 {engine.Uploads} 次    去重 {engine.Skipped} 次";
        Preview.Text = engine.Preview.ToJsonString(new JsonSerializerOptions { WriteIndented = true, Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping });
        Health.Text = $"前台应用：{engine.Sources.AppStatus}；媒体接口：{engine.Sources.MediaCapability}；请求正文累计 {engine.Bytes} 字节。受众权限以服务端为准。";
        PulseStatus.Text = engine.WhatPulseStatus;
        LyricsStatus.Text = engine.LyricStatus;
    }
    async void Save(object sender, RoutedEventArgs e)
    {
        if (!Uri.TryCreate(Endpoint.Text.Trim(), UriKind.Absolute, out var u) || u.Scheme != "https" || !string.IsNullOrEmpty(u.UserInfo) || settings.Token.Length < 24 || string.IsNullOrWhiteSpace(settings.Account))
        {
            StatusText.Text = "请先在账号与设备页登录";
            return;
        }
        if (settings.Token.Length >= 24 && settings.Endpoint != Endpoint.Text.Trim().TrimEnd('/'))
        { StatusText.Text = "请先断开旧设备，再更换账号或服务地址"; return; }
        hideTimer?.Cancel();
        settings.Endpoint = Endpoint.Text.Trim().TrimEnd('/');
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
    void AudiencePreview(object sender, RoutedEventArgs e)
    {
        if (string.IsNullOrWhiteSpace(settings.Account) || settings.Token.Length < 24)
        { StatusText.Text = "请先连接账号，再查看受众预览"; return; }
        ManageAccount(sender, e);
    }
}
