package io.github.stateofme;

import android.app.*;
import android.content.*;
import android.media.*;
import android.net.*;
import android.os.*;
import android.provider.Settings;
import org.json.*;
import java.util.*;

/** Optional snapshots from public Android APIs. Unknown values are omitted. */
final class DeviceContext {
 static void collect(Context c, SharedPreferences prefs, JSONObject event) {
  for(String key:new String[]{"network","audio","resources","collector_health"}) {
   if(!prefs.getBoolean(key,false))continue;
   try { JSONObject value=new JSONObject();
    if(key.equals("network")) {
     ConnectivityManager cm=c.getSystemService(ConnectivityManager.class);
     Network network=cm.getActiveNetwork();NetworkCapabilities caps=cm.getNetworkCapabilities(network);
     value.put("connected",network!=null);
     if(caps!=null){value.put("validated",caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).put("metered",cm.isActiveNetworkMetered());
      JSONArray types=new JSONArray();int[] ids={1,0,3,4,2};String[] names={"wifi","cellular","ethernet","vpn","bluetooth"};
      for(int i=0;i<ids.length;i++)if(caps.hasTransport(ids[i]))types.put(names[i]);if(types.length()==0)types.put("other");value.put("transports",types);}
    } else if(key.equals("audio")) {
     AudioManager am=c.getSystemService(AudioManager.class);int max=am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
     if(max>0)value.put("music_volume_percent",Math.min(100,Math.max(0,Math.round(100f*am.getStreamVolume(AudioManager.STREAM_MUSIC)/max))));
     value.put("media_muted",am.isStreamMute(AudioManager.STREAM_MUSIC));
     int mode=am.getRingerMode();value.put("ringer_mode",mode==0?"silent":mode==1?"vibrate":mode==2?"normal":"unknown");
     int filter=c.getSystemService(NotificationManager.class).getCurrentInterruptionFilter();value.put("interruption_filter",filter==1?"all":filter==2?"priority":filter==3?"none":filter==4?"alarms":"unknown");
     Set<String> outputs=new LinkedHashSet<>();
     for(AudioDeviceInfo d:am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)){
      int t=d.getType();outputs.add(t==1||t==2?"speaker":t==3||t==4?"wired":t==7||t==8||t==23||t==26||t==27||t==30?"bluetooth":t==11||t==12||t==22?"usb":t==9||t==10||t==29?"hdmi":"other");
     }value.put("connected_output_types",new JSONArray(outputs));
    } else if(key.equals("resources")) {
     ActivityManager.MemoryInfo info=new ActivityManager.MemoryInfo();c.getSystemService(ActivityManager.class).getMemoryInfo(info);
     value.put("available_memory_mib",info.availMem/1048576).put("total_memory_mib",info.totalMem/1048576).put("low_memory",info.lowMemory);
     StatFs disk=new StatFs(c.getFilesDir().getAbsolutePath());value.put("available_storage_mib",disk.getAvailableBytes()/1048576).put("total_storage_mib",disk.getTotalBytes()/1048576);
     if(Build.VERSION.SDK_INT>=29)value.put("thermal_status",c.getSystemService(PowerManager.class).getCurrentThermalStatus());
    } else {
     value.put("version","0.9.0").put("sdk",Build.VERSION.SDK_INT).put("music_directory_access",MusicLibrary.hasReadPermission(c));
     String listeners=Settings.Secure.getString(c.getContentResolver(),"enabled_notification_listeners");boolean notification=false;
     if(listeners!=null)for(String s:listeners.split(":")){ComponentName component=ComponentName.unflattenFromString(s);if(component!=null&&component.equals(new ComponentName(c,MediaAccess.class)))notification=true;}
     value.put("notification_access",notification);
     int op=c.getSystemService(AppOpsManager.class).checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,android.os.Process.myUid(),c.getPackageName());
     value.put("usage_access",op==AppOpsManager.MODE_ALLOWED).put("battery_optimization_exempt",c.getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(c.getPackageName()));
    }
    event.put(key,value);
   }catch(Exception unavailable){ /* A missing API/permission must not block other categories. */ }
  }
 }
 static void battery(Intent b,JSONObject value)throws JSONException{
  if(b==null)return;
  if(b.hasExtra(BatteryManager.EXTRA_TEMPERATURE)){double temp=b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,0)/10.0;if(temp>=-50&&temp<=100)value.put("battery_temperature_c",temp);}
  int voltage=b.getIntExtra(BatteryManager.EXTRA_VOLTAGE,-1);if(voltage>=0&&voltage<=20000)value.put("battery_voltage_mv",voltage);
  int source=b.getIntExtra(BatteryManager.EXTRA_PLUGGED,-1);value.put("charge_source",source==0?"none":source==1?"ac":source==2?"usb":source==4?"wireless":source==8?"dock":"unknown");
  int health=b.getIntExtra(BatteryManager.EXTRA_HEALTH,1);String[] names={"unknown","unknown","good","overheat","dead","over_voltage","failure","cold"};value.put("battery_health",health>=0&&health<names.length?names[health]:"unknown");
 }
}
