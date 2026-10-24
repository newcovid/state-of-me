package io.github.stateofme;

import android.app.AppOpsManager;
import android.app.usage.*;
import android.content.*;
import android.util.AtomicFile;
import java.io.*;
import java.net.*;
import java.time.*;
import java.util.*;
import org.json.*;

/** Local daily summaries; upload cadence is independent from presence updates. */
public final class UsageHistory {
 private static UsageLedger ledger;
 private static long lastRefresh,nextSync;
 private static String syncKey="";
 private static final Map<String,String> labels=new HashMap<>();
 private static AtomicFile file(Context c){return new AtomicFile(new File(c.getFilesDir(),"usage-ledger-v1.bin"));}
 public static boolean granted(Context c){return c.getSystemService(AppOpsManager.class).checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,android.os.Process.myUid(),c.getPackageName())==AppOpsManager.MODE_ALLOWED;}
 private static void load(Context c,long now){
  if(ledger==null)try(ObjectInputStream in=new ObjectInputStream(file(c).openRead())){ledger=(UsageLedger)in.readObject();}catch(Exception ignored){}
  String zone=ZoneId.systemDefault().getId();
  if(ledger==null||!ledger.zone.equals(zone)||ledger.cursor>now)ledger=new UsageLedger(zone,now-7*86400000L);
 }
 public static synchronized void clear(Context c){file(c).delete();ledger=new UsageLedger(ZoneId.systemDefault().getId(),System.currentTimeMillis());lastRefresh=0;nextSync=0;}
 public static synchronized JSONObject refresh(Context c,boolean force)throws Exception{
  long now=System.currentTimeMillis();load(c,now);
  if(!granted(c))throw new SecurityException("需要使用情况访问权限");
  long elapsed=android.os.SystemClock.elapsedRealtime();
  if(force||lastRefresh==0||elapsed-lastRefresh>=15*60000L){
   // An old checkpoint may precede the system event retention window. Do not extend stale state through that gap.
   if(lastRefresh==0&&ledger.firstEvent!=0&&now-ledger.cursor>86400000L)ledger.gap(now-86400000L);
   UsageEvents events=c.getSystemService(UsageStatsManager.class).queryEvents(ledger.cursor,now);
   if(events==null)throw new IOException("系统暂未提供统计，请解锁后重试");
   UsageEvents.Event e=new UsageEvents.Event();
   while(events.hasNextEvent()){events.getNextEvent(e);ledger.event(e.getTimeStamp(),e.getEventType(),e.getPackageName(),e.getClassName());}
   ledger.advance(now);
   AtomicFile f=file(c);FileOutputStream out=f.startWrite();
   try{ObjectOutputStream objects=new ObjectOutputStream(out);objects.writeObject(ledger);objects.flush();f.finishWrite(out);}catch(Exception ex){f.failWrite(out);throw ex;}
   lastRefresh=elapsed;
  }
  return snapshot(c,false);
 }
 private static JSONObject snapshot(Context c,boolean filtered)throws Exception{
  SharedPreferences p=c.getSharedPreferences("som",0);JSONArray days=new JSONArray();
  for(Map.Entry<String,UsageLedger.Day> entry:ledger.days.entrySet()){
   UsageLedger.Day d=entry.getValue();
   // The initial query window may be longer than the system's retained events.
   // Omit completely unobserved days instead of presenting them as zero-use days.
   if(d.known==0&&d.screen==0&&d.unlocks==0&&d.apps.isEmpty())continue;
   JSONArray apps=new JSONArray(),hours=new JSONArray();for(long hour:d.hours)hours.put(hour);
   List<Map.Entry<String,UsageLedger.App>> ranked=new ArrayList<>(d.apps.entrySet());ranked.sort((a,b)->Long.compare(b.getValue().ms,a.getValue().ms));
   int eligible=0;
   for(Map.Entry<String,UsageLedger.App> a:ranked){
    if(filtered&&!AppFilter.allows(p.getString("apps_mode","whitelist"),p.getString("apps",""),p.getString("apps_blocked",""),p.getString("apps_candidates",""),a.getKey()))continue;
    eligible++;if(apps.length()>=50)continue;
    String label=labels.get(a.getKey());if(label==null){label=a.getKey();try{label=c.getPackageManager().getApplicationLabel(c.getPackageManager().getApplicationInfo(a.getKey(),0)).toString();}catch(Exception ignored){}labels.put(a.getKey(),label);}
    apps.put(new JSONObject().put("package",a.getKey()).put("label",label.substring(0,Math.min(120,label.length()))).put("foreground_ms",a.getValue().ms).put("sessions",a.getValue().opens));
   }
   days.put(new JSONObject().put("date",entry.getKey()).put("screen_ms",d.screen).put("unlocked_ms",d.unlocked).put("known_ms",d.known).put("unlocks",d.unlocks).put("hourly_screen_ms",hours).put("apps",apps).put("apps_truncated",eligible>50));
  }
  return new JSONObject().put("source","android_usage_events").put("timezone",ledger.zone).put("sampled_at",ledger.cursor/1000).put("first_event_at",ledger.firstEvent/1000).put("days",days);
 }
 public static synchronized void retry(){nextSync=0;}
 public static synchronized void sync(Context c,boolean hidden){
  SharedPreferences p=c.getSharedPreferences("som",0);long elapsed=android.os.SystemClock.elapsedRealtime();
  boolean enabled=!hidden&&p.getBoolean("usage_history",false)&&granted(c);
  String key=enabled+"/"+p.getString("token","")+"/"+p.getString("url","")+"/"+p.getString("apps_mode","")+"/"+p.getString("apps","")+"/"+p.getString("apps_blocked","")+"/"+p.getString("apps_candidates","");
  if(!key.equals(syncKey)){syncKey=key;nextSync=0;}
  if(elapsed<nextSync)return;
  nextSync=elapsed+15*60000L;
  if(!enabled&&!p.getBoolean("usage_uploaded",false))return;
  if(p.getString("token","").length()<24||!p.getString("url","").startsWith("https://"))return;
  HttpURLConnection conn=null;
  try{
   JSONObject body;
   if(enabled){refresh(c,false);body=snapshot(c,true);}else body=new JSONObject().put("clear",true);
   conn=(HttpURLConnection)new URL(p.getString("url","")+"/v1/usage").openConnection();conn.setConnectTimeout(8000);conn.setReadTimeout(8000);conn.setInstanceFollowRedirects(false);conn.setRequestMethod("POST");conn.setDoOutput(true);conn.setRequestProperty("Content-Type","application/json");conn.setRequestProperty("Authorization","Bearer "+p.getString("token",""));
   // A timeout can happen after the server accepts the body. Remember the possible
   // upload before sending, so disabling sharing still retries a server-side clear.
   if(enabled)p.edit().putBoolean("usage_uploaded",true).commit();
   byte[] bytes=body.toString().getBytes("UTF-8");conn.setFixedLengthStreamingMode(bytes.length);try(OutputStream out=conn.getOutputStream()){out.write(bytes);}
   if(conn.getResponseCode()!=200)throw new IOException("HTTP "+conn.getResponseCode());
   p.edit().putBoolean("usage_uploaded",enabled).putString("usage_sync_status",enabled?"统计已同步；每 15 分钟择机更新":"服务端统计已清除").apply();
  }catch(Exception ex){android.util.Log.w("SomUsage",ex.getClass().getSimpleName()+": "+ex.getMessage());p.edit().putString("usage_sync_status","统计未同步（"+(ex.getMessage()!=null&&ex.getMessage().startsWith("HTTP ")?ex.getMessage():"网络或系统暂不可用")+"），点刷新重试；可在账号页撤回群授权").apply();}
  finally{if(conn!=null)conn.disconnect();}
 }
}
