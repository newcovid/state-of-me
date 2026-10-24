/* Public, same-origin companion. No authentication, cookies or activity persistence. */
(()=>{
 function model(devices){
  const visible=(devices||[]).filter(d=>Number.isFinite(d.expires_in)&&d.expires_in>0);
  const music=visible.find(d=>d.music?.playing===true&&[undefined,'unknown','playing'].includes(d.music.playback_status));
  const rows=visible.map(d=>{const state=d.device_state||{},details=[];
   if(Number.isFinite(state.battery_percent))details.push('电量 '+state.battery_percent+'%');
   if(d.presence?.state==='idle')details.push('电脑暂时空闲');
   if(d.manual_status?.text)details.push(d.manual_status.text);
   if(state.charging===true)details.push('充电中');if(state.locked===true)details.push('已锁屏');
   return {label:d.device==='phone'?'手机':d.device==='desktop'?'电脑':d.device||'设备',app:d.app?(d.app_details?.label||d.app):'',details};
  }).filter(row=>row.app||row.details.length);
  return {music,rows};
 }
 if(typeof module!=='undefined'&&module.exports){module.exports={model};return;}
 if(customElements.get('state-of-me-status'))return;
 class Presence extends HTMLElement{
  connectedCallback(){
   if(this.poll)return;
   if(!this.root){this.root=this.attachShadow({mode:'open'});this.root.innerHTML=`
<style>
:host{position:fixed;right:22px;bottom:22px;z-index:1000;display:block;width:min(332px,calc(100vw - 28px));color:var(--som-fg,#ddd);font:14px/1.55 system-ui;contain:layout style}*{box-sizing:border-box}section{background:var(--som-bg,#181818);border:1px solid var(--som-border,#6666);border-radius:18px;box-shadow:0 12px 40px #0003;overflow:hidden}header{display:flex;align-items:center;gap:8px;padding:10px 12px;border-bottom:1px solid var(--som-border,#6666)}.grip{flex:1;cursor:grab;touch-action:none;text-align:left;font-size:12px;color:inherit}.dot{display:inline-block;width:6px;height:6px;background:currentColor;border-radius:50%;margin-right:8px;opacity:.5}button{font:inherit;color:inherit;background:none;border:0;border-radius:8px;cursor:pointer;min-height:36px;min-width:36px;padding:5px 9px}button:hover{background:#8882}button:focus-visible{outline:2px solid currentColor;outline-offset:-2px}.body{padding:18px;max-height:min(640px,70vh);overflow-y:auto;overscroll-behavior:contain}.summary:empty{display:none}.device{padding:0 0 14px}.device+.device{padding-top:10px;border-top:1px solid #8883}.device-name{font-size:11px;opacity:.6;margin-bottom:4px}.activity{font-size:14px;font-weight:600;overflow-wrap:anywhere}.badges{display:flex;flex-wrap:wrap;gap:6px;margin-top:7px}.badge{font-size:11px;padding:3px 8px;background:#8881;border:1px solid #8882;border-radius:7px}.summary:not(:empty)~.music{border-top:1px solid #8883;padding-top:16px}.album{font-size:11px;opacity:.55;margin-top:5px;overflow-wrap:anywhere}.updated{font-size:10px;opacity:.55;margin:12px 0 0}.empty-note{margin:4px 0 10px;opacity:.65;font-size:13px}.state{display:inline-block;max-width:180px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;vertical-align:middle}:host([collapsed]) .state{max-width:100px}.track{display:flex;gap:14px;align-items:center}.disc{flex-shrink:0;width:52px;height:52px;border-radius:50%;background:repeating-radial-gradient(circle,#242424 0 3px,#383838 4px 5px);display:grid;place-items:center;border:1px solid #7776}.disc:after{content:'♪';display:grid;place-items:center;border-radius:50%;width:22px;height:22px;background:#b9b4a7;color:#242424;font-size:13px}.meta{min-width:0}.title{font-weight:650;font-size:15px;overflow-wrap:anywhere}.artist{font-size:12px;opacity:.65;margin-top:3px;overflow-wrap:anywhere}.lyrics{padding:20px 0 12px;text-align:center}.line{font-family:inherit;font-size:16px;line-height:1.8;overflow-wrap:anywhere;min-height:52px;display:grid;align-items:center;margin:0}.next{font-size:12px;opacity:.5;overflow-wrap:anywhere;margin:8px 0 0;min-height:19px}progress{width:100%;height:3px;display:block;border:0;accent-color:var(--som-fg,#ccc);background:#8883}progress::-webkit-progress-bar{background:#8883}progress::-webkit-progress-value{background:var(--som-fg,#ccc)}.time{font-size:11px;opacity:.6;font-variant-numeric:tabular-nums;text-align:right;margin:7px 0}.actions{display:flex;justify-content:space-between;align-items:center;gap:8px;margin-top:7px}.hint{font-size:10px;opacity:.55}.actions button{font-size:11px}[hidden]{display:none!important}:host([collapsed]){width:220px}:host([collapsed]) .body{display:none}:host([collapsed]) header{border:0}@media(max-width:480px){:host{right:14px;bottom:14px;width:min(290px,calc(100vw - 28px))}}@media(prefers-reduced-motion:reduce){*{transition:none!important}}
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
   this.restorePreferences();
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
  disconnectedCallback(){clearInterval(this.poll);cancelAnimationFrame(this.animation);this.abort?.abort();this.observer?.disconnect();cancelAnimationFrame(this.themeFrame);this.colorPreference?.removeEventListener('change',this.scheduleTheme);document.body.removeEventListener('transitionend',this.scheduleTheme);window.removeEventListener('resize',this.onResize);this.poll=null;this.music=null;}
  text(node,value){if(node.textContent!==value)node.textContent=value;}
  render(){
   const now=performance.now();this.entries=(this.entries||[]).filter(d=>d.until>now);
   const view=model(this.entries.map(d=>({...d,expires_in:(d.until-now)/1000})));
   this.music=view.music?.music||null;this.nodes.music.hidden=!this.music;
   this.nodes['empty-note'].hidden=!!(view.rows.length||this.music);
   this.nodes.summary.replaceChildren();
   for(const row of view.rows){const device=document.createElement('div');device.className='device';
    const label=document.createElement('div');label.className='device-name';label.textContent=row.label;device.append(label);
    if(row.app){const app=document.createElement('div');app.className='activity';app.textContent='正在使用 '+row.app;device.append(app);}
    if(row.details.length){const badges=document.createElement('div');badges.className='badges';for(const value of row.details){const badge=document.createElement('span');badge.className='badge';badge.textContent=value;badges.append(badge);}device.append(badges);}
    this.nodes.summary.append(device);
   }
   this.text(this.nodes.state,this.music?(this.music.media_kind==='video'?'此刻在看':this.music.media_kind==='unknown'?'正在播放':'此刻在听'):view.rows.some(r=>r.app)?'此刻在用':view.rows.length?'设备近况':'暂无动态');
   this.text(this.nodes.hint,view.music?'来自 '+(view.music.device==='phone'?'手机':view.music.device==='desktop'?'电脑':view.music.device):'来自设备分享');
   this.text(this.nodes.title,this.music?.title||'');this.text(this.nodes.artist,this.music?.artist||'');
   this.text(this.nodes.album,this.music?.album?'专辑 · '+this.music.album:'');
   if(!this.music){this.text(this.nodes.line,'');this.text(this.nodes.next,'');this.text(this.nodes.time,'');this.bar.value=0;}
   const ages=this.entries.map(d=>d.age_seconds).filter(Number.isFinite);
   this.text(this.nodes.updated,this.entries.length?'同步于 '+new Date(this.fetchedWall-Math.max(0,...ages)*1000).toLocaleTimeString([],{hour:'2-digit',minute:'2-digit',second:'2-digit'}):'');
   this.clamp();
  }
  empty(){this.entries=[];this.render();}
  frame(){if(!this.isConnected)return;const now=performance.now();if(this.entries?.some(d=>d.until<=now))this.render();const m=this.music;if(m){const pos=Math.min(m.duration_ms||604800000,m.position_ms+(m.playing&&m.position_known!==false?(now-this.anchor)*(m.playback_rate??1):0));let line=m.lyric,next='';const w=m.lyric_window;if(w){line=null;if(pos>=w.start_ms&&pos<w.end_ms){for(let i=0;i<w.lines.length;i++){if(w.lines[i].at_ms<=pos){line=w.lines[i].text;next=w.lines[i+1]?.text||'';}else break;}}}this.text(this.nodes.line,line??(m.lyric_status==='plain'?'歌词暂无同步时间轴':'暂无同步歌词'));this.text(this.nodes.next,next);this.bar.max=m.duration_ms||1;this.bar.value=pos;const format=x=>Math.floor(x/60000)+':'+String(Math.floor(x/1000)%60).padStart(2,'0');this.text(this.nodes.time,m.position_known===false?'播放器未提供进度':format(pos)+(m.duration_ms?' / '+format(m.duration_ms)+' · 剩余 '+format(Math.max(0,m.duration_ms-pos)):''));}this.animation=requestAnimationFrame(()=>this.frame());}
  async update(){if(this.busy)return;this.busy=true;const controller=new AbortController();this.abort=controller;const timeout=setTimeout(()=>controller.abort(),8000);try{const url=new URL(this.getAttribute('endpoint')||'/presence/v1/public/status',location.href);if(url.origin!==location.origin)throw Error('same-origin required');const r=await fetch(url,{credentials:'omit',cache:'no-store',signal:controller.signal});if(!r.ok)throw Error('unavailable');const d=await r.json();if(!this.isConnected)return;this.anchor=performance.now();this.fetchedWall=Date.now();this.entries=(d.devices||[]).filter(x=>Number.isFinite(x.expires_in)&&x.expires_in>0).map(x=>({...x,until:this.anchor+x.expires_in*1000}));this.render();}catch(e){this.empty();}finally{clearTimeout(timeout);this.busy=false;}}
 }
 customElements.define('state-of-me-status',Presence);
 function mount(){if(!document.querySelector('state-of-me-status'))document.body.append(document.createElement('state-of-me-status'));}
 if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',mount,{once:true});else mount();
})();
