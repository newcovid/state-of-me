/* Public, same-origin companion. No authentication, cookies or activity persistence. */
(()=>{
 if(customElements.get('state-of-me-status'))return;
 class Presence extends HTMLElement{
  connectedCallback(){
   if(this.poll)return;
   if(!this.root){this.root=this.attachShadow({mode:'open'});this.root.innerHTML=`
<style>
:host{position:fixed;right:22px;bottom:22px;z-index:1000;display:block;width:min(312px,calc(100vw - 28px));color:var(--som-fg,#ddd);font:14px/1.55 system-ui;contain:layout style}*{box-sizing:border-box}section{background:var(--som-bg,#181818);border:1px solid var(--som-border,#6666);border-radius:18px;box-shadow:0 12px 40px #0003;overflow:hidden}header{display:flex;align-items:center;gap:8px;padding:10px 12px;border-bottom:1px solid var(--som-border,#6666)}.grip{flex:1;cursor:grab;touch-action:none;text-align:left;font-size:12px;color:inherit}.dot{display:inline-block;width:6px;height:6px;background:currentColor;border-radius:50%;margin-right:8px;opacity:.5}button{font:inherit;color:inherit;background:none;border:0;border-radius:8px;cursor:pointer;min-height:36px;min-width:36px;padding:5px 9px}button:hover{background:#8882}button:focus-visible{outline:2px solid currentColor;outline-offset:-2px}.body{padding:18px}.track{display:flex;gap:14px;align-items:center}.disc{flex-shrink:0;width:52px;height:52px;border-radius:50%;background:repeating-radial-gradient(circle,#242424 0 3px,#383838 4px 5px);display:grid;place-items:center;border:1px solid #7776}.disc:after{content:'♪';display:grid;place-items:center;border-radius:50%;width:22px;height:22px;background:#b9b4a7;color:#242424;font-size:13px}.meta{min-width:0}.title{font-weight:650;font-size:15px;overflow-wrap:anywhere}.artist{font-size:12px;opacity:.65;margin-top:3px;overflow-wrap:anywhere}.lyrics{padding:20px 0 12px;text-align:center}.line{font-family:inherit;font-size:16px;line-height:1.8;overflow-wrap:anywhere;min-height:52px;display:grid;align-items:center;margin:0}.next{font-size:12px;opacity:.5;overflow-wrap:anywhere;margin:8px 0 0;min-height:19px}progress{width:100%;height:3px;display:block;border:0;accent-color:var(--som-fg,#ccc);background:#8883}progress::-webkit-progress-bar{background:#8883}progress::-webkit-progress-value{background:var(--som-fg,#ccc)}.time{font-size:11px;opacity:.6;font-variant-numeric:tabular-nums;text-align:right;margin:7px 0}.actions{display:flex;justify-content:space-between;align-items:center;gap:8px;margin-top:7px}.hint{font-size:10px;opacity:.55}.actions button{font-size:11px}[hidden]{display:none!important}:host([collapsed]){width:220px}:host([collapsed]) .body{display:none}:host([collapsed]) header{border:0}@media(max-width:480px){:host{right:14px;bottom:14px;width:min(290px,calc(100vw - 28px))}}@media(prefers-reduced-motion:reduce){*{transition:none!important}}
</style><section aria-label="公开音乐与歌词"><header><button class="grip" aria-label="拖动音乐卡片，或使用方向键移动"><span class="dot"></span><span class="state">此刻在听</span></button><button class="reset" title="恢复默认位置" aria-label="恢复默认位置">↺</button><button class="collapse" aria-label="收起音乐卡片" aria-expanded="true">−</button></header><div class="body"><div class="track"><div class="disc" aria-hidden="true"></div><div class="meta"><div class="title">暂无公开音乐</div><div class="artist">等待设备分享</div></div></div><div class="lyrics"><p class="line">音乐响起时，在这里见。</p><p class="next"></p></div><progress aria-label="歌曲进度" max="1" value="0"></progress><p class="time">—</p><div class="actions"><span class="hint">设备状态 · 非远程播放器</span><button class="toggle">收起歌词</button></div></div></section>`;
    this.nodes={};for(const name of ['grip','state','reset','collapse','title','artist','lyrics','line','next','time','toggle'])this.nodes[name]=this.root.querySelector('.'+name);this.bar=this.root.querySelector('progress');
    this.nodes.collapse.onclick=()=>{const collapsed=this.toggleAttribute('collapsed');this.nodes.collapse.textContent=collapsed?'+':'−';this.nodes.collapse.setAttribute('aria-label',collapsed?'展开音乐卡片':'收起音乐卡片');this.nodes.collapse.setAttribute('aria-expanded',String(!collapsed));this.clamp();this.savePreferences();};
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
   this.nodes.collapse.setAttribute('aria-label',collapsed?'展开音乐卡片':'收起音乐卡片');
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
  empty(){this.music=null;this.text(this.nodes.state,'暂无分享');this.text(this.nodes.title,'暂无公开音乐');this.text(this.nodes.artist,'等待设备分享');this.text(this.nodes.line,'音乐响起时，在这里见。');this.text(this.nodes.next,'');this.text(this.nodes.time,'—');this.bar.value=0;}
  frame(){if(!this.isConnected)return;const m=this.music,now=performance.now();if(m&&now>=this.expires)this.empty();else if(m){const pos=Math.min(m.duration_ms||604800000,m.position_ms+(m.playing?(now-this.anchor)*(m.playback_rate??1):0));let line=m.lyric,next='';const w=m.lyric_window;if(w){line=null;if(pos>=w.start_ms&&pos<w.end_ms){for(let i=0;i<w.lines.length;i++){if(w.lines[i].at_ms<=pos){line=w.lines[i].text;next=w.lines[i+1]?.text||'';}else break;}}}this.text(this.nodes.line,line??(m.lyric_status==='plain'?'歌词暂无同步时间轴':'暂无同步歌词'));this.text(this.nodes.next,next);this.bar.max=m.duration_ms||1;this.bar.value=pos;const format=x=>Math.floor(x/60000)+':'+String(Math.floor(x/1000)%60).padStart(2,'0');this.text(this.nodes.time,format(pos)+(m.duration_ms?' / '+format(m.duration_ms):''));}this.animation=requestAnimationFrame(()=>this.frame());}
  async update(){if(this.busy)return;this.busy=true;const controller=new AbortController();this.abort=controller;const timeout=setTimeout(()=>controller.abort(),8000);try{const url=new URL(this.getAttribute('endpoint')||'/presence/v1/public/status',location.href);if(url.origin!==location.origin)throw Error('same-origin required');const r=await fetch(url,{credentials:'omit',cache:'no-store',signal:controller.signal});if(!r.ok)throw Error('unavailable');const d=await r.json();if(!this.isConnected)return;const item=d.devices?.find(x=>x.music);if(!item){this.empty();return;}this.music=item.music;this.anchor=performance.now();this.expires=this.anchor+Math.max(0,item.expires_in)*1000;this.text(this.nodes.state,this.music.playing?'此刻在听':'音乐已暂停');this.text(this.nodes.title,this.music.title);this.text(this.nodes.artist,[this.music.artist,this.music.album].filter(Boolean).join(' / '));}catch(e){this.empty();}finally{clearTimeout(timeout);this.busy=false;}}
 }
 customElements.define('state-of-me-status',Presence);
 function mount(){if(!document.querySelector('state-of-me-status'))document.body.append(document.createElement('state-of-me-status'));}
 if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',mount,{once:true});else mount();
})();
