import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import { fixture, flush, deferred, findNode, textOf } from './helpers/carePlanDoctorHarness.mjs'
const snapshot={id:17,patientId:1,patientName:'Synthetic patient',contentVersion:4,recordDate:'2026-10-11',recordType:'BLOOD',sourceType:'OCR',hospitalName:'Synthetic hospital',doctorName:'Synthetic clinician',remark:'原文 <script>do not rewrite</script>',aiRawResult:'原始 OCR',verificationNote:'Prior review',items:[{id:2,itemName:'Synthetic analyte',resultValue:'7.1',unit:'mmol/L',referenceRange:'3.5–5.1',isAbnormal:1,aiConfidence:0.92}],attachments:[{id:9,fileName:'synthetic.pdf',fileType:'PDF',fileSize:900}]}
const copy=()=>JSON.parse(JSON.stringify(snapshot))
const posts=v=>v.calls.filter(c=>c.method==='post')
const api=(overrides={})=>async c=>overrides[c.url]?overrides[c.url](c):{code:200,data:c.method==='post'?null:copy()}
async function dialog(t,overrides={},props={}){return fixture(t,'components/MedicalRecordReviewDialog',{recordId:17,patientId:1,...props},api(overrides))}
test('review preview displays exact source and metadata before explicit confirmation',async t=>{
 const v=await dialog(t);assert.equal(v.calls[0].url,'/medical-record/17/review-preview');assert.equal(posts(v).length,0)
 for(const value of ['Synthetic patient','Synthetic hospital','Synthetic clinician',snapshot.remark,'原始 OCR','Synthetic analyte','7.1','mmol/L','3.5–5.1','synthetic.pdf','Prior review'])assert.ok(textOf(v.root).includes(value),value)
 assert.match(textOf(v.root),/High|偏高/);assert.ok(textOf(v.root).includes('0.92'));
 assert.equal(findNode(v.root,'confirm-medical-approve').props.disabled,true);v.vm.acknowledged=true;v.vm.note='My review';await v.click('confirm-medical-approve')
 assert.equal(posts(v).length,1);assert.deepEqual(posts(v)[0].data,{approved:true,note:'My review',expectedContentVersion:4});assert.equal(posts(v)[0].expectedAuth.actorId,'51');assert.equal(v.events[0][0],'reviewed')
 assert.ok(!v.calls.some(c=>c.url.includes('attachment')))
})
test('workspace sends exact preview version through workspace contract',async t=>{
 const v=await dialog(t,{}, {workspace:true});v.vm.acknowledged=true;v.vm.note='No';await v.click('confirm-medical-reject');assert.equal(posts(v)[0].url,'/doctor-workspace/reviews')
 assert.deepEqual(posts(v)[0].data,{sourceType:'MEDICAL_RECORD',sourceId:17,decision:'REJECTED',reviewNote:'No',expectedContentVersion:4})
})
test('stale version is not automatically retried and reload requires a new confirmation',async t=>{
 let version=4;const v=await dialog(t,{'/medical-record/17/review-preview':()=>({data:{...copy(),contentVersion:version}}),'/medical-record/17/review':()=>{throw Object.assign(new Error('stale'),{response:{status:409}})}})
 v.vm.acknowledged=true;await v.click('confirm-medical-approve');assert.equal(posts(v).length,1);assert.equal(v.calls.filter(c=>c.method==='get').length,1);assert.equal(findNode(v.root,'confirm-medical-approve').props.disabled,true)
 version=5;await v.click('reload-medical-preview');assert.equal(v.vm.acknowledged,false);assert.equal(findNode(v.root,'confirm-medical-approve').props.disabled,true);v.vm.acknowledged=true;await v.click('confirm-medical-reject');assert.equal(posts(v)[1].data.expectedContentVersion,5)
})
test('failed or mismatched previews never enable review',async t=>{
 for(const data of [{...copy(),patientId:2},{...copy(),id:18},{...copy(),contentVersion:null}]){const v=await dialog(t,{'/medical-record/17/review-preview':()=>({data})});assert.equal(v.vm.preview,null);v.vm.acknowledged=true;await v.vm.submit(true);assert.equal(posts(v).length,0);await v.unmount()}
})
test('non-positive or invalid snapshot versions never enable or submit a review',async t=>{
 for(const contentVersion of [0,-0,-1,1.5,NaN,Infinity,-Infinity,Number.MAX_SAFE_INTEGER+1,'1',null,undefined]){
  const v=await dialog(t,{'/medical-record/17/review-preview':()=>({data:{...copy(),contentVersion}})})
  assert.equal(v.vm.preview,null,`invalid version ${String(contentVersion)} must not become a viewed snapshot`)
  v.vm.acknowledged=true;await flush()
  for(const action of ['confirm-medical-approve','confirm-medical-reject'])assert.equal(findNode(v.root,action).props.disabled,true)
  await v.vm.submit(true);await v.vm.submit(false);assert.equal(posts(v).length,0);await v.unmount()
 }
})
test('positive safe-integer snapshot boundaries submit the exact viewed version',async t=>{
 for(const contentVersion of [1,Number.MAX_SAFE_INTEGER]){
  const v=await dialog(t,{'/medical-record/17/review-preview':()=>({data:{...copy(),contentVersion}})})
  assert.equal(v.vm.preview.contentVersion,contentVersion);v.vm.acknowledged=true;await v.click('confirm-medical-approve')
  assert.equal(posts(v).length,1);assert.equal(posts(v)[0].data.expectedContentVersion,contentVersion);await v.unmount()
 }
})
test('patient changes and logout invalidate in-flight reads and decisions',async t=>{
 const pending=deferred();const v=await dialog(t,{'/medical-record/17/review-preview':()=>pending.promise});await v.update({recordId:18,patientId:2});pending.resolve({data:copy()});await flush();assert.equal(v.vm.preview,null);assert.equal(posts(v).length,0)
 const second=await dialog(t);second.vm.acknowledged=true;second.auth.clearAuthSession();await flush();await second.vm.submit(true);assert.equal(second.vm.preview,null);assert.equal(posts(second).length,0)
})
test('duplicate clicks stay single-flight and unknown outcome requires reload',async t=>{
 const pending=deferred();const v=await dialog(t,{'/medical-record/17/review':()=>pending.promise});v.vm.acknowledged=true;const first=v.vm.submit(true);await v.vm.submit(false);assert.equal(posts(v).length,1);pending.reject(new Error('unknown'));await first;assert.equal(findNode(v.root,'confirm-medical-approve').props.disabled,true);await v.vm.submit(true);assert.equal(posts(v).length,1)
})
test('both workspaces route medical decisions into source dialog instead of direct submit',async t=>{
 const row={sourceType:'MEDICAL_RECORD',sourceId:17,patientId:1,title:'Synthetic record'}
 const v=await fixture(t,'DoctorWorkspace',{},async c=>c.url==='/doctor-workspace/summary'?{data:{patients:[{id:1,name:'Synthetic patient'}],reviewQueue:[row]}}:c.url==='/medical-record/17/review-preview'?{data:copy()}:c.url.endsWith('/capabilities')?{data:{enabled:false}}:{data:[]})
 await v.vm.review(row,'APPROVED');await flush();assert.equal(posts(v).length,0);assert.ok(v.calls.some(c=>c.url==='/medical-record/17/review-preview'));assert.ok(textOf(v.root).includes(snapshot.remark))
 const clinical=fs.readFileSync(new URL('../src/views/ClinicalWorkbench.vue',import.meta.url),'utf8');assert.match(clinical,/<MedicalRecordReviewDialog/);assert.doesNotMatch(clinical,/await reviewMedicalRecord\(/)
})

test('clinical workbench opens the real dialog and patient switch destroys the viewed snapshot',async t=>{
 const v=await fixture(t,'views/ClinicalWorkbench',{},async c=>c.url==='/medical-record/17/review-preview'?{data:copy()}:{data:{}})
 await v.vm.reviewRecord({sourceId:17},true);await flush();assert.equal(posts(v).length,0);assert.ok(textOf(v.root).includes(snapshot.remark));assert.ok(v.calls.some(c=>c.url==='/medical-record/17/review-preview'))
 v.vm.patientId=2;await flush();assert.equal(v.vm.medicalReviewTarget,null);assert.ok(!textOf(v.root).includes(snapshot.remark));assert.equal(posts(v).length,0)
})
