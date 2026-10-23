package io.github.stateofme;
import android.content.*;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.DocumentsContract;
import org.json.*;
import java.io.*;
import java.util.*;

public final class MusicLibrary {
 static final String INDEX="music-index.json";
 static long generation=0;
 public static synchronized void scan(Context context,Uri tree){
  final long job=++generation;
  android.content.SharedPreferences prefs=context.getSharedPreferences("som",0);
  prefs.edit().putString("libraryStatus","正在建立本地索引…").apply();
  new Thread(()->{try{
   JSONArray entries=new JSONArray();int[] visited={0};walk(context,tree,DocumentsContract.getTreeDocumentId(tree),entries,visited,0);
   JSONObject index=new JSONObject().put("tree",tree.toString()).put("entries",entries);
   synchronized(MusicLibrary.class){
   if(job!=generation)return;
   File temp=new File(context.getFilesDir(),INDEX+".tmp");try(FileOutputStream out=new FileOutputStream(temp)){out.write(index.toString().getBytes("UTF-8"));}
   if(!temp.renameTo(new File(context.getFilesDir(),INDEX)))throw new IOException();
   prefs.edit().putString("tree",tree.toString()).putLong("libraryVersion",System.currentTimeMillis()).putString("libraryStatus","已索引 "+entries.length()+" 首；目录/标签仅存本机").apply();
   }
  }catch(Exception e){synchronized(MusicLibrary.class){if(job==generation)prefs.edit().putString("libraryStatus","索引失败或超出 10000 项/12 层限制，请选择更小的音乐目录").apply();}}},"som-library").start();
 }
 static void walk(Context c,Uri tree,String id,JSONArray entries,int[] visited,int depth)throws Exception{
  if(depth>12)throw new IOException();
  Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,id);
  try(Cursor cursor=c.getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){
   if(cursor==null)throw new IOException();while(cursor.moveToNext()){
    if(++visited[0]>10000)throw new IOException();String child=cursor.getString(0),mime=cursor.getString(1),name=cursor.getString(2);
    if(DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)){walk(c,tree,child,entries,visited,depth+1);continue;}
    if(name==null||!name.toLowerCase(Locale.ROOT).matches(".*\\.(mp3|flac|m4a|mp4)$"))continue;
    Uri uri=DocumentsContract.buildDocumentUriUsingTree(tree,child);
    MediaMetadataRetriever r=new MediaMetadataRetriever();try{
     r.setDataSource(c,uri);String title=r.extractMetadata(7),artist=r.extractMetadata(2),album=r.extractMetadata(1),dur=r.extractMetadata(9);
     if(title!=null&&artist!=null&&dur!=null)entries.put(new JSONObject().put("uri",uri.toString()).put("title",title).put("artist",artist).put("album",album==null?"":album).put("duration",Long.parseLong(dur)));
    }catch(Exception ignored){}finally{try{r.release();}catch(Exception ignored){}}
   }
  }
 }
 public static synchronized void clear(Context c){
  generation++;
  android.content.SharedPreferences p=c.getSharedPreferences("som",0);String tree=p.getString("tree","");
  for(UriPermission grant:c.getContentResolver().getPersistedUriPermissions())try{c.getContentResolver().releasePersistableUriPermission(grant.getUri(),Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}
  p.edit().remove("tree").putLong("libraryVersion",System.currentTimeMillis()).putString("libraryStatus","音乐目录授权已清除").apply();new File(c.getFilesDir(),INDEX).delete();
 }
 public static class Result {public String status="unavailable",lyrics=null;}
 public static boolean hasReadPermission(Context c){
  String tree=c.getSharedPreferences("som",0).getString("tree","");
  for(UriPermission grant:c.getContentResolver().getPersistedUriPermissions())if(grant.isReadPermission()&&grant.getUri().toString().equals(tree))return true;
  return false;
 }
 public static Result find(Context c,String title,String artist,String album,long duration){
  Result result=new Result();android.content.SharedPreferences p=c.getSharedPreferences("som",0);String tree=p.getString("tree","");if(tree.isEmpty())return result;
  if(!hasReadPermission(c)){result.status="permission_required";return result;}
  try{
   File file=new File(c.getFilesDir(),INDEX);if(file.length()>16*1024*1024)return result;
   byte[] bytes=new byte[(int)file.length()];try(DataInputStream in=new DataInputStream(new FileInputStream(file))){in.readFully(bytes);}
   JSONObject index=new JSONObject(new String(bytes,"UTF-8"));if(!tree.equals(index.getString("tree")))return result;
   JSONArray entries=index.getJSONArray("entries");String uri=null;
   for(int i=0;i<entries.length();i++){JSONObject item=entries.getJSONObject(i);if(TrackMatch.matches(title,artist,album,duration,item.getString("title"),item.getString("artist"),item.getString("album"),item.getLong("duration"))){if(uri!=null){result.status="ambiguous";return result;}uri=item.getString("uri");}}
   if(uri==null){result.status="unmatched";return result;}
   try(InputStream in=c.getContentResolver().openInputStream(Uri.parse(uri))){result.lyrics=EmbeddedLyrics.read(in);}
   if(result.lyrics!=null)result.status=Lyrics.hasTimeline(result.lyrics)?"synced":"plain";
  }catch(SecurityException e){result.status="permission_required";}catch(Exception e){result.status="unsupported";}
  return result;
 }
}
