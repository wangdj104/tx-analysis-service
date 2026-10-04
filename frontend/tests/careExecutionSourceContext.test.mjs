import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import {reactive,ref,effectScope,nextTick} from 'vue'
const flush=async()=>{await nextTick();await Promise.resolve();await nextTick()}
function deferred(){let resolve;const promise=new Promise(r=>resolve=r);return{promise,resolve}}
async function fixture(t,fetcher=async source=>({data:{id:source.sourceId,patientId:source.patientId}})){
 const path=new URL('../src/composables/useFocusedCareSource.js',import.meta.url);assert.ok(fs.existsSync(path),'Focused source lifecycle exists')
 const storage=new Map([['token','A'],['userId','7']]);globalThis.localStorage={getItem:k=>storage.get(k),removeItem:k=>storage.delete(k),setItem:(k,v)=>storage.set(k,v),get length(){return storage.size},key:i=>[...storage.keys()][i]};globalThis.window=new EventTarget()
 const {useFocusedCareSource}=await import(path),scope=effectScope(),route=reactive({fullPath:'/medical-record?tab=list&patientId=1&recordId=17'}),globalPatient=ref(99),calls=[]
 const focus=scope.run(()=>useFocusedCareSource(route,globalPatient,async(...args)=>{calls.push(args);return fetcher(...args)},'MEDICAL_RECORD'));await flush();t.after(()=>scope.stop());return{...focus,route,globalPatient,calls,storage}
}
test('cold source entry uses exact locator patient instead of stale shared selection',async t=>{const v=await fixture(t);assert.equal(v.state.record.patientId,1);assert.deepEqual(v.calls[0][0],{patientId:1,sourceId:17,sourceType:'MEDICAL_RECORD'});assert.equal(v.calls[0][1].expectedAuth.actorId,'7');assert.equal(v.calls.length,1)})
test('warm/repeated IDs and Back/Forward replace obsolete source with the exact new one',async t=>{const old=deferred();const v=await fixture(t,source=>source.sourceId===17?old.promise:Promise.resolve({data:{id:source.sourceId,patientId:source.patientId}}));v.route.fullPath='/medical-record?tab=list&patientId=2&recordId=18';await flush();assert.equal(v.state.record.id,18);old.resolve({data:{id:17,patientId:1}});await flush();assert.equal(v.state.record.id,18);v.route.fullPath='/medical-record?tab=list&patientId=1&recordId=17';await flush();assert.equal(v.state.record.id,17);v.route.fullPath='/medical-record?tab=list&patientId=2&recordId=18';await flush();assert.equal(v.state.record.id,18)})
test('context patient A-B-A immediately clears a focused read without broad fallback or stale acceptance',async t=>{const old=deferred(),v=await fixture(t,()=>old.promise);v.globalPatient.value=2;v.globalPatient.value=99;old.resolve({data:{id:17,patientId:1}});await flush();assert.equal(v.state.record,null);assert.equal(v.calls.length,1)})
test('account A-B-A clears and rejects delivered old content',async t=>{const old=deferred(),v=await fixture(t,()=>old.promise);for(const key of ['B','A']){v.storage.set('token',key);window.dispatchEvent(new Event('auth-session-cleared'))}old.resolve({data:{id:17,patientId:1}});await flush();assert.equal(v.state.record,null)})
test('wrong-patient source produces only generic unavailable state',async t=>{const v=await fixture(t,async()=>({data:{id:17,patientId:2}}));assert.equal(v.state.record,null);assert.equal(v.state.error,true);v.route.fullPath='/medical-record?tab=list&patientId=1&recordId=18';await flush();assert.equal(v.state.record,null)})
test('duplicate or extra keys never dispatch a focused or broad source read',async t=>{const v=await fixture(t);for(const suffix of ['&recordId=17','&patientId=1','&extra=1']){v.route.fullPath='/medical-record?tab=list&patientId=1&recordId=17'+suffix;await flush();assert.equal(v.state.record,null);assert.equal(v.attempted.value,true)}assert.equal(v.calls.length,1)})
test('focused page wiring suppresses broad module bootstraps and binds original detail rendering',()=>{
 for(const name of ['CareJourneyManager','MedicalRecordManager']){const source=fs.readFileSync(new URL(`../src/views/${name}.vue`,import.meta.url),'utf8');assert.match(source,/useFocusedCareSource/);assert.match(source,/focusedMode/)}
})

import {createRouter,createMemoryHistory} from 'vue-router'
import {canAccessWorkspace} from '../src/utils/workspaceAccess.js'
test('real router Back and Forward preserve exact source ownership and the CN base',async t=>{
 const v=await fixture(t);const base=import.meta.url.includes('/cn/frontend/')?'/cn/':'/'
 const router=createRouter({history:createMemoryHistory(base),routes:[{path:'/medical-record',component:{}},{path:'/denied',component:{}}]})
 router.beforeEach(to=>to.path==='/denied'||canAccessWorkspace(to.fullPath,[],['family'])?true:'/denied');router.afterEach(to=>{v.route.fullPath=to.fullPath})
 const a='/medical-record?tab=list&patientId=1&recordId=17',b='/medical-record?tab=list&patientId=2&recordId=18'
 await router.push(a);await router.isReady();await router.push(b);await flush();assert.equal(v.state.record.id,18);assert.equal(router.resolve(a).href,base+a.slice(1))
 async function pop(direction){let stop;const settled=new Promise(resolve=>{stop=router.afterEach(()=>resolve())});router[direction]();await settled;stop();await flush()}
 await pop('back');assert.equal(v.state.record.id,17);await pop('forward');assert.equal(v.state.record.id,18)
})

test('an outgoing source page never reads the other source kind during synchronous route replacement',async t=>{const v=await fixture(t);v.route.fullPath='/care-journey?tab=measurements&patientId=1&measurementId=99';await flush();assert.equal(v.calls.length,1);assert.equal(v.state.record,null)})

for(const outcome of ['missing','wrong ID'])test(`focused medical ${outcome} response is rejected independently`,async t=>{const v=await fixture(t,async()=>({data:outcome==='missing'?null:{id:18,patientId:1}}));assert.equal(v.state.record,null);assert.equal(v.state.error,true);assert.equal(v.calls.length,1)})
test('focused medical recheck handles a genuinely revoked source independently of identity mismatch',async t=>{let revoked=false;const v=await fixture(t,async source=>{if(revoked)throw{code:403};return{data:{id:source.sourceId,patientId:source.patientId}}});assert.equal(v.state.record.id,17);revoked=true;const refresh=v.reload();assert.equal(v.state.record,null);await refresh;assert.equal(v.state.error,true);assert.equal(v.calls.length,2)})
