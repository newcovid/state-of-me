namespace StateOfMe;

/// <summary>Only local collection preferences; applying these never changes synchronization intent or audience grants.</summary>
public sealed class CollectionOptions
{
    public string AppMode { get; set; } = "";
    public List<string> Apps { get; set; } = new();
    public Dictionary<string, string> AppAliases { get; set; } = new();
    public List<string> Players { get; set; } = new();
    public string PreferredPlayer { get; set; } = "";
    public bool Presence { get; set; }
    public bool Device { get; set; }
    public bool Network { get; set; }
    public bool Audio { get; set; }
    public bool Resources { get; set; }
    public bool Display { get; set; }
    public bool WhatPulse { get; set; }
    public bool Music { get; set; }
    public string MusicDirectory { get; set; } = "";
    public int IdleMinutes { get; set; }
    public int WhatPulsePort { get; set; }
    public void ApplyTo(Settings target)
    {
        target.AppMode = AppMode;
        target.Apps = Apps;
        target.AppAliases = AppAliases;
        target.Players = Players;
        target.PreferredPlayer = PreferredPlayer;
        target.Presence = Presence;
        target.Device = Device;
        target.Network = Network;
        target.Audio = Audio;
        target.Resources = Resources;
        target.Display = Display;
        target.WhatPulse = WhatPulse;
        target.Music = Music;
        target.MusicDirectory = MusicDirectory;
        target.IdleMinutes = IdleMinutes;
        target.WhatPulsePort = WhatPulsePort;
    }
}
