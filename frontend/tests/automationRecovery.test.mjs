import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import {ref,reactive,watch,nextTick,effectScope} from 'vue'
function deferred(){let resolve;const promise=new Promise(r=>resolve=r);return{resolve,promise}}
function panel(t,overrides={}){
 const props=reactive({patientId:10}),calls=[],confirmations=[]
 const source=fs.readFileSync(new URL('../src/components/AutomationRunStatus.vue',import.meta.url),'utf8').match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import.*$/gm,'')
 const deps={ref,watch,defineProps:()=>props,defineEmits:()=>()=>{},defineExpose(){},ElMessage:{success(){}},ElMessageBox:{confirm:async(...args)=>{confirmations.push(args)}},listHealthAutomations:async()=>({data:[]}),runHealthAutomation:async(...args)=>{calls.push(args)},...overrides}
 const scope=effectScope(),v=scope.run(()=>new Function(...Object.keys(deps),source+'\nreturn {rows,busyId,load,acknowledge,stateText}')(...Object.values(deps)))
 t.after(()=>scope.stop());return{...v,props,calls,confirmations}
}
const unknown=(state='NOTIFY_UNKNOWN')=>({id:2,patientId:10,lastRunStatus:state,uncertainRunId:44,uncertainRunState:state})
test('interrupted run acknowledgement carries exact observed run and state and is single flight',async t=>{
 const pending=deferred();let count=0,captured=[]
 const view=panel(t,{runHealthAutomation:async(...args)=>{count++;captured=args;return pending.promise}})
 const one=view.acknowledge(unknown());await nextTick();await view.acknowledge(unknown());assert.equal(count,1);assert.deepEqual(captured,[2,true,44,'NOTIFY_UNKNOWN']);assert.equal(view.confirmations.length,1);pending.resolve({});await one;assert.equal(view.busyId.value,null)
})
test('patient switch during confirmation prevents acknowledgement and clears private rows',async t=>{
 const pending=deferred();let count=0
 const view=panel(t,{ElMessageBox:{confirm:()=>pending.promise},runHealthAutomation:async()=>{count++}})
 view.rows.value=[unknown()];const one=view.acknowledge(unknown());view.props.patientId=20;await nextTick();assert.equal(view.rows.value.length,0);pending.resolve();await one;assert.equal(count,0)
})
test('missing or mismatched observation refreshes instead of claiming',async t=>{
 const view=panel(t);await view.acknowledge({...unknown(),uncertainRunId:null});await view.acknowledge({...unknown(),uncertainRunState:'UNKNOWN'});assert.equal(view.calls.length,0);assert.equal(view.confirmations.length,0)
})
test('cancelled confirmation never acknowledges or generates',async t=>{
 const view=panel(t,{ElMessageBox:{confirm:async()=>{throw new Error('cancel')}}});await view.acknowledge(unknown('UNKNOWN'));assert.equal(view.calls.length,0);assert.equal(view.busyId.value,null)
})
test('older list response cannot expose a previous patient run after switching',async t=>{
 const pending=deferred();const view=panel(t,{listHealthAutomations:()=>pending.promise});view.props.patientId=20;await nextTick();pending.resolve({data:[unknown(),{...unknown(),id:3,patientId:20}]});await nextTick();await nextTick();assert.deepEqual(view.rows.value.map(x=>x.id),[3])
})
test('run API includes exact acknowledgement observation',()=>{
 const source=fs.readFileSync(new URL('../src/api/healthAutomation.js',import.meta.url),'utf8');assert.match(source,/params:\{acknowledgeUncertain,uncertainRunId,uncertainRunState\}/)
})

test('workbench refresh reloads automation status with the latest response',async t=>{
 let calls=0;const view=panel(t,{listHealthAutomations:async()=>{calls++;return{data:[unknown(calls===1?'UNKNOWN':'NOTIFY_UNKNOWN')]}}});await nextTick();await nextTick();assert.equal(view.rows.value[0].lastRunStatus,'UNKNOWN');view.props.refreshKey={};await nextTick();await nextTick();assert.equal(calls,2);assert.equal(view.rows.value[0].lastRunStatus,'NOTIFY_UNKNOWN')
})
test('same-patient refresh discards the previous late journal response',async t=>{
 const pending=deferred();let calls=0
 const view=panel(t,{listHealthAutomations:async()=>{calls++;return calls===1?pending.promise:{data:[unknown('NOTIFY_UNKNOWN')]}}})
 view.props.refreshKey={};await nextTick();await nextTick();assert.equal(view.rows.value[0].lastRunStatus,'NOTIFY_UNKNOWN')
 pending.resolve({data:[unknown('UNKNOWN')]});await nextTick();await nextTick();assert.equal(view.rows.value[0].lastRunStatus,'NOTIFY_UNKNOWN');assert.equal(calls,2)
})
