using System.Diagnostics;
using System.Net.Http;
using System.Net.Http.Json;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Threading;
using Microsoft.Win32;

namespace StateOfMe;
public partial class MainWindow : Window
{
    [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
    [StructLayout(LayoutKind.Sequential)] struct LastInput { public uint size, tick; }
    [DllImport("user32.dll")] static extern bool GetLastInputInfo(ref LastInput input);
    readonly HttpClient client = new(new HttpClientHandler { AllowAutoRedirect = false }) { Timeout = TimeSpan.FromSeconds(8) };
    readonly SemaphoreSlim gate = new(1,1);
    readonly DispatcherTimer timer = new() { Interval = TimeSpan.FromSeconds(5) };
    bool enabled, locked, closing;
    string endpoint="", token="";
    HashSet<string> allow = new(StringComparer.OrdinalIgnoreCase);
    long sequence;
    public MainWindow() {
        InitializeComponent();
        // Optional private provisioning file only fills controls; never auto-start.
        var args=Environment.GetCommandLineArgs();
        if(args.Length==3 && args[1]=="--config")try {
            using var cfg=System.Text.Json.JsonDocument.Parse(System.IO.File.ReadAllText(args[2]));
            Endpoint.Text=cfg.RootElement.GetProperty("url").GetString();
            Token.Password=cfg.RootElement.GetProperty("token").GetString()??"";
        }catch {Status.Text="配置文件读取失败，请手动填写";}
        timer.Tick += async (_,_) => await Send(); SystemEvents.SessionSwitch += SessionChanged; Closing += async (_,e) => { if(closing)return; e.Cancel=true;enabled=false;timer.Stop();await Send();closing=true;SystemEvents.SessionSwitch-=SessionChanged;Close(); };
    }
    void SessionChanged(object sender,SessionSwitchEventArgs e) { Dispatcher.Invoke(()=> { if(e.Reason==SessionSwitchReason.SessionLock)locked=true; if(e.Reason==SessionSwitchReason.SessionUnlock)locked=false; }); }
    async void Start(object sender,RoutedEventArgs e) {
        if(!Uri.TryCreate(Endpoint.Text.Trim(),UriKind.Absolute,out var uri)||uri.Scheme!="https"||Token.Password.Length<24){Status.Text="请填写 HTTPS 地址和有效写入令牌";return;}
        enabled=false;await gate.WaitAsync();try{endpoint=Endpoint.Text.Trim().TrimEnd('/');token=Token.Password;allow=Allow.Text.Split(',').Select(x=>x.Trim()).Where(x=>x.Length>0).ToHashSet(StringComparer.OrdinalIgnoreCase);enabled=true;}finally{gate.Release();}timer.Start();await Send();
    }
    async void Pause(object sender,RoutedEventArgs e) { enabled=false;timer.Stop();await Send(); }
    async Task Send() {
        await gate.WaitAsync();try {
            if(endpoint.Length==0)return;
            string? app=null;
            var input=new LastInput { size=(uint)Marshal.SizeOf<LastInput>() };
            bool idle=!GetLastInputInfo(ref input)||unchecked((uint)Environment.TickCount-input.tick)>180000;
            if(enabled&&!locked&&!idle)try{GetWindowThreadProcessId(GetForegroundWindow(),out uint pid);using var process=Process.GetProcessById((int)pid);if(allow.Contains(process.ProcessName))app=process.ProcessName;}catch { }
            sequence=Math.Max(DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),sequence+1);
            var payload=new {seq=sequence,observed_at=DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()/1000.0,hidden=!enabled,app};
            using var request=new HttpRequestMessage(HttpMethod.Post,endpoint+"/v1/events");request.Headers.Authorization=new("Bearer",token);request.Content=JsonContent.Create(payload);
            using var response=await client.SendAsync(request);
            Status.Text=response.IsSuccessStatusCode?(!enabled?"已暂停，服务端已清空":$"已同步 {DateTime.Now:HH:mm:ss} · {app??"暂无允许分享的前台应用"}"):$"HTTP {(int)response.StatusCode}；旧状态会过期";
        }catch { Status.Text="连接失败；旧状态最多保留 45 秒"; }finally {gate.Release();}
    }
}
