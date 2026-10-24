using StateOfMe;
static void Check(bool condition,string name){if(!condition)throw new Exception(name);}
var policy=new PublishPolicy();Check(policy.Due(0,"a"),"initial");policy.Success(0,"a");Check(!policy.Due(19999,"a"),"dedup");Check(policy.Due(20000,"a"),"heartbeat");Check(policy.Due(1200,"b"),"event");policy.Fail(1200);Check(!policy.Due(6199,"b",true),"forced event respects retry");Check(policy.Due(6200,"b"),"retry");for(int i=0;i<15;i++)policy.Fail(10000);Check(policy.RetryAt==310000,"retry cap");policy.Reset();Check(policy.Due(10001,"a"),"reconnect");
var cfg=new Settings{Apps=new(){"Editor"}};Check(cfg.Permitted("editor"),"case insensitive whitelist");Check(!cfg.Permitted("browser"),"unknown denied");cfg.AppMode="blacklist";Check(!cfg.Permitted("EDITOR")&&cfg.Permitted("browser"),"blacklist");cfg.AppMode="off";Check(!cfg.Permitted("editor"),"disabled");cfg.Token="test-only-not-a-real-credential";Check(cfg.Token=="test-only-not-a-real-credential","DPAPI round trip");Check(!cfg.ProtectedToken.Contains("test-only"),"encrypted storage");
var lines=LyricsIndex.Parse("[offset:-100]\n[00:00.100]start\n[00:01.20][00:02.300]repeat\n[00:01.20]replace\n[999:59.999]end");Check(lines.Count==4,"timestamps and duplicate");Check(lines[0].Time==0&&lines[1].Time==1100&&lines[1].Text=="replace","offset and fractions");Check(LyricsIndex.Parse("plain lyrics").Count==0,"plain not synchronized");Check(LyricsIndex.Parse("[00:01]"+new string('x',300))[0].Text.Length==200,"line bound");
Console.WriteLine("Windows policy, filtering, DPAPI and lyrics tests passed");

var first=WhatPulseSource.Summarize(System.Text.Json.Nodes.JsonNode.Parse("{\"keys\":100,\"download\":1000,\"uptime\":200}")!,null,20,1000);
var second=WhatPulseSource.Summarize(System.Text.Json.Nodes.JsonNode.Parse("{\"keys\":120,\"download\":3000,\"uptime\":220}")!,first,20,1020);
Check(second["keys_per_second"]!.GetValue<double>()==1&&second["download_bytes_per_second"]!.GetValue<double>()==100,"WhatPulse rates");
var reset=WhatPulseSource.Summarize(System.Text.Json.Nodes.JsonNode.Parse("{\"keys\":10,\"download\":4000,\"uptime\":10}")!,second,20,1040);
Check(reset["download_bytes_per_second"]==null,"Pulse reset invalidates all differences");
Check(WhatPulseSource.Summarize(second,first,100,1100)["sample_seconds"]==null,"stale sample ignored");
Console.WriteLine("WhatPulse delta and reset tests passed");

Check(!System.Text.Json.JsonSerializer.Serialize(cfg).Contains("test-only-not-a-real-credential"),"plaintext token excluded from serialized settings");

var empty=new Settings{AppMode="blacklist"};Check(empty.Permitted("Editor")&&empty.Permitted("UnlistedApp"),"empty blacklist shares all foreground apps");empty.AppMode="whitelist";Check(!empty.Permitted("Editor"),"empty whitelist shares none");

var managed=new Settings{Endpoint="https://status.example.com/presence",NativeEndpoint="https://status.example.com/presence",NativeAccount="alice",AccountSessionExpiresAt=DateTimeOffset.UtcNow.AddHours(1).ToUnixTimeSeconds()};
managed.Token="device-only-test-credential";managed.AccountSession="account-only-test-credential";
Check(managed.AccountSession=="account-only-test-credential"&&managed.Token=="device-only-test-credential","native account and device credentials remain distinct");
Check(!System.Text.Json.JsonSerializer.Serialize(managed).Contains("account-only-test-credential"),"native session plaintext not serialized");
managed.Endpoint="https://another.example.com/presence";Check(managed.AccountSession=="","account session not usable for another service");
managed.Endpoint=managed.NativeEndpoint;managed.NativeEndpoint="https://another.example.com/presence";Check(managed.AccountSession=="","modified endpoint cannot unlock DPAPI session");
managed.NativeEndpoint=managed.Endpoint;managed.AccountSessionExpiresAt=0;Check(managed.AccountSession=="","expired account session not exposed");
managed.ClearAccountSession();Check(managed.ProtectedAccountSession==""&&managed.Token=="device-only-test-credential","logout clears management without changing collector credential");
Check(AccountApi.NormalizeEndpoint(" https://example.com/presence/ ")=="https://example.com/presence","normalized native endpoint");
foreach(var invalid in new[]{"http://example.com","https://user:password@example.com","https://example.com/?token=secret","https://example.com/#account"}){bool rejected=false;try{AccountApi.NormalizeEndpoint(invalid);}catch(InvalidOperationException){rejected=true;}Check(rejected,"reject unsafe endpoint "+invalid);}
Console.WriteLine("Native account credential separation, expiry and service binding tests passed");

var enrolling = new Settings(); var enrollment = enrolling.PrepareEnrollment("https://example.test", "alice");
Check(enrolling.PrepareEnrollment("https://example.test", "alice") == enrollment, "enrollment retries retain id");
Check(enrolling.PrepareEnrollment("https://example.test", "bob") != enrollment, "different account gets independent enrollment");
Console.WriteLine("Windows account isolation and enrollment idempotency tests passed");

var pausedCollector = new Settings { Enabled = false, Account = "alice", Endpoint = "https://example.test", ManualText = "intentional status", ManualUntil = 999, AutoStart = true };
pausedCollector.Token = "device-test-credential-for-isolation";
var choices = new CollectionOptions { AppMode = "blacklist", Apps = new() { "hidden" }, Music = true, WhatPulse = true, WhatPulsePort = 3490, IdleMinutes = 5 };
choices.ApplyTo(pausedCollector);
Check(!pausedCollector.Enabled, "saving collection does not start a paused collector");
Check(pausedCollector.AutoStart && pausedCollector.ManualText == "intentional status" && pausedCollector.ManualUntil == 999, "collection changes preserve startup and manual status");
Check(pausedCollector.Account == "alice" && pausedCollector.Token == "device-test-credential-for-isolation", "collection changes preserve account and device ownership");
Check(pausedCollector.AppMode == "blacklist" && pausedCollector.Apps.SequenceEqual(new[] { "hidden" }) && pausedCollector.Music, "collection draft is applied");
pausedCollector.Enabled = true; choices.ApplyTo(pausedCollector); Check(pausedCollector.Enabled, "saving collection preserves active sync intent");
Console.WriteLine("Collection ownership and explicit synchronization intent tests passed");

var audienceDraft = ScopeDraft.Capture(new[] { "music.title" }, new[] { "music.title", "music.lyric" }, 4);
Check(audienceDraft != null && audienceDraft.Revision == 4, "dirty audience retains original server revision");
Check(audienceDraft!.Fields.SetEquals(new[] { "music.title", "music.lyric" }), "draft preserves selected fields across other audience refresh");
Check(ScopeDraft.Capture(new[] { "music.title" }, new[] { "music.title" }, 8) == null, "clean audience may accept refreshed revision");
Console.WriteLine("Audience draft preservation and revision ownership tests passed");

var emptyPresentation = new Settings();
Check(ClientPresentation.CollectionSummary(emptyPresentation).Contains("尚未选择"), "empty collection guides user to choose data");
emptyPresentation.AppMode = "blacklist";
Check(ClientPresentation.CollectionSummary(emptyPresentation).Contains("排除 0 项"), "empty blacklist accurately means all foreground applications");
emptyPresentation.WhatPulse = true;
Check(ClientPresentation.Statistics(emptyPresentation, null).Contains("已暂停"), "paused WhatPulse does not ask user to enable again");
emptyPresentation.Enabled = true;
Check(ClientPresentation.Statistics(emptyPresentation, null).Contains("连接状态"), "enabled empty WhatPulse points to connection diagnostics");
Check(!ClientPresentation.Statistics(emptyPresentation, first).Contains("since_pulse"), "WhatPulse scope uses readable user language");
Console.WriteLine("Windows onboarding and statistics presentation tests passed");

var completePulse = System.Text.Json.Nodes.JsonNode.Parse("""
{"unpulsed":{"keys":120,"clicks":30,"words":20,"scrolls":9,"distance_inches":25.5,"download":4096,"upload":1024,"uptime":300,"distance_formatted":"64.77cm","words_formatted":"20"},"account-totals":{"keys":"100000","words":"12000","download":"12.5","upload":"3","distance_miles":"2.25","ranks":{"rank_words":"15","rank_words_formatted":"15th"}},"realtime":{"keys":"1,25","clicks":"0,50","download":"12,34KB/s","upload":"1MB/s"},"idle":{"idle_seconds":3,"last_input":"2026-10-25T09:20:00"},"profiles":[{"id":1,"name":"Focus","active":true,"created_at":"2024-01-01","updated_at":"2026-10-25"}]}
""")!;
var fullPulse = WhatPulseSource.SummarizeAll(completePulse, null, 20, 1000);
Check(fullPulse["words"]!.GetValue<long>() == 20 && fullPulse["distance_inches"]!.GetValue<double>() == 25.5, "unpulsed words and distance retained");
Check(fullPulse["account_download_mb"]!.GetValue<double>() == 12.5 && fullPulse["download"]!.GetValue<long>() == 4096, "account MB and local bytes remain separate");
Check(fullPulse["account_rank_words"]!.GetValue<long>() == 15, "all account ranks include words");
Check(fullPulse["realtime_keys"]!.ToString() == "1,25" && fullPulse["realtime_download"]!.ToString() == "12,34KB/s", "localized realtime values preserved without ambiguous parsing");
Check(fullPulse["profiles"]![0]!["active"]!.GetValue<bool>() && fullPulse["whatpulse_idle_seconds"]!.GetValue<long>() == 3, "profiles and idle fields retained");
Check(!fullPulse.ContainsKey("sample_seconds"), "first snapshot never invents differential rates");
var withoutSections = WhatPulseSource.SummarizeAll(System.Text.Json.Nodes.JsonNode.Parse("{\"unpulsed\":{\"keys\":1}}")!, fullPulse, 20, 1020);
Check(!withoutSections.ContainsKey("account_keys") && !withoutSections.ContainsKey("realtime_keys"), "missing sections retract previous values");
var badNumbers = WhatPulseSource.SummarizeAll(System.Text.Json.Nodes.JsonNode.Parse("{\"account-totals\":{\"distance_miles\":\"NaN\",\"keys\":\"-1\"},\"profiles\":[]}")!, null, 20, 1000);
Check(!badNumbers.ContainsKey("account_keys") && !badNumbers.ContainsKey("account_distance_miles") && badNumbers["profiles"]!.AsArray().Count == 0, "invalid numbers omitted and empty profile list is meaningful");
Check(ClientPresentation.WhatPulseDetails(fullPulse).Contains("Focus") && ClientPresentation.WhatPulseDetails(fullPulse).Contains("账号累计"), "all statistics have readable sections");
Console.WriteLine("Full WhatPulse snapshot, units, optional sections and privacy-safe normalization tests passed");
var manyProfiles = new System.Text.Json.Nodes.JsonArray();
for (int i = 0; i < 129; i++) manyProfiles.Add(new System.Text.Json.Nodes.JsonObject { ["id"] = i, ["name"] = "Profile " + i, ["active"] = false });
var boundedProfiles = WhatPulseSource.SummarizeAll(new System.Text.Json.Nodes.JsonObject { ["profiles"] = manyProfiles }, null, 20, 1000);
Check(boundedProfiles["profiles"]!.AsArray().Count == 128 && boundedProfiles["profiles_truncated"]!.GetValue<bool>(), "profile payload bounded with explicit truncation marker");
if (args.Length == 2)
{
    var live = System.Text.Json.Nodes.JsonNode.Parse(File.ReadAllText(args[0]))!;
    var normalized = WhatPulseSource.SummarizeAll(live, null, 20, DateTimeOffset.UtcNow.ToUnixTimeSeconds());
    File.WriteAllText(args[1], normalized.ToJsonString());
    Console.WriteLine("Normalized live snapshot field count: " + normalized.Count);
}
