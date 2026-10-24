using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
namespace StateOfMe;
public sealed class Settings
{
    public string Endpoint { get; set; } = "";
    public string ProtectedToken { get; set; } = "";
    public string AppMode { get; set; } = "whitelist";
    public Dictionary<string, string> AppAliases { get; set; } = new(StringComparer.OrdinalIgnoreCase);
    public List<string> Apps { get; set; } = new();
    public List<string> Players { get; set; } = new();
    public string PreferredPlayer { get; set; } = "";
    public bool Music { get; set; } = false;
    public bool Device { get; set; } = false;
    public bool Presence { get; set; } = false;
    public bool Network { get; set; } = false;
    public bool Audio { get; set; } = false;
    public bool Resources { get; set; } = false;
    public bool Display { get; set; } = false;
    public bool WhatPulse { get; set; } = false;
    public int WhatPulsePort { get; set; } = 3490;
    public int IdleMinutes { get; set; } = 3;
    public string MusicDirectory { get; set; } = "";
    public string ManualText { get; set; } = "";
    public long ManualUntil
    {
        get; set;
    }
    public long Sequence
    {
        get; set;
    }
    public bool Enabled
    {
        get; set;
    }
    public bool AutoStart
    {
        get; set;
    }
    public static string Folder => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "StateOfMe");
    public static string FilePath => Path.Combine(Folder, "settings.json");
    public string Token
    {
        get
        {
            try
            {
                return Encoding.UTF8.GetString(ProtectedData.Unprotect(Convert.FromBase64String(ProtectedToken), null, DataProtectionScope.CurrentUser));
            }
            catch { return ""; }
        }
        set
        {
            ProtectedToken = Convert.ToBase64String(ProtectedData.Protect(Encoding.UTF8.GetBytes(value), null, DataProtectionScope.CurrentUser));
        }
    }
    public static Settings Load()
    {
        try
        {
            return JsonSerializer.Deserialize<Settings>(File.ReadAllText(FilePath)) ?? new();
        }
        catch { return new(); }
    }
    public void Save()
    {
        Directory.CreateDirectory(Folder);
        var tmp = FilePath + ".tmp";
        File.WriteAllText(tmp, JsonSerializer.Serialize(this));
        File.Move(tmp, FilePath, true);
    }
    public bool Permitted(string id) => AppMode == "blacklist" ? !Apps.Contains(id, StringComparer.OrdinalIgnoreCase) : AppMode == "whitelist" && Apps.Contains(id, StringComparer.OrdinalIgnoreCase);
}
public sealed class PublishPolicy
{
    public long LastSuccess { get; private set; } = -20000;
    public long RetryAt
    {
        get; private set;
    }
    public string Key { get; private set; } = "";
    int failures;
    public bool Due(long now, string key, bool force = false) => now >= RetryAt && (force || now - LastSuccess >= 20000 || key != Key);
    public void Success(long now, string key)
    {
        LastSuccess = now;
        Key = key;
        RetryAt = 0;
        failures = 0;
    }
    public void Fail(long now)
    {
        RetryAt = now + Math.Min(300000, 5000L << Math.Min(failures++, 6));
    }
    public void Reset()
    {
        RetryAt = 0;
        Key = "";
        failures = 0;
    }
}
