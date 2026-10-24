const assert=require('node:assert/strict');
const {model,playback,formatValue}=require('./web/widget.js');
const paused={device:'private-random-id',expires_in:30,music:{title:'Old',playing:false,playback_status:'paused'}};
assert.equal(model([paused]).rows.length,0);
assert.equal(model([{...paused,expires_in:0,app:'editor'}]).rows.length,0);
assert.equal(model([{...paused,music:{playing:true,playback_status:'paused'}}]).rows.length,0);
let view=model([{...paused,device_state:{battery_percent:0,charging:false,locked:false},music:{playing:true,title:'one'}},{device:'other-secret',expires_in:40,music:{playing:true,lyric:'<script>safe text</script>'}}]);
assert.equal(view.rows.length,2);assert.equal(view.rows[0].label,'设备 1');assert.equal(view.rows[1].label,'设备 2');
assert.equal(view.rows[0].music.title,'one');assert.equal(view.rows[1].music.lyric,'<script>safe text</script>');
assert.deepEqual(view.rows[0].groups[0].fields,[['battery_percent',0],['charging',false],['locked',false]]);
assert.equal(formatValue('battery_percent',0),'0%');assert.equal(formatValue('locked',false),'否');assert.equal(formatValue('cpu_percent',undefined),'');
assert.equal(formatValue('future_field',{safe:'<b>text</b>'}),'{"safe":"<b>text</b>"}');
view=model([{device:'d',expires_in:5,resources:{cpu_percent:80},future_category:{future_field:'kept'}}],[{device:'d',days:[{date:'2026-10-24',apps:[]}]}]);
assert.equal(view.rows[0].groups[1].fields[0][1],'kept');assert.equal(view.history[0].label,'设备 1');
assert.deepEqual(playback({title:'only'},1000),{position:null,duration:null,line:'',next:''});
assert.deepEqual(playback({lyric:'only'},1000),{position:null,duration:null,line:'only',next:''});
assert.equal(playback({position_ms:0},500).position,500);
assert.equal(playback({position_ms:100,position_known:false},500).position,null);
assert.equal(playback({position_ms:900,duration_ms:1000},500).position,1000);
assert.equal(playback({duration_ms:5000},500).position,null);
assert.equal(playback({position_ms:0,lyric_window:{start_ms:0,end_ms:2000,lines:[{at_ms:0,text:'a'},{at_ms:1000,text:'b'}]}},1200).line,'b');
assert.equal(playback({position_ms:0,lyric_window:{start_ms:0,end_ms:2000,lines:[{at_ms:0,text:'a'}]}},2200).line,'');
// Exercise the actual DOM renderer without a browser; all untrusted values must stay text.
const vm=require('node:vm'),fs=require('node:fs');
class Node{constructor(){this.children=[];this.textContent='';this.style={};}append(...children){this.children.push(...children);}replaceChildren(...children){this.children=children;}setAttribute(){} }
let Component;const context={HTMLElement:class{},customElements:{get:()=>false,define:(_,type)=>Component=type},document:{createElement:()=>new Node()},performance:{now:()=>1000},localStorage:{setItem(){}},Date,console};
vm.runInNewContext(fs.readFileSync(require.resolve('./web/widget.js'),'utf8'),context);
const card=new Component();card.nodes=Object.fromEntries(['music','empty-note','summary','state','updated','lyrics'].map(x=>[x,new Node()]));card.sections={};card.clamp=()=>{};card.anchor=1000;card.fetchedWall=Date.now();card.entries=[{device:'hidden-id',until:31000,music:{playing:true,title:'<img src=x onerror=alert(1)>'}},{device:'two',until:31000,music:{playing:true,lyric:'second'}}];card.history=[{device:'offline',sampled_at:'yesterday',days:[{date:'today',apps:[],hourly_screen_ms:[0,null]}]}];card.render();
assert.equal(card.players.length,2);assert.equal(card.players[0].bar.hidden,true);assert.equal(card.players[0].time.hidden,true);
const texts=n=>[n.textContent,...(n.children||[]).flatMap(texts)];const all=texts(card.nodes.summary);
assert.ok(all.includes('<img src=x onerror=alert(1)>'));assert.ok(!all.includes('hidden-id'));assert.ok(all.includes('统计截至 yesterday'));assert.ok(all.includes('未观测'));
card.empty();assert.equal(card.history.length,0);assert.equal(card.players.length,0);assert.equal(card.nodes.summary.children.length,0);
console.log('Widget full-field, multiple-media, partial authorization, history, safe DOM and failure-clear contracts passed');

assert.equal(formatValue('sampled_at',1792800000),new Date(1792800000*1000).toLocaleString());
card.history=[{sampled_at:1792800000,days:[]}];card.render();assert.ok(texts(card.nodes.summary).some(t=>t.includes('（浏览器本地时间）')));

assert.equal(formatValue('profiles',[]),'暂无 Profile');
assert.ok(formatValue('profiles',[{id:1,name:'<script>profile</script>',active:true,created_at:'2026-10-25',updated_at:'2026-10-25'}]).includes('当前启用'));
assert.equal(formatValue('realtime_download','12,3KB/s'),'12,3KB/s');
