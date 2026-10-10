import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import {ref,reactive,computed,watch,nextTick,effectScope} from 'vue'
import {localDateKey,replaceTarget} from '../src/utils/familyHealth.js'
function deferred(){let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b});return{promise,resolve,reject}}
async function setup(t,{summaryRequest,projectionRequest}={}){
 const scope=effectScope(),patientId=ref(1),listeners=new EventTarget(),storage=new Map([['userId','A'],['token','a']]),calls=[],windows=[],hooks=[],context=ref({patientId:1,timeZone:'UTC',language:'en',fromDate:'2026-09-05',toDate:'2026-10-04'}),reportState=reactive({report:null,error:null})
 const api={getIntakes:async()=>({data:[]}),getTimeline:async()=>({data:[]}),getDialysisSchedules:async()=>({data:[]}),getHealthTarget:async()=>({data:{}}),getVisitSummary:async(id,options)=>{calls.push({kind:'summary',id,options});return summaryRequest?summaryRequest(id):{data:{patient:{id,name:'Synthetic fresh'},generatedAt:'fresh'}}}}
 const panel={context:context.value,state:reportState,clear(){reportState.report=null;reportState.error=null},async refresh(){calls.push({kind:'projection'});const result=projectionRequest?await projectionRequest():{status:'succeeded'};if(result.status==='succeeded')reportState.report={patient:{id:patientId.value},scope:{planId:null}};else reportState.error={errorCode:'ACCESS_DENIED'};return result}}
 const bindings={ref,reactive,computed,watch,nextTick,inject:(_key,fallback)=>fallback,onMounted(){},onUnmounted:fn=>hooks.push(fn),useCurrentPatient:()=>({currentPatientId:patientId}),useRoute:()=>({query:{},path:'/family-health'}),useRouter:()=>({push(){}}),readPermissionCache:()=>({menuPaths:['/family-health'],roleCodes:[]}),canAccessWorkspace:()=>true,api,localDateKey,replaceTarget,formatPlanTime:x=>x,ElMessage:{warning(){},success(){}},ElMessageBox:{},captureAuthSession:()=>({token:storage.get('token')}),isAuthSessionCurrent:s=>s.token===storage.get('token'),AUTH_STORAGE_KEYS:['token','userId'],localStorage:{getItem:k=>storage.get(k)},window:{addEventListener:(...a)=>listeners.addEventListener(...a),removeEventListener:(...a)=>listeners.removeEventListener(...a),clearInterval(){},open(){const popup={closed:false,document:{body:{replaceChildren(){}},write(html){popup.html=html},close(){}},focus(){},print(){popup.printed=true},close(){popup.closed=true},setTimeout(fn){popup.pending=fn}};windows.push(popup);return popup}}}
 const src=fs.readFileSync(new URL('../src/views/FamilyHealthManager.vue',import.meta.url),'utf8').match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import.*$/gm,'')
 const result=scope.run(()=>new Function(...Object.keys(bindings),src+'\nreturn {summary,summaryElement,summaryLoading,loadSummary,printSummary,reportPanel:typeof reportPanel!=="undefined"?reportPanel:null}')(...Object.values(bindings)))
 if(result.reportPanel)result.reportPanel.value=panel
 result.summaryElement.value={innerHTML:'<p>Synthetic rendered fresh content</p>'};await nextTick()
 t.after(()=>{hooks.forEach(fn=>fn());scope.stop()})
 return{...result,patientId,storage,listeners,calls,windows,panel,hooks}
}
test('printing always re-reads both sources and opens only after both authorize the same patient',async t=>{
 const old=deferred(),projection=deferred(),v=await setup(t,{summaryRequest:()=>old.promise,projectionRequest:()=>projection.promise});v.summary.value={patient:{id:1,name:'Obsolete cached name'}}
 const job=v.printSummary();await nextTick();assert.deepEqual(v.calls.map(c=>c.kind),['summary','projection']);assert.equal(v.windows.length,0);assert.equal(v.summary.value,null)
 old.resolve({data:{patient:{id:1,name:'Fresh summary'}}});await nextTick();assert.equal(v.windows.length,0);projection.resolve({status:'succeeded'});await job;assert.equal(v.windows.length,1);assert.ok(v.windows[0].html.includes('<html lang="en">'));assert.ok(v.windows[0].html.includes('<title>Visit Summary</title>'));assert.equal(v.summary.value.patient.name,'Fresh summary');assert.ok(v.calls[0].options.expectedAuth)
})
for(const kind of ['denied','wrong patient','patient A-B-A','account A-B-A','silent account','report options','unmount'])test(`fresh print rejects ${kind} instead of printing cached DOM`,async t=>{
 const old=deferred(),v=await setup(t,{summaryRequest:()=>old.promise});v.summary.value={patient:{id:1,name:'Stale'}};const job=v.printSummary();await nextTick()
 if(kind==='patient A-B-A'){v.patientId.value=2;v.patientId.value=1}
 if(kind==='account A-B-A'){v.storage.set('token','b');v.listeners.dispatchEvent(new Event('auth-session-cleared'));v.storage.set('token','a');v.listeners.dispatchEvent(new Event('auth-session-cleared'))}
 if(kind==='silent account')v.storage.set('token','silently-replaced')
 if(kind==='report options')v.panel.context.fromDate='2026-08-01'
 if(kind==='unmount')v.hooks.forEach(fn=>fn())
 if(kind==='denied')old.reject(new Error('Denied'));else old.resolve({data:{patient:{id:kind==='wrong patient'?2:1,name:'Fresh'}}})
 await job;assert.equal(v.windows.length,0);assert.equal(v.summaryLoading.value,false)
})
test('an open print window is cleared and closed when auth changes before its delayed print',async t=>{
 const v=await setup(t);await v.printSummary();assert.equal(v.windows.length,1);v.storage.set('token','b');v.listeners.dispatchEvent(new Event('auth-session-cleared'));v.windows[0].pending?.();assert.equal(v.windows[0].closed,true);assert.notEqual(v.windows[0].printed,true)
})
test('fresh legacy-summary read opts into original-text and late-auth response protection without changing ordinary calls',()=>{
 const calls=[],source=fs.readFileSync(new URL('../src/api/familyHealth.js',import.meta.url),'utf8').replace(/^import.*$/gm,'').replace(/export /g,'');const api=new Function('request',source+'\nreturn {getVisitSummary}')((config)=>{calls.push(config)})
 api.getVisitSummary(1,{expectedAuth:{token:'synthetic',actorId:'A'},signal:'synthetic-signal'});assert.equal(calls[0].executionReport,true);assert.equal(calls[0].signal,'synthetic-signal');api.getVisitSummary(1);assert.equal(calls[1].executionReport,undefined)
})
