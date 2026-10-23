package io.github.stateofme;
import android.app.*;
import android.os.*;
import android.content.*;
import android.provider.Settings;
import android.widget.*;
import android.text.InputType;
import java.io.*;

public class MainActivity extends Activity {
 EditText url,token,apps,players,title; TextView status;
 android.content.SharedPreferences prefs;
 final Handler handler=new Handler();
 final Runnable refresh=new Runnable(){public void run(){status.setText(prefs.getString("status","尚未开始")+"\n最近前台包："+prefs.getString("lastApp", "未知")+"\n"+prefs.getString("libraryStatus", "尚未授权音乐目录")+"\n歌词："+prefs.getString("lyricStatus", "未提供"));handler.postDelayed(this,2000);}};
 @Override public void onCreate(Bundle b){super.onCreate(b);prefs=getSharedPreferences("som",0);
  ScrollView scroll=new ScrollView(this);LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(32,32,32,32);scroll.addView(box);setContentView(scroll);
  scroll.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(0,insets.getSystemWindowInsetTop(),0,insets.getSystemWindowInsetBottom());return insets;});
  getWindow().getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
  TextView h=new TextView(this);h.setText("State of Me · 免 Root 预览\n仅白名单项目出机，后台持续显示通知。通知访问仅用于媒体会话，不上传通知正文。");h.setTextSize(19);box.addView(h);
  url=edit(box,"服务地址（https://…，无结尾斜杠）","url",false);token=edit(box,"此设备写入令牌","token",true);
  apps=edit(box,"允许的应用包名，逗号分隔；空表示不分享应用","apps",false);players=edit(box,"允许的播放器包名","players",false);if(players.getText().length()==0)players.setText("com.maxmpz.audioplayer");
  if(getIntent().hasExtra("server"))url.setText(getIntent().getStringExtra("server"));
  if(getIntent().hasExtra("device_token"))token.setText(getIntent().getStringExtra("device_token"));
  button(box,"1 · 授予通知访问（音乐）",()->startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
  button(box,"2 · 可选：使用情况访问（前台应用）",()->startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
  button(box,"保存并开始分享",()->{String u=url.getText().toString().trim();if(!u.startsWith("https://")||token.getText().length()<24){toast("请填写 HTTPS 地址和有效令牌");return;}prefs.edit().putString("url",u.replaceAll("/+$", "")).putString("token",token.getText().toString().trim()).putString("apps",apps.getText().toString()).putString("players",players.getText().toString()).putBoolean("enabled",true).apply();if(Build.VERSION.SDK_INT>=33)requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},9);startForegroundService(new Intent(this,Collector.class));});
  button(box,"暂停分享并清空服务端状态",()->{prefs.edit().putBoolean("enabled",false).apply();startForegroundService(new Intent(this,Collector.class).setAction("stop"));});
  button(box,"授权音乐目录并索引内嵌歌词",()->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),11));
  button(box,"撤销音乐目录授权",()->MusicLibrary.clear(this));
  title=edit(box,"LRC 对应完整歌曲名（必须与播放器一致）","lyricTitle",false);
  button(box,"选择本地 LRC（可选）",()->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,10);});
  button(box,"清除歌词授权",()->prefs.edit().remove("lrc").remove("lyricTitle").apply());
  status=new TextView(this);box.addView(status);
 }
 EditText edit(LinearLayout box,String label,String key,boolean secret){TextView l=new TextView(this);l.setText(label);box.addView(l);EditText e=new EditText(this);e.setSingleLine(true);e.setText(prefs.getString(key,""));if(secret)e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);box.addView(e);return e;}
 void button(LinearLayout box,String label,Runnable r){Button b=new Button(this);b.setText(label);b.setOnClickListener(v->r.run());box.addView(b);}
 void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
 @Override protected void onResume(){super.onResume();handler.post(refresh);}
 @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
 @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==11&&result==RESULT_OK&&data!=null){try{getContentResolver().takePersistableUriPermission(data.getData(),Intent.FLAG_GRANT_READ_URI_PERMISSION);prefs.edit().putString("tree",data.getData().toString()).putLong("libraryVersion",System.currentTimeMillis()).apply();MusicLibrary.scan(this,data.getData());}catch(Exception e){toast("目录授权失败");}return;}if(request==10&&result==RESULT_OK&&data!=null){try(InputStream in=getContentResolver().openInputStream(data.getData());ByteArrayOutputStream out=new ByteArrayOutputStream()){
  byte[] buf=new byte[4096];int n;while((n=in.read(buf))>0){out.write(buf,0,n);if(out.size()>262144)throw new IOException("Too large");}
  if(title.getText().toString().trim().isEmpty()){toast("请先填写对应歌曲名");return;}prefs.edit().putString("lrc",out.toString("UTF-8")).putString("lyricTitle",title.getText().toString().trim()).apply();toast("已导入；仅对应歌曲会分享当前句");
 }catch(Exception e){toast("读取失败：请选择 UTF-8 LRC，最大 256KB");}}}
}
