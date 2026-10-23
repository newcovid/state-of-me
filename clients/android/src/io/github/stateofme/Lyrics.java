package io.github.stateofme;
import java.util.*;
import java.util.regex.*;

public final class Lyrics {
 public static String current(String lrc,long position){
  Pattern p=Pattern.compile("\\[(\\d{1,5}):(\\d{2})(?:[.:](\\d{1,3}))?\\]");
  long best=-1;String found=null;
  for(String line:lrc.split("\\n")){Matcher m=p.matcher(line);String text=line.replaceAll("\\[[^]]*\\]","").trim();while(m.find()){
   String fraction=m.group(3);long ms=(Long.parseLong(m.group(1))*60+Long.parseLong(m.group(2)))*1000+(fraction==null?0:Long.parseLong((fraction+"00").substring(0,3)));
   if(ms<=position&&ms>=best){best=ms;found=text;}
  }}return found==null?null:found.substring(0,Math.min(500,found.length()));
 }
}
