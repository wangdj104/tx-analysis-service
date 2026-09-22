import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { ref, reactive, computed, watch, nextTick } from 'vue';

function deferred(){let resolve;const promise=new Promise(r=>resolve=r);return {promise,resolve};}
function setup(file, bindings, returned){
  const source=fs.readFileSync(new URL('../src/views/'+file,import.meta.url),'utf8').match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import[\s\S]*?from\s+['"][^'"]+['"];?\r?$/gm,'');
  const dependencies={ref,reactive,computed,watch,onMounted(){},useRouter:()=>({push(){}}),ElMessage:{success(){},warning(){},error(){}},ElMessageBox:{confirm:async()=>{}},platformBranding:{platformName:'Review'},...bindings};
  return new Function(...Object.keys(dependencies), source+'\nreturn {'+returned.join(',')+'}')(...Object.values(dependencies));
}
function clinical(overrides={}){
  const patient=ref(1);let imported=0,scheduled=0;
  const view=setup('ClinicalWorkbench.vue',{
    useCurrentPatient:()=>({currentPatientId:patient,currentPatientName:ref('Review')}),
    getClinicalWorkbenchOverview:async id=>({data:{patientId:id}}),getDialysisQuality:async()=>({data:{}}),
    previewClinicalImport:async()=>({data:{itemCount:1,items:[{}]}}),commitClinicalImport:async()=>{imported++;return{data:{created:1}};},
    generateDialysisSchedule:async body=>{if(body.confirmed)scheduled++;return{data:{preview:[{scheduleDate:'2026-09-22'}],created:1}};},...overrides
  },['load','overview','importText','importPreview','lastImportBody','previewImport','commitImport','schedule','schedulePreview','previewSchedule','confirmSchedule','submitting']);
  return {...view,patient,imported:()=>imported,scheduled:()=>scheduled};
}
test('clinical overview ignores a late response after patient switching',async()=>{
  const pending=deferred();
  const view=clinical({getClinicalWorkbenchOverview:id=>id===1?pending.promise:Promise.resolve({data:{patientId:id}})});
  const first=view.load();view.patient.value=2;await view.load();
  pending.resolve({data:{patientId:1}});await first;
  assert.equal(view.overview.value.patientId,2);
});
test('clinical import preview is invalidated immediately by edits or patient changes',async()=>{
  const view=clinical();view.importText.value='{"resourceType":"Bundle"}';await view.previewImport();
  assert.equal(view.importPreview.value.itemCount,1);
  view.importText.value='{}';assert.equal(view.lastImportBody.value,null);await view.commitImport();assert.equal(view.imported(),0);
  await view.previewImport();view.patient.value=2;await view.commitImport();assert.equal(view.imported(),0);
});
test('a late import preview cannot restore stale source data',async()=>{
  const pending=deferred();const view=clinical({previewClinicalImport:()=>pending.promise});
  view.importText.value='{}';const action=view.previewImport();view.importText.value='{"changed":true}';
  pending.resolve({data:{itemCount:1}});await action;assert.equal(view.lastImportBody.value,null);
});
test('switching patient while import confirmation is open prevents wrong-patient submission',async()=>{
  const pending=deferred();const view=clinical({ElMessageBox:{confirm:()=>pending.promise}});
  view.importText.value='{}';await view.previewImport();const action=view.commitImport();
  view.patient.value=2;pending.resolve();await action;assert.equal(view.imported(),0);assert.equal(view.submitting.value,false);
});
test('schedule changes invalidate confirmation and prevent duplicate submission',async()=>{
  const pending=deferred();const view=clinical({ElMessageBox:{confirm:()=>pending.promise}});
  await view.previewSchedule();const first=view.confirmSchedule();await view.confirmSchedule();
  view.schedule.time='10:00';pending.resolve();await first;assert.equal(view.scheduled(),0);
  await view.previewSchedule();await view.confirmSchedule();assert.equal(view.scheduled(),1);
});
test('doctor notes and plans from the previous patient cannot replace the current context',async()=>{
  const pending=deferred();
  const view=setup('DoctorWorkspace.vue',{defineComponent:x=>x,getDoctorNotes:id=>id===1?pending.promise:Promise.resolve({data:[{patientId:id}]}),getDoctorPlans:async id=>({data:[{patientId:id}]})},['selectedPatientId','loadPatientContext','notes','plans']);
  view.selectedPatientId.value=1;const first=view.loadPatientContext();view.selectedPatientId.value=2;await view.loadPatientContext();
  pending.resolve({data:[{patientId:1}]});await first;assert.equal(view.notes.value[0].patientId,2);assert.equal(view.plans.value[0].patientId,2);
});
