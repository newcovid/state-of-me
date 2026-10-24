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
