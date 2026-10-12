import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import { ref, reactive, computed, watch, effectScope } from 'vue'
import { fixture, findNode, textOf, flush, deferred } from './helpers/carePlanDoctorHarness.mjs'
import { captureAuthSession, isAuthSessionCurrent } from '../src/utils/authSession.js'
import { useFocusedCareSource } from '../src/composables/useFocusedCareSource.js'

globalThis.document = { activeElement: null }
const action = { id:31,planId:17,patientId:1,patientName:'Fictional patient',instruction:'Fictional action',allowedActions:['SUBMIT_RECEIPT'],allowedEntryModes:['ASSISTED'] }
const record = { id:8,patientId:1,analysisContent:'Full original fictional analysis <script>never execute</script>',periodLabel:'Fictional period',timeType:'month',timeValue:'2026-09',userId:51,reviewStatus:'REVIEW_REQUIRED',createdAt:'2026-10-01 10:00:00',totalCount:4,avgOnWeight:72,mainRisk:'Fictional risk',remark:'Original provenance note' }
const writes = v => v.calls.filter(c => c.method === 'post')
const server = async c => c.url === '/patient/names' ? {code:200,data:[{id:1,patientName:'Fictional patient'}]} : c.url === '/ai/history' ? {code:200,data:[record]} : {code:200,data:{id:17,version:4,actions:[action]}}

function trendView(t, api, edition='en') {
 const path=edition==='cn'?'../src/views/MedicalRecordManager.vue':'../../../frontend/src/views/MedicalRecordManager.vue'
 const source=fs.readFileSync(new URL(path,import.meta.url),'utf8'),script=source.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import.*$/gm,'')
 const scope=effectScope(),unmounts=[],errors=[],infos=[],mounts=[],eventWindow=new EventTarget(),storage=new Map([['token','synthetic-trend-token'],['userId','51']])
 globalThis.localStorage={getItem:key=>storage.get(key)??null,setItem:(key,value)=>storage.set(key,String(value)),removeItem:key=>storage.delete(key),key:index=>[...storage.keys()][index],get length(){return storage.size}};globalThis.window=eventWindow
 const deps={captureAuthSession,isAuthSessionCurrent,useFocusedCareSource,ref,reactive,computed,watch,inject:(_k,d)=>d,onMounted:f=>mounts.push(f),onUnmounted:f=>unmounts.push(f),use(){},CanvasRenderer:{},EchartsLineChart:{},GridComponent:{},TooltipComponent:{},LegendComponent:{},TitleComponent:{},useCurrentPatient:()=>({currentPatientId:ref(1)}),useTableColumns:()=>({}),useMobile:()=>({isMobile:ref(false)}),useRoute:()=>({query:{},path:'/medical-record'}),useRouter:()=>({push(){}}),localDateKey:()=> '2026-10-01',readPermissionCache:()=>({}),canAccessWorkspace:()=>true,dedupeRecognizedItems:v=>v,isImageFile:()=>false,compressImageFile:async v=>v,formatFileSize:String,ElMessage:{error:v=>errors.push(v),info:v=>infos.push(v),warning(){}},window:eventWindow,URL:{revokeObjectURL(){}},getPatientNames:async()=>({code:200,data:[]}),api:{listRecords:async()=>({code:200,data:[]}),getAllItemNames:async()=>({code:200,data:[]}),...api}}
 const vm=scope.run(()=>new Function(...Object.keys(deps),script+'\nreturn {trendForm,trendData,trendLoading,trendStatus,trendError,trendQuery,trendResultQuery,loadTrend,patientList,patientLabel}')(...Object.values(deps)))
 t.after(()=>{unmounts.forEach(f=>f());scope.stop()});return {vm,source,errors,infos,storage,eventWindow,start(){mounts.forEach(fn=>fn())}}
}
for(const edition of ['en','cn']) {
 test(`${edition}: trend distinguishes idle, empty, loaded, retryable error and exact query context`,async t=>{
  let result=[];const {vm,source}=trendView(t,{getItemTrend:async()=>{if(result instanceof Error)throw result;return {code:200,data:result}}},edition)
  assert.equal(vm.trendStatus.value,'idle');vm.patientList.value=[{id:1,patientName:'Same name'},{id:2,patientName:'Same name'}]
  assert.notEqual(vm.patientLabel(vm.patientList.value[0]),vm.patientLabel(vm.patientList.value[1]))
  vm.trendForm.itemName='Original test';await vm.loadTrend();assert.equal(vm.trendStatus.value,'empty');assert.equal(vm.trendQuery.value.patientId,1);assert.equal(vm.trendQuery.value.itemName,'Original test')
  result=[{recordDate:'2026-10-01',resultValue:'7',itemName:'Original test'}];await vm.loadTrend();assert.equal(vm.trendStatus.value,'loaded')
  result=new Error('offline');await vm.loadTrend();assert.equal(vm.trendStatus.value,'error');assert.ok(vm.trendError.value);assert.equal(vm.trendResultQuery.value.itemName,'Original test');assert.equal(vm.trendData.value.length,1)
  assert.match(source,/data-testid="retry-trend"/);assert.doesNotMatch(source,/querytrend/)
  result=[];await vm.loadTrend();assert.equal(vm.trendStatus.value,'empty');assert.equal(vm.trendError.value,'');vm.trendForm.patientId=2;assert.equal(vm.trendStatus.value,'idle');assert.deepEqual(vm.trendData.value,[])
 })
}
test('AI decisions require original authorized source, full evidence, identity, provenance and acknowledgement',async t=>{
 const v=await fixture(t,'AiAnalysisReviewDialog',{item:{sourceType:'AI_ANALYSIS',sourceId:8,patientId:1,patientName:'Fictional patient'}},server)
 assert.match(textOf(v.root),/Full original fictional analysis/);assert.match(textOf(v.root),/Original provenance note/);assert.match(textOf(v.root),/72/);assert.match(textOf(v.root),/Fictional patient.*1/);assert.equal(writes(v).length,0)
 assert.equal(findNode(v.root,'approve-ai-review').props.disabled,true);await v.vm.submit('APPROVED');assert.equal(writes(v).length,0)
 v.vm.acknowledged=true;await v.click('approve-ai-review');assert.equal(writes(v).length,1);assert.equal(writes(v)[0].data.sourceId,8);assert.equal(writes(v)[0].data.decision,'APPROVED')
})
test('AI source missing, wrong patient, revoked or late after close never enables decisions or writes',async t=>{
 for(const data of [[],[{...record,patientId:2}]]){const v=await fixture(t,'AiAnalysisReviewDialog',{item:{sourceType:'AI_ANALYSIS',sourceId:8,patientId:1}},async()=>({code:200,data}));assert.equal(v.vm.preview,null);v.vm.acknowledged=true;await v.vm.submit('APPROVED');assert.equal(writes(v).length,0)}
 const pending=deferred(),v=await fixture(t,'AiAnalysisReviewDialog',{item:{sourceType:'AI_ANALYSIS',sourceId:8,patientId:1}},()=>pending.promise);await v.unmount();pending.resolve({code:200,data:[record]});await flush();assert.equal(writes(v).length,0)
 const r=await fixture(t,'AiAnalysisReviewDialog',{item:{sourceType:'AI_ANALYSIS',sourceId:8,patientId:1}},server);r.auth.clearAuthSession();await flush();assert.equal(r.vm.preview,null);assert.doesNotMatch(textOf(r.root),/Full original fictional analysis/)
})
test('doctor queue opens read-only review once, including imported source',async t=>{
 const v=await fixture(t,'DoctorWorkspace',{},async c=>c.url==='/doctor-workspace/summary'?{data:{patients:[],reviewQueue:[{sourceType:'AI_ANALYSIS',sourceId:8,patientId:1,patientName:'Fictional patient'}]}}:server(c))
 await v.vm.openReview({sourceType:'AI_ANALYSIS',sourceId:8,patientId:1,patientName:'Fictional patient'});await flush();assert.equal(writes(v).length,0);assert.match(textOf(v.root),/查看并复核/)
 const source=fs.readFileSync(new URL('../src/views/DoctorWorkspace.vue',import.meta.url),'utf8');assert.doesNotMatch(source,/@click="review\(item,'(?:APPROVED|REJECTED)'\)"/)
})
test('receipt time picker preserves exact instant and explicit offset without manually entering ISO',async t=>{
 const v=await fixture(t,'ReceiptDialog',{action,planVersion:4},server),input=findNode(v.root,'receipt-execution-time')
 assert.equal(input.props.type,'datetime-local');assert.match(v.vm.form.occurredAt,/[+-]\d{2}:\d{2}$/)
 v.vm.executionClock='2026-10-01T10:30:15.123';await flush();assert.equal(Date.parse(v.vm.form.occurredAt),new Date(2026,9,1,10,30,15,123).getTime())
 v.vm.form.note='Fictional execution';await v.click('submit-receipt');assert.equal(writes(v)[0].data.occurredAt,v.vm.form.occurredAt)
})
test('receipt picker shows recognizable authorized patient records, deduplicates and keeps cap',async t=>{
 const v=await fixture(t,'ReceiptDialog',{action,planVersion:4},async c=>c.url==='/care-journey/measurements'?{code:200,data:[{id:9,patient_id:1,metric_type:'BP',measured_at:'2026-10-01 09:00:00',value_primary:120,value_secondary:80,unit:'mmHg'},{id:10,patient_id:2,metric_type:'BP',measured_at:'Private date'}]}:server(c))
 await v.vm.loadReferences();await flush();assert.match(textOf(v.root),/2026-10-01.*血压.*120/);assert.doesNotMatch(textOf(v.root),/Private date/)
 v.vm.selectedReferenceId=9;v.vm.addReference();v.vm.addReference();assert.deepEqual(v.vm.form.evidence,[{sourceType:'MEASUREMENT',sourceId:9}])
 assert.ok(v.calls.some(c=>c.url==='/care-journey/measurements'&&c.params.patientId===1));assert.equal(writes(v).length,0)
 v.auth.clearAuthSession();await flush();assert.equal(v.vm.referenceOptions.length,0);assert.deepEqual(v.vm.form.evidence,[])
})
test('AI review ignores old source after patient replacement and never accepts application-level rejection',async t=>{
 const old=deferred(),newItem={sourceType:'AI_ANALYSIS',sourceId:10,patientId:2,patientName:'Current fictional patient'}
 const v=await fixture(t,'AiAnalysisReviewDialog',{item:{sourceType:'AI_ANALYSIS',sourceId:8,patientId:1}},c=>c.params?.patientId===1?old.promise:{code:200,data:[{...record,id:10,patientId:2,analysisContent:'Current patient original'}]})
 await v.update({item:newItem});old.resolve({code:200,data:[record]});await flush();assert.equal(v.vm.preview.id,10);assert.doesNotMatch(textOf(v.root),/Full original fictional analysis/)
 const r=await fixture(t,'AiAnalysisReviewDialog',{item:{sourceType:'AI_ANALYSIS',sourceId:8,patientId:1}},c=>c.method==='post'?{code:403,message:'Denied'}:server(c));r.vm.acknowledged=true;await r.vm.submit('APPROVED');assert.equal(r.events.some(e=>e[0]==='reviewed'),false);assert.equal(r.vm.preview,null)
})
test('receipt references ignore old patient reads and remove revoked evidence while keeping recoverable explanation',async t=>{
 const old=deferred();let denied=false
 const v=await fixture(t,'ReceiptDialog',{action,planVersion:4},c=>c.url==='/care-journey/measurements'?denied?Promise.reject(Object.assign(new Error('denied'),{code:403})):old.promise:server(c))
 const pending=v.vm.loadReferences();await v.update({action:{...action,id:32,patientId:2,patientName:'Current patient'}});old.resolve({code:200,data:[{id:9,patient_id:1,metric_type:'BP',measured_at:'Old private date'}]});await pending;assert.equal(v.vm.referenceOptions.length,0);assert.doesNotMatch(textOf(v.root),/Old private date/)
 v.vm.form.note='Recoverable explanation';v.vm.form.evidence=[{sourceType:'MEASUREMENT',sourceId:9}];denied=true;await v.vm.loadReferences();assert.deepEqual(v.vm.form.evidence,[]);assert.equal(v.vm.form.note,'Recoverable explanation');assert.ok(v.vm.referencesError)
})
test('receipt timezone picker exposes DST repeats and rejects skipped clocks',async t=>{
 const previous=process.env.TZ;process.env.TZ='America/New_York';t.after(()=>{if(previous==null)delete process.env.TZ;else process.env.TZ=previous})
 const v=await fixture(t,'ReceiptDialog',{action,planVersion:4},server);v.vm.executionClock='2026-11-01T01:30:00.123';await flush();assert.deepEqual(v.vm.executionOffsets,[-240,-300]);assert.equal(v.vm.form.occurredAt,'')
 v.vm.executionOffset=-240;await flush();assert.equal(v.vm.form.occurredAt,'2026-11-01T01:30:00.123-04:00');v.vm.executionOffset=-300;await flush();assert.equal(v.vm.form.occurredAt,'2026-11-01T01:30:00.123-05:00')
 v.vm.executionClock='2026-03-08T02:30:00';await flush();assert.deepEqual(v.vm.executionOffsets,[]);assert.equal(v.vm.form.occurredAt,'');assert.ok(v.vm.executionTimeError)
})

test('imported medical source review identifies the authorized patient record', async t=>{
 const v=await fixture(t,'components/MedicalRecordReviewDialog',{recordId:8,patientId:1,workspace:true},async()=>({code:200,data:{id:8,patientId:1,patientName:'Fictional patient',contentVersion:1,recordDate:'2026-10-01',items:[],attachments:[]}}))
 assert.match(textOf(v.root),/Fictional patient.*档案 #1/);assert.equal(writes(v).length,0)
})
for(const edition of ['en','cn']) for(const status of [401,403]) for(const transport of ['http','application']) {
 test(`${edition}: trend ${transport} ${status} purges protected previous values while retaining query input`,async t=>{
  let failure=false;const {vm}=trendView(t,{getItemTrend:async()=>{if(!failure)return {code:200,data:[{recordDate:'2026-10-01',resultValue:'Protected original result',itemName:'Fictional test'}]};if(transport==='application')return {code:status,message:'Access denied',data:[{resultValue:'Denied stale payload'}]};throw Object.assign(new Error('Access denied'),{response:{status}})}},edition)
  vm.trendForm.itemName='Fictional test';await vm.loadTrend();assert.equal(vm.trendData.value.length,1);failure=true;await vm.loadTrend()
  assert.deepEqual(vm.trendData.value,[]);assert.equal(vm.trendResultQuery.value,null);assert.equal(vm.trendStatus.value,'error');assert.ok(vm.trendError.value);assert.equal(vm.trendForm.patientId,1);assert.equal(vm.trendForm.itemName,'Fictional test')
  failure=false;await vm.loadTrend();assert.equal(vm.trendStatus.value,'loaded');assert.equal(vm.trendError.value,'')
 })
}
for(const key of ['token','userId',null]) test(`AI review clears protected source promptly on actual cross-tab ${key ?? 'clear'} identity change`,async t=>{
 const v=await fixture(t,'AiAnalysisReviewDialog',{item:{sourceType:'AI_ANALYSIS',sourceId:8,patientId:1}},server)
 v.vm.acknowledged=true;v.vm.note='Private review note';if(key==='userId')v.storage.set('userId','99');else v.storage.set('token','replacement-token')
 const event=new Event('storage');Object.defineProperty(event,'key',{value:key});window.dispatchEvent(event);await flush()
 assert.equal(v.vm.preview,null);assert.equal(v.vm.acknowledged,false);assert.equal(v.vm.note,'');assert.doesNotMatch(textOf(v.root),/Full original fictional analysis|Original provenance note/);assert.equal(writes(v).length,0)
})
test('AI unrelated or unchanged auth storage event keeps the current authorized source and local note',async t=>{
 const v=await fixture(t,'AiAnalysisReviewDialog',{item:{sourceType:'AI_ANALYSIS',sourceId:8,patientId:1}},server);v.vm.note='Current note';v.vm.acknowledged=true
 for(const key of ['theme','username','token','userId',null]){const event=new Event('storage');Object.defineProperty(event,'key',{value:key});window.dispatchEvent(event);await flush();assert.equal(v.vm.preview.id,8);assert.equal(v.vm.note,'Current note');assert.equal(v.vm.acknowledged,true)}
 assert.equal(writes(v).length,0)
})
test('AI cross-tab identity replacement invalidates an in-flight original source read',async t=>{
 const pending=deferred(),v=await fixture(t,'AiAnalysisReviewDialog',{item:{sourceType:'AI_ANALYSIS',sourceId:8,patientId:1}},()=>pending.promise)
 v.storage.set('token','replacement-token');const event=new Event('storage');Object.defineProperty(event,'key',{value:'token'});window.dispatchEvent(event);await flush();assert.equal(v.vm.loading,false)
 pending.resolve({code:200,data:[record]});await flush();assert.equal(v.vm.preview,null);assert.doesNotMatch(textOf(v.root),/Full original fictional analysis/)
})

for(const edition of ['en','cn']) for(const key of ['token','userId',null,'auth-session-cleared']) test(`${edition}: lab trend clears source/query/error at the ${key ?? 'storage clear'} account boundary`,async t=>{
 const {vm,storage,eventWindow,start}=trendView(t,{getItemTrend:async()=>({code:200,data:[{resultValue:'Private original trend'}]})},edition);start();await flush();vm.trendForm.itemName='Fictional item';await vm.loadTrend();assert.equal(vm.trendData.value.length,1)
 if(key==='userId')storage.set('userId','99');else storage.set('token','replacement-token')
 const event=new Event(key==='auth-session-cleared'?key:'storage');Object.defineProperty(event,'key',{value:key==='auth-session-cleared'?null:key});eventWindow.dispatchEvent(event);await flush()
 assert.deepEqual(vm.trendData.value,[]);assert.equal(vm.trendResultQuery.value,null);assert.equal(vm.trendQuery.value,null);assert.equal(vm.trendError.value,'');assert.equal(vm.trendLoading.value,false);assert.equal(vm.trendForm.patientId,1);assert.equal(vm.trendForm.itemName,'Fictional item')
})
for(const edition of ['en','cn']) test(`${edition}: lab trend ignores old account response and irrelevant storage events preserve valid results`,async t=>{
 const pending=deferred();let delayed=false;const {vm,storage,eventWindow,start}=trendView(t,{getItemTrend:()=>delayed?pending.promise:Promise.resolve({code:200,data:[{resultValue:'Current result'}]})},edition);start();await flush();vm.trendForm.itemName='Fictional item';await vm.loadTrend()
 for(const key of ['theme','token','userId',null]){const event=new Event('storage');Object.defineProperty(event,'key',{value:key});eventWindow.dispatchEvent(event);await flush();assert.equal(vm.trendData.value.length,1);assert.equal(vm.trendStatus.value,'loaded')}
 delayed=true;const loading=vm.loadTrend();storage.set('token','replacement-token');const event=new Event('storage');Object.defineProperty(event,'key',{value:'token'});eventWindow.dispatchEvent(event);await flush();assert.deepEqual(vm.trendData.value,[]);assert.equal(vm.trendLoading.value,false);pending.resolve({code:200,data:[{resultValue:'Obsolete account result'}]});await loading;assert.deepEqual(vm.trendData.value,[]);assert.equal(vm.trendQuery.value,null)
})

for(const edition of ['en','cn']) test(`${edition}: a new trend read cannot relabel cached results to a replaced account before response`,async t=>{
 const pending=deferred();let delayed=false;const {vm,storage}=trendView(t,{getItemTrend:()=>delayed?pending.promise:Promise.resolve({code:200,data:[{resultValue:'Prior account cached result'}]})},edition);vm.trendForm.itemName='Fictional item';await vm.loadTrend();storage.set('token','replacement-token');delayed=true;const loading=vm.loadTrend();assert.deepEqual(vm.trendData.value,[]);assert.equal(vm.trendResultQuery.value,null);pending.resolve({code:200,data:[{resultValue:'Replacement account result'}]});await loading;assert.equal(vm.trendData.value[0].resultValue,'Replacement account result')
})

for(const outcome of ['success','failure']) test(`AI invalidation releases obsolete decision busy UI and ignores late ${outcome}`,async t=>{
 const pending=deferred(),v=await fixture(t,'AiAnalysisReviewDialog',{item:{sourceType:'AI_ANALYSIS',sourceId:8,patientId:1}},c=>c.method==='post'?pending.promise:server(c));v.vm.acknowledged=true;v.vm.note='Original private note';const decision=v.vm.submit('APPROVED');await flush();assert.equal(v.vm.saving,true)
 v.storage.set('token','replacement-token');const event=new Event('storage');Object.defineProperty(event,'key',{value:'token'});window.dispatchEvent(event);await flush();assert.equal(v.vm.preview,null);assert.equal(v.vm.saving,false);assert.equal(v.vm.note,'');v.vm.close();assert.ok(v.events.some(e=>e[0]==='closed'))
 if(outcome==='success')pending.resolve({code:200,data:true});else pending.reject(new Error('Obsolete decision failure'));await decision;await flush();assert.equal(v.vm.saving,false);assert.equal(v.vm.preview,null);assert.equal(v.vm.error,'');assert.equal(v.events.some(e=>e[0]==='reviewed'),false);assert.equal(writes(v).length,1)
})
