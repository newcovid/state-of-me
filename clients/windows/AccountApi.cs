using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.Json;
namespace StateOfMe;

/// <summary>Account capabilities never use the collector device credential.</summary>
public sealed class AccountApi
{
    readonly Settings settings;
    public AccountApi(Settings settings) { this.settings = settings; }
    public static string NormalizeEndpoint(string value)
    {
        if (!Uri.TryCreate(value.Trim(), UriKind.Absolute, out var uri) || uri.Scheme != "https" || !string.IsNullOrEmpty(uri.UserInfo) || !string.IsNullOrEmpty(uri.Query) || !string.IsNullOrEmpty(uri.Fragment))
            throw new InvalidOperationException("请填写 HTTPS 服务地址，不要包含账号、查询参数或页面锚点。");
        return uri.AbsoluteUri.TrimEnd('/');
    }
    public async Task<JsonElement> Request(string endpoint, string path, object? body = null, bool authenticated = true)
    {
        endpoint = NormalizeEndpoint(endpoint);
        var token = settings.AccountSession;
        if (authenticated && (token.Length < 24 || settings.NativeEndpoint != endpoint))
            throw new InvalidOperationException("账号登录已过期，请重新登录；本机采集连接不受影响。");
        using var http = new HttpClient(new HttpClientHandler { AllowAutoRedirect = false, UseCookies = false }) { Timeout = TimeSpan.FromSeconds(15), MaxResponseContentBufferSize = 1048576 };
        using var request = new HttpRequestMessage(body == null ? HttpMethod.Get : HttpMethod.Post, endpoint + path);
        if (body != null) request.Content = new StringContent(JsonSerializer.Serialize(body), Encoding.UTF8, "application/json");
        request.Headers.Add("X-SOM-CSRF", "1");
        if (authenticated)
        {
            request.Headers.Authorization = new("Bearer", token);
            request.Headers.Add("X-SOM-Account", settings.NativeAccount);
        }
        using var response = await http.SendAsync(request);
        var text = await response.Content.ReadAsStringAsync();
        if (!response.IsSuccessStatusCode)
        {
            if (response.StatusCode == HttpStatusCode.Unauthorized && authenticated && settings.AccountSession == token)
            { settings.ClearAccountSession(); settings.Save(); }
            var detail = "服务请求失败（HTTP " + (int)response.StatusCode + "），请重试。";
            try { var error = JsonDocument.Parse(text).RootElement; if (error.TryGetProperty("detail", out var value) && value.ValueKind == JsonValueKind.String) detail = value.GetString()!; } catch { }
            throw new InvalidOperationException(detail);
        }
        return JsonDocument.Parse(text).RootElement.Clone();
    }
}
