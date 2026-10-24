/* Pure playback functions shared by the browser and contract tests. */
(function(root){
 function position(m,elapsed){return Math.min(m.duration_ms||604800000,Math.max(0,m.position_ms+(m.playing&&m.position_known!==false?Math.max(0,elapsed)*(m.playback_rate??1):0)));}
 function lyric(m,pos){const w=m.lyric_window;if(!w)return m.lyric;if(pos<w.start_ms||pos>=w.end_ms)return null;let result=null;for(const line of w.lines){if(line.at_ms>pos)break;result=line.text;}return result;}
 function context(state){
  const rows=[],add=(group,key,label,unit='')=>{const value=state[group]?.[key];if(value!==null&&value!==undefined)rows.push([label,typeof value==='boolean'?(value?'是':'否'):Array.isArray(value)?value.join('、'):String(value)+unit]);};
  for(const [key,label] of Object.entries({battery_present:'有电池',battery_percent:'电量 %',charging:'充电中',ac_connected:'外接电源',locked:'锁屏',screen_on:'屏幕亮起',power_save:'节能模式'}))add('device_state',key,label);
  for(const [key,label] of Object.entries({connected:'网络连接',validated:'系统联网验证',metered:'计费网络',transports:'连接类型'}))add('network',key,label);
  for(const [key,label] of Object.entries({music_volume_percent:'音量 %',media_muted:'媒体静音',default_output_form:'默认输出设备形式',active_output_sessions:'活跃输出会话'}))add('audio',key,label);
  for(const [key,label] of Object.entries({cpu_percent:'CPU %',available_memory_mib:'可用内存 MiB',total_memory_mib:'总内存 MiB',available_storage_mib:'可用系统卷 MiB',total_storage_mib:'系统卷容量 MiB'}))add('resources',key,label);
  for(const [key,label] of Object.entries({monitor_count:'显示器数量',remote_session:'远程会话',fullscreen:'前台全屏'}))add('display',key,label);
  for(const [key,label] of Object.entries({keys:'本次 Pulse 后按键',clicks:'本次 Pulse 后点击',scrolls:'本次 Pulse 后滚动',download:'本次 Pulse 后下载 bytes',upload:'本次 Pulse 后上传 bytes',uptime:'本次 Pulse 后运行秒数',keys_per_second:'平均按键/秒',clicks_per_second:'平均点击/秒',download_bytes_per_second:'平均下载 bytes/s',upload_bytes_per_second:'平均上传 bytes/s',sample_seconds:'速率采样窗口/秒'}))add('usage_summary',key,label);
  return rows;
 }
 const api={position,lyric,context};if(typeof module!=='undefined')module.exports=api;else root.Playback=api;
})(globalThis);
