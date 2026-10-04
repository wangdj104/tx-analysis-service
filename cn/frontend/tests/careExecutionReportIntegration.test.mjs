import test from 'node:test'
import assert from 'node:assert/strict'
import {mountedReport,deferred,flush,syntheticReport,syntheticSummary} from './helpers/executionReportIntegration.mjs'

for(const operation of ['preview','CSV'])test(`real App shared A-B-A selection rejects a late ${operation} even when navigation coalesces`,async t=>{
 const pending=deferred(),v=await mountedReport(t,{preview:()=>pending.promise,exportFile:()=>pending.promise}),original=v.panel.state
 const job=operation==='preview'?v.panel.refresh():v.panelSetup.download('actions_csv');v.switchPatient(2);v.switchPatient(1);await flush()
 assert.equal(v.route.fullPath,'/care-plans/reports?patientId=1');assert.equal(v.panel.state,original)
 if(operation==='preview')pending.resolve({code:200,data:syntheticReport({patientId:1},'Obsolete A clinical text')})
 else pending.resolve({blob:new Blob(['\ufeffheader\r\nObsolete A clinical text\r\n'],{type:'text/csv'}),headers:{'content-disposition':`attachment; filename="care-execution-report-${v.panel.context.language}-20261004T120000Z-actions.csv"`}})
 assert.equal((await job).status,'stale');await flush();assert.equal(v.panel.state,original);assert.equal(v.route.fullPath,'/care-plans/reports?patientId=1');assert.equal(v.panel.state.report,null);assert.equal(v.panel.state.lastExport,null);assert.equal(v.downloads.length,0);assert.equal(v.createdUrls.length,0);assert.ok(!v.html.includes('Obsolete A clinical text'))
})
test('cold report keeps route-owned patient despite a different initial shared selection',async t=>{const v=await mountedReport(t,{selectedPatient:99});assert.equal((await v.panel.refresh()).status,'succeeded');assert.equal(v.panel.state.report.patient.id,1);assert.equal(v.currentPatientId.value,99)})
test('a rejected shared-patient change leaves the current authorized report request valid',async t=>{const pending=deferred(),v=await mountedReport(t,{preview:()=>pending.promise});window.addEventListener('care-plan-before-context-change',event=>event.preventDefault());const job=v.panel.refresh();v.switchPatient(2);pending.resolve({data:syntheticReport({},'Authorized current A')});assert.equal((await job).status,'succeeded');assert.equal(v.currentPatientId.value,1)})

function assertFreshMarkup(popup){assert.ok(popup.html.includes('Fresh legacy clinical text'));assert.ok(popup.html.includes('Fresh care clinical text'));assert.ok(!popup.html.includes('Old legacy clinical text'));assert.ok(!popup.html.includes('Old care clinical text'))}
async function preparedPrint(t,options={}){
 let count=0;const v=await mountedReport(t,{family:true,preview:async body=>({data:syntheticReport(body,++count===1?'Old care clinical text':'Fresh care clinical text')}),...options})
 v.family.summary=syntheticSummary(1,'Old legacy clinical text');await v.panel.refresh();await flush();assert.ok(v.html.includes('Old legacy clinical text'));assert.ok(v.html.includes('Old care clinical text'));assert.equal(await v.family.printSummary(),true);assert.equal(v.windows.length,1);assertFreshMarkup(v.windows[0]);return v
}
test('print serializes newly rendered legacy and care content, never the previous clinical DOM',async t=>{const v=await preparedPrint(t);v.windows[0].pending();assert.equal(v.windows[0].printed,true)})
for(const replacement of ['success','denial','clear'])test(`a ${replacement} of the actual report generation clears pending clinical print DOM`,async t=>{
 let calls=0;const pending=deferred(),v=await preparedPrint(t,{preview:body=>++calls<=2?Promise.resolve({data:syntheticReport(body,calls===1?'Old care clinical text':'Fresh care clinical text')}):pending.promise});const popup=v.windows[0]
 let job;if(replacement==='clear')v.panel.clear();else job=v.panel.refresh()
 assert.equal(popup.closed,true,'generation replacement closes immediately');assert.equal(popup.html,'','generation replacement clears old clinical DOM')
 if(job){if(replacement==='denial')pending.reject({code:403,data:{errorCode:'ACCESS_DENIED'}});else pending.resolve({data:syntheticReport({},'Replacement care text')});const result=await job;assert.equal(result.status,replacement==='denial'?'failed':'succeeded')}
 popup.pending();assert.equal(popup.printed,false);assert.equal(popup.html,'');if(replacement==='success')assert.equal(v.panel.state.report.currentActions[0].instruction,'Replacement care text');if(replacement==='denial')assert.equal(v.panel.state.error.errorCode,'ACCESS_DENIED')
})
for(const change of ['patient','auth','legacy refresh'])test(`an accepted ${change} invalidates the actual rendered pending print`,async t=>{const v=await preparedPrint(t),popup=v.windows[0];if(change==='patient')v.currentPatientId.value=2;else if(change==='auth')v.auth.clearAuthSession();else await v.family.loadSummary();popup.pending();assert.equal(popup.closed,true);assert.equal(popup.printed,false);assert.equal(popup.html,'')})
for(const code of ['ACCESS_DENIED','FEATURE_DISABLED'])test(`paired fresh ${code} care area still permits a newly authorized legacy summary`,async t=>{const v=await mountedReport(t,{family:true,preview:async()=>{throw{code:code==='ACCESS_DENIED'?403:404,data:{errorCode:code}}}});assert.equal(await v.family.printSummary(),true);const popup=v.windows[0];assert.ok(popup.html.includes('Fresh legacy clinical text'));assert.equal(v.panel.state.report,null);assert.equal(v.panel.state.error.errorCode,code);popup.pending();assert.equal(popup.printed,true)})
test('blocked popup performs both fresh reads and never opens or prints cached clinical content',async t=>{const v=await mountedReport(t,{family:true,blockPopup:true});assert.equal(await v.family.printSummary(),false);assert.equal(v.windows.length,0);assert.ok(v.calls.some(c=>c.url==='/family-health/visit-summary'));assert.ok(v.calls.some(c=>c.url==='/care-plans/reports/preview'));assert.ok(v.html.includes('Fresh legacy clinical text'));assert.ok(v.html.includes('Fresh care clinical text'))})
test('a replacement report while the paired legacy read waits prevents obsolete mixed-source printing',async t=>{const legacy=deferred();let count=0;const v=await mountedReport(t,{family:true,summary:()=>legacy.promise,preview:async body=>({data:syntheticReport(body,++count===1?'Paired care text':'Newer care text')})});const print=v.family.printSummary();await flush();assert.equal(v.panel.state.phase,'ready');await v.panel.refresh();legacy.resolve({data:syntheticSummary()});assert.equal(await print,false);assert.equal(v.windows.length,0);assert.equal(v.panel.state.report.currentActions[0].instruction,'Newer care text');assert.equal(v.family.summary,null);assert.equal(v.family.summaryLoading,false)})

test('mounted measurement cold locator restores the ordinary same-tab list once through the real router',async t=>{
 const v=await mountedReport(t,{journey:true,selectedPatient:10,sourceRead:async params=>({data:[{id:params.measurementId||88,patient_id:params.patientId}]})});assert.equal(v.journey.sourceFocus.state.record.id,17);assert.equal(v.calls.filter(c=>c.url==='/care-journey/measurements').length,1);await v.router.push('/care-journey?tab=measurements');await flush();const reads=v.calls.filter(c=>c.url==='/care-journey/measurements');assert.deepEqual(reads.map(c=>c.params),[{patientId:10,measurementId:17},{patientId:10}]);assert.equal(v.journey.measurements[0].id,88)
})
test('mounted measurement page warm source entry and actual router exit never start another ordinary read',async t=>{
 const v=await mountedReport(t,{journey:true,selectedPatient:10,initialRoute:'/care-journey?tab=measurements',sourceRead:async params=>({data:[{id:params.measurementId||88,patient_id:params.patientId}]})});assert.equal(v.journey.measurements[0].id,88);await v.router.push('/care-journey?tab=measurements&patientId=10&measurementId=17');await flush();assert.equal(v.journey.sourceFocus.state.record.id,17);await v.router.push('/care');await flush();assert.deepEqual(v.calls.filter(c=>c.url==='/care-journey/measurements').map(c=>c.params),[{patientId:10},{patientId:10,measurementId:17}]);assert.equal(v.journey,undefined)
})

test('rendered print assertions detect the reviewer’s stale-literal markup mutation',async t=>{
 const v=await mountedReport(t,{family:true,mutateFamily:source=>source.replace('const markup=summaryElement.value?.innerHTML',"const markup='Obsolete cached name'")});assert.equal(await v.family.printSummary(),true);assert.equal(v.windows[0].html.includes('Obsolete cached name'),true);assert.throws(()=>assertFreshMarkup(v.windows[0]),assert.AssertionError)
})
