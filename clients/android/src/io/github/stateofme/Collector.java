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
 String lyricKey=""; MusicLibrary.Result embedded=new MusicLibrary.Result();
 ScheduledExecutorService executor;android.content.SharedPreferences prefs;volatile boolean stopping;
 @Override public void onCreate(){super.onCreate();prefs=getSharedPreferences("som",0);NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("sharing","状态分享",NotificationManager.IMPORTANCE_LOW));
  PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
  PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,Collector.class).setAction("stop"),PendingIntent.FLAG_IMMUTABLE);
  Notification n=new Notification.Builder(this,"sharing").setSmallIcon(android.R.drawable.ic_menu_info_details).setContentTitle("State of Me 正在分享允许的状态").setContentText("点击查看权限和过滤；暂停会清空状态").setContentIntent(open).addAction(new Notification.Action.Builder(null,"暂停",stop).build()).setOngoing(true).build();startForeground(1,n);executor=Executors.newSingleThreadScheduledExecutor();
 }
 @Override public int onStartCommand(Intent i,int flags,int id){if(i!=null&&"stop".equals(i.getAction())){stopping=true;prefs.edit().putBoolean("enabled",false).apply();executor.execute(()->{send(true);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();});}else if(!prefs.getBoolean("enabled",false)){stopSelf();}else if(!stopping){executor.scheduleWithFixedDelay(()->send(false),0,5,TimeUnit.SECONDS);stopping=true;}return START_NOT_STICKY;}
 boolean allowed(String list,String item){for(String s:prefs.getString(list,"").split(","))if(s.trim().equals(item))return true;return false;}
 String trim(String s,int n){if(s==null)return "";return s.substring(0,Math.min(n,s.length()));}
 void send(boolean forceHidden){
  try{
   boolean hidden=forceHidden||!prefs.getBoolean("enabled",false);JSONObject e=new JSONObject();long now=System.currentTimeMillis();long seq=Math.max(now,prefs.getLong("seq",0)+1);prefs.edit().putLong("seq",seq).commit();e.put("seq",seq).put("observed_at",now/1000.0).put("hidden",hidden);
   if(!hidden){
    PowerManager power=getSystemService(PowerManager.class);KeyguardManager keyguard=getSystemService(KeyguardManager.class);
    if(power.isInteractive()&&!keyguard.isKeyguardLocked()){
     UsageEvents events=getSystemService(UsageStatsManager.class).queryEvents(now-3600000,now);UsageEvents.Event event=new UsageEvents.Event();String last="";
     while(events.hasNextEvent()){events.getNextEvent(event);if(event.getEventType()==UsageEvents.Event.ACTIVITY_RESUMED)last=event.getPackageName();else if(event.getEventType()==UsageEvents.Event.ACTIVITY_PAUSED&&event.getPackageName().equals(last))last="";}
     prefs.edit().putString("lastApp",last).apply();if(!last.isEmpty()&&allowed("apps",last))e.put("app",last);
    }
    try{
     List<MediaController> cs=getSystemService(MediaSessionManager.class).getActiveSessions(new ComponentName(this,MediaAccess.class));MediaController chosen=null;
     for(MediaController c:cs){if(!allowed("players",c.getPackageName())||c.getMetadata()==null||c.getPlaybackState()==null)continue;if(c.getPlaybackState().getState()!=PlaybackState.STATE_PLAYING&&c.getPlaybackState().getState()!=PlaybackState.STATE_PAUSED)continue;if(chosen==null)chosen=c;PlaybackState p=c.getPlaybackState();if(p!=null&&p.getState()==PlaybackState.STATE_PLAYING){chosen=c;break;}}
     if(chosen!=null){MediaMetadata m=chosen.getMetadata();PlaybackState p=chosen.getPlaybackState();String title=trim(m.getString(MediaMetadata.METADATA_KEY_TITLE),200);boolean playing=p!=null&&p.getState()==PlaybackState.STATE_PLAYING;long pos=p==null?0:Math.max(0,p.getPosition());if(playing)pos+=(long)((SystemClock.elapsedRealtime()-p.getLastPositionUpdateTime())*p.getPlaybackSpeed());long duration=Math.max(0,m.getLong(MediaMetadata.METADATA_KEY_DURATION));if(duration>0)pos=Math.min(pos,duration);
      JSONObject music=new JSONObject().put("title",title).put("artist",trim(m.getString(MediaMetadata.METADATA_KEY_ARTIST),200)).put("player",chosen.getPackageName()).put("playing",playing).put("position_ms",Math.min(604800000,Math.max(0,pos))).put("duration_ms",Math.min(duration,604800000));
      String artist=trim(m.getString(MediaMetadata.METADATA_KEY_ARTIST),200),album=trim(m.getString(MediaMetadata.METADATA_KEY_ALBUM),200);
      String key=title+"\u0001"+artist+"\u0001"+album+"\u0001"+duration+"/"+prefs.getLong("libraryVersion",0)+"/"+MusicLibrary.hasReadPermission(this);
      if(!key.equals(lyricKey)){embedded=MusicLibrary.find(this,title,artist,album,duration);lyricKey=key;}
      String lyricStatus=embedded.status;
      if(embedded.lyrics!=null&&"synced".equals(lyricStatus)){String line=Lyrics.current(embedded.lyrics,pos);if(line!=null)music.put("lyric",line);}
      else if(title.equals(prefs.getString("lyricTitle",""))&&!prefs.getString("lrc","").isEmpty()){String manual=prefs.getString("lrc","");lyricStatus=Lyrics.hasTimeline(manual)?"synced":"plain";String line=Lyrics.current(manual,pos);if(line!=null)music.put("lyric",line);}
      music.put("lyric_status",lyricStatus);prefs.edit().putString("lyricStatus",lyricStatus).apply();e.put("music",music);
     }
    }catch(SecurityException ex){prefs.edit().putString("status","通知访问未授权；只能发送已允许的应用").apply();}
   }
   String base=prefs.getString("url","");if(!base.startsWith("https://"))return;
   HttpURLConnection conn=(HttpURLConnection)new URL(base+"/v1/events").openConnection();conn.setConnectTimeout(8000);conn.setReadTimeout(8000);conn.setInstanceFollowRedirects(false);conn.setRequestMethod("POST");conn.setDoOutput(true);conn.setRequestProperty("Content-Type","application/json");conn.setRequestProperty("Authorization","Bearer "+prefs.getString("token",""));byte[] body=e.toString().getBytes("UTF-8");conn.setFixedLengthStreamingMode(body.length);try(OutputStream out=conn.getOutputStream()){out.write(body);}int code=conn.getResponseCode();conn.disconnect();prefs.edit().putString("status",code==200?(hidden?"已暂停，服务端已清空":"已同步 · "+new java.text.SimpleDateFormat("HH:mm:ss",Locale.ROOT).format(new Date())):"同步失败 HTTP "+code+"；旧状态会过期").apply();
  }catch(Exception ex){prefs.edit().putString("status","连接失败；旧状态最多保留 45 秒").apply();}
 }
 @Override public IBinder onBind(Intent i){return null;}
 @Override public void onDestroy(){if(executor!=null)executor.shutdownNow();super.onDestroy();}
}
