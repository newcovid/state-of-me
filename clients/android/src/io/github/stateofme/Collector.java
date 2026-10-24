package io.github.stateofme;
import android.app.*;
import android.app.usage.*;
import android.content.*;
import android.media.*;
import android.media.session.*;
import android.net.*;
import android.os.*;
import org.json.*;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class Collector extends Service {
 final Map<MediaSession.Token,MediaController> watched=new HashMap<>();
 ScheduledFuture<?> pending,tick;long completedAt;
 final PublishPolicy policy=new PublishPolicy();
 boolean started,sessionsRegistered,networkRegistered,receiverRegistered;
 long collections,uploads,skipped,uploadedBytes,usageQueries,usageEvents;
 long lastSentPosition,lastSentAt;double lastSentRate;String lastSentTrack="";
 long usageCursor,resumedAt;String foregroundApp="",labelPackage="",appLabel="";
 JSONObject cachedContext=new JSONObject();long contextAt=-1;volatile boolean contextDirty=true;
 final Handler mainHandler=new Handler(Looper.getMainLooper());
 final MediaSessionManager.OnActiveSessionsChangedListener sessions=controllers->requestUpdate();
 final SharedPreferences.OnSharedPreferenceChangeListener preferences=(p,key)->{
  if(key!=null&&(key.equals("apps")||key.startsWith("apps_")||key.equals("players")||key.startsWith("players_")||
   Arrays.asList("device_state","network","audio","resources","collector_health","app_details","libraryVersion","url","token").contains(key))){contextDirty=true;if(key.equals("url")||key.equals("token"))connectionChanged();else requestUpdate();}
 };
 final BroadcastReceiver stateEvents=new BroadcastReceiver(){
  int batteryLevel=-1,batteryStatus=-1;
  @Override public void onReceive(Context c,Intent i){
   if(Intent.ACTION_BATTERY_CHANGED.equals(i.getAction())){
    int level=i.getIntExtra(BatteryManager.EXTRA_LEVEL,-1),status=i.getIntExtra(BatteryManager.EXTRA_STATUS,-1);
    if(level==batteryLevel&&status==batteryStatus)return;
    batteryLevel=level;batteryStatus=status;
    if(!prefs.getBoolean("device_state",false))return;
   }
   requestUpdate();
  }
 };
 final ConnectivityManager.NetworkCallback networkEvents=new ConnectivityManager.NetworkCallback(){
  @Override public void onAvailable(Network network){connectionChanged();}
  @Override public void onLost(Network network){contextDirty=true;requestUpdate();}
 };
 void connectionChanged(){
   if(executor==null||executor.isShutdown())return;
   try{executor.execute(()->{contextDirty=true;policy.reconnect();if(pending!=null){pending.cancel(false);pending=null;}requestUpdate();});}catch(RejectedExecutionException ignored){}
 }
 boolean pollApps(){
  String mode=prefs.getString("apps_mode","whitelist");
  boolean selected="blacklist".equals(mode)?!prefs.getString("apps_candidates","").isEmpty():"whitelist".equals(mode)&&!prefs.getString("apps","").isEmpty();
  return selected&&getSystemService(PowerManager.class).isInteractive()&&!getSystemService(KeyguardManager.class).isKeyguardLocked();
 }
 void scheduleNext(){
  if(stopping||!prefs.getBoolean("enabled",false))return;
  if(tick!=null)tick.cancel(false);
  tick=executor.schedule(this::requestUpdate,policy.nextDelay(SystemClock.elapsedRealtime(),pollApps()),TimeUnit.MILLISECONDS);
 }
 synchronized void registerSessions(){
  if(stopping||sessionsRegistered)return;
  try{getSystemService(MediaSessionManager.class).addOnActiveSessionsChangedListener(sessions,new ComponentName(this,MediaAccess.class),mainHandler);sessionsRegistered=true;}catch(SecurityException ignored){}
 }
 final MediaController.Callback playbackCallback=new MediaController.Callback(){
  @Override public void onPlaybackStateChanged(PlaybackState state){requestUpdate();}
  @Override public void onMetadataChanged(MediaMetadata metadata){requestUpdate();}
  @Override public void onSessionDestroyed(){requestUpdate();}
 };
 void requestUpdate(){
  if(executor==null||executor.isShutdown())return;
  try{executor.execute(()->{
   if(stopping||!prefs.getBoolean("enabled",false)||pending!=null&&!pending.isDone())return;
   long delay=UpdateTiming.delay(SystemClock.elapsedRealtime(),completedAt);
   pending=executor.schedule(()->{pending=null;if(!stopping&&prefs.getBoolean("enabled",false)){send(false);completedAt=SystemClock.elapsedRealtime();scheduleNext();}},delay,TimeUnit.MILLISECONDS);
  });}catch(RejectedExecutionException ignored){}
 }
 synchronized void watchAll(List<MediaController> controllers){
  Set<MediaSession.Token> keep=new HashSet<>();
  if(!stopping)for(MediaController controller:controllers)if(allowed("players",controller.getPackageName())){
   MediaSession.Token token=controller.getSessionToken();keep.add(token);
   if(!watched.containsKey(token)){controller.registerCallback(playbackCallback,mainHandler);watched.put(token,controller);}
  }
  for(Iterator<Map.Entry<MediaSession.Token,MediaController>> it=watched.entrySet().iterator();it.hasNext();){Map.Entry<MediaSession.Token,MediaController> entry=it.next();if(!keep.contains(entry.getKey())){entry.getValue().unregisterCallback(playbackCallback);it.remove();}}
 }
 String lyricKey="",trackIdentity="",trackHash=""; MusicLibrary.Result embedded=new MusicLibrary.Result();
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
  prefs.registerOnSharedPreferenceChangeListener(preferences);
  IntentFilter filter=new IntentFilter();
  for(String action:new String[]{Intent.ACTION_SCREEN_ON,Intent.ACTION_SCREEN_OFF,Intent.ACTION_USER_PRESENT,Intent.ACTION_BATTERY_CHANGED,PowerManager.ACTION_POWER_SAVE_MODE_CHANGED})filter.addAction(action);
  if(Build.VERSION.SDK_INT>=33)registerReceiver(stateEvents,filter,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(stateEvents,filter);
  receiverRegistered=true;
  getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(networkEvents);networkRegistered=true;
  registerSessions();
 }
 @Override public int onStartCommand(Intent i,int flags,int id){if(i!=null&&"stop".equals(i.getAction())){stopping=true;prefs.edit().putBoolean("enabled",false).apply();executor.execute(()->{send(true);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();});}else if(!prefs.getBoolean("enabled",false)){stopSelf();}else if(!started){started=true;requestUpdate();}return START_NOT_STICKY;}
 boolean allowed(String list,String item){return AppFilter.allows(prefs.getString(list+"_mode","whitelist"),prefs.getString(list,""),prefs.getString(list+"_blocked",""),prefs.getString(list+"_candidates",""),item);}

 String trim(String s,int n){if(s==null)return "";return s.substring(0,Math.min(n,s.length()));}
 void send(boolean forceHidden){
  try{
   boolean hidden=forceHidden||!prefs.getBoolean("enabled",false);
   long monotonic=SystemClock.elapsedRealtime();
   if(!hidden&&!policy.retryReady(monotonic))return;
   if(!hidden&&getSystemService(ConnectivityManager.class).getActiveNetwork()==null){policy.failed(monotonic);String message="网络不可用；等待恢复，旧状态会过期";if(!message.equals(prefs.getString("status","")))prefs.edit().putString("status",message).apply();return;}
   collections++;registerSessions();
   JSONObject e=new JSONObject();long now=System.currentTimeMillis();e.put("observed_at",now/1000.0).put("hidden",hidden);
   if(!hidden){
    PowerManager power=getSystemService(PowerManager.class);KeyguardManager keyguard=getSystemService(KeyguardManager.class);
    if(prefs.getBoolean("device_state",false)){
     JSONObject deviceState=new JSONObject().put("screen_on",power.isInteractive()).put("locked",keyguard.isKeyguardLocked()).put("power_save",power.isPowerSaveMode());
     Intent battery=registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
     if(battery!=null){int level=battery.getIntExtra(BatteryManager.EXTRA_LEVEL,-1),scale=battery.getIntExtra(BatteryManager.EXTRA_SCALE,-1);if(level>=0&&scale>0)deviceState.put("battery_percent",Math.max(0,Math.min(100,Math.round(100f*level/scale))));int charging=battery.getIntExtra(BatteryManager.EXTRA_STATUS,-1);deviceState.put("charging",charging==BatteryManager.BATTERY_STATUS_CHARGING||charging==BatteryManager.BATTERY_STATUS_FULL);}
     DeviceContext.battery(battery,deviceState);e.put("device_state",deviceState);
    }
    if(contextDirty||contextAt<0||monotonic-contextAt>=PublishPolicy.HEARTBEAT_MS){contextDirty=false;cachedContext=new JSONObject();DeviceContext.collect(this,prefs,cachedContext);contextAt=monotonic;}
    for(Iterator<String> keys=cachedContext.keys();keys.hasNext();){String key=keys.next();if(prefs.getBoolean(key,false))e.put(key,cachedContext.get(key));}
    try{if(pollApps()){
     long start=usageCursor==0||usageCursor>now?now-3600000:Math.max(now-3600000,usageCursor-1000);
     UsageEvents events=getSystemService(UsageStatsManager.class).queryEvents(start,now);UsageEvents.Event event=new UsageEvents.Event();usageQueries++;
     while(events.hasNextEvent()){events.getNextEvent(event);usageEvents++;if(event.getEventType()==UsageEvents.Event.ACTIVITY_RESUMED){foregroundApp=event.getPackageName();resumedAt=event.getTimeStamp();}else if(event.getEventType()==UsageEvents.Event.ACTIVITY_PAUSED&&event.getPackageName().equals(foregroundApp))foregroundApp="";}
     usageCursor=now;
     if(!foregroundApp.isEmpty()&&allowed("apps",foregroundApp)){e.put("app",foregroundApp);if(prefs.getBoolean("app_details",false)){JSONObject details=new JSONObject().put("active_for_ms",Math.min(3600000,Math.max(0,now-resumedAt)));try{
      if(!labelPackage.equals(foregroundApp)){appLabel=trim(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(foregroundApp,0)).toString(),120);labelPackage=foregroundApp;}
      details.put("label",appLabel);
     }catch(Exception unavailable){}e.put("app_details",details);}}
    }else{usageCursor=0;foregroundApp="";}
    }catch(SecurityException unavailable){}
    try{
     List<MediaController> cs=getSystemService(MediaSessionManager.class).getActiveSessions(new ComponentName(this,MediaAccess.class));MediaController chosen=null;
     for(MediaController c:cs){if(!allowed("players",c.getPackageName())||c.getMetadata()==null||c.getPlaybackState()==null)continue;if(c.getPlaybackState().getState()==PlaybackState.STATE_NONE)continue;if(chosen==null)chosen=c;PlaybackState p=c.getPlaybackState();if(p!=null&&p.getState()==PlaybackState.STATE_PLAYING){chosen=c;break;}}
     watchAll(cs);
     if(chosen!=null&&chosen.getPlaybackState()!=null&&chosen.getPlaybackState().getState()==PlaybackState.STATE_PLAYING){MediaMetadata m=chosen.getMetadata();PlaybackState p=chosen.getPlaybackState();String title=trim(m.getString(MediaMetadata.METADATA_KEY_TITLE),200);boolean playing=p!=null&&p.getState()==PlaybackState.STATE_PLAYING;long pos=p==null?0:Math.max(0,p.getPosition());if(playing)pos+=(long)((SystemClock.elapsedRealtime()-p.getLastPositionUpdateTime())*p.getPlaybackSpeed());long duration=Math.max(0,m.getLong(MediaMetadata.METADATA_KEY_DURATION));if(duration>0)pos=Math.min(pos,duration);
      JSONObject music=new JSONObject().put("title",title).put("artist",trim(m.getString(MediaMetadata.METADATA_KEY_ARTIST),200)).put("player",chosen.getPackageName()).put("playing",playing).put("position_ms",Math.min(604800000,Math.max(0,pos))).put("duration_ms",Math.min(duration,604800000));
      String artist=trim(m.getString(MediaMetadata.METADATA_KEY_ARTIST),200),album=trim(m.getString(MediaMetadata.METADATA_KEY_ALBUM),200);
      String key=title+"\u0001"+artist+"\u0001"+album+"\u0001"+duration+"/"+prefs.getLong("libraryVersion",0)+"/"+MusicLibrary.hasReadPermission(this);
      if(!key.equals(lyricKey)){embedded=MusicLibrary.find(this,title,artist,album,duration);lyricKey=key;}
      int state=p.getState();music.put("playback_status",state==PlaybackState.STATE_PLAYING?"playing":state==PlaybackState.STATE_PAUSED?"paused":state==PlaybackState.STATE_BUFFERING?"buffering":state==PlaybackState.STATE_CONNECTING?"connecting":state==PlaybackState.STATE_STOPPED?"stopped":state==PlaybackState.STATE_ERROR?"error":"unknown");
      music.put("album",album);
      String lyricStatus=embedded.status;
      String source="";
      if(embedded.lyrics!=null&&"synced".equals(lyricStatus))source=embedded.lyrics;

      if(!source.isEmpty()){music.put("lyric_window",window(source,pos,duration));String line=null;for(Lyrics.Line entry:timeline){if(entry.at>pos)break;line=entry.text;}if(line!=null)music.put("lyric",line);}
      String identity=chosen.getPackageName()+"\u0001"+title+"\u0001"+artist+"\u0001"+album+"\u0001"+duration+"\u0001"+m.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
      if(!identity.equals(trackIdentity)){byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(identity.getBytes("UTF-8"));StringBuilder hash=new StringBuilder();for(byte b:digest)hash.append(String.format(Locale.ROOT,"%02x",b&255));trackIdentity=identity;trackHash=hash.toString();}
      music.put("track_id",trackHash).put("playback_rate",Math.max(0,Math.min(8,p.getPlaybackSpeed())));
      music.put("lyric_status",lyricStatus);prefs.edit().putString("lyricStatus",lyricStatus).apply();e.put("music",music);
     }
    }catch(SecurityException ex){prefs.edit().putString("status","通知访问未授权；只能发送已允许的应用").apply();}
   }
   JSONObject payloadMusic=e.optJSONObject("music");
   String stateKey=fingerprint(e);
   long duration=payloadMusic==null?0:payloadMusic.optLong("duration_ms");
   double predicted=lastSentPosition+(monotonic-lastSentAt)*lastSentRate;if(duration>0)predicted=Math.min(predicted,duration);
   boolean seek=payloadMusic!=null&&payloadMusic.optString("track_id").equals(lastSentTrack)&&Math.abs(payloadMusic.optLong("position_ms")-predicted)>1500;
   if(!hidden&&!policy.shouldPublish(monotonic,stateKey,seek)){skipped++;return;}
   long seq=Math.max(now,prefs.getLong("seq",0)+1);
   // Persist only when sending; failure to reserve the sequence must not send a replay.
   if(!prefs.edit().putLong("seq",seq).commit())throw new IOException("sequence persistence failed");
   e.put("seq",seq);
   while(e.toString().getBytes("UTF-8").length>16000&&payloadMusic!=null&&payloadMusic.has("lyric_window")){
    JSONObject w=payloadMusic.optJSONObject("lyric_window");JSONArray lines=w==null?null:w.optJSONArray("lines");
    if(lines==null||lines.length()<=1){payloadMusic.remove("lyric_window");break;}
    JSONObject removed=lines.getJSONObject(lines.length()-1);lines.remove(lines.length()-1);w.put("end_ms",removed.getLong("at_ms"));
   }
   String base=prefs.getString("url","");if(!base.startsWith("https://"))throw new IOException("HTTPS required");
   HttpURLConnection conn=(HttpURLConnection)new URL(base+"/v1/events").openConnection();int code;
   try{
    conn.setConnectTimeout(8000);conn.setReadTimeout(8000);conn.setInstanceFollowRedirects(false);conn.setRequestMethod("POST");conn.setDoOutput(true);conn.setRequestProperty("Content-Type","application/json");conn.setRequestProperty("Authorization","Bearer "+prefs.getString("token",""));byte[] body=e.toString().getBytes("UTF-8");conn.setFixedLengthStreamingMode(body.length);uploads++;uploadedBytes+=body.length;
    try(OutputStream out=conn.getOutputStream()){out.write(body);}code=conn.getResponseCode();
    InputStream response=code>=400?conn.getErrorStream():conn.getInputStream();if(response!=null)try(InputStream in=response){byte[] buffer=new byte[1024];int total=0,n;while((n=in.read(buffer))!=-1){total+=n;if(total>16384)break;}}
   }finally{conn.disconnect();}
   if(code==401){prefs.edit().putBoolean("enabled",false).putBoolean("connection_invalid",true).putString("status","连接已失效，请在连接页断开旧设备后重新连接").apply();stopSelf();return;}
   if(code==200){
    long lyricDelay=Long.MAX_VALUE;
    if(payloadMusic!=null){lastSentTrack=payloadMusic.optString("track_id");lastSentPosition=payloadMusic.optLong("position_ms");lastSentRate=payloadMusic.optDouble("playback_rate",1);lastSentAt=monotonic;
     JSONObject w=payloadMusic.optJSONObject("lyric_window");if(w!=null)lyricDelay=PublishPolicy.lyricDelay(lastSentPosition,w.optLong("end_ms"),lastSentRate);
    }else{lastSentTrack="";}
    policy.accepted(monotonic,stateKey,lyricDelay);
   }else policy.failed(SystemClock.elapsedRealtime());
   prefs.edit().putString("status",code==200?(hidden?"已暂停，服务端已清空":"已同步 · "+new java.text.SimpleDateFormat("HH:mm:ss",Locale.ROOT).format(new Date())):"同步失败 HTTP "+code+"；旧状态会过期").apply();
  }catch(Exception ex){policy.failed(SystemClock.elapsedRealtime());prefs.edit().putString("status","连接失败；旧状态最多保留 45 秒").apply();}
 }
 String fingerprint(JSONObject event)throws JSONException{
  JSONObject key=new JSONObject(event.toString());key.remove("observed_at");
  JSONObject music=key.optJSONObject("music");if(music!=null){music.remove("position_ms");music.remove("lyric");music.remove("lyric_window");}
  JSONObject details=key.optJSONObject("app_details");if(details!=null)details.remove("active_for_ms");
  JSONObject battery=key.optJSONObject("device_state");if(battery!=null){battery.remove("battery_temperature_c");battery.remove("battery_voltage_mv");}
  JSONObject resources=key.optJSONObject("resources");if(resources!=null){resources.remove("available_memory_mib");resources.remove("available_storage_mib");}
  return key.toString();
 }
 @Override public IBinder onBind(Intent i){return null;}
 @Override protected void dump(FileDescriptor fd,PrintWriter out,String[] args){out.println("collections="+collections+" uploads="+uploads+" skipped="+skipped+" payload_bytes="+uploadedBytes+" usage_queries="+usageQueries+" usage_events="+usageEvents);}
 @Override public void onDestroy(){stopping=true;prefs.unregisterOnSharedPreferenceChangeListener(preferences);if(receiverRegistered)unregisterReceiver(stateEvents);if(networkRegistered)getSystemService(ConnectivityManager.class).unregisterNetworkCallback(networkEvents);if(sessionsRegistered)getSystemService(MediaSessionManager.class).removeOnActiveSessionsChangedListener(sessions);watchAll(Collections.emptyList());if(executor!=null)executor.shutdownNow();super.onDestroy();}
}
