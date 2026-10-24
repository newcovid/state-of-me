package io.github.stateofme;

import android.app.job.*;
import android.content.*;
import android.os.*;
import java.util.concurrent.*;

/** Deferrable system-scheduled maintenance; no timer or wake lock owned by the app. */
public final class UsageJob extends JobService {
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private volatile boolean stopped;
 public static void schedule(Context c){
  if(!c.getSharedPreferences("som",0).getBoolean("usage_local",false))return;
  JobScheduler scheduler=c.getSystemService(JobScheduler.class);
  if(scheduler.getPendingJob(1401)==null)scheduler.schedule(new JobInfo.Builder(1401,new ComponentName(c,UsageJob.class)).setPeriodic(15*60000L).setPersisted(true).setRequiresBatteryNotLow(true).build());
 }
 @Override public boolean onStartJob(JobParameters params){
  stopped=false;
  worker.execute(()->{
   try{
    if(getSharedPreferences("som",0).getBoolean("usage_local",false)&&UsageHistory.granted(this))UsageHistory.refresh(this,false);
    UsageHistory.sync(this,!getSharedPreferences("som",0).getBoolean("enabled",false));
   }catch(Exception ignored){}
   new Handler(Looper.getMainLooper()).post(()->{if(!stopped)jobFinished(params,false);});
  });return true;
 }
 @Override public boolean onStopJob(JobParameters params){stopped=true;return true;}
 @Override public void onDestroy(){stopped=true;worker.shutdownNow();super.onDestroy();}
}
