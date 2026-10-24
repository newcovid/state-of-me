using System.IO;
using System.Text;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using Microsoft.Win32;
using Wpf.Ui.Appearance;
namespace StateOfMe;

/// <summary>Native account management, sharing and device enrolment.</summary>
public sealed class AccountWindow : Wpf.Ui.Controls.FluentWindow
{
    readonly Settings settings; readonly AccountApi api; readonly Func<Task> deviceChanged;
    readonly TextBlock notice = new() { TextWrapping = TextWrapping.Wrap, Margin = new Thickness(24, 12, 24, 20) };
    readonly TabControl tabs = new();
    readonly TextBox endpoint = new(), username = new(); readonly PasswordBox password = new();
    readonly StackPanel accountPanel = new(), groupPanel = new(), publicPanel = new();
    readonly Dictionary<string, CheckBox> publicChecks = new(), groupChecks = new();
    readonly ComboBox groupChoice = new() { IsEditable = true, MinWidth = 240 };
    readonly TextBox bindCommand = new() { IsReadOnly = true };
    JsonElement info, me; bool busy, recoverySaved; string recovery = ""; string renderedGroup = "";

    public AccountWindow(Settings settings, Func<Task> deviceChanged)
    {
        this.settings = settings; api = new(settings); this.deviceChanged = deviceChanged;
        Title = "账号与分享 · State of Me"; Width = 800; Height = 780; MinWidth = 640; MinHeight = 580;
        WindowStartupLocation = WindowStartupLocation.CenterOwner; FontFamily = new System.Windows.Media.FontFamily("Segoe UI, Microsoft YaHei UI"); ExtendsContentIntoTitleBar = true;
        SystemThemeWatcher.Watch(this);
        var shell = new DockPanel(); var titleBar = new Wpf.Ui.Controls.TitleBar { Title = "State of Me · 账号与分享" }; DockPanel.SetDock(titleBar, Dock.Top); shell.Children.Add(titleBar); DockPanel.SetDock(notice, Dock.Bottom); shell.Children.Add(notice);
        var intro = new StackPanel { Margin = new Thickness(24, 20, 24, 12) };
        intro.Children.Add(Label("账号与分享", 25)); intro.Children.Add(Label("管理自己的设备，并决定每个群和公开访客能看到什么。", 13));
        DockPanel.SetDock(intro, Dock.Top); shell.Children.Add(intro);
        tabs.Items.Add(Tab("账号与设备", accountPanel)); tabs.Items.Add(Tab("QQ 群分享", groupPanel)); tabs.Items.Add(Tab("公开展示", publicPanel));
        shell.Children.Add(tabs); Content = shell;
        Loaded += async (_, _) => await Run(Initialize);
        Closing += (_, e) => { if (busy) { e.Cancel = true; notice.Text = "请等待当前请求完成后关闭。"; return; } if (recovery.Length > 0 && !recoverySaved && MessageBox.Show(this, "恢复码尚未保存。关闭后将不能再次显示，要继续关闭吗？", "保存账号恢复码", MessageBoxButton.YesNo) != MessageBoxResult.Yes) e.Cancel = true; };
    }
    static TextBlock Label(string text, double size = 14) => new() { Text = text, FontSize = size, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 6, 0, 8) };
    static TabItem Tab(string title, StackPanel panel) => new() { Header = title, Content = new ScrollViewer { Content = panel, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Margin = new Thickness(24, 12, 24, 12) } };
    Button Button(string text, Func<Task> work, bool primary = false)
    {
        var b = new Wpf.Ui.Controls.Button { Content = text, Margin = new Thickness(0, 6, 10, 8), Appearance = primary ? Wpf.Ui.Controls.ControlAppearance.Primary : Wpf.Ui.Controls.ControlAppearance.Secondary };
        b.Click += async (_, _) => await Run(work); return b;
    }
    async Task Run(Func<Task> action)
    {
        if (busy) return; busy = true; tabs.IsEnabled = false; notice.Text = "正在处理…";
        try { await action(); }
        catch (Exception e) { if (me.ValueKind == JsonValueKind.Object && settings.AccountSession.Length < 24) { settings.ClearAccountSession(); settings.Save(); RenderLogin(); } notice.Text = e is InvalidOperationException ? e.Message : "暂时无法连接服务，请检查网络后重试。"; }
        finally { busy = false; tabs.IsEnabled = true; }
    }
    string Endpoint => AccountApi.NormalizeEndpoint(endpoint.Text);
    static string String(JsonElement value, string key) => value.TryGetProperty(key, out var property) && property.ValueKind == JsonValueKind.String ? property.GetString()! : "";
    static IEnumerable<JsonElement> Items(JsonElement value, string key) => value.ValueKind == JsonValueKind.Object && value.TryGetProperty(key, out var a) && a.ValueKind == JsonValueKind.Array ? a.EnumerateArray().ToArray() : Array.Empty<JsonElement>();
    static string[] Strings(JsonElement value, string key) => Items(value, key).Where(x => x.ValueKind == JsonValueKind.String).Select(x => x.GetString()!).ToArray();

    async Task Initialize()
    {
        endpoint.Text = settings.Endpoint; username.Text = settings.Account;
        if (settings.Endpoint.Length > 0)
        {
            try { info = await api.Request(Endpoint, "/v1/accounts/info", authenticated: false); }
            catch { RenderLogin(); throw; }
        }
        if (settings.AccountSession.Length >= 24) await Refresh(); else RenderLogin();
    }
    void RenderLogin()
    {
        me = default; accountPanel.Children.Clear(); groupPanel.Children.Clear(); publicPanel.Children.Clear();
        groupPanel.Children.Add(Label("登录后，在这里验证 QQ 并管理各群的分享范围。")); publicPanel.Children.Add(Label("登录后，在这里选择公开字段。新账号默认不公开。"));
        accountPanel.Children.Add(Label(settings.Token.Length >= 24 ? "当前设备已连接；登录一次即可在客户端管理账号。" : "登录后，本机会自动添加到你的账号。", 18));
        accountPanel.Children.Add(Label("服务地址")); endpoint.IsReadOnly = settings.Token.Length >= 24; accountPanel.Children.Add(endpoint);
        accountPanel.Children.Add(Label("账号名")); accountPanel.Children.Add(username);
        accountPanel.Children.Add(Label("密码")); password.Clear(); accountPanel.Children.Add(password);
        var buttons = new WrapPanel(); buttons.Children.Add(Button("登录", () => Authenticate(false), true)); buttons.Children.Add(Button("注册新账号", () => Authenticate(true))); accountPanel.Children.Add(buttons);
        accountPanel.Children.Add(Label("账号用于管理权限，设备连接用于后台采集。账号登录过期不会把设备的数据改到另一个账号。", 12));
        var recoveryPanel = new StackPanel { Margin = new Thickness(8) };
        recoveryPanel.Children.Add(Label("填写上方服务地址和账号名，再输入保存的恢复码与新密码。重置会撤销这个账号的全部设备连接与登录。", 12));
        var recoveryInput = new PasswordBox(); var replacementPassword = new PasswordBox();
        recoveryPanel.Children.Add(Label("恢复码")); recoveryPanel.Children.Add(recoveryInput);
        recoveryPanel.Children.Add(Label("新密码")); recoveryPanel.Children.Add(replacementPassword);
        recoveryPanel.Children.Add(Button("使用恢复码重设密码", async () => {
            var target = Endpoint; var name = username.Text.Trim().ToLowerInvariant();
            if (settings.Token.Length >= 24 && (AccountApi.NormalizeEndpoint(settings.Endpoint) != target || !string.Equals(settings.Account, name, StringComparison.OrdinalIgnoreCase)))
                throw new InvalidOperationException("本机连接到另一个账号。请先在主窗口断开设备，再恢复其他账号。");
            if (name.Length == 0 || recoveryInput.Password.Length == 0 || replacementPassword.Password.Length == 0) throw new InvalidOperationException("请填写账号、恢复码和新密码。");
            if (!Confirm("重设密码会立即撤销此账号的全部登录会话和设备凭据。所有设备都需要重新登录登记。继续吗？", "重设密码并撤销旧连接")) return;
            JsonElement result;
            try { result = await api.Request(target, "/v1/accounts/recover", new { name, password = replacementPassword.Password, recovery = recoveryInput.Password }, false); }
            finally { recoveryInput.Clear(); replacementPassword.Clear(); password.Clear(); }
            settings.ClearAccountSession(); settings.Save(); recovery = String(result, "recovery"); recoverySaved = false;
            RenderLogin(); accountPanel.Children.Clear(); accountPanel.Children.Add(Label("密码已重设，请先保存新的恢复码", 20));
            accountPanel.Children.Add(Label("旧恢复码已失效。全部设备已在服务器撤销；本机如仍显示旧连接，请关闭此窗口，在主窗口断开后重新登录。", 13));
            accountPanel.Children.Add(new TextBox { Text = recovery, IsReadOnly = true, TextWrapping = TextWrapping.Wrap });
            accountPanel.Children.Add(Button("保存新的恢复码文件", () => {
                var picker = new SaveFileDialog { FileName = "StateOfMe-恢复码.txt", Filter = "文本文件|*.txt" };
                if (picker.ShowDialog(this) == true) { File.WriteAllText(picker.FileName, "State of Me\n服务：" + target + "\n账号：" + name + "\n恢复码：" + recovery, Encoding.UTF8); recoverySaved = true; notice.Text = "新恢复码已保存，请重新登录登记设备。"; }
                return Task.CompletedTask;
            }, true));
            accountPanel.Children.Add(Button("返回登录", () => { if (!recoverySaved) throw new InvalidOperationException("请先保存新的恢复码。"); recovery = ""; RenderLogin(); return Task.CompletedTask; }));
            notice.Text = "请保存新恢复码，并重新连接各设备。";
        }));
        accountPanel.Children.Add(new Expander { Header = "忘记密码？使用恢复码", Content = recoveryPanel, Margin = new Thickness(0, 12, 0, 12) });

        notice.Text = settings.Token.Length >= 24 ? "本机采集设置会保留，请登录当前设备所属账号。" : "填写维护者提供的 HTTPS 服务地址，再注册或登录。";
    }
    async Task Authenticate(bool register)
    {
        var target = Endpoint; var name = username.Text.Trim().ToLowerInvariant(); var secret = password.Password;
        if (settings.Token.Length >= 24 && (!string.Equals(settings.Account, name, StringComparison.OrdinalIgnoreCase) || AccountApi.NormalizeEndpoint(settings.Endpoint) != target))
            throw new InvalidOperationException("本机仍连接到 " + settings.Account + "。如需换账号，请先在主窗口断开此设备。");
        if (name.Length == 0 || secret.Length == 0) throw new InvalidOperationException("请填写账号与密码。");
        info = await api.Request(target, "/v1/accounts/info", authenticated: false);
        if (register && (!info.TryGetProperty("registration", out var allowed) || !allowed.GetBoolean())) throw new InvalidOperationException("此服务未开放注册，请联系维护者。");
        JsonElement result;
        try { result = await api.Request(target, "/v1/accounts/" + (register ? "register" : "login"), new { name, password = secret, client = "native" }, false); }
        finally { password.Clear(); }
        var token = String(result, "access_token"); var account = String(result, "name");
        if (token.Length < 24 || account.Length == 0) throw new InvalidOperationException("服务尚不支持原生账号登录，请升级后端到 0.15 或更新版本。");
        settings.Endpoint = target; settings.NativeEndpoint = target; settings.NativeAccount = account;
        settings.AccountSessionExpiresAt = DateTimeOffset.UtcNow.ToUnixTimeSeconds() + result.GetProperty("expires_in").GetInt64(); settings.AccountSession = token; settings.Save();
        recovery = String(result, "recovery"); recoverySaved = recovery.Length == 0;
        if (!register) { try { await Enroll(); } catch { await Refresh(); throw; } } await Refresh(); notice.Text = register ? "账号已创建，请先保存恢复码，再选择采集和分享范围。" : "登录成功，账号管理已在客户端就绪。";
    }
    async Task Enroll()
    {
        if (recovery.Length > 0 && !recoverySaved) throw new InvalidOperationException("请先保存恢复码，再将本机添加到账号。");
        if (settings.Token.Length >= 24 && !string.Equals(settings.Account, settings.NativeAccount, StringComparison.OrdinalIgnoreCase)) throw new InvalidOperationException("账号与本机连接不一致，请先断开旧设备。");
        var existed = settings.Token.Length >= 24;
        var enrollmentId = settings.PrepareEnrollment(Endpoint, settings.NativeAccount); settings.Save();
        var result = await api.Request(Endpoint, "/v1/accounts/devices/enroll", new { label = Environment.MachineName, platform = "windows", existing_token = existed ? settings.Token : "", enrollment_id = enrollmentId });
        var token = String(result, "token"); var account = String(result, "account");
        if (token.Length < 24 || account != settings.NativeAccount) throw new InvalidOperationException("服务返回的设备归属无法确认，请重试。");
        settings.EnrollmentId = ""; settings.Account = account; settings.Token = token; settings.ConnectionInvalid = false; if (!existed) settings.Enabled = false; settings.Save(); await deviceChanged();
    }
    async Task Refresh()
    {
        try { me = await api.Request(Endpoint, "/v1/accounts/me"); }
        catch { if (settings.AccountSession.Length == 0) RenderLogin(); throw; }
        info = await api.Request(Endpoint, "/v1/accounts/info", authenticated: false);
        RenderAccount(); RenderGroups(); RenderPublic(); notice.Text = "已读取服务器上的最新设置。修改分享范围后请保存。";
    }
    void RenderAccount()
    {
        accountPanel.Children.Clear();
        accountPanel.Children.Add(Label("✓ 已登录 " + String(me, "name"), 20)); accountPanel.Children.Add(Label(Endpoint, 12));
        var controls = new WrapPanel(); controls.Children.Add(Button("刷新账号", Refresh)); controls.Children.Add(Button("退出账号管理", Logout)); accountPanel.Children.Add(controls);
        accountPanel.Children.Add(Label("退出账号管理只清除本机登录会话，设备后台分享仍按当前设置运行。", 12));
        if (recovery.Length > 0)
        {
            accountPanel.Children.Add(Label("保存恢复码", 18)); accountPanel.Children.Add(Label("忘记密码时需要它。恢复码只显示这一次，请保存到私密位置。", 12));
            accountPanel.Children.Add(new TextBox { Text = recovery, IsReadOnly = true, TextWrapping = TextWrapping.Wrap });
            accountPanel.Children.Add(Button("保存恢复码文件", () => { var picker = new SaveFileDialog { FileName = "StateOfMe-恢复码.txt", Filter = "文本文件|*.txt" }; if (picker.ShowDialog(this) == true) { File.WriteAllText(picker.FileName, "State of Me\n服务：" + Endpoint + "\n账号：" + settings.NativeAccount + "\n恢复码：" + recovery, Encoding.UTF8); recoverySaved = true; notice.Text = "恢复码已保存，请私密保管。"; } return Task.CompletedTask; }));
        }
        if (settings.Token.Length < 24) accountPanel.Children.Add(Button("将本机添加到账号", async () => { await Enroll(); await Refresh(); }));
        if (settings.Token.Length < 24 && settings.EnrollmentId.Length > 0) accountPanel.Children.Add(Button("重新开始设备登记", () => { if (!Confirm("仅在服务器提示原登记已撤销时使用。网络超时请继续原登记，避免重复设备。确认开始一次新的登记？", "重置登记请求")) return Task.CompletedTask; settings.EnrollmentId = ""; settings.Save(); notice.Text = "已重置，请点击将本机添加到账号。"; return Task.CompletedTask; }));
        accountPanel.Children.Add(Label("我的设备", 19));
        foreach (var device in Items(me, "devices"))
        {
            var id = String(device, "id"); var label = String(device, "label");
            var row = new StackPanel { Margin = new Thickness(0, 6, 0, 12) }; row.Children.Add(Label(label + " · " + String(device, "platform")));
            row.Children.Add(Button("移除此设备", async () => { if (!Confirm("移除“" + label + "”后，该设备的上传凭据会失效。需要重新登录才能恢复连接。", "移除设备")) return; await api.Request(Endpoint, "/v1/accounts/devices/revoke", new { id }); await Refresh(); notice.Text = "设备已移除。若移除的是本机，采集连接将在下次请求时失效。"; })); accountPanel.Children.Add(row);
        }
    }
    async Task Logout()
    {
        try { await api.Request(Endpoint, "/v1/accounts/logout", new { }); }
        finally { settings.ClearAccountSession(); settings.Save(); recovery = ""; RenderLogin(); }
        notice.Text = "已退出账号管理，本机采集连接未改变。";
    }
    bool Confirm(string text, string title) => MessageBox.Show(this, text, title, MessageBoxButton.YesNo, MessageBoxImage.Question) == MessageBoxResult.Yes;
    void RenderGroups()
    {
        groupPanel.Children.Clear(); groupChecks.Clear(); renderedGroup = "";
        var qq = Strings(me, "qq"); groupPanel.Children.Add(Label(qq.Length > 0 ? "✓ QQ 已验证：" + string.Join("、", qq) : "先验证你的 QQ 身份", 20));
        groupPanel.Children.Add(Label("维护者接入群后，你可以决定自己的哪些数据允许该群查询。验证命令需在 QQ 中发送，回到这里确认即可。", 13));
        foreach (var pending in Items(me, "pending"))
        {
            var id = String(pending, "id"); var identity = "QQ " + String(pending, "qq") + "，群 " + String(pending, "group_id");
            groupPanel.Children.Add(Button("确认 " + identity, async () => { if (!Confirm("请核对这是你的身份：" + identity, "确认 QQ 身份")) return; await api.Request(Endpoint, "/v1/accounts/bind-confirm", new { id }); await Refresh(); }));
        }
        groupPanel.Children.Add(Label("群号（也可直接输入）"));
        groupChoice.ItemsSource = Strings(me, "groups").Concat(Strings(info, "groups")).Distinct().ToArray();
        groupPanel.Children.Add(groupChoice); groupPanel.Children.Add(bindCommand);
        var controls = new WrapPanel();
        controls.Children.Add(Button("生成 QQ 验证命令", async () => { var group = groupChoice.Text.Trim(); if (!System.Text.RegularExpressions.Regex.IsMatch(group, "^\\d{5,12}$")) throw new InvalidOperationException("请输入正确的群号。"); if (!Confirm("将在群 " + group + " 验证身份。确认后可按字段调整查询范围。", "验证 QQ")) return; var result = await api.Request(Endpoint, "/v1/accounts/bind-code", new { group_id = group, consent = true }); bindCommand.Text = "/状态绑定 " + String(result, "code"); notice.Text = "在该群发送此命令，再点击刷新，核对 QQ 后确认。"; }));
        controls.Children.Add(Button("复制验证命令", () => { if (bindCommand.Text.Length == 0) throw new InvalidOperationException("请先生成验证命令。"); Clipboard.SetText(bindCommand.Text); notice.Text = "命令已复制，请发送到所填 QQ 群。"; return Task.CompletedTask; }));
        controls.Children.Add(Button("刷新验证结果", Refresh)); groupPanel.Children.Add(controls);
        groupPanel.Children.Add(Button("读取此群的分享范围", async () => { await LoadGroupScope(); }));
        groupPanel.Children.Add(Label("选择分享字段", 18));
        var choices = new StackPanel(); choices.Tag = "scope-choices"; groupPanel.Children.Add(choices);
        groupPanel.Children.Add(Button("保存此群分享范围", async () => { var group = groupChoice.Text.Trim(); if (group != renderedGroup) throw new InvalidOperationException("请先读取这个群的分享范围，再保存。"); var fields = groupChecks.Where(x => x.Value.IsChecked == true).Select(x => x.Key).ToArray(); await api.Request(Endpoint, "/v1/accounts/groups/scope", new { group_id = group, fields, expected_revision = me.GetProperty("scope_revision").GetInt64() }); await Refresh(); notice.Text = "群分享范围已保存。手机和网页会读取相同设置。"; }, true));
        groupPanel.Children.Add(Button("撤回此群全部授权", async () => { var id = groupChoice.Text.Trim(); if (!Confirm("撤回群 " + id + " 的全部查询权限？", "撤回群授权")) return; await api.Request(Endpoint, "/v1/accounts/groups/revoke", new { id }); await Refresh(); }));
        if (qq.Length > 0) groupPanel.Children.Add(Button("解除 QQ 绑定", async () => { if (!Confirm("解除 QQ 身份并撤回全部群授权？", "解除 QQ")) return; await api.Request(Endpoint, "/v1/accounts/qq/unbind", new { }); await Refresh(); }));
    }
    Task LoadGroupScope()
    {
        var group = groupChoice.Text.Trim();
        if (!Strings(me, "groups").Contains(group)) throw new InvalidOperationException("请先为这个群完成 QQ 验证和确认。");
        renderedGroup = group; groupChecks.Clear();
        var selected = me.TryGetProperty("group_scopes", out var scopes) ? Strings(scopes, group) : Array.Empty<string>();
        var panel = groupPanel.Children.OfType<StackPanel>().First(x => Equals(x.Tag, "scope-choices")); panel.Children.Clear(); AddScopes(panel, groupChecks, selected);
        notice.Text = "正在编辑群 " + group + "。勾选字段后保存，未勾选的内容不向该群展示。"; return Task.CompletedTask;
    }
    void AddScopes(StackPanel target, Dictionary<string, CheckBox> checks, IEnumerable<string> selected)
    {
        var set = selected.ToHashSet();
        foreach (var group in Items(info, "scopes").GroupBy(x => String(x, "group")))
        {
            var panel = new StackPanel { Margin = new Thickness(8) };
            var sectionChecks = new List<CheckBox>();
            foreach (var item in group)
            {
                var key = String(item, "key"); if (key.Length == 0) continue;
                var c = new CheckBox { Content = String(item, "label"), ToolTip = String(item, "description"), IsChecked = set.Contains(key), Margin = new Thickness(0, 5, 0, 5) };
                checks[key] = c; sectionChecks.Add(c); panel.Children.Add(c);
            }
            var controls = new WrapPanel(); controls.Children.Add(Button("选择本类全部", () => { foreach (var c in sectionChecks) c.IsChecked = true; notice.Text = "已选择本类字段，保存后生效。"; return Task.CompletedTask; })); controls.Children.Add(Button("清空本类", () => { foreach (var c in sectionChecks) c.IsChecked = false; notice.Text = "已清空本类字段，保存后生效。"; return Task.CompletedTask; })); panel.Children.Add(controls);
            target.Children.Add(new Expander { Header = group.Key + "（" + sectionChecks.Count + " 项）", Content = panel, Margin = new Thickness(0, 4, 0, 8), IsExpanded = sectionChecks.Any(c => c.IsChecked == true) });
        }
    }
    void RenderPublic()
    {
        publicPanel.Children.Clear(); publicChecks.Clear();
        publicPanel.Children.Add(Label("访客可以看到哪些内容", 20));
        publicPanel.Children.Add(Label("这里勾选并保存的内容，任何获得公开页面或博客卡片地址的人都能查看。可以全部不选，或只公开少量字段；新增字段不会自动勾选。", 13));
        AddScopes(publicPanel, publicChecks, Strings(me, "public_fields"));
        publicPanel.Children.Add(Button("保存公开范围", async () => { var fields = publicChecks.Where(x => x.Value.IsChecked == true).Select(x => x.Key).ToArray(); var names = publicChecks.Where(x => x.Value.IsChecked == true).Select(x => x.Value.Content?.ToString()); if (!Confirm(fields.Length == 0 ? "关闭全部公开展示？" : "以下内容将向所有访客公开：\n" + string.Join("、", names), "确认公开范围")) return; await api.Request(Endpoint, "/v1/accounts/public", new { fields, expected_revision = me.GetProperty("scope_revision").GetInt64() }); await Refresh(); notice.Text = fields.Length == 0 ? "公开展示已关闭。" : "公开范围已保存，博客卡片会按已公开字段更新。"; }, true));
        publicPanel.Children.Add(Button("关闭全部公开展示", async () => { if (!Confirm("关闭这个账号的全部公开展示？", "关闭公开展示")) return; await api.Request(Endpoint, "/v1/accounts/public", new { fields = Array.Empty<string>(), expected_revision = me.GetProperty("scope_revision").GetInt64() }); await Refresh(); }));
        publicPanel.Children.Add(Button("复制公开页面地址", () => { Clipboard.SetText(Endpoint + "/?user=" + Uri.EscapeDataString(settings.NativeAccount)); notice.Text = "公开页面地址已复制。页面只包含服务器已授权的字段。"; return Task.CompletedTask; }));
    }
}
