/* Public, same-origin companion. No authentication, cookies or activity persistence. */
(()=>{
 const GROUPS={"app": "应用", "app_details": "应用详情", "music": "音乐与媒体", "device_state": "设备与电源", "network": "网络", "audio": "声音", "resources": "系统资源", "collector_health": "采集诊断", "presence": "活跃状态", "manual_status": "手动状态", "display": "显示环境", "usage_summary": "WhatPulse 统计"},LABELS={"app": "前台应用标识", "device_label": "设备名称", "label": "名称", "active_for_ms": "当前前台片段时长", "foreground": "是否前台窗口", "input_idle": "是否缺少近期输入", "z_order": "窗口层级", "always_on_top": "窗口置顶", "maximized": "窗口最大化", "fullscreen": "全屏状态", "title": "媒体标题", "artist": "歌手或作者", "album": "专辑", "player": "播放器标识", "playing": "正在播放", "playback_status": "播放状态", "position_ms": "播放进度", "duration_ms": "媒体时长", "playback_rate": "播放速率", "track_id": "曲目标识", "lyric": "当前歌词", "lyric_window": "歌词时间窗口", "lyric_status": "歌词匹配状态", "media_kind": "媒体类型", "position_known": "进度可用性", "battery_present": "是否有电池", "ac_connected": "外接电源", "battery_percent": "电量", "charging": "充电状态", "screen_on": "屏幕交互状态", "locked": "锁屏状态", "power_save": "省电模式", "battery_temperature_c": "电池温度", "battery_voltage_mv": "电池电压", "charge_source": "充电来源", "battery_health": "电池健康状态", "connected": "网络连接", "validated": "系统联网验证", "metered": "计费网络", "transports": "网络类型", "default_output_form": "默认音频输出类型", "active_output_sessions": "活跃音频会话数", "music_volume_percent": "媒体音量", "media_muted": "媒体静音", "ringer_mode": "响铃模式", "interruption_filter": "免打扰模式", "connected_output_types": "已连接音频设备类型", "available_memory_mib": "可用内存", "total_memory_mib": "总内存", "low_memory": "低内存状态", "available_storage_mib": "可用存储", "total_storage_mib": "总存储", "cpu_percent": "CPU 使用率", "thermal_status": "系统热状态", "version": "采集器版本", "sdk": "Android SDK 版本", "notification_access": "通知访问授权状态", "usage_access": "使用情况授权状态", "music_directory_access": "音乐目录授权状态", "battery_optimization_exempt": "电池优化豁免状态", "state": "活跃状态", "idle_seconds": "缺少输入时长", "text": "手动状态文字", "expires_at": "手动状态有效期", "monitor_count": "显示器数量", "remote_session": "远程会话", "source": "统计来源", "scope": "统计范围", "sampled_at": "统计截至", "keys": "按键次数", "clicks": "点击次数", "scrolls": "滚动次数", "download": "下载字节数", "upload": "上传字节数", "uptime": "运行时长", "keys_per_second": "每秒按键", "clicks_per_second": "每秒点击", "download_bytes_per_second": "下载速率", "upload_bytes_per_second": "上传速率", "sample_seconds": "速率采样窗口", "date": "日期", "known_ms": "已观测时长", "screen_ms": "屏幕使用时长", "unlocked_ms": "解锁使用时长", "unlocks": "解锁次数", "apps": "应用排行", "hourly_screen_ms": "每小时屏幕使用", "package": "应用标识", "foreground_ms": "前台时长", "launches": "启动次数", "sessions": "前台片段次数", "received_at": "接收时间", "timezone": "时区", "first_event_at": "最早记录", "apps_truncated": "应用列表已截断"};
 const own=(o,k)=>Object.prototype.hasOwnProperty.call(o||{},k);
 function formatValue(key,value){
  if(value===null||value===undefined)return '';
  if(typeof value==='boolean')return value?'是':'否';
  if(typeof value==='number'){
   if(!Number.isFinite(value))return '';
   if(['sampled_at','received_at','first_event_at','expires_at'].includes(key))return new Date(value*1000).toLocaleString();
   if(key.endsWith('_ms'))return duration(value);
   if(key.endsWith('_percent'))return value+'%';
   if(key.endsWith('_mib'))return value.toLocaleString()+' MiB';
   if(key.endsWith('_seconds'))return value+' 秒';
   if(key.endsWith('_c'))return value+' °C';
   if(key.endsWith('_mv'))return value+' mV';
   return String(value);
  }
  if(typeof value==='object')return JSON.stringify(value);
  return String(value);
 }
 function duration(ms){if(!Number.isFinite(ms))return '';const seconds=Math.floor(Math.max(0,ms)/1000);return seconds>=3600?Math.floor(seconds/3600)+'时 '+Math.floor(seconds/60)%60+'分':Math.floor(seconds/60)+'分 '+seconds%60+'秒';}
 function model(devices,history=[]){
  const rows=(Array.isArray(devices)?devices:[]).filter(d=>Number.isFinite(d.expires_in)&&d.expires_in>0).map((d,index)=>{
   const groups=[];for(const [key,value] of Object.entries(d)){
    if(['device','device_label','expires_in','age_seconds','until','music'].includes(key)||value===null)continue;
    const fields=typeof value==='object'&&!Array.isArray(value)?Object.entries(value):[[key,value]];
    if(fields.length)groups.push({key,label:GROUPS[key]||LABELS[key]||key,fields});
   }
   const music=d.music?.playing===true&&[undefined,'unknown','playing'].includes(d.music.playback_status)?d.music:null;
   return {id:String(d.device||index),label:d.device_label||'设备 '+(index+1),groups,music};
  }).filter(d=>d.groups.length||d.music);
  const histories=(Array.isArray(history)?history:[]).map((d,index)=>({...d,label:d.device_label||'设备 '+((Array.isArray(devices)?devices:[]).findIndex(x=>x.device===d.device)+1||index+1)}));
  return {rows,history:histories};
 }
 function playback(m,elapsed){
  const known=Number.isFinite(m.position_ms)&&m.position_known!==false;
  const limit=Number.isFinite(m.duration_ms)&&m.duration_ms>0?m.duration_ms:null;
  const rate=Number.isFinite(m.playback_rate)?Math.max(0,m.playback_rate):1;
  const position=known?Math.min(limit??Infinity,Math.max(0,m.position_ms+elapsed*rate)):null;
  let line=typeof m.lyric==='string'?m.lyric:'',next='';const w=m.lyric_window;
  if(known&&w&&Array.isArray(w.lines)){line='';if(position>=w.start_ms&&position<w.end_ms){for(let i=0;i<w.lines.length;i++){if(w.lines[i].at_ms<=position){line=w.lines[i].text||'';next=w.lines[i+1]?.text||'';}else break;}}}
  return {position,duration:limit,line,next};
 }
 if(typeof module!=='undefined'&&module.exports){module.exports={model,playback,formatValue};return;}
 if(customElements.get('state-of-me-status'))return;
 class Presence extends HTMLElement{
  connectedCallback(){
   if(this.poll)return;
   if(!this.root){this.root=this.attachShadow({mode:'open'});this.root.innerHTML=`
<style>
:host{position:fixed;right:22px;bottom:22px;z-index:1000;display:block;width:min(332px,calc(100vw - 28px));color:var(--som-fg,#ddd);font:14px/1.55 system-ui;contain:layout style}*{box-sizing:border-box}section{background:var(--som-bg,#181818);border:1px solid var(--som-border,#6666);border-radius:18px;box-shadow:0 12px 40px #0003;overflow:hidden}header{display:flex;align-items:center;gap:8px;padding:10px 12px;border-bottom:1px solid var(--som-border,#6666)}.grip{flex:1;cursor:grab;touch-action:none;text-align:left;font-size:12px;color:inherit}.dot{display:inline-block;width:6px;height:6px;background:currentColor;border-radius:50%;margin-right:8px;opacity:.5}button{font:inherit;color:inherit;background:none;border:0;border-radius:8px;cursor:pointer;min-height:36px;min-width:36px;padding:5px 9px}button:hover{background:#8882}button:focus-visible{outline:2px solid currentColor;outline-offset:-2px}.body{padding:18px;max-height:min(640px,70vh);overflow-y:auto;overscroll-behavior:contain}.summary:empty{display:none}.device{padding:0 0 14px}.device+.device{padding-top:10px;border-top:1px solid #8883}details{margin:8px 0}summary{cursor:pointer;padding:7px 0;font-weight:600}dl{margin:4px 0 12px}dt{font-size:11px;opacity:.65;margin-top:7px}dd{margin:1px 0 0;overflow-wrap:anywhere;font-size:12px}.media-card{padding:10px 0;border-top:1px solid #8883}.media-card .lyrics{padding:10px 0}.device-name{font-size:11px;opacity:.6;margin-bottom:4px}.activity{font-size:14px;font-weight:600;overflow-wrap:anywhere}.badges{display:flex;flex-wrap:wrap;gap:6px;margin-top:7px}.badge{font-size:11px;padding:3px 8px;background:#8881;border:1px solid #8882;border-radius:7px}.summary:not(:empty)~.music{border-top:1px solid #8883;padding-top:16px}.album{font-size:11px;opacity:.55;margin-top:5px;overflow-wrap:anywhere}.updated{font-size:10px;opacity:.55;margin:12px 0 0}.empty-note{margin:4px 0 10px;opacity:.65;font-size:13px}.state{display:inline-block;max-width:180px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;vertical-align:middle}:host([collapsed]) .state{max-width:100px}.track{display:flex;gap:14px;align-items:center}.disc{flex-shrink:0;width:52px;height:52px;border-radius:50%;background:repeating-radial-gradient(circle,#242424 0 3px,#383838 4px 5px);display:grid;place-items:center;border:1px solid #7776}.disc:after{content:'♪';display:grid;place-items:center;border-radius:50%;width:22px;height:22px;background:#b9b4a7;color:#242424;font-size:13px}.meta{min-width:0}.title{font-weight:650;font-size:15px;overflow-wrap:anywhere}.artist{font-size:12px;opacity:.65;margin-top:3px;overflow-wrap:anywhere}.lyrics{padding:20px 0 12px;text-align:center}.line{font-family:inherit;font-size:16px;line-height:1.8;overflow-wrap:anywhere;min-height:52px;display:grid;align-items:center;margin:0}.next{font-size:12px;opacity:.5;overflow-wrap:anywhere;margin:8px 0 0;min-height:19px}progress{width:100%;height:3px;display:block;border:0;accent-color:var(--som-fg,#ccc);background:#8883}progress::-webkit-progress-bar{background:#8883}progress::-webkit-progress-value{background:var(--som-fg,#ccc)}.time{font-size:11px;opacity:.6;font-variant-numeric:tabular-nums;text-align:right;margin:7px 0}.actions{display:flex;justify-content:space-between;align-items:center;gap:8px;margin-top:7px}.hint{font-size:10px;opacity:.55}.actions button{font-size:11px}[hidden]{display:none!important}:host([collapsed]){width:220px}:host([collapsed]) .body{display:none}:host([collapsed]) header{border:0}@media(max-width:480px){:host{right:14px;bottom:14px;width:min(290px,calc(100vw - 28px))}}@media(prefers-reduced-motion:reduce){*{transition:none!important}}
</style><section aria-label="公开近况"><header><button class="grip" aria-label="拖动状态卡片，或使用方向键移动"><span class="dot"></span><span class="state">正在更新</span></button><button class="reset" title="恢复默认位置" aria-label="恢复默认位置">⌖</button><button class="collapse" aria-label="收起状态卡片" aria-expanded="true">−</button></header><div class="body"><div class="summary"></div><p class="empty-note">暂时没有可展示的近况</p><div class="music" hidden><div class="track"><div class="disc" aria-hidden="true"></div><div class="meta"><div class="title">暂无公开音乐</div><div class="artist"></div><div class="album"></div></div></div><div class="lyrics"><p class="line">音乐响起时，在这里见。</p><p class="next"></p></div><progress aria-label="歌曲进度" max="1" value="0"></progress><p class="time">—</p><div class="actions"><span class="hint">来自设备分享</span><button class="toggle">收起歌词</button></div></div><p class="updated"></p></div></section>`;
    this.nodes={};for(const name of ['grip','state','reset','collapse','title','artist','album','lyrics','line','next','time','toggle','summary','music','empty-note','updated','hint'])this.nodes[name]=this.root.querySelector('.'+name);this.bar=this.root.querySelector('progress');
    this.nodes.collapse.onclick=()=>{const collapsed=this.toggleAttribute('collapsed');this.nodes.collapse.textContent=collapsed?'+':'−';this.nodes.collapse.setAttribute('aria-label',collapsed?'展开状态卡片':'收起状态卡片');this.nodes.collapse.setAttribute('aria-expanded',String(!collapsed));this.clamp();this.savePreferences();};
    this.nodes.toggle.onclick=()=>{this.nodes.lyrics.hidden=!this.nodes.lyrics.hidden;this.nodes.toggle.textContent=this.nodes.lyrics.hidden?'展开歌词':'收起歌词';this.clamp();this.savePreferences();};
    this.nodes.reset.onclick=()=>{this.style.left='';this.style.top='';this.style.right='';this.style.bottom='';this.savePreferences();};
    this.nodes.grip.onpointerdown=e=>{if(e.button!==0)return;const rect=this.getBoundingClientRect();this.drag={x:e.clientX,y:e.clientY,left:rect.left,top:rect.top};this.nodes.grip.setPointerCapture(e.pointerId);};
    this.nodes.grip.onpointermove=e=>{if(this.drag)this.move(this.drag.left+e.clientX-this.drag.x,this.drag.top+e.clientY-this.drag.y);};
    this.nodes.grip.onpointerup=this.nodes.grip.onpointercancel=()=>{this.drag=null;this.savePreferences();};
    this.nodes.grip.onkeydown=e=>{const delta={ArrowLeft:[-16,0],ArrowRight:[16,0],ArrowUp:[0,-16],ArrowDown:[0,16]}[e.key];if(delta){e.preventDefault();const r=this.getBoundingClientRect();this.move(r.left+delta[0],r.top+delta[1]);this.savePreferences();}};
   }
   this.sections={};try{this.sections=JSON.parse(localStorage.getItem('state-of-me:widget-sections:v1'))||{};}catch(e){}this.restorePreferences();
   this.onResize=()=>this.clamp();window.addEventListener('resize',this.onResize);this.theme=()=>{const body=getComputedStyle(document.body),html=getComputedStyle(document.documentElement);this.style.setProperty('--som-fg',body.color);this.style.setProperty('--som-bg',body.backgroundColor==='rgba(0, 0, 0, 0)'?html.backgroundColor:body.backgroundColor);};// Themes use different attributes (e.g. theme/data-color-scheme), not only classes.
   this.scheduleTheme=()=>{cancelAnimationFrame(this.themeFrame);this.themeFrame=requestAnimationFrame(this.theme);};
   this.observer=new MutationObserver(this.scheduleTheme);
   this.observer.observe(document.documentElement,{attributes:true});
   this.observer.observe(document.body,{attributes:true});
   this.colorPreference=matchMedia('(prefers-color-scheme: dark)');
   this.colorPreference.addEventListener('change',this.scheduleTheme);
   document.body.addEventListener('transitionend',this.scheduleTheme);
   this.theme();this.update();this.poll=setInterval(()=>this.update(),5000);this.frame();
  }
  restorePreferences(){
   let prefs={};try{const value=JSON.parse(localStorage.getItem('state-of-me:widget-ui:v1'));if(value&&typeof value==='object')prefs=value;}catch(e){}
   const collapsed=typeof prefs.collapsed==='boolean'?prefs.collapsed:true;
   this.toggleAttribute('collapsed',collapsed);
   this.nodes.collapse.textContent=collapsed?'+':'−';
   this.nodes.collapse.setAttribute('aria-label',collapsed?'展开状态卡片':'收起状态卡片');
   this.nodes.collapse.setAttribute('aria-expanded',String(!collapsed));
   this.nodes.lyrics.hidden=prefs.lyricsHidden===true;
   this.nodes.toggle.textContent=this.nodes.lyrics.hidden?'展开歌词':'收起歌词';
   if(prefs.position&&Number.isFinite(prefs.position.x)&&Number.isFinite(prefs.position.y))this.move(prefs.position.x,prefs.position.y);
  }
  savePreferences(){
   const position=this.style.left?{x:parseFloat(this.style.left),y:parseFloat(this.style.top)}:null;
   try{localStorage.setItem('state-of-me:widget-ui:v1',JSON.stringify({collapsed:this.hasAttribute('collapsed'),lyricsHidden:this.nodes.lyrics.hidden,position}));}catch(e){}
  }
  move(x,y){this.style.right='auto';this.style.bottom='auto';this.style.left=Math.max(8,Math.min(x,innerWidth-this.offsetWidth-8))+'px';this.style.top=Math.max(8,Math.min(y,innerHeight-this.offsetHeight-8))+'px';}
  clamp(){const r=this.getBoundingClientRect();if(this.style.left||r.right>innerWidth||r.bottom>innerHeight)this.move(r.left,r.top);}
  disconnectedCallback(){clearInterval(this.poll);cancelAnimationFrame(this.animation);this.abort?.abort();this.observer?.disconnect();cancelAnimationFrame(this.themeFrame);this.colorPreference?.removeEventListener('change',this.scheduleTheme);document.body.removeEventListener('transitionend',this.scheduleTheme);window.removeEventListener('resize',this.onResize);this.poll=null;this.entries=[];this.history=[];this.players=[];}
  text(node,value){if(node.textContent!==value)node.textContent=value;}
  section(parent,key,title,open=false){
   const box=document.createElement('details'),caption=document.createElement('summary');caption.textContent=title;box.append(caption);box.open=own(this.sections,key)?this.sections[key]===true:open;
   box.ontoggle=()=>{this.sections[key]=box.open;try{localStorage.setItem('state-of-me:widget-sections:v1',JSON.stringify(this.sections));}catch(e){}this.clamp();};parent.append(box);return box;
  }
  fields(parent,fields){const dl=document.createElement('dl');for(const [key,value] of fields){if(value===null||value===undefined)continue;const dt=document.createElement('dt'),dd=document.createElement('dd');dt.textContent=LABELS[key]||key;dd.textContent=formatValue(key,value);dl.append(dt,dd);}parent.append(dl);}
  media(parent,m,key){
   const box=document.createElement('div');box.className='media-card';parent.append(box);
   for(const [field,cls] of [['title','title'],['artist','artist'],['album','album']]){if(own(m,field)){const el=document.createElement('div');el.className=cls;el.textContent=m[field];box.append(el);}}
   const lyrics=this.section(box,key+':lyrics','歌词',!this.nodes.lyrics.hidden);lyrics.hidden=!own(m,'lyric')&&!own(m,'lyric_window');
   const line=document.createElement('p'),next=document.createElement('p');line.className='line';next.className='next';lyrics.append(line,next);
   const bar=document.createElement('progress'),time=document.createElement('p');bar.setAttribute('aria-label','歌曲进度');time.className='time';box.append(bar,time);
   const extras=Object.entries(m).filter(([k])=>!['title','artist','album','lyric','lyric_window','position_ms','duration_ms','position_known','playing','playback_status'].includes(k));
   if(extras.length)this.fields(this.section(box,key+':media-info','媒体详情'),extras);
   this.players.push({m,line,next,bar,time});
  }
  render(){
   const now=performance.now();this.entries=(this.entries||[]).filter(d=>d.until>now);
   const view=model(this.entries.map(d=>({...d,expires_in:(d.until-now)/1000})),this.history);this.players=[];
   this.nodes.music.hidden=true;this.nodes['empty-note'].hidden=!!(view.rows.length||view.history.length);this.nodes.summary.replaceChildren();
   for(const row of view.rows){const device=this.section(this.nodes.summary,'device:'+row.id,row.label,true);
    if(row.music)this.media(device,row.music,'device:'+row.id);
    for(const group of row.groups)this.fields(this.section(device,'device:'+row.id+':'+group.key,group.label),group.fields);
   }
   for(const [index,entry] of view.history.entries()){
    const key='history:'+String(entry.device||index),box=this.section(this.nodes.summary,key,entry.label+' · 历史统计');
    const stamp=document.createElement('p');stamp.className='updated';stamp.textContent='统计截至 '+(own(entry,'sampled_at')?formatValue('sampled_at',entry.sampled_at):'时间未提供')+(Number.isFinite(entry.sampled_at)?'（浏览器本地时间）':'');box.append(stamp);
    for(const day of Array.isArray(entry.days)?entry.days:[]){const part=this.section(box,key+':'+day.date,String(day.date||'日期未提供'));
     this.fields(part,Object.entries(day).filter(([k])=>!['date','apps','hourly_screen_ms'].includes(k)));
     if(Array.isArray(day.apps)){const apps=this.section(part,key+':'+day.date+':apps','应用排行');for(const app of day.apps)this.fields(apps,Object.entries(app));if(!day.apps.length){const text=document.createElement('p');text.textContent='本日没有已分享的应用记录';apps.append(text);}}
     if(Array.isArray(day.hourly_screen_ms)){const hours=this.section(part,key+':'+day.date+':hours','每小时屏幕使用');this.fields(hours,day.hourly_screen_ms.map((v,i)=>[String(i).padStart(2,'0')+':00',Number.isFinite(v)?duration(v):'未观测']));}
    }
   }
   this.text(this.nodes.state,this.players.length?'正在播放 · '+this.players.length+' 台':view.rows.length?'设备近况 · '+view.rows.length+' 台':view.history.length?'公开历史统计':'暂无动态');
   const ages=this.entries.map(d=>d.age_seconds).filter(Number.isFinite);this.text(this.nodes.updated,this.entries.length?'实时状态同步于 '+new Date(this.fetchedWall-Math.max(0,...ages)*1000).toLocaleTimeString([],{hour:'2-digit',minute:'2-digit',second:'2-digit'}):'');this.tick();this.clamp();
  }
  empty(){this.entries=[];this.history=[];this.players=[];this.render();}
  tick(){for(const p of this.players||[]){const state=playback(p.m,performance.now()-this.anchor);this.text(p.line,state.line);this.text(p.next,state.next);p.bar.hidden=state.position===null||state.duration===null;p.time.hidden=state.position===null&&state.duration===null;if(!p.bar.hidden){p.bar.max=state.duration;p.bar.value=state.position;}this.text(p.time,(state.position!==null?duration(state.position):'')+(state.duration!==null?(state.position!==null?' / ':'时长 ')+duration(state.duration):''));}}
  frame(){if(!this.isConnected)return;const now=performance.now();if(this.entries?.some(d=>d.until<=now))this.render();this.tick();this.animation=requestAnimationFrame(()=>this.frame());}
  async update(){if(this.busy)return;this.busy=true;const controller=new AbortController();this.abort=controller;const timeout=setTimeout(()=>controller.abort(),8000);try{const endpoint=this.getAttribute('endpoint');if(!endpoint)throw Error('endpoint required');const url=new URL(endpoint,location.href);if(url.origin!==location.origin)throw Error('same-origin required');const r=await fetch(url,{credentials:'omit',cache:'no-store',signal:controller.signal});if(!r.ok)throw Error('unavailable');const d=await r.json();if(!this.isConnected)return;this.anchor=performance.now();this.fetchedWall=Date.now();this.history=Array.isArray(d.history)?d.history:[];this.entries=(Array.isArray(d.devices)?d.devices:[]).filter(x=>Number.isFinite(x.expires_in)&&x.expires_in>0).map(x=>({...x,until:this.anchor+x.expires_in*1000}));this.render();}catch(e){this.empty();}finally{clearTimeout(timeout);this.busy=false;}}
 }
 customElements.define('state-of-me-status',Presence);
})();
