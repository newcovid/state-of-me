package io.github.stateofme;

/** Monotonic scheduling independent of Android, suitable for deterministic tests. */
public final class PublishPolicy {
 public static final long HEARTBEAT_MS=20000, APP_POLL_MS=5000;
 private long acceptedAt=-1, retryAt, lyricAt=Long.MAX_VALUE;
 private String acceptedKey;
 private int failures;
 public boolean retryReady(long now){return now>=retryAt;}
 public boolean shouldPublish(long now,String key,boolean seek){
  return retryReady(now)&&(acceptedKey==null||!acceptedKey.equals(key)||seek||now-acceptedAt>=HEARTBEAT_MS||now>=lyricAt);
 }
 public void accepted(long now,String key,long lyricDelay){acceptedAt=now;acceptedKey=key;failures=0;retryAt=0;lyricAt=lyricDelay==Long.MAX_VALUE?Long.MAX_VALUE:now+lyricDelay;}
 public void failed(long now){failures=Math.min(7,failures+1);retryAt=now+Math.min(300000,5000L<<(failures-1));}
 public void reconnect(){retryAt=0;acceptedKey=null;}
 public long nextDelay(long now,boolean pollApps){
  if(now<retryAt)return retryAt-now;
  long due=acceptedAt<0?0:Math.max(0,acceptedAt+HEARTBEAT_MS-now);
  due=Math.min(due,Math.max(0,lyricAt-now));
  if(pollApps)due=Math.min(due,APP_POLL_MS);
  return Math.max(1100,due);
 }
 public static long lyricDelay(long position,long windowEnd,double rate){
  if(rate<=0||windowEnd<=position)return Long.MAX_VALUE;
  return Math.max(1100,Math.min(HEARTBEAT_MS,(long)((windowEnd-position)/rate)-5000));
 }
}
