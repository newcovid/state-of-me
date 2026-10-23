package io.github.stateofme;
import java.util.*;
import java.util.regex.*;

public final class Lyrics {
 public static final class Line {public final long at;public final String text;Line(long a,String t){at=a;text=t;}}
 public static final class Window {
  public final long start,end;public final List<Line> lines;
  Window(long s,long e,List<Line> l){start=s;end=e;lines=Collections.unmodifiableList(l);}
 }
 /** Provider-independent bounded window. Input is the sorted output of parse(). */
 public static Window window(List<Line> timeline,long position,long duration){
  if(timeline.isEmpty())return null;
  position=Math.max(0,Math.min(604800000,position));
  int first=0;while(first+1<timeline.size()&&timeline.get(first+1).at<=position)first++;
  long start=Math.min(position,timeline.get(first).at),end=Math.min(604800001,position+60000);
  if(duration>position)end=Math.min(end,duration+1);
  List<Line> lines=new ArrayList<>();int i=first;
  for(;i<timeline.size()&&timeline.get(i).at<end&&lines.size()<16;i++)lines.add(timeline.get(i));
  if(i<timeline.size()&&timeline.get(i).at<end)end=timeline.get(i).at;
  return new Window(start,end,lines);
 }
 public static List<Line> parse(String lrc){
  TreeMap<Long,String> sorted=new TreeMap<>();
  Matcher off=Pattern.compile("\\[offset:([+-]?\\d{1,8})\\]",Pattern.CASE_INSENSITIVE).matcher(lrc);
  long offset=off.find()?Long.parseLong(off.group(1)):0;
  Pattern stamp=Pattern.compile("\\[(\\d{1,5}):(\\d{2})(?:[.:](\\d{1,3}))?\\]");
  for(String row:lrc.split("\\n")){Matcher m=stamp.matcher(row);String text=row.replaceAll("\\[[^]]*\\]","").trim();
   while(m.find()){String f=m.group(3);long at=(Long.parseLong(m.group(1))*60+Long.parseLong(m.group(2)))*1000+(f==null?0:Long.parseLong((f+"00").substring(0,3)))-offset;
    if(at<=604800000)sorted.put(Math.max(0,at),text.substring(0,Math.min(200,text.length())));
   }
  }
  List<Line> result=new ArrayList<>();for(Map.Entry<Long,String> e:sorted.entrySet())result.add(new Line(e.getKey(),e.getValue()));return result;
 }
 public static boolean hasTimeline(String text){return Pattern.compile("\\[\\d{1,5}:\\d{2}(?:[.:]\\d{1,3})?\\]").matcher(text).find();}
 public static String current(String lrc,long position){
  Pattern p=Pattern.compile("\\[(\\d{1,5}):(\\d{2})(?:[.:](\\d{1,3}))?\\]");
  long best=-1;String found=null;
  Matcher offset=Pattern.compile("\\[offset:([+-]?\\d{1,8})\\]",Pattern.CASE_INSENSITIVE).matcher(lrc);if(offset.find())position+=Long.parseLong(offset.group(1));
  for(String line:lrc.split("\\n")){Matcher m=p.matcher(line);String text=line.replaceAll("\\[[^]]*\\]","").trim();while(m.find()){
   String fraction=m.group(3);long ms=(Long.parseLong(m.group(1))*60+Long.parseLong(m.group(2)))*1000+(fraction==null?0:Long.parseLong((fraction+"00").substring(0,3)));
   if(ms<=position&&ms>=best){best=ms;found=text;}
  }}return found==null?null:found.substring(0,Math.min(500,found.length()));
 }
}
