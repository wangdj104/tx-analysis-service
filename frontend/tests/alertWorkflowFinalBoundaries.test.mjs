// Synthetic Vue route/component execution; no browser or provider acceptance claim.
import assert from 'node:assert/strict'
import test from 'node:test'
import {readFileSync} from 'node:fs'
import {createRequire} from 'node:module'
import path from 'node:path'
import {parse,compileScript} from '@vue/compiler-sfc'
import {transformSync} from 'esbuild'
import * as Vue from 'vue'
import {renderToString} from '@vue/server-renderer'
const require=createRequire(import.meta.url),base=new URL('../src/',import.meta.url)
function load(file,overrides={}){
 if(file in overrides)return overrides[file];if(file.endsWith('.css'))return {}
 let source=readFileSync(new URL(file,base),'utf8');if(file.endsWith('.vue'))source=compileScript(parse(source).descriptor,{id:'final-boundary',inlineTemplate:true}).content
 const module={exports:{}};new Function('require','module','exports',transformSync(source,{format:'cjs',loader:'js'}).code)(id=>{
  if(id in overrides)return overrides[id];if(id==='vue')return Vue
  if(id.startsWith('@/'))return load(id.slice(2)+(path.extname(id)?'':'.js'),overrides)
  if(id.startsWith('.'))return load(path.posix.normalize(path.posix.join(path.posix.dirname(file),id)),overrides)
  return require(id)
 },module,module.exports);return module.exports
}
const notification=JSON.parse(readFileSync(new URL('./fixtures/alert-workflow-notification.json',import.meta.url),'utf8'))
async function routeFixture(selectedValue,url=notification.location){
 const urlObject=new URL(url,'https://synthetic.invalid'),route=Vue.reactive({params:{id:urlObject.pathname.split('/').at(-1)},query:Object.fromEntries(urlObject.searchParams)}),selected=Vue.ref(selectedValue),seen=[]
 const child=Vue.defineComponent({props:['patientId','alertId'],setup(p){seen.push(p);return()=>Vue.h('div',`${p.patientId}:${p.alertId}`)}})
 const module=load('views/AlertWorkflowDetailRoute.vue',{'vue-router':{useRoute:()=>route,onBeforeRouteLeave(){},onBeforeRouteUpdate(){}},'composables/useCurrentPatient.js':{useCurrentPatient:()=>({currentPatientId:selected})},'components/alert-workflow/FollowUpDetail.vue':child})
 const app=Vue.createSSRApp(module.default);app.component('RouterLink',{render:()=>Vue.h('a')});await renderToString(app);return {route,selected,props:seen[0]}
}
for(const selection of [20,null])test(`notification patient context ${selection} is route-local and does not overwrite global selection`,async()=>{
 const f=await routeFixture(selection);assert.equal(f.props.patientId,10);assert.equal(f.props.alertId,1);assert.equal(f.selected.value,selection)
})
test('actual patient-qualified route props drive current authorization and mismatch protection',async t=>{
 const f=await routeFixture(20),calls=[],api={capabilities:async patient=>{calls.push(patient);return {data:{enabled:true,canAdopt:true}}},detail:async id=>({data:{alertId:id,patient:{id:10},allowedActions:[]}})}
 const scope=Vue.effectScope();t.after(()=>scope.stop());const flow=scope.run(()=>load('composables/useAlertWorkflow.js',{'api/alertWorkflow.js':api,'utils/authSession.js':{}}).useAlertWorkflow(Vue.ref(f.props.patientId),{api,events:new EventTarget(),capture:()=>({token:'synthetic',actorId:'1'}),isCurrent:()=>true}))
 await flow.loadCapabilities();assert.equal((await flow.open(f.props.alertId)).status,'succeeded');assert.deepEqual(calls,[10]);assert.equal(f.selected.value,20)
 api.detail=async id=>({data:{alertId:id,patient:{id:20},allowedActions:[]}});await flow.open(1);assert.equal(flow.state.detail,null);assert.equal(flow.state.error,'PATIENT_MISMATCH')
})
test('revoked notification target and late destination response cannot leak or switch global context',async t=>{
 const f=await routeFixture(20),patient=Vue.ref(f.props.patientId);let resolve
 const api={capabilities:async()=>({data:{enabled:true}}),detail:async()=>new Promise(done=>{resolve=done})}
 const scope=Vue.effectScope();t.after(()=>scope.stop());const flow=scope.run(()=>load('composables/useAlertWorkflow.js',{'api/alertWorkflow.js':api,'utils/authSession.js':{}}).useAlertWorkflow(patient,{api,events:new EventTarget(),capture:()=>({token:'synthetic',actorId:'1'}),isCurrent:()=>true}))
 await flow.loadCapabilities();const pending=flow.open(1);patient.value=30;await Vue.nextTick();resolve({data:{alertId:1,patient:{id:10},note:'old target'}});assert.equal((await pending).status,'stale');assert.equal(flow.state.detail,null)
 api.capabilities=async()=>{throw {response:{status:403}}};await flow.loadCapabilities();assert.equal(flow.state.detail,null);assert.notEqual(flow.state.capabilityPhase,'enabled');assert.equal(f.selected.value,20)
})
test('offset workflow times display an explicit browser-local offset without changing naive source times',()=>{
 const original=process.env.TZ;process.env.TZ='Asia/Shanghai'
 try{const {formatWorkflowTime}=load('utils/alertWorkflowTime.js');assert.equal(formatWorkflowTime('2026-10-20T10:00:00Z'),'2026-10-20 18:00:00 UTC+08:00');assert.equal(formatWorkflowTime('2026-10-20T18:00:00+08:00'),'2026-10-20 18:00:00 UTC+08:00');assert.equal(formatWorkflowTime('2026-10-20T10:00:00'),'2026-10-20T10:00:00');assert.equal(formatWorkflowTime(null),'—');assert.equal(formatWorkflowTime('bad'),'bad')}
 finally{if(original===undefined)delete process.env.TZ;else process.env.TZ=original}
})
