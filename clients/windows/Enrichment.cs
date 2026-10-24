using System.Globalization;
using System.IO;
using System.Net.Http;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
namespace StateOfMe;
public sealed class LyricsIndex
{
    record Track(string Title, string Artist, string Album, long Duration, List<Line> Lines);
    public record Line(long Time, string Text);
    List<Track> tracks = new(); string folder = "";
    public string Status { get; private set; } = "未选择音乐目录";
    public async Task Index(string path, bool force = false)
    {
        if (path == folder && !force)
            return;
        folder = path;
        if (!Directory.Exists(path))
        {
            tracks = new();
            Status = "未选择可访问的音乐目录";
            return;
        }
        Status = "正在索引内嵌歌词…";
        try
        {
            var result = await Task.Run(() =>
            {
                var list = new List<Track>();
                var options = new EnumerationOptions { RecurseSubdirectories = true, IgnoreInaccessible = true, AttributesToSkip = FileAttributes.ReparsePoint };
                foreach (var f in Directory.EnumerateFiles(path, "*", options).Where(f => new[] { ".mp3", ".flac", ".m4a", ".ogg", ".opus", ".wma" }.Contains(Path.GetExtension(f).ToLowerInvariant())).Take(50000))
                {
                    try
                    {
                        using var file = TagLib.File.Create(f);
                        var t = file.Tag;
                        var text = t.Lyrics ?? "";
                        if (file.GetTag(TagLib.TagTypes.Id3v2, false) is TagLib.Id3v2.Tag id3)
                        {
                            var sylt = id3.GetFrames<TagLib.Id3v2.SynchronisedLyricsFrame>().FirstOrDefault(f => f.Format == TagLib.Id3v2.TimestampFormat.AbsoluteMilliseconds && f.Type==TagLib.Id3v2.SynchedTextType.Lyrics);
                            if (sylt != null)
                                text = string.Join("\n", sylt.Text.Select(x => $"[{x.Time / 60000:D2}:{x.Time / 1000 % 60:D2}.{x.Time % 1000:D3}]{x.Text}"));
                            if (string.IsNullOrWhiteSpace(text))
                            {
                                var custom = id3.GetFrames<TagLib.Id3v2.UserTextInformationFrame>().FirstOrDefault(f => new[] { "LYRICS", "SYNCEDLYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS" }.Contains(f.Description.ToUpperInvariant()));
                                if (custom != null)
                                    text = string.Join("\n", custom.Text);
                            }
                        }
                        var lrc = Path.ChangeExtension(f, ".lrc");
                        if (File.Exists(lrc) && new FileInfo(lrc).Length < 262144)
                            text = File.ReadAllText(lrc);
                        if (text.Length > 262144)
                            continue;
                        if (!string.IsNullOrWhiteSpace(t.Title) && !string.IsNullOrWhiteSpace(text))
                            list.Add(new(t.Title, t.FirstPerformer ?? "", t.Album ?? "", (long)file.Properties.Duration.TotalMilliseconds, Parse(text)));
                    }
                    catch { }
                }
                return list;
            });
            tracks = result;
            Status = $"已索引 {tracks.Count} 首带歌词歌曲";
        }
        catch { tracks = new(); folder = ""; Status = "目录读取失败，请重新选择"; }
    }
    static string Normalize(string s) => s.Trim().Normalize().ToUpperInvariant();
    public void Enrich(JsonObject music)
    {
        var title = Normalize(music["title"]!.ToString());
        var artist = Normalize(music["artist"]!.ToString());
        var album = Normalize(music["album"]!.ToString());
        long duration = music["duration_ms"]!.GetValue<long>();
        var matches = tracks.Where(t => Normalize(t.Title) == title && artist != "" && Normalize(t.Artist) == artist && (album == "" || Normalize(t.Album) == album) && (duration == 0 || Math.Abs(t.Duration - duration) <= 3000)).Take(2).ToArray();
        if (matches.Length != 1)
        {
            music["lyric_status"] = matches.Length > 1 ? "ambiguous" : "unmatched";
            return;
        }
        var lines = matches[0].Lines;
        if (lines.Count == 0)
        {
            music["lyric_status"] = "plain";
            return;
        }
        long pos = music["position_ms"]!.GetValue<long>();
        int current = lines.FindLastIndex(l => l.Time <= pos);
        int begin = Math.Max(0, current);
        var selected = lines.Skip(begin).TakeWhile(l => l.Time <= pos + 60000).Take(16).ToList();
        if (selected.Count == 0)
        {
            music["lyric_status"] = "synced";
            return;
        }
        var end = Math.Min(604800001, Math.Min(pos + 60000, begin + selected.Count < lines.Count ? lines[begin + selected.Count].Time : pos + 60000));
        if (end <= selected[0].Time)
            end = selected[0].Time + 1;
        music["lyric_status"] = "synced";
        music["lyric_window"] = new JsonObject { ["start_ms"] = selected[0].Time, ["end_ms"] = end, ["lines"] = new JsonArray(selected.Where(l => l.Time < end).Select(l => (JsonNode)new JsonObject { ["at_ms"] = l.Time, ["text"] = l.Text }).ToArray()) };
    }
    public static List<Line> Parse(string text)
    {
        var map = new SortedDictionary<long, string>();
        var offsetMatch = Regex.Match(text, @"\[offset:([+-]?\d+)\]", RegexOptions.IgnoreCase);
        long.TryParse(offsetMatch.Groups[1].Value, out var offset);
        foreach (var row in text.Split('\n'))
        {
            var stamps = Regex.Matches(row, @"\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]");
            var value = Regex.Replace(row, @"\[[^\]]*\]", "").Trim();
            if (value.Length > 200)
                value = value[..200];
            foreach (Match stamp in stamps)
            {
                var fraction = stamp.Groups[3].Value.PadRight(3, '0');
                var ms = long.Parse(stamp.Groups[1].Value) * 60000 + long.Parse(stamp.Groups[2].Value) * 1000 + long.Parse(fraction) + offset;
                if (ms >= 0 && ms < 604800000)
                    map[ms] = value;
            }
        }
        return map.Select(x => new Line(x.Key, x.Value)).ToList();
    }
}
