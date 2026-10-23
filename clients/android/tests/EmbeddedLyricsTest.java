import io.github.stateofme.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

public class EmbeddedLyricsTest {
 static byte[] cat(byte[]... parts)throws Exception{ByteArrayOutputStream o=new ByteArrayOutputStream();for(byte[] p:parts)o.write(p);return o.toByteArray();}
 static byte[] ascii(String s){return s.getBytes(StandardCharsets.ISO_8859_1);}
 static byte[] utf(String s){return s.getBytes(StandardCharsets.UTF_8);}
 static byte[] be(int n){return new byte[]{(byte)(n>>24),(byte)(n>>16),(byte)(n>>8),(byte)n};}
 static byte[] le(int n){return new byte[]{(byte)n,(byte)(n>>8),(byte)(n>>16),(byte)(n>>24)};}
 static byte[] sync(int n){return new byte[]{(byte)((n>>21)&127),(byte)((n>>14)&127),(byte)((n>>7)&127),(byte)(n&127)};}
 static byte[] box(String type,byte[] body)throws Exception{return cat(be(body.length+8),ascii(type),body);}
 static void check(byte[] file,String expected)throws Exception{String result=EmbeddedLyrics.read(new ByteArrayInputStream(file));if(!expected.equals(result))throw new AssertionError("Tag did not round-trip");}
 public static void main(String[] args)throws Exception{
  String lyrics="[00:01.00]测试\n[00:03.00]second";
  byte[] body=cat(new byte[]{3},ascii("eng"),new byte[]{0},utf(lyrics));
  byte[] frame=cat(ascii("USLT"),be(body.length),new byte[]{0,0},body);
  check(cat(ascii("ID3"),new byte[]{3,0,0},sync(frame.length),frame),lyrics);
  byte[] utf16=cat(new byte[]{1},ascii("eng"),new byte[]{0,0},lyrics.getBytes(StandardCharsets.UTF_16));
  byte[] frame16=cat(ascii("USLT"),sync(utf16.length),new byte[]{0,0},utf16);
  check(cat(ascii("ID3"),new byte[]{4,0,0},sync(frame16.length),frame16),lyrics);
  byte[] sylt=cat(new byte[]{3},ascii("eng"),new byte[]{2,1,0},utf("first"),new byte[]{0},be(1500));
  byte[] syltFrame=cat(ascii("SYLT"),be(sylt.length),new byte[]{0,0},sylt);
  check(cat(ascii("ID3"),new byte[]{3,0,0},sync(syltFrame.length),syltFrame),"[00:01.500]first\n");
  if(!"first".equals(Lyrics.current("[offset:500]\n[00:01.500]first",1000)))throw new AssertionError("offset");
  if(Lyrics.hasTimeline("plain lyrics"))throw new AssertionError("plain must not imply timing");
  byte[] comment=utf("LYRICS="+lyrics),vc=cat(le(0),le(1),le(comment.length),comment);
  check(cat(ascii("fLaC"),new byte[]{(byte)0x84,(byte)(vc.length>>16),(byte)(vc.length>>8),(byte)vc.length},vc),lyrics);
  byte[] data=box("data",cat(be(1),be(0),utf(lyrics)));
  check(cat(box("ftyp",ascii("M4A     ")),box("mdat",new byte[30]),box("moov",box("udta",box("meta",cat(be(0),box("ilst",box("\u00a9lyr",data))))))),lyrics);
  if(!TrackMatch.matches(" Song ","Artist","Album",100000,"song","artist","album",100500))throw new AssertionError();
  if(TrackMatch.matches("Song","Artist","Album",100000,"Song","Other","Album",100000))throw new AssertionError("wrong artist");
  if(TrackMatch.matches("Song","Artist","Album",100000,"Song","Artist","Album",130000))throw new AssertionError("wrong version");
  System.out.println("Embedded tag formats + strong matching passed");
  for(String path:args)try(InputStream in=new FileInputStream(path)){
   String result=EmbeddedLyrics.read(in);System.out.println("Local sample: embedded="+(result!=null)+", synchronized="+(result!=null&&Lyrics.hasTimeline(result)));
  }
 }
}
