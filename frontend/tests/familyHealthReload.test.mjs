import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import {ref,reactive,computed,watch,nextTick,effectScope} from 'vue'
import {localDateKey,replaceTarget} from '../src/utils/familyHealth.js'
const flush=async()=>{for(let n=0;n<12;n++)await nextTick()}
const deferred=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b});return {promise,resolve,reject}}
const enabled=id=>({data:{patientId:id,allowedPaths:['/family-health?tab=schedule'],restrictedPaths:['/family-health?tab=schedule']}})
function setup(t,{scope=async id=>enabled(id),schedules=async id=>({data:[{id,patientId:id}]}),intakes=async id=>({data:[{id}]})}={}){
 const patientId=ref(1),session=ref('A'),calls=[],hooks=[],listeners=new EventTarget(),effect=effectScope()
 const api={getIntakes:async id=>{calls.push(['intakes',id]);return intakes(id)},getTimeline:async()=>({data:[]}),getHealthTarget:async()=>({data:{id:1}}),getSpecialtyMenuScope:async id=>{calls.push(['scope',id]);return scope(id)},getDialysisSchedules:async id=>{calls.push(['schedules',id]);return schedules(id)}}
 const bindings={ref,reactive,computed,watch,nextTick,onMounted(){},onUnmounted:fn=>hooks.push(fn),inject:(_,fallback)=>fallback,useCurrentPatient:()=>({currentPatientId:patientId}),useRoute:()=>({path:'/family-health',query:{}}),useRouter:()=>({push(){}}),readPermissionCache:()=>({menuPaths:['/family-health'],roleCodes:[]}),canAccessWorkspace:()=>true,api,localDateKey,replaceTarget,ElMessage:{warning(){},success(){}},ElMessageBox:{},captureAuthSession:()=>session.value,isAuthSessionCurrent:token=>token===session.value,AUTH_STORAGE_KEYS:['token','userId'],localStorage:{getItem:()=>session.value},window:{addEventListener:(...args)=>listeners.addEventListener(...args),removeEventListener:(...args)=>listeners.removeEventListener(...args),clearInterval(){}}}
 const script=fs.readFileSync(new URL('../src/views/FamilyHealthManager.vue',import.meta.url),'utf8').match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import.*$/gm,'')
 const view=effect.run(()=>new Function(...Object.keys(bindings),script+'\nreturn {reload,intakes,schedules,events,target,loading,refreshTasks,reloadState:typeof reloadState!=="undefined"?reloadState:null,scheduleState:typeof scheduleState!=="undefined"?scheduleState:null}')( ...Object.values(bindings)))
 const unmount=()=>{hooks.splice(0).forEach(fn=>fn());effect.stop()};t.after(unmount)
 return {...view,patientId,session,calls,listeners,unmount}
}
test('disabled dialysis has an explicit disabled state, no clinical schedule read, and working general reads',async t=>{
 const v=setup(t,{scope:async id=>({data:{patientId:id,allowedPaths:[],restrictedPaths:['/family-health?tab=schedule']}})});await flush()
 assert.equal(v.calls.filter(([name])=>name==='schedules').length,0);assert.equal(v.scheduleState.value,'disabled');assert.equal(v.reloadState.value,'ready');assert.equal(v.intakes.value.length,1)
})
for(const boundary of ['scope','schedules'])test(`${boundary} denial is explicit and recoverable, not fake empty success or an unhandled rejection`,async t=>{
 let deny=true;const rejected=()=>Promise.reject({code:403}),v=setup(t,{scope:id=>boundary==='scope'&&deny?rejected():Promise.resolve(enabled(id)),schedules:id=>boundary==='schedules'&&deny?rejected():Promise.resolve({data:[{id}]})});await flush()
 assert.equal(v.scheduleState?.value,'denied');assert.equal(v.reloadState?.value,'ready');assert.deepEqual(v.schedules.value,[]);assert.equal(v.loading.value,false)
 deny=false;await v.reload();assert.equal(v.scheduleState.value,'ready');assert.equal(v.schedules.value.length,1)
})
test('ordinary full reload rejection clears stale records, settles and recovers',async t=>{
 let fail=false;const v=setup(t,{intakes:async id=>{if(fail)throw Error('Synthetic private error');return{data:[{id}]}}});await flush();assert.equal(v.intakes.value.length,1)
 fail=true;await assert.doesNotReject(v.reload());assert.equal(v.reloadState.value,'unavailable');assert.deepEqual(v.intakes.value,[]);assert.deepEqual(v.schedules.value,[]);assert.equal(v.target.id,undefined);assert.equal(v.loading.value,false)
 fail=false;await v.reload();assert.equal(v.reloadState.value,'ready');assert.equal(v.intakes.value.length,1)
})
for(const change of ['patient','A-B-A','silent account','unmount'])test(`late optional reads cannot restore ${change} context`,async t=>{
 const old=deferred();let first=true;const v=setup(t,{scope:async id=>{if(first){first=false;return old.promise}return enabled(id)}});await flush()
 if(change==='patient')v.patientId.value=2
 if(change==='A-B-A'){v.patientId.value=2;v.patientId.value=1}
 if(change==='silent account')v.session.value='B'
 if(change==='unmount')v.unmount()
 await flush();old.resolve(enabled(1));await flush()
 assert.equal(v.calls.filter(([name,id])=>name==='schedules'&&id===1).length,change==='A-B-A'?1:0)
 if(change==='patient')assert.equal(v.schedules.value[0].patientId,2)
 if(change==='silent account'||change==='unmount')assert.deepEqual(v.schedules.value,[])
})
test('account notification immediately clears records and invalidates pending reads',async t=>{
 const v=setup(t);await flush();assert.equal(v.intakes.value.length,1);v.session.value='B';v.listeners.dispatchEvent(new Event('auth-session-cleared'));await flush();assert.deepEqual(v.intakes.value,[]);assert.deepEqual(v.schedules.value,[]);assert.equal(v.target.id,undefined)
})

test('scope unknown or wrong-patient result is unavailable and cannot start a clinical read',async t=>{
 for(const data of [null,{patientId:2,allowedPaths:['/family-health?tab=schedule']}]){const v=setup(t,{scope:async()=>({data})});await flush();assert.equal(v.scheduleState.value,'unavailable');assert.equal(v.calls.some(([name])=>name==='schedules'),false)}
})
test('a superseded schedule response cannot clear or replace a recovered new patient',async t=>{
 const old=deferred();const v=setup(t,{schedules:id=>id===1?old.promise:Promise.resolve({data:[{id,patientId:id}]})});await flush();v.patientId.value=2;await flush();assert.equal(v.schedules.value[0].patientId,2);old.reject({code:403});await flush();assert.equal(v.scheduleState.value,'ready');assert.equal(v.schedules.value[0].patientId,2)
})
test('initial general permission denial settles with an explicit denied state',async t=>{
 const v=setup(t,{intakes:async()=>{throw{code:403}}});await flush();assert.equal(v.reloadState.value,'denied');assert.equal(v.loading.value,false);assert.deepEqual(v.intakes.value,[]);assert.deepEqual(v.schedules.value,[])
})

test('a failed general reload retires its deferred scope before any optional clinical read',async t=>{
 const pending=deferred(),v=setup(t,{scope:()=>pending.promise,intakes:async()=>{throw{code:403}}});await flush();assert.equal(v.reloadState.value,'denied');pending.resolve(enabled(1));await flush();assert.equal(v.calls.some(([name])=>name==='schedules'),false)
})
