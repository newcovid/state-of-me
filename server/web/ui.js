let token='', mode='', busy=false;
const notice=document.querySelector('#notice'),cards=document.querySelector('#cards');
function node(tag,text){const e=document.createElement(tag);e.textContent=text;return e;}
function clear(){cards.replaceChildren();}
async function refresh(){if(!mode||busy)return;busy=true;const generation=mode+token;try{
 const r=await fetch(mode==='public'?'v1/public/status':'v1/status',{headers:token?{Authorization:'Bearer '+token}:{},cache:'no-store',signal:AbortSignal.timeout(8000)});
 if(!r.ok)throw Error(r.status===401?'令牌无效':r.status===404?'尚未开启公开分享':'服务暂不可用');
 const d=await r.json();if(generation!==mode+token)return;clear();notice.textContent=d.devices.length?'已更新 · '+new Date().toLocaleTimeString():'暂无可分享状态（离线、过期或已隐藏）';
 for(const s of d.devices){const c=node('article','');c.append(node('h2',s.device));if(s.app)c.append(node('p','应用 · '+s.app));if(s.music){const m=s.music;c.append(node('h3',(m.playing?'播放中 · ':'已暂停 · ')+m.title));c.append(node('p',m.artist));c.append(node('p',Math.floor(m.position_ms/60000)+':'+String(Math.floor(m.position_ms/1000)%60).padStart(2,'0')));c.append(node('blockquote',m.lyric||({plain:'有内嵌歌词，但无时间轴，无法定位当前句',ambiguous:'存在多个匹配文件，未选择歌词',unmatched:'授权目录中未找到精确匹配歌曲',permission_required:'需要重新授权音乐目录',unsupported:'此标签格式暂不支持',synced:'当前时间点暂无歌词'}[m.lyric_status]||'未提供同步歌词')));}c.append(node('small',s.age_seconds+' 秒前采集'));cards.append(c);}
 }catch(e){if(generation!==mode+token)return;clear();notice.textContent=e.message+'；旧状态已隐藏';}finally{busy=false;}}
document.querySelector('#auth').onsubmit=e=>{e.preventDefault();token=document.querySelector('#token').value.trim();document.querySelector('#token').value='';mode='private';clear();refresh();};
document.querySelector('#public').onclick=()=>{token='';mode='public';clear();refresh();};
document.querySelector('#forget').onclick=()=>{token='';mode='';clear();notice.textContent='已断开';};
setInterval(refresh,5000);
