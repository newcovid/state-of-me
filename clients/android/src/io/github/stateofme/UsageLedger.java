package io.github.stateofme;

import java.io.Serializable;
import java.time.*;
import java.util.*;

/** Platform-independent interval accounting. No raw event history is retained. */
public final class UsageLedger implements Serializable {
 public static final long serialVersionUID=1L;
 public static final class App implements Serializable {
  public static final long serialVersionUID=1L;
  public long ms; public int opens;
 }
 public static final class Day implements Serializable {
  public static final long serialVersionUID=1L;
  public long screen,unlocked,known; public int unlocks;
  public final long[] hours=new long[24];
  public final Map<String,App> apps=new TreeMap<>();
 }
 public final String zone;
 public long cursor,firstEvent;
 public Boolean screen,locked;
 public final Map<String,Set<String>> active=new HashMap<>();
 public final TreeMap<String,Day> days=new TreeMap<>();
 public UsageLedger(String zone,long start){this.zone=zone;cursor=start;}
 private Day day(long at){return days.computeIfAbsent(Instant.ofEpochMilli(at).atZone(ZoneId.of(zone)).toLocalDate().toString(),x->new Day());}
 public void advance(long end){
  if(end<cursor)throw new IllegalArgumentException("Clock moved backwards");
  while(cursor<end){
   ZonedDateTime t=Instant.ofEpochMilli(cursor).atZone(ZoneId.of(zone));
   long next=Math.min(end,t.plusHours(1).withMinute(0).withSecond(0).withNano(0).toInstant().toEpochMilli());
   if(next<=cursor)next=end;
   Day d=day(cursor);long dt=next-cursor;
   if(screen!=null&&locked!=null)d.known+=dt;
   if(Boolean.TRUE.equals(screen)){d.screen+=dt;d.hours[t.getHour()]+=dt;if(Boolean.FALSE.equals(locked))d.unlocked+=dt;}
   if(!Boolean.FALSE.equals(screen)&&!Boolean.TRUE.equals(locked))for(String pkg:active.keySet())d.apps.computeIfAbsent(pkg,x->new App()).ms+=dt;
   cursor=next;
  }
  String oldest=Instant.ofEpochMilli(end).atZone(ZoneId.of(zone)).toLocalDate().minusDays(29).toString();
  days.headMap(oldest).clear();
 }
 public void event(long at,int type,String pkg,String activity){
  if(at<cursor)return;
  advance(at);if(firstEvent==0)firstEvent=at;
  switch(type){
   case 15:screen=true;break;
   case 16:screen=false;active.clear();break;
   case 17:locked=true;active.clear();break;
   case 18:if(Boolean.TRUE.equals(locked))day(at).unlocks++;locked=false;break;
   case 26:case 27:screen=null;locked=null;active.clear();break;
   case 1:
    if(pkg==null||pkg.isEmpty())break;
    if(!active.containsKey(pkg))day(at).apps.computeIfAbsent(pkg,x->new App()).opens++;
    active.computeIfAbsent(pkg,x->new HashSet<>()).add(activity==null?"":activity);break;
   case 2:case 23:
    Set<String> entries=active.get(pkg);if(entries!=null){entries.remove(activity==null?"":activity);if(entries.isEmpty())active.remove(pkg);}break;
   default:break;
  }
 }
 public void gap(long until){screen=null;locked=null;active.clear();cursor=until;}
}
