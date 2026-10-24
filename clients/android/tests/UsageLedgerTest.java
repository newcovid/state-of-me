import io.github.stateofme.UsageLedger;
import java.time.*;

public class UsageLedgerTest {
 static void eq(long actual,long expected){if(actual!=expected)throw new AssertionError(actual+" != "+expected);}
 public static void main(String[] args){
  long t=Instant.parse("2026-10-23T23:59:00Z").toEpochMilli();
  UsageLedger l=new UsageLedger("UTC",t);
  l.event(t,15,null,null);l.event(t,17,null,null);l.event(t+1000,18,null,null);
  l.event(t+1000,1,"app","A");l.event(t+2000,1,"app","B");l.event(t+3000,2,"app","A");
  l.advance(t+120000);
  eq(l.days.get("2026-10-23").screen,60000);eq(l.days.get("2026-10-24").screen,60000);
  eq(l.days.get("2026-10-23").unlocked,59000);eq(l.days.get("2026-10-23").unlocks,1);
  eq(l.days.get("2026-10-23").apps.get("app").ms,59000);eq(l.days.get("2026-10-23").apps.get("app").opens,1);
  l.event(t+120000,16,null,null);l.advance(t+180000);
  eq(l.days.get("2026-10-24").screen,60000);eq(l.days.get("2026-10-24").apps.get("app").ms,60000);
  l.event(t,15,null,null);eq(l.cursor,t+180000); // duplicate older data cannot add time
  UsageLedger missing=new UsageLedger("UTC",t);missing.advance(t+60000);eq(missing.days.firstEntry().getValue().known,0);
  // A repeated local hour during DST contributes to the same histogram bucket, not a duplicated day.
  long dst=Instant.parse("2026-11-01T05:00:00Z").toEpochMilli();
  UsageLedger d=new UsageLedger("America/New_York",dst);d.event(dst,15,null,null);d.event(dst,18,null,null);d.advance(dst+7200000);
  eq(d.days.firstEntry().getValue().hours[1],7200000);eq(d.days.firstEntry().getValue().screen,7200000);
  d.event(dst+7200000,26,null,null);d.advance(dst+7260000);eq(d.days.firstEntry().getValue().screen,7200000);
  System.out.println("UsageLedger: midnight, overlapping activities, lock, duplicates, unknown coverage and DST passed");
 }
}
