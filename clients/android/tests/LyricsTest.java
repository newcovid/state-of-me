import io.github.stateofme.Lyrics;
public class LyricsTest {
 public static void main(String[] args) {
  String lrc="[00:01.50]first\n[00:03.000][00:06.00]second\n[00:08]last";
  if(Lyrics.current(lrc,1000)!=null)throw new AssertionError("before first line");
  if(!"first".equals(Lyrics.current(lrc,1500)))throw new AssertionError("fraction");
  if(!"second".equals(Lyrics.current(lrc,6000)))throw new AssertionError("multiple stamps");
  if(!"first".equals(Lyrics.current(lrc,2000)))throw new AssertionError("seek backwards");
  if(!"last".equals(Lyrics.current(lrc,9000)))throw new AssertionError("last line");
  if(Lyrics.current("[999999999999999999999:00]bad",10000)!=null)throw new AssertionError("malformed");
  System.out.println("6 lyric cases passed");
  java.util.List<Lyrics.Line> lines=Lyrics.parse("[offset:500]\n[00:03]b\n[00:01]a\n[00:03]translation");
  if(lines.size()!=2||lines.get(0).at!=500||lines.get(1).at!=2500||!lines.get(1).text.equals("translation"))throw new AssertionError("timeline ordering, offset, duplicate");
 }
}
