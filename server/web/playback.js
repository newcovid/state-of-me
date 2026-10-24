/* Pure playback functions shared by the browser and contract tests. */
(function(root){
 function position(m,elapsed){return Math.min(m.duration_ms||604800000,Math.max(0,m.position_ms+(m.playing&&m.position_known!==false?Math.max(0,elapsed)*(m.playback_rate??1):0)));}
 function lyric(m,pos){const w=m.lyric_window;if(!w)return m.lyric;if(pos<w.start_ms||pos>=w.end_ms)return null;let result=null;for(const line of w.lines){if(line.at_ms>pos)break;result=line.text;}return result;}
 const api={position,lyric};if(typeof module!=='undefined')module.exports=api;else root.Playback=api;
})(globalThis);
