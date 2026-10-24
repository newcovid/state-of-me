import io.github.stateofme.PublishPolicy;

public class PublishPolicyTest {
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 public static void main(String[] args){
  PublishPolicy p=new PublishPolicy();
  check(p.shouldPublish(0,"idle",false),"initial snapshot");
  p.accepted(0,"idle",Long.MAX_VALUE);
  for(int t=1000;t<20000;t+=1000)check(!p.shouldPublish(t,"idle",false),"unchanged snapshot suppressed");
  check(p.nextDelay(5000,false)==15000,"deadline survives local samples");
  check(p.nextDelay(5000,true)==5000,"apps poll locally");
  check(p.shouldPublish(20000,"idle",false),"heartbeat");
  check(p.shouldPublish(2000,"paused",false),"pause retracts music");
  check(p.shouldPublish(2000,"idle",true),"seek resynchronizes");
  p.failed(2000);check(!p.shouldPublish(6999,"changed",true),"events respect backoff");
  check(p.shouldPublish(7000,"changed",false),"retry keeps dirty state");
  p.failed(7000);check(p.nextDelay(7000,true)==10000,"backoff skips polling");
  for(int i=0;i<10;i++)p.failed(7000);
  check(p.nextDelay(7000,false)==300000,"retry ceiling");
  p.reconnect();check(p.shouldPublish(7001,"idle",false),"reconnect refreshes unchanged state");
  p.accepted(8000,"song",4000);check(p.shouldPublish(12000,"song",false),"lyric deadline");
  check(PublishPolicy.lyricDelay(10000,24000,2)==2000,"speed-aware window refill");
  check(PublishPolicy.lyricDelay(10000,11000,1)==1100,"dense lyrics bounded by rate limit");
  check(PublishPolicy.lyricDelay(10000,11000,0)==Long.MAX_VALUE,"zero speed");
  int uploads=0;p=new PublishPolicy();
  for(int t=0;t<3600000;t+=5000)if(p.shouldPublish(t,"idle",false)){uploads++;p.accepted(t,"idle",Long.MAX_VALUE);}
  check(uploads==180,"one stable hour uploads 180 instead of 720");
  System.out.println("Publish policy contracts passed");
 }
}
