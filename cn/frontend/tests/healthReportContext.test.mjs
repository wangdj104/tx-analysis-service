import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import { ref, reactive, computed, watch, nextTick, effectScope } from 'vue'
function deferred(){let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b});return{promise,resolve,reject}}
function setup(t,overrides={}){
 const patientId=ref(1),scope=effectScope(),unmount=[],messages=[],requests=[],downloads=[],urls=[],revoked=[],chartRequests=[]
 const bindings={ref,reactive,computed,watch,onMounted(){},onUnmounted:fn=>unmount.push(fn),useCurrentPatient:()=>({currentPatientId:patientId}),useMenuPermission:()=>({hasMenu:()=>true,hasMenuName:()=>true}),
  ElMessage:Object.fromEntries(['success','error','warning'].map(kind=>[kind,text=>messages.push({kind,text})])),
  echarts:{use(){}},...Object.fromEntries(['CanvasRenderer','LineChart','BarChart','PieChart','GridComponent','TooltipComponent','LegendComponent','TitleComponent','ToolboxComponent'].map(k=>[k,{}])),
  window:{URL:{createObjectURL(blob){urls.push(blob);return 'blob:synthetic-'+urls.length},revokeObjectURL(url){revoked.push(url)}}},
  document:{createElement:()=>({click(){downloads.push({href:this.href,filename:this.download})}})},
  generateReport:async payload=>{requests.push(payload);return{text:async()=>'<p>Synthetic report</p>'}},
  getStats:async(...args)=>{chartRequests.push(args);return{code:200,data:{}}},getChartData:async(...args)=>{chartRequests.push(args);return{code:200,data:{}}},...overrides}
 const source=fs.readFileSync(new URL('../src/views/HealthReportManager.vue',import.meta.url),'utf8').match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import.*$/gm,'')
 const view=scope.run(()=>new Function(...Object.keys(bindings),source+'\nreturn {form,formRef,exporting,previewing,previewHtml,handleExport,handlePreview,buildReportPayload,generateTrendChartImages,renderChartImage}')(...Object.values(bindings)))
 view.formRef.value={validate:async()=>true};Object.assign(view.form,{reportType:'bp',format:'pdf',timeValue:'2026-10'})
 t.after(()=>{unmount.forEach(fn=>fn());scope.stop()})
 return{...view,patientId,messages,requests,downloads,urls,revoked,chartRequests,unmount(){unmount.forEach(fn=>fn());scope.stop()}}
}
for (const action of ['handleExport','handlePreview']) {
 test(`${action}: patient change cancels delayed validation before report request`,async t=>{
  const pending=deferred(),v=setup(t);v.formRef.value.validate=()=>pending.promise
  const job=v[action]();v.patientId.value=2;pending.resolve(true);await job
  assert.equal(v.requests.length,0);assert.equal(v.downloads.length,0);assert.equal(v.previewHtml.value,'')
 })
 test(`${action}: a completed old-patient request has no visible result`,async t=>{
  const pending=deferred(),v=setup(t,{generateReport:()=>pending.promise})
  const job=v[action]();await nextTick();await nextTick();v.patientId.value=2
  pending.resolve({text:async()=>'<p>Old A report</p>'});await job
  assert.equal(v.downloads.length,0);assert.equal(v.previewHtml.value,'');assert.equal(v.messages.length,0)
 })
 test(`${action}: duplicate clicks only validate and generate once`,async t=>{
  const pending=deferred(),v=setup(t);let validations=0
  v.formRef.value.validate=()=>{validations++;return pending.promise}
  const first=v[action](),second=v[action]();pending.resolve(true);await Promise.all([first,second])
  assert.equal(validations,1);assert.equal(v.requests.length,1)
 })
 test(`${action}: unmount cancels delayed work`,async t=>{
  const pending=deferred(),v=setup(t,{generateReport:()=>pending.promise})
  const job=v[action]();await nextTick();await nextTick();v.unmount()
  pending.resolve({text:async()=>'<p>Old report</p>'});await job
  assert.equal(v.downloads.length,0);assert.equal(v.previewHtml.value,'')
 })
}
test('patient changes immediately clear an existing preview',t=>{
 const v=setup(t);v.previewHtml.value='<p>A report</p>';v.patientId.value=2;assert.equal(v.previewHtml.value,'')
})
test('same-patient A-B-A navigation invalidates an old report',async t=>{
 const pending=deferred(),v=setup(t,{generateReport:()=>pending.promise})
 const job=v.handlePreview();await nextTick();await nextTick();v.patientId.value=2;v.patientId.value=1
 pending.resolve({text:async()=>'<p>Obsolete A report</p>'});await job;assert.equal(v.previewHtml.value,'')
})
test('changing report options clears the preview and invalidates pending text conversion',async t=>{
 const text=deferred(),v=setup(t,{generateReport:async()=>({text:()=>text.promise})})
 const job=v.handlePreview();await nextTick();await nextTick();await nextTick()
 v.form.timeValue='2026-09';text.resolve('<p>October report</p>');await job;assert.equal(v.previewHtml.value,'')
})
test('summary chart reads use a single immutable patient/filter snapshot and cancel obsolete dispatch',async t=>{
 const pending=deferred(),args=[]
 const v=setup(t,{getStats:(...a)=>{args.push(a);return pending.promise},getChartData:(...a)=>{args.push(a);return pending.promise}})
 v.form.reportType='summary';const job=v.handleExport();await nextTick();await nextTick()
 v.patientId.value=2;v.form.timeValue='2026-09'
 pending.resolve({code:200,data:{}});await job
 assert.deepEqual(args,[['month','2026-10',1],['month','2026-10',1]]);assert.equal(v.requests.length,0)
})
test('preview/export cannot race each other during validation',async t=>{
 const pending=deferred(),v=setup(t);v.formRef.value.validate=()=>pending.promise
 const first=v.handlePreview(),second=v.handleExport();pending.resolve(true);await Promise.all([first,second]);assert.equal(v.requests.length,1)
})
test('an older finally cannot unlock a newer patient request',async t=>{
 const first=deferred(),second=deferred();let calls=0
 const v=setup(t,{generateReport:()=>++calls===1?first.promise:second.promise})
 const a=v.handlePreview();await nextTick();await nextTick();v.patientId.value=2
 const b=v.handlePreview();await nextTick();await nextTick()
 first.resolve({text:async()=>'<p>Old</p>'});await a;assert.equal(v.previewing.value,true)
 second.resolve({text:async()=>'<p>New</p>'});await b;assert.equal(v.previewHtml.value,'<p>New</p>');assert.equal(v.previewing.value,false)
})
test('failed export retains the options and allows retry',async t=>{
 let calls=0;const v=setup(t,{generateReport:async()=>{if(++calls===1)throw new Error('Synthetic failure');return{}}})
 await v.handleExport();assert.equal(v.exporting.value,false);assert.equal(v.form.timeValue,'2026-10');await v.handleExport();assert.equal(calls,2);assert.equal(v.downloads.length,1)
})
test('successful export uses its requested patient/format and revokes its URL',async t=>{
 const v=setup(t);await v.handleExport();assert.equal(v.requests[0].patientId,1);assert.equal(v.requests[0].format,'pdf');assert.match(v.downloads[0].filename,/_1\.pdf$/);assert.deepEqual(v.revoked,['blob:synthetic-1'])
})
test('failed chart rendering still disposes its instance and removes its temporary node',t=>{
 let disposed=0,removed=0,appended=0
 const v=setup(t,{echarts:{use(){},init:()=>({setOption(){throw new Error('Synthetic canvas failure')},dispose(){disposed++}})},document:{body:{appendChild(){appended++},removeChild(){removed++}},createElement:()=>({style:{}})}})
 assert.throws(()=>v.renderChartImage({},600,320),/Synthetic canvas failure/);assert.equal(appended,1);assert.equal(disposed,1);assert.equal(removed,1)
})
for (const cancel of ['patient change', 'unmount']) {
 test(`a canceled summary skips obsolete chart rendering after ${cancel}`,async t=>{
  const pending=deferred();let renders=0
  const v=setup(t,{getStats:()=>pending.promise,getChartData:()=>pending.promise,
   echarts:{use(){},init(){renders++;return{setOption(){},getDataURL(){return'data:image/png;base64,synthetic'},dispose(){}}}},
   document:{body:{appendChild(){},removeChild(){}},createElement:()=>({style:{}})}})
  v.form.reportType='summary';const job=v.handleExport();await nextTick();await nextTick()
  if(cancel==='unmount')v.unmount();else v.patientId.value=2
  pending.resolve({code:200,data:{dateList:['2026-10-01']}});await job
  assert.equal(renders,0);assert.equal(v.requests.length,0)
 })
}
