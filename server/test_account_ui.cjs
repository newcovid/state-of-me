const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const html=fs.readFileSync('server/web/account.html','utf8'),elements=new Map();
let scrolls=0;
function element(tag='div') {return {tagName:tag.toUpperCase(),value:'',textContent:'',hidden:false,open:false,disabled:false,checked:false,children:[],append(...items){this.children.push(...items)},replaceChildren(...items){this.children=items},scrollIntoView(){scrolls++},reportValidity(){return true}};}
for(const match of html.matchAll(/<(\w+)[^>]*\bid="([^"]+)"/g))elements.set(match[2],element(match[1]));
const account={name:'alice',devices:[{id:'phone',label:'Phone',platform:'android',phase:'paired'}],groups:[],pending:[],qq:[],public:false};
const calls=[];
const context=vm.createContext({document:{getElementById:id=>{assert.ok(elements.has(id),id);return elements.get(id)},createElement:tag=>element(tag)},location:{href:'https://example.com/presence/account',search:'',pathname:'/presence/account'},history:{replaceState(){}},URL,URLSearchParams,AbortSignal,setTimeout:()=>1,clearTimeout(){},matchMedia:()=>({matches:true}),navigator:{clipboard:{writeText:async()=>{}}},confirm:()=>true,Playback:{context:()=>[]},fetch:async(url,options)=>{
 calls.push([String(url),options]);
 const path=new URL(url).pathname;
 const data=path.endsWith('/info')?{registration:true,groups:['123456']}:path.endsWith('/me')?account:path.endsWith('/status')?{devices:[]}:{};
 return {ok:true,json:async()=>data};
}});
vm.runInContext(fs.readFileSync('server/web/account.js','utf8'),context);
const settle=()=>new Promise(resolve=>setImmediate(resolve));
(async()=>{
 await settle();await settle();
 assert.equal(elements.get('group').tagName,'INPUT');
 assert.equal(elements.get('group-suggestions').children.length,1);
 // Notifications must not scroll, replace focus or alter the disclosure state.
 context.message('Saved');assert.equal(elements.get('toast').hidden,false);assert.equal(scrolls,0);
 elements.get('dismiss-toast').onclick();assert.equal(elements.get('toast').hidden,true);
 elements.get('group-consent').checked=true;elements.get('group').value='654321';elements.get('group').oninput();
 assert.equal(elements.get('group-consent').checked,false);assert.equal(scrolls,0);
 elements.get('pair-code').value='A';elements.get('pair-code').oninput();assert.equal(scrolls,0);
 assert.equal(elements.get('device-details').open,false);
 elements.get('refresh-status').onclick();await settle();await settle();assert.equal(scrolls,0);
 assert.equal(elements.get('device-details').open,false);
 // Only the explicit navigation button moves to the destination section.
 elements.get('next-action').onclick();await settle();await settle();assert.equal(scrolls,1);
 console.log('Account toast, typed group and scroll contracts passed');
})().catch(error=>{console.error(error);process.exitCode=1});
