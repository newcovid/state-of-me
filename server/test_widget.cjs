const assert=require('node:assert/strict');const {model}=require('./web/widget.js');
const phone={device:'phone',expires_in:30,app:'editor',app_details:{label:'编辑器'},device_state:{battery_percent:0,charging:true,locked:true,battery_temperature_c:38},music:{title:'Old',playing:false,playback_status:'paused'}};
let result=model([phone]);assert.equal(result.music,undefined);assert.equal(result.rows[0].app,'编辑器');assert.deepEqual(result.rows[0].details,['电量 0%','充电中','已锁屏']);
assert.equal(model([{...phone,expires_in:0}]).rows.length,0);
assert.equal(model([{...phone,music:{playing:true,playback_status:'paused'}}]).music,undefined);
assert.ok(model([{...phone,music:{playing:true}}]).music);
assert.equal(model([{device:'phone',expires_in:30,device_state:{charging:false,locked:false}}]).rows.length,0);
assert.equal(model([{...phone,app:'<script>',app_details:null}]).rows[0].app,'<script>'); // Renderer inserts textContent only.
assert.deepEqual(model([]),{music:undefined,rows:[]});console.log('Widget signal contracts passed');
