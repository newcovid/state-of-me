const assert=require('node:assert/strict');const p=require('./web/playback.js');
const m={position_ms:1000,playing:true,playback_rate:1.5,duration_ms:10000,lyric_window:{start_ms:0,end_ms:5000,lines:[{at_ms:0,text:'a'},{at_ms:2500,text:'b'}]}};
assert.equal(p.position(m,1000),2500);assert.equal(p.lyric(m,2499),'a');assert.equal(p.lyric(m,2500),'b');assert.equal(p.lyric(m,5000),null);assert.equal(p.position({...m,playing:false},9000),1000);assert.equal(p.position(m,20000),10000);assert.equal(p.lyric({...m,lyric_window:null,lyric:null},3000),null);assert.equal(p.position({...m,position_ms:0},0),0);
console.log('Playback contracts passed');

assert.equal(p.position({position_ms:1000,playing:true,position_known:false},5000),1000);

assert.deepEqual(p.context({resources:{cpu_percent:0},audio:{media_muted:false},usage_summary:{keys:0}}),[['媒体静音','否'],['CPU %','0'],['本次 Pulse 后按键','0']]);
assert.deepEqual(p.context({resources:{cpu_percent:null},secret:{token:'hidden'}}),[]);
