import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import { ref, reactive, computed, watch, effectScope } from 'vue'
import { exportDateRange } from '../src/utils/exportPresentation.js'
function setup(t, exportCsv) {
 const currentPatientId=ref(1),currentPatientName=ref('Synthetic Same'),scope=effectScope(),downloads=[],messages=[],unmount=[]
 const code=fs.readFileSync(new URL('../src/views/DataExportManager.vue',import.meta.url),'utf8').match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import.*$/gm,'')
 const deps={ref,reactive,computed,watch,onMounted(){},onUnmounted:fn=>unmount.push(fn),useCurrentPatient:()=>({currentPatientId,currentPatientName}),useMenuPermission:()=>({hasMenu:()=>true,hasMenuName:()=>true}),exportCsv,exportDateRange,captureAuthSession:()=>({token:'synthetic'}),isAuthSessionCurrent:()=>true,window:{URL:{createObjectURL:()=> 'blob:synthetic',revokeObjectURL(){}}},document:{createElement:()=>({click(){downloads.push(this.download)}})},ElMessage:Object.fromEntries(['warning','error','success','info'].map(x=>[x,m=>messages.push(m)]))}
 const v=scope.run(()=>new Function(...Object.keys(deps),code+';return {form,formRef,handleExport,exportMessage:typeof exportMessage==="undefined"?ref(""):exportMessage,exporting}')(...Object.values(deps)))
 v.formRef.value={validate:async()=>true};Object.assign(v.form,{dataType:'nutrition',timeValue:'2026-10'})
 t.after(()=>{unmount.forEach(fn=>fn());scope.stop()})
 return {...v,currentPatientId,downloads,messages}
}
test('export suppresses an old patient response and keeps data scope immutable',async t=>{
 let resolve;const pending=new Promise(r=>resolve=r),calls=[]
 const v=setup(t,async p=>{calls.push(p);return pending})
 const job=v.handleExport();await Promise.resolve();await Promise.resolve();v.currentPatientId.value=2
 resolve(new Blob(['Date,Value\n2026-10-01,12\n']));await job
 assert.equal(v.downloads.length,0);assert.equal(calls[0].patientId,1)
})
test('export dates the filename without patient identifiers and explains empty CSV',async t=>{
 let empty=true;const v=setup(t,async()=>new Blob([empty?'Date,Value\n':'Date,Value\n2026-10-01,12\n']))
 await v.handleExport();assert.equal(v.downloads.length,0);assert.match(v.exportMessage.value,/没有记录/)
 empty=false;await v.handleExport();assert.match(v.downloads[0],/2026-10-01_2026-10-31/);assert.doesNotMatch(v.downloads[0],/Synthetic/)
})
