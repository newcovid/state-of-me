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
 int ink=0xff20354a,muted=0xff49647d,background=0xffeaf0f5,surface=0xffffffff,accent=0xff235d91;
 final java.util.List<LinearLayout> pages=new java.util.ArrayList<>();
 final java.util.List<Button> tabs=new java.util.ArrayList<>();
 int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
 android.graphics.drawable.GradientDrawable shape(int color,int radius){android.graphics.drawable.GradientDrawable d=new android.graphics.drawable.GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
 TextView copy(LinearLayout box,String text,int size,int color){TextView t=new TextView(this);t.setText(text);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(8),0,dp(8));box.addView(t);return t;}
 LinearLayout page(LinearLayout parent,String title,String description){LinearLayout p=new LinearLayout(this);p.setOrientation(1);p.setPadding(dp(20),dp(16),dp(20),dp(20));p.setBackground(shape(surface,20));parent.addView(p);pages.add(p);copy(p,title,23,ink);copy(p,description,14,muted);return p;}
 void select(int index){for(int i=0;i<pages.size();i++){pages.get(i).setVisibility(i==index?android.view.View.VISIBLE:android.view.View.GONE);tabs.get(i).setTextColor(i==index?surface:ink);tabs.get(i).setBackground(shape(i==index?accent:background,12));tabs.get(i).setSelected(i==index);}}

 android.content.SharedPreferences prefs;
 final Handler handler=new Handler();
 String lyricLabel(){String value=prefs.getString("lyricStatus","unavailable");switch(value){case "synced":return "已匹配同步歌词";case "plain":return "歌词没有时间轴";case "ambiguous":return "存在多个匹配文件";case "permission_required":return "需要重新授权音乐目录";case "unmatched":return "目录中未找到匹配歌曲";default:return "暂无可用歌词";}}
 final Runnable refresh=new Runnable(){public void run(){status.setText((prefs.getBoolean("enabled",false)?"分享已开启":"分享已暂停")+"\n\n"+prefs.getString("status","完成连接设置后，即可开始分享。")+"\n\n"+prefs.getString("libraryStatus","音乐目录尚未授权")+"\n"+lyricLabel());handler.postDelayed(this,2000);}};

 @Override public void onCreate(Bundle b){super.onCreate(b);prefs=getSharedPreferences("som",0);
  if((getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES){ink=0xffe4edf5;muted=0xffb3c6d7;background=0xff142331;surface=0xff203444;accent=0xffa6cef0;}
  ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(background);LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(dp(20),dp(24),dp(20),dp(28));scroll.addView(root);setContentView(scroll);
  scroll.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(0,insets.getSystemWindowInsetTop(),0,insets.getSystemWindowInsetBottom());return insets;});
  if(background==0xffeaf0f5)getWindow().getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
  copy(root,"State of Me",28,ink);copy(root,"分享你选择的此刻",14,muted);
  LinearLayout navigation=new LinearLayout(this);root.addView(navigation);String[] names={"状态","连接","分享","歌词"};
  for(int n=0;n<names.length;n++){final int index=n;Button btab=new Button(this);btab.setText(names[n]);btab.setTextSize(14);btab.setAllCaps(false);btab.setMinHeight(dp(48));btab.setPadding(0,0,0,0);navigation.addView(btab,new LinearLayout.LayoutParams(0,-2,1));btab.setOnClickListener(v->select(index));tabs.add(btab);}
  android.view.View gap=new android.view.View(this);root.addView(gap,new LinearLayout.LayoutParams(1,dp(20)));
  LinearLayout overview=page(root,"分享状态","只有允许的内容会发送到你的服务。你可以随时暂停分享。");
  status=copy(overview,"尚未开始",16,ink);
  LinearLayout connection=page(root,"连接服务","使用你部署的 State of Me 服务和此设备专属的写入令牌。");
  LinearLayout sharing=page(root,"分享范围","应用与播放器均使用允许列表。留空表示不分享对应内容。");
  LinearLayout lyrics=page(root,"同步歌词","选择音乐所在目录，在本机读取内嵌歌词。无需 Root。");
  LinearLayout box=connection;
  url=edit(box,"服务地址（https://…，无结尾斜杠）","url",false);token=edit(box,"此设备写入令牌","token",true);
  box=sharing;
  apps=edit(box,"允许的应用包名，逗号分隔；空表示不分享应用","apps",false);players=edit(box,"允许的播放器包名","players",false);
  if(getIntent().hasExtra("server"))url.setText(getIntent().getStringExtra("server"));
  if(getIntent().hasExtra("device_token"))token.setText(getIntent().getStringExtra("device_token"));
  button(box,"1 · 授予通知访问（音乐）",()->startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
  button(box,"2 · 可选：使用情况访问（前台应用）",()->startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
  box=overview;
  button(box,"保存并开始分享",()->{String u=url.getText().toString().trim();if(!u.startsWith("https://")||token.getText().length()<24){toast("请填写 HTTPS 地址和有效令牌");return;}prefs.edit().putString("url",u.replaceAll("/+$", "")).putString("token",token.getText().toString().trim()).putString("apps",apps.getText().toString()).putString("players",players.getText().toString()).putBoolean("enabled",true).apply();if(Build.VERSION.SDK_INT>=33)requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},9);startForegroundService(new Intent(this,Collector.class));});
  button(box,"暂停分享并清空服务端状态",()->{prefs.edit().putBoolean("enabled",false).apply();startForegroundService(new Intent(this,Collector.class).setAction("stop"));});
  box=lyrics;
  button(box,"授权音乐目录并索引内嵌歌词",()->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),11));
  button(box,"撤销音乐目录授权",()->MusicLibrary.clear(this));
  title=edit(box,"LRC 对应完整歌曲名（必须与播放器一致）","lyricTitle",false);
  button(box,"选择本地 LRC（可选）",()->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,10);});
  button(box,"清除歌词授权",()->prefs.edit().remove("lrc").remove("lyricTitle").apply());
  select(0);
 }
 EditText edit(LinearLayout box,String label,String key,boolean secret){TextView l=copy(box,label,14,muted);EditText e=new EditText(this);e.setId(android.view.View.generateViewId());l.setLabelFor(e.getId());e.setSingleLine(true);e.setTextSize(16);e.setTextColor(ink);e.setHintTextColor(muted);e.setMinHeight(dp(52));e.setPadding(dp(12),dp(8),dp(12),dp(8));e.setBackground(shape(background,10));e.setText(prefs.getString(key,""));if(secret)e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);else e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(12);box.addView(e,lp);return e;}
 void button(LinearLayout box,String label,Runnable r){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(14);b.setMinHeight(dp(48));b.setTextColor(ink);b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x3349647d),shape(background,10),null));b.setPadding(dp(12),dp(10),dp(12),dp(10));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(12);b.setOnClickListener(v->r.run());box.addView(b,lp);}
 void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
 @Override protected void onResume(){super.onResume();handler.post(refresh);}
 @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
 @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==11&&result==RESULT_OK&&data!=null){try{getContentResolver().takePersistableUriPermission(data.getData(),Intent.FLAG_GRANT_READ_URI_PERMISSION);prefs.edit().putString("tree",data.getData().toString()).putLong("libraryVersion",System.currentTimeMillis()).apply();MusicLibrary.scan(this,data.getData());}catch(Exception e){toast("目录授权失败");}return;}if(request==10&&result==RESULT_OK&&data!=null){try(InputStream in=getContentResolver().openInputStream(data.getData());ByteArrayOutputStream out=new ByteArrayOutputStream()){
  byte[] buf=new byte[4096];int n;while((n=in.read(buf))>0){out.write(buf,0,n);if(out.size()>262144)throw new IOException("Too large");}
  if(title.getText().toString().trim().isEmpty()){toast("请先填写对应歌曲名");return;}prefs.edit().putString("lrc",out.toString("UTF-8")).putString("lyricTitle",title.getText().toString().trim()).apply();toast("已导入；仅对应歌曲会分享当前句");
 }catch(Exception e){toast("读取失败：请选择 UTF-8 LRC，最大 256KB");}}}
}
