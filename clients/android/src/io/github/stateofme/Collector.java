package io.github.stateofme;
import android.app.*;
import android.app.usage.*;
import android.content.*;
import android.media.*;
import android.media.session.*;
import android.os.*;
import org.json.*;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class Collector extends Service {
 MediaController watched;ScheduledFuture<?> pending;long completedAt;
 final MediaController.Callback playbackCallback=new MediaController.Callback(){
  @Override public void onPlaybackStateChanged(PlaybackState state){requestUpdate();}
  @Override public void onMetadataChanged(MediaMetadata metadata){requestUpdate();}
  @Override public void onSessionDestroyed(){requestUpdate();}
 };
 void requestUpdate(){
  if(executor==null||executor.isShutdown())return;
  try{executor.execute(()->{
   if(!prefs.getBoolean("enabled",false)||pending!=null&&!pending.isDone())return;
   long delay=UpdateTiming.delay(SystemClock.elapsedRealtime(),completedAt);
   pending=executor.schedule(()->{pending=null;if(prefs.getBoolean("enabled",false)){send(false);completedAt=SystemClock.elapsedRealtime();}},delay,TimeUnit.MILLISECONDS);
  });}catch(RejectedExecutionException ignored){}
 }
 void watch(MediaController controller){
  if(watched!=null&&controller!=null&&watched.getSessionToken().equals(controller.getSessionToken()))return;
  if(watched!=null)watched.unregisterCallback(playbackCallback);
  watched=controller;if(watched!=null)watched.registerCallback(playbackCallback,new Handler(Looper.getMainLooper()));
 }
 String lyricKey=""; MusicLibrary.Result embedded=new MusicLibrary.Result();
 String timelineSource="";List<Lyrics.Line> timeline=Collections.emptyList();
 JSONObject window(String source,long position,long duration)throws Exception{
  if(!source.equals(timelineSource)){timeline=Lyrics.parse(source);timelineSource=source;}
  Lyrics.Window window=Lyrics.window(timeline,position,duration);if(window==null)return null;
  JSONArray lines=new JSONArray();
  for(Lyrics.Line line:window.lines)lines.put(new JSONObject().put("at_ms",line.at).put("text",line.text));
  return new JSONObject().put("start_ms",window.start).put("end_ms",window.end).put("lines",lines);
 }
 ScheduledExecutorService executor;android.content.SharedPreferences prefs;volatile boolean stopping;
 @Override public void onCreate(){super.onCreate();prefs=getSharedPreferences("som",0);NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("sharing","状态分享",NotificationManager.IMPORTANCE_LOW));
  PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
  PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,Collector.class).setAction("stop"),PendingIntent.FLAG_IMMUTABLE);
  Notification n=new Notification.Builder(this,"sharing").setSmallIcon(android.R.drawable.ic_menu_info_details).setContentTitle("State of Me 正在分享允许的状态").setContentText("点击查看权限和过滤；暂停会清空状态").setContentIntent(open).addAction(new Notification.Action.Builder(null,"暂停",stop).build()).setOngoing(true).build();startForeground(1,n);executor=Executors.newSingleThreadScheduledExecutor();
 }
 @Override public int onStartCommand(Intent i,int flags,int id){if(i!=null&&"stop".equals(i.getAction())){stopping=true;prefs.edit().putBoolean("enabled",false).apply();executor.execute(()->{send(true);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();});}else if(!prefs.getBoolean("enabled",false)){stopSelf();}else if(!stopping){executor.scheduleWithFixedDelay(()->requestUpdate(),0,5,TimeUnit.SECONDS);stopping=true;}return START_NOT_STICKY;}
 boolean allowed(String list,String item){for(String s:prefs.getString(list,"").split(","))if(s.trim().equals(item))return true;return false;}
 String trim(String s,int n){if(s==null)return "";return s.substring(0,Math.min(n,s.length()));}
 void send(boolean forceHidden){
  try{
   boolean hidden=forceHidden||!prefs.getBoolean("enabled",false);JSONObject e=new JSONObject();long now=System.currentTimeMillis();long seq=Math.max(now,prefs.getLong("seq",0)+1);prefs.edit().putLong("seq",seq).commit();e.put("seq",seq).put("observed_at",now/1000.0).put("hidden",hidden);
   if(!hidden){
    PowerManager power=getSystemService(PowerManager.class);KeyguardManager keyguard=getSystemService(KeyguardManager.class);
    if(prefs.getBoolean("device_state",false)){
     JSONObject deviceState=new JSONObject().put("screen_on",power.isInteractive()).put("locked",keyguard.isKeyguardLocked()).put("power_save",power.isPowerSaveMode());
     Intent battery=registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
     if(battery!=null){int level=battery.getIntExtra(BatteryManager.EXTRA_LEVEL,-1),scale=battery.getIntExtra(BatteryManager.EXTRA_SCALE,-1);if(level>=0&&scale>0)deviceState.put("battery_percent",Math.max(0,Math.min(100,Math.round(100f*level/scale))));int charging=battery.getIntExtra(BatteryManager.EXTRA_STATUS,-1);deviceState.put("charging",charging==BatteryManager.BATTERY_STATUS_CHARGING||charging==BatteryManager.BATTERY_STATUS_FULL);}
     DeviceContext.battery(battery,deviceState);e.put("device_state",deviceState);
    }
    DeviceContext.collect(this,prefs,e);
    try{if(power.isInteractive()&&!keyguard.isKeyguardLocked()){
     UsageEvents events=getSystemService(UsageStatsManager.class).queryEvents(now-3600000,now);UsageEvents.Event event=new UsageEvents.Event();String last="";long resumed=0;
     while(events.hasNextEvent()){events.getNextEvent(event);if(event.getEventType()==UsageEvents.Event.ACTIVITY_RESUMED){last=event.getPackageName();resumed=event.getTimeStamp();}else if(event.getEventType()==UsageEvents.Event.ACTIVITY_PAUSED&&event.getPackageName().equals(last))last="";}
     prefs.edit().putString("lastApp",last).apply();if(!last.isEmpty()&&allowed("apps",last)){e.put("app",last);if(prefs.getBoolean("app_details",false)){JSONObject details=new JSONObject().put("active_for_ms",Math.min(3600000,Math.max(0,now-resumed)));try{details.put("label",trim(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(last,0)).toString(),120));}catch(Exception unavailable){}e.put("app_details",details);}}
    }
    }catch(SecurityException unavailable){}
    try{
     List<MediaController> cs=getSystemService(MediaSessionManager.class).getActiveSessions(new ComponentName(this,MediaAccess.class));MediaController chosen=null;
     for(MediaController c:cs){if(!allowed("players",c.getPackageName())||c.getMetadata()==null||c.getPlaybackState()==null)continue;if(c.getPlaybackState().getState()==PlaybackState.STATE_NONE)continue;if(chosen==null)chosen=c;PlaybackState p=c.getPlaybackState();if(p!=null&&p.getState()==PlaybackState.STATE_PLAYING){chosen=c;break;}}
     watch(chosen);
     if(chosen!=null){MediaMetadata m=chosen.getMetadata();PlaybackState p=chosen.getPlaybackState();String title=trim(m.getString(MediaMetadata.METADATA_KEY_TITLE),200);boolean playing=p!=null&&p.getState()==PlaybackState.STATE_PLAYING;long pos=p==null?0:Math.max(0,p.getPosition());if(playing)pos+=(long)((SystemClock.elapsedRealtime()-p.getLastPositionUpdateTime())*p.getPlaybackSpeed());long duration=Math.max(0,m.getLong(MediaMetadata.METADATA_KEY_DURATION));if(duration>0)pos=Math.min(pos,duration);
      JSONObject music=new JSONObject().put("title",title).put("artist",trim(m.getString(MediaMetadata.METADATA_KEY_ARTIST),200)).put("player",chosen.getPackageName()).put("playing",playing).put("position_ms",Math.min(604800000,Math.max(0,pos))).put("duration_ms",Math.min(duration,604800000));
      String artist=trim(m.getString(MediaMetadata.METADATA_KEY_ARTIST),200),album=trim(m.getString(MediaMetadata.METADATA_KEY_ALBUM),200);
      String key=title+"\u0001"+artist+"\u0001"+album+"\u0001"+duration+"/"+prefs.getLong("libraryVersion",0)+"/"+MusicLibrary.hasReadPermission(this);
      if(!key.equals(lyricKey)){embedded=MusicLibrary.find(this,title,artist,album,duration);lyricKey=key;}
      int state=p.getState();music.put("playback_status",state==PlaybackState.STATE_PLAYING?"playing":state==PlaybackState.STATE_PAUSED?"paused":state==PlaybackState.STATE_BUFFERING?"buffering":state==PlaybackState.STATE_CONNECTING?"connecting":state==PlaybackState.STATE_STOPPED?"stopped":state==PlaybackState.STATE_ERROR?"error":"unknown");
      music.put("album",album);
      String lyricStatus=embedded.status;
      String source="";
      if(embedded.lyrics!=null&&"synced".equals(lyricStatus))source=embedded.lyrics;
      else if(title.equals(prefs.getString("lyricTitle",""))&&!prefs.getString("lrc","").isEmpty()){source=prefs.getString("lrc","");lyricStatus=Lyrics.hasTimeline(source)?"synced":"plain";}
      if(!source.isEmpty()){String line=Lyrics.current(source,pos);if(line!=null)music.put("lyric",line);music.put("lyric_window",window(source,pos,duration));}
      String identity=chosen.getPackageName()+"\u0001"+title+"\u0001"+artist+"\u0001"+album+"\u0001"+duration+"\u0001"+m.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
      byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(identity.getBytes("UTF-8"));StringBuilder hash=new StringBuilder();for(byte b:digest)hash.append(String.format(Locale.ROOT,"%02x",b&255));
      music.put("track_id",hash.toString()).put("playback_rate",Math.max(0,Math.min(8,p.getPlaybackSpeed())));
      music.put("lyric_status",lyricStatus);prefs.edit().putString("lyricStatus",lyricStatus).apply();e.put("music",music);
     }
    }catch(SecurityException ex){prefs.edit().putString("status","通知访问未授权；只能发送已允许的应用").apply();}
   }
   JSONObject payloadMusic=e.optJSONObject("music");
   while(e.toString().getBytes("UTF-8").length>16000&&payloadMusic!=null&&payloadMusic.has("lyric_window")){
    JSONObject w=payloadMusic.optJSONObject("lyric_window");JSONArray lines=w==null?null:w.optJSONArray("lines");
    if(lines==null||lines.length()<=1){payloadMusic.remove("lyric_window");break;}
    JSONObject removed=lines.getJSONObject(lines.length()-1);lines.remove(lines.length()-1);w.put("end_ms",removed.getLong("at_ms"));
   }
   String base=prefs.getString("url","");if(!base.startsWith("https://"))return;
   HttpURLConnection conn=(HttpURLConnection)new URL(base+"/v1/events").openConnection();conn.setConnectTimeout(8000);conn.setReadTimeout(8000);conn.setInstanceFollowRedirects(false);conn.setRequestMethod("POST");conn.setDoOutput(true);conn.setRequestProperty("Content-Type","application/json");conn.setRequestProperty("Authorization","Bearer "+prefs.getString("token",""));byte[] body=e.toString().getBytes("UTF-8");conn.setFixedLengthStreamingMode(body.length);try(OutputStream out=conn.getOutputStream()){out.write(body);}int code=conn.getResponseCode();conn.disconnect();prefs.edit().putString("status",code==200?(hidden?"已暂停，服务端已清空":"已同步 · "+new java.text.SimpleDateFormat("HH:mm:ss",Locale.ROOT).format(new Date())):"同步失败 HTTP "+code+"；旧状态会过期").apply();
  }catch(Exception ex){prefs.edit().putString("status","连接失败；旧状态最多保留 45 秒").apply();}
 }
 @Override public IBinder onBind(Intent i){return null;}
 @Override public void onDestroy(){watch(null);if(executor!=null)executor.shutdownNow();super.onDestroy();}
}
