import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import { reactive, ref, computed, watch, effectScope, nextTick, compile, createSSRApp } from 'vue'
import { renderToString } from 'vue/server-renderer'
import ElementPlus, { ID_INJECTION_KEY, ZINDEX_INJECTION_KEY } from 'element-plus'
import { Check, Refresh, EditPen, TrendCharts } from '@element-plus/icons-vue'

function deferred(){ let resolve, reject; const promise=new Promise((a,b)=>{resolve=a;reject=b});return {promise,resolve,reject} }
function setup(t,overrides={}) {
 const patientId=ref(1), messages=[],writes=[],unmountCallbacks=[],scope=effectScope()
 const bindings={ref,reactive,computed,watch,onMounted(){},onUnmounted:fn=>unmountCallbacks.push(fn),
  useCurrentPatient:()=>({currentPatientId:patientId}),
  ElMessage:Object.fromEntries(['warning','error','success'].map(kind=>[kind,text=>messages.push({kind,text})])),
  listDiaries:async id=>({code:200,data:[{id:id*10,patientId:id}]}),
  saveDiary:async data=>{writes.push(data);return {code:200}},updateDiary:async data=>{writes.push(data);return {code:200}},deleteDiary:async()=>({code:200}),...overrides}
 const source=fs.readFileSync(new URL('../src/views/NutritionDiaryManager.vue',import.meta.url),'utf8').match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import.*$/gm,'')
 const names='records,loading,saving,form,formRef,editingId,selectedSymptoms,loadRecords,handleSave,handleEdit,resetForm,handleDelete,latestWeightText,latestAppetiteText,formRules,SYMPTOM_OPTIONS,toggleSymptom,appetiteTagType,appetiteLabel,mealsText'
 const view=scope.run(()=>new Function(...Object.keys(bindings),source+'\nreturn {'+names+'}')(...Object.values(bindings)))
 view.formRef.value={validate:async()=>true,resetFields(){}}
 t.after(()=>{unmountCallbacks.forEach(fn=>fn());scope.stop()})
 return {...view,patientId,messages,writes,unmount(){unmountCallbacks.forEach(fn=>fn());scope.stop()}}
}

async function renderEntryControls(view) {
 const source=fs.readFileSync(new URL('../src/views/NutritionDiaryManager.vue',import.meta.url),'utf8')
 // Compile the production template so removing its saving bindings breaks this test.
 const template=source.match(/<template>([\s\S]*?)<\/template>\s*<script setup>/)[1]
 const app=createSSRApp({setup:()=>view,render:compile(template)})
 app.use(ElementPlus)
 for(const [name,component] of Object.entries({Check,Refresh,EditPen,TrendCharts}))app.component(name,component)
 app.provide(ID_INJECTION_KEY,{prefix:100,current:0})
 app.provide(ZINDEX_INJECTION_KEY,{current:0})
 const html=await renderToString(app)
 const entry=html.match(/<form\b[\s\S]*?<\/form>/)?.[0]
 assert.ok(entry,'the production entry form must render')
 return {
  inputs:[...entry.matchAll(/<(?:input|textarea|button)\b[^>]*>/g)].map(match=>match[0]),
  symptoms:[...entry.matchAll(/<span\b[^>]*class="[^"]*\bel-check-tag\b[^"]*"[^>]*>/g)].map(match=>match[0])
 }
}

test('rendered diary controls follow saving in the real Vue template',async t=>{
 const validation=deferred(),v=setup(t)
 v.formRef.value.validate=()=>validation.promise
 async function assertDisabled(disabled) {
  const {inputs,symptoms}=await renderEntryControls(v)
  assert.equal(inputs.length,13,'date, two numbers, three radios, four checkboxes, notes, Save and Reset')
  assert.equal(symptoms.length,v.SYMPTOM_OPTIONS.length)
  for(const input of inputs)assert.equal(/\sdisabled(?:\s|=|>)/.test(input),disabled,input)
  for(const symptom of symptoms)assert.equal(/\bis-disabled\b/.test(symptom),disabled,symptom)
 }
 await assertDisabled(false)
 const save=v.handleSave()
 await assertDisabled(true)
 validation.reject(new Error('synthetic validation failure'))
 await save
 await assertDisabled(false)
})

test('save captures record and form fields before pending validation resolves',async t=>{
 const validation=deferred(),updates=[],v=setup(t,{updateDiary:async data=>{updates.push(data);return {code:200}}})
 v.handleEdit({id:10,patientId:1,recordDate:'2026-10-02',remark:'original'})
 v.formRef.value.validate=()=>validation.promise
 const save=v.handleSave()
 // Model changes must not alter the already captured request, even before dispatch.
 v.form.recordDate='2026-10-03'
 v.form.remark='later model change'
 assert.equal(updates.length,0)
 validation.resolve(true)
 await save
 assert.equal(updates.length,1)
 assert.equal(updates[0].id,10)
 assert.equal(updates[0].patientId,1)
 assert.equal(updates[0].recordDate,'2026-10-02')
 assert.equal(updates[0].remark,'original')
})

for(const outcome of ['success','failure','rejection']) {
 test(`an API-dispatched save is inert after unmount (${outcome})`,async t=>{
  const pending=deferred();let writes=0,reads=0
  const v=setup(t,{
   saveDiary:()=>{writes++;return pending.promise},
   listDiaries:async()=>{reads++;return {code:200,data:[]}}
  })
  v.form.remark='preserve this draft'
  const before={...v.form},save=v.handleSave()
  await nextTick()
  assert.equal(writes,1)
  v.unmount()
  if(outcome==='rejection')pending.reject(new Error('synthetic network failure'))
  else pending.resolve({code:outcome==='success'?200:500,msg:'synthetic result'})
  await save
  assert.deepEqual({...v.form},before)
  assert.deepEqual(v.messages,[])
  assert.equal(reads,0)
  assert.equal(v.saving.value,false)
 })
}
 test(`latest patient response wins`,async t=>{
  const pending=deferred();let calls=0
  const v=setup(t,{listDiaries:()=>++calls===1?pending.promise:Promise.resolve({code:200,data:[{id:20,patientId:2}]})})
  const first=v.loadRecords();v.patientId.value=2;await nextTick();await nextTick()
  pending.resolve({code:200,data:[{id:10,patientId:1}]});await first
  assert.deepEqual(v.records.value,[{id:20,patientId:2}])
 })
 test(`patient switch invalidates pending validation`,async t=>{
  const pending=deferred(),v=setup(t)
  v.form.remark='Patient A';v.formRef.value.validate=()=>pending.promise
  const save=v.handleSave();v.patientId.value=2;await nextTick();v.form.remark='Patient B draft'
  pending.resolve(true);await save
  assert.equal(v.writes.length,0)
 })
 test(`old save cannot clear replacement draft`,async t=>{
  const pending=deferred(),v=setup(t,{saveDiary:()=>pending.promise})
  v.form.remark='Patient A';const save=v.handleSave();await nextTick()
  v.patientId.value=2;await nextTick();v.form.remark='Patient B draft'
  pending.resolve({code:200});await save
  assert.equal(v.form.remark,'Patient B draft')
 })
 test(`duplicate saves are single flight`,async t=>{
  const pending=deferred(),v=setup(t)
  v.formRef.value.validate=()=>pending.promise
  const first=v.handleSave(),second=v.handleSave()
  pending.resolve(true);await Promise.all([first,second])
  assert.equal(v.writes.length,1)
 })
 test(`switching patients clears previous rows synchronously`,async t=>{
  const pending=deferred(),v=setup(t,{listDiaries:()=>pending.promise})
  v.records.value=[{id:10,patientId:1}];v.patientId.value=2
  assert.deepEqual(v.records.value,[])
  pending.resolve({code:200,data:[]})
 })


for (const jump of ['clear', 'A-B-A', 'unmount']) {
 test(`late list result is invalid after ${jump}`, async t=>{
  const pending=deferred();let calls=0
  const v=setup(t,{listDiaries:()=>++calls===1?pending.promise:Promise.resolve({code:200,data:[]})})
  const first=v.loadRecords()
  if(jump==='clear')v.patientId.value=null
  else if(jump==='A-B-A'){v.patientId.value=2;v.patientId.value=1}
  else v.unmount()
  pending.resolve({code:200,data:[{id:10,patientId:1}]});await first;await nextTick()
  assert.deepEqual(v.records.value,[])
 })
}
test('older refresh cannot clear the current request loading state',async t=>{
 const first=deferred(),second=deferred();let calls=0
 const v=setup(t,{listDiaries:()=>++calls===1?first.promise:second.promise})
 const a=v.loadRecords(),b=v.loadRecords()
 first.resolve({code:200,data:[{id:10,patientId:1}]});await a
 assert.equal(v.loading.value,true);assert.deepEqual(v.records.value,[])
 second.resolve({code:200,data:[{id:11,patientId:1}]});await b
 assert.deepEqual(v.records.value,[{id:11,patientId:1}]);assert.equal(v.loading.value,false)
})
test('resetting the form invalidates pending validation',async t=>{
 const pending=deferred(),v=setup(t)
 v.form.remark='old';v.formRef.value.validate=()=>pending.promise
 const save=v.handleSave();v.resetForm();v.form.remark='replacement'
 pending.resolve(true);await save
 assert.equal(v.writes.length,0);assert.equal(v.form.remark,'replacement')
})
test('failed saves preserve the draft and permit deliberate retry',async t=>{
 let calls=0
 const v=setup(t,{saveDiary:async()=>++calls===1?{code:500,msg:'synthetic failure'}:{code:200}})
 v.form.remark='keep me';await v.handleSave()
 assert.equal(v.form.remark,'keep me');assert.equal(v.saving.value,false)
 await v.handleSave();assert.equal(calls,2);assert.equal(v.form.remark,'')
})
test('an old save completion cannot unlock a newer patient save',async t=>{
 const first=deferred(),second=deferred();let calls=0
 const v=setup(t,{saveDiary:()=>++calls===1?first.promise:second.promise})
 const a=v.handleSave();await nextTick();v.patientId.value=2
 const b=v.handleSave();await nextTick()
 first.resolve({code:200});await a
 assert.equal(v.saving.value,true)
 second.resolve({code:500});await b;assert.equal(v.saving.value,false)
})
test('the saved request is a stable snapshot of the originating patient and record',async t=>{
 const pending=deferred(),writes=[]
 const v=setup(t,{updateDiary:data=>{writes.push(data);return pending.promise}})
 v.handleEdit({id:10,patientId:1,recordDate:'2026-10-02',remark:'original'})
 const save=v.handleSave();await nextTick();v.patientId.value=2;v.form.remark='replacement'
 assert.equal(writes.length,1);assert.equal(writes[0].patientId,1);assert.equal(writes[0].id,10);assert.equal(writes[0].remark,'original')
 pending.resolve({code:200});await save;assert.equal(v.form.remark,'replacement')
})
test('stale rows cannot open in another patient context',t=>{
 const v=setup(t);v.patientId.value=2;v.form.remark='new'
 v.handleEdit({id:10,patientId:1,remark:'old'})
 assert.equal(v.editingId.value,null);assert.equal(v.form.remark,'new')
})
test('validation failure releases the save lock without discarding the draft',async t=>{
 const v=setup(t);v.form.remark='invalid draft';v.formRef.value.validate=async()=>{throw new Error('synthetic validation')}
 await v.handleSave();assert.equal(v.saving.value,false);assert.equal(v.form.remark,'invalid draft');assert.equal(v.writes.length,0)
})
test('a late patient failure cannot show an obsolete error',async t=>{
 const pending=deferred();let calls=0
 const v=setup(t,{listDiaries:()=>++calls===1?pending.promise:Promise.resolve({code:200,data:[]})})
 const first=v.loadRecords();v.patientId.value=2;pending.reject(new Error('synthetic old read'));await first
 assert.equal(v.messages.filter(x=>x.kind==='error').length,0)
})
test('unmount invalidates pending validation without dispatch',async t=>{
 const pending=deferred(),v=setup(t);v.formRef.value.validate=()=>pending.promise
 const save=v.handleSave();v.unmount();pending.resolve(true);await save;assert.equal(v.writes.length,0)
})
