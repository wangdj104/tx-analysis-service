import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import { computed, reactive, ref, watch, nextTick, effectScope } from 'vue'
function deferred(){let resolve;const promise=new Promise(done=>{resolve=done});return{promise,resolve}}
function setup(t,file,bindings,exposed){
  const source=fs.readFileSync(new URL('../src/views/'+file,import.meta.url),'utf8').match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import.*$/gm,'')
  const deps={computed,reactive,ref,watch,onMounted(){},ElMessage:{success(){},warning(){}},...bindings}
  const scope=effectScope(),view=scope.run(()=>new Function(...Object.keys(deps),source+'\nreturn {'+exposed.join(',')+'}')(...Object.values(deps)))
  t.after(()=>scope.stop());return view
}
function notifications(t,overrides={}){
  return setup(t,'NotificationSettings.vue',{window:{Notification:{}},Notification:{permission:'default',requestPermission:async()=>'granted'},listNotificationChannels:async()=>({data:[]}),saveNotificationChannel:async()=>({}),testNotificationChannel:async()=>({}),deleteNotificationChannel:async()=>({}),...overrides},['submit','form','test','toggle','remove','saving','browserStatus','enableBrowser'])
}
function automations(t,overrides={}){
  const patientId=ref(10)
  return {...setup(t,'HealthAutomationManager.vue',{useCurrentPatient:()=>({currentPatientId:patientId}),listHealthAutomations:async()=>({data:[]}),listNotificationChannels:async()=>({data:[]}),saveHealthAutomation:async()=>({}),runHealthAutomation:async()=>({}),deleteHealthAutomation:async()=>({}),...overrides},['load','rows','form','submit','runNow','runningId','resultContent','resultVisible','visible']),patientId}
}
test('notification save validates missing webhook and blocks duplicate submissions',async t=>{
  const pending=deferred();let calls=0
  const view=notifications(t,{saveNotificationChannel:async()=>{calls++;return pending.promise}})
  await view.submit();assert.equal(calls,0)
  view.form.webhookUrl='https://example.test/hook'
  const first=view.submit();await view.submit();assert.equal(calls,1)
  pending.resolve({});await first;assert.equal(view.saving.value,false)
})
test('notification test is single-flight per channel and cannot race a toggle',async t=>{
  const pending=deferred();let tests=0,saves=0
  const view=notifications(t,{testNotificationChannel:async()=>{tests++;return pending.promise},saveNotificationChannel:async()=>{saves++;return {}}})
  const row={id:3,enabled:1},first=view.test(row)
  await view.test(row);await view.toggle(row)
  assert.equal(tests,1);assert.equal(saves,0)
  pending.resolve({});await first
})
test('browser permission button updates after permission is granted',async t=>{
  const view=notifications(t),previous=view.browserStatus.value
  await view.enableBrowser()
  assert.notEqual(view.browserStatus.value,previous)
})
test('automation drafts and lists are cleared when the patient changes',async t=>{
  const pending=deferred()
  const view=automations(t,{listHealthAutomations:async()=>pending.promise})
  const first=view.load()
  view.resultContent.value='patient A private result';view.resultVisible.value=true;view.visible.value=true
  view.patientId.value=20;await nextTick()
  assert.equal(view.resultContent.value,'');assert.equal(view.resultVisible.value,false);assert.equal(view.visible.value,false)
  pending.resolve({data:[{id:1,patientId:10},{id:2,patientId:20}]})
  await first;await nextTick();await nextTick()
  assert.ok(view.rows.value.every(row=>row.patientId===20))
})
test('automation saves need a patient and duplicate runs are blocked',async t=>{
  const pending=deferred();let saved=0,runs=0
  const view=automations(t,{saveHealthAutomation:async()=>{saved++;return{}},runHealthAutomation:async()=>{runs++;return pending.promise}})
  view.patientId.value=null;await nextTick();await view.submit();assert.equal(saved,0)
  view.patientId.value=10;await nextTick()
  const first=view.runNow({id:2});await view.runNow({id:2});assert.equal(runs,1)
  pending.resolve({});await first
})
