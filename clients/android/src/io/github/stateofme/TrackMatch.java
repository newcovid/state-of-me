package io.github.stateofme;
import java.text.Normalizer;
import java.util.Locale;
public final class TrackMatch {
 public static String norm(String s){return Normalizer.normalize(s==null?"":s,Normalizer.Form.NFKC).trim().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);}
 public static boolean matches(String title,String artist,String album,long duration,String otherTitle,String otherArtist,String otherAlbum,long otherDuration){
  if(norm(title).isEmpty()||!norm(title).equals(norm(otherTitle)))return false;
  // Require a second strong identity field; title alone never selects a file.
  if(norm(artist).isEmpty()||!norm(artist).equals(norm(otherArtist)))return false;
  if(!norm(album).isEmpty()&&!norm(album).equals(norm(otherAlbum)))return false;
  return duration>0&&otherDuration>0&&Math.abs(duration-otherDuration)<=2000;
 }
}
