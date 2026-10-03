import assert from 'node:assert/strict'
import test from 'node:test'
import {h} from 'vue'
import {fixture,flush,deferred,findNode,textOf} from './helpers/carePlanDoctorHarness.mjs'
const action={id:31,ordinal:1,assignedUserId:51,instruction:'Synthetic action',dueAt:'2026-10-04T12:00:00Z',status:'SUBMITTED',version:99,evidence:[],events:[],allowedActions:['REVIEW_RECEIPT']}
const plan={id:17,patientId:1,workflowVersion:1,title:'Synthetic plan',instructions:'Synthetic instructions',planType:'FOLLOW_UP',revisionId:23,revisionNo:1,revisionStatus:'DRAFT',draftRevisionId:23,currentRevisionId:null,version:4,lifecycle:'DRAFT',actions:[action],allowedActions:['SAVE_DRAFT','PUBLISH_PLAN']}
const copy=x=>JSON.parse(JSON.stringify(x))
function server(overrides={}){let view=copy(plan);return async c=>{if(overrides[c.url])return overrides[c.url](c);if(c.url.endsWith('/capabilities'))return {code:200,data:{enabled:true}};if(c.url.endsWith('/assignees'))return {code:200,data:[{userId:51,displayName:'Synthetic clinician',role:'doctor'}]};if(c.method==='post'){view={...view,version:view.version+1};if(c.url==='/care-plans')view={...view,...c.data};return {code:200,data:c.url.endsWith('/publish')||c.url.includes('/actions/')?{planId:17,eventId:40,version:view.version,lifecycle:'ACTIVE'}:view}};if(c.url.endsWith('/revisions'))return {code:200,data:{items:[{id:23,revisionNo:1,status:'DRAFT'}],nextCursor:null}};if(c.url==='/care-plans')return {code:200,data:{items:[view],nextCursor:null}};return {code:200,data:view}}}
const posts=v=>v.calls.filter(c=>c.method==='post')
// Match CarePlanController's strict command fields at the HTTP boundary.
// Keep the component, composable and API dispatch real; reject unsupported extras.
function transitionServer(initial, afterDispatch=()=>{}) {
 let current=copy(initial)
 const transition=action=>async c=>{
  const allowed=action==='CLOSE'?['commandKey','expectedVersion']:['commandKey','expectedVersion','reason']
  if(Object.keys(c.data).some(key=>!allowed.includes(key)) ||
     typeof c.data.commandKey!=='string' || !Number.isSafeInteger(c.data.expectedVersion) || c.data.expectedVersion<0 ||
     (action==='CANCEL' && (typeof c.data.reason!=='string' || !c.data.reason.trim() || Array.from(c.data.reason.trim()).length>1000))) {
   throw Object.assign(new Error('Invalid care-plan request fields or identifiers.'),{response:{status:400}})
  }
  await afterDispatch(c,action)
  current={...current,version:current.version+1,lifecycle:action==='CLOSE'?'COMPLETED':'CANCELLED',allowedActions:[]}
  return {code:200,data:{planId:current.id,eventId:40,version:current.version,lifecycle:current.lifecycle}}
 }
 return server({
  [`/care-plans/${initial.id}`]:()=>({code:200,data:copy(current)}),
  [`/care-plans/${initial.id}/close`]:transition('CLOSE'),
  [`/care-plans/${initial.id}/cancel`]:transition('CANCEL')
 })
}
const closablePlan=()=>({...copy(plan),lifecycle:'ACTIVE',revisionStatus:'PUBLISHED',currentRevisionId:23,draftRevisionId:null,allowedActions:['CANCEL_PLAN','CLOSE_PLAN'],actions:[{...action,status:'CONFIRMED',allowedActions:[]}]})
async function checkInitialHistory(v){while(findNode(v.root,'more-create-history'))await v.click('more-create-history');if(findNode(v.root,'acknowledge-create-history'))await v.click('acknowledge-create-history')}
test('saveDraftDoesNotPublish: only explicit confirmed publish sends publish',async t=>{
 const v=await fixture(t,'PlanEditor',{patientId:1,planId:17,revisionId:23},server());await v.click('save-draft');assert.equal(posts(v).filter(c=>c.url.endsWith('/publish')).length,0);assert.equal(posts(v)[0].data.expectedVersion,4);assert.equal(posts(v)[0].data.actions[0].ordinal,1)
 await v.click('prepare-publish');assert.equal(posts(v).filter(c=>c.url.endsWith('/publish')).length,0);v.vm.publishConfirmed=true;await v.click('confirm-publish');assert.equal(posts(v).filter(c=>c.url.endsWith('/publish')).length,1)
})
test('revisionRequiresCurrentImpactConfirmation: sends frozen server impact only after confirmation',async t=>{
 const impact={currentRevisionId:20,actionIds:[70,71],digest:'server-exact-digest'},p={...copy(plan),currentRevisionId:20,revisionImpact:impact}
 const v=await fixture(t,'PlanEditor',{patientId:1,planId:17,revisionId:23},server({'/care-plans/17/revisions/23':()=>({data:p}),'/care-plans/17':()=>({data:{...p,title:'Prior title',actions:[{...action,id:70,status:'OPEN'}]}})}));await v.click('prepare-publish');assert.match(textOf(v.root),/70/);assert.match(textOf(v.root),/Prior title/);assert.equal(posts(v).length,0);v.vm.publishConfirmed=true;await v.click('confirm-publish');assert.equal(posts(v)[0].data.supersededActionDigest,'server-exact-digest');assert.equal(posts(v)[0].data.currentRevisionId,20)
})
test('legacyCopyRemainsPrivate: copying saves a new draft and never publishes',async t=>{
 const v=await fixture(t,'PlanEditor',{patientId:1,legacySource:{id:9,title:'Legacy title',instructions:'Old instructions',planType:'FOLLOW_UP',targetDate:'2026-10-05'}},server());await checkInitialHistory(v);v.vm.state.draft.actions[0].instruction='One synthetic action';v.vm.state.draft.actions[0].dueAt='2026-10-05T12:00:00+00:00';v.vm.state.draft.actions[0].assignedUserId=51;await v.click('save-draft');assert.equal(posts(v).length,1);assert.equal(posts(v)[0].url,'/care-plans');assert.equal(posts(v)[0].data.legacySourceId,9);assert.equal(posts(v)[0].data.status,undefined);assert.equal(posts(v).filter(c=>c.url.endsWith('/publish')).length,0)
})
test('reviewCannotBeConfusedWithPatientCompletion: uses review decision and aggregate version',async t=>{
 const p={...copy(plan),lifecycle:'ACTIVE',revisionStatus:'PUBLISHED',currentRevisionId:23,draftRevisionId:null,allowedActions:['CANCEL_PLAN']};const v=await fixture(t,'PlanDetail',{planId:17},server({'/care-plans/17':()=>({data:p})}));await v.click('review-confirm-31');assert.equal(posts(v)[0].url,'/care-plans/actions/31/reviews');assert.equal(posts(v)[0].data.decision,'CONFIRM');assert.equal(posts(v)[0].data.expectedVersion,4);assert.equal(posts(v).filter(c=>c.url.endsWith('/receipts')).length,0);assert.equal(findNode(v.root,'close-plan'),undefined)
})
test('server allowedActions hide controls; RETURN and CANCEL require nonblank reasons',async t=>{
 const p={...copy(plan),allowedActions:['CANCEL_PLAN','CLOSE_PLAN'],lifecycle:'ACTIVE',revisionStatus:'PUBLISHED'};const v=await fixture(t,'PlanDetail',{planId:17},server({'/care-plans/17':()=>({data:p})}));await v.click('review-return-31');await v.click('send-return');assert.equal(posts(v).length,0);v.vm.reviewNote='Needs more information';await v.click('send-return');assert.equal(posts(v)[0].data.decision,'RETURN');await v.click('cancel-plan');await v.click('confirm-transition');assert.equal(posts(v).length,1);v.vm.transitionReason='Plan replaced';await v.click('confirm-transition');assert.equal(posts(v)[1].data.reason,'Plan replaced')
 const readOnly=await fixture(t,'PlanEditor',{patientId:1,planId:17,revisionId:23},server({'/care-plans/17/revisions/23':()=>({data:{...plan,allowedActions:[]}})}));assert.equal(findNode(readOnly.root,'save-draft'),undefined);assert.equal(findNode(readOnly.root,'prepare-publish'),undefined)
})
test('UNKNOWN replay preserves edited input and reuses original command; fresh command needs warned reload',async t=>{
 let n=0;const v=await fixture(t,'PlanEditor',{patientId:1,planId:17,revisionId:23},server({'/care-plans/17/revisions/23':()=>({data:{...copy(plan),version:n>=2?5:4}}),'/care-plans/17/revisions/23/save':c=>{if(++n===1)throw new Error('uncertain');return {data:{...copy(plan),version:5}}}}));await v.click('save-draft');assert.equal(v.vm.state.commandPhase,'unknown');v.vm.state.draft.title='Edited after timeout';await v.click('retry-original');assert.equal(posts(v).length,2);assert.deepEqual(posts(v)[0].data,posts(v)[1].data);assert.equal(v.vm.state.draft.title,'Edited after timeout');assert.equal(v.events.some(e=>e[0]==='closed'),false);await v.click('save-draft');assert.equal(posts(v).length,3);assert.equal(posts(v)[2].data.title,'Edited after timeout');assert.equal(posts(v)[2].data.expectedVersion,5)
})
test('UNKNOWN close and reload require explicit warning acknowledgement; reload keeps input',async t=>{
 const v=await fixture(t,'PlanEditor',{patientId:1,planId:17,revisionId:23},server({'/care-plans/17/revisions/23/save':()=>{throw new Error('uncertain')}}));await v.click('save-draft');await v.click('close-editor');assert.equal(v.events.length,0);assert.ok(findNode(v.root,'confirm-abandon'));await v.click('keep-editing');v.vm.state.draft.title='Local draft';await v.click('reload-server');assert.ok(findNode(v.root,'confirm-abandon'));await v.click('confirm-abandon');assert.equal(v.vm.state.draft.title,'Local draft');assert.equal(v.vm.state.commandPhase,'idle');assert.ok(v.calls.filter(c=>c.url.endsWith('/revisions/23')).length>=2)
})
test('409 preserves form; invalid counts, code points, evidence and offset dates never send',async t=>{
 const v=await fixture(t,'PlanEditor',{patientId:1,planId:17,revisionId:23},server({'/care-plans/17/revisions/23/save':()=>{throw Object.assign(new Error('conflict'),{response:{status:409}})}}));v.vm.state.draft.title='Unsaved title';await v.click('save-draft');assert.equal(v.vm.state.draft.title,'Unsaved title');assert.equal(v.vm.state.commandPhase,'conflict');const count=posts(v).length
 for(const mutate of [d=>d.actions=[],d=>d.actions=Array.from({length:51},()=>copy(action)),d=>d.title='😀'.repeat(161),d=>d.actions[0].dueAt='2026-10-04',d=>d.actions[0].assignedUserId=88,d=>d.actions[0].evidence=[{sourceType:'MEASUREMENT',sourceId:2},{sourceType:'MEASUREMENT',sourceId:2}]]){v.vm.state.draft=copy(plan);mutate(v.vm.state.draft);await v.click('save-draft');assert.equal(posts(v).length,count)}
})
test('patient/editor and same-token session replacement reject obsolete reads and writes',async t=>{
 const pending=deferred();const v=await fixture(t,'PlanEditor',{patientId:1,planId:17,revisionId:23},server({'/care-plans/17/revisions/23/save':()=>pending.promise}));const request=v.vm.save();await v.update({patientId:2,planId:null,revisionId:null});v.vm.state.draft.title='New patient draft';pending.resolve({data:plan});await request;await flush();assert.equal(v.vm.state.draft.title,'New patient draft');assert.equal(v.events.length,0);v.auth.clearAuthSession();await flush();assert.equal(v.vm.state.draft,null);assert.equal(findNode(v.root,'save-draft'),undefined)
})
test('review queue selection and pagination use real composable and open exact plan ID',async t=>{
 const v=await fixture(t,'PlanReviewQueue',{patientId:null},server());assert.equal(v.calls.find(c=>c.url==='/care-plans').params.queue,'REVIEW');assert.equal(v.calls.find(c=>c.url==='/care-plans').params.patientId,undefined);await v.click('open-plan-17');assert.deepEqual(v.events,[['open',17]]);v.vm.queue='HELP';await flush();assert.equal(v.calls.filter(c=>c.url==='/care-plans').at(-1).params.queue,'HELP')
})
test('restricted evidence hides title and detail link; history GET explicitly selects revision',async t=>{
 const p={...copy(plan),actions:[{...action,evidence:[{sourceType:'MEDICAL_RECORD',sourceId:7,restricted:true,title:'Sensitive title',detailLink:'/secret'}]}]};const v=await fixture(t,'PlanDetail',{planId:17},server({'/care-plans/17':()=>({data:p})}));assert.doesNotMatch(textOf(v.root),/Sensitive title/);v.vm.historyRevisionId=23;await flush();assert.ok(v.calls.some(c=>c.url==='/care-plans/17/revisions/23'))
})
test('feature false renders no collaboration commands',async t=>{
 const v=await fixture(t,'PlanEditor',{patientId:1},server({'/care-plans/capabilities':()=>({data:{enabled:false}})}));assert.equal(findNode(v.root,'save-draft'),undefined);assert.equal(posts(v).length,0)
})

test('uncertain create reload preserves edits and checks patient history before fresh creation',async t=>{
 const v=await fixture(t,'PlanEditor',{patientId:1,legacySource:{id:9,title:'Legacy title',instructions:'Synthetic instructions',planType:'FOLLOW_UP'}},server({'/care-plans':c=>{if(c.method==='post')throw new Error('uncertain create');return {data:{items:[plan],nextCursor:null}}}}));await checkInitialHistory(v);Object.assign(v.vm.state.draft.actions[0],{instruction:'Synthetic action',dueAt:'2026-10-04T12:00:00Z',assignedUserId:51});await v.click('save-draft');v.vm.state.draft.title='Edited new input';await v.click('reload-server');await v.click('confirm-abandon');assert.equal(v.vm.state.draft.title,'Edited new input');assert.ok(v.calls.some(c=>c.method==='get'&&c.url==='/care-plans'&&c.params.queue==='HISTORY'));assert.ok(findNode(v.root,'acknowledge-create-history'));assert.equal(posts(v).length,1)
})
test('history authorization rejection immediately clears prior clinical content',async t=>{
 const v=await fixture(t,'PlanDetail',{planId:17},server({'/care-plans/17/revisions':()=>{throw Object.assign(new Error('revoked'),{response:{status:403}})}}));assert.equal(v.vm.state.plan,null);assert.doesNotMatch(textOf(v.root),/Synthetic instructions/)
})
test('editor can create and remove up to 50 actions with contiguous ordinals',async t=>{
 const v=await fixture(t,'PlanEditor',{patientId:1,planId:17,revisionId:23},server());for(let i=0;i<60;i++)v.vm.addAction();assert.equal(v.vm.state.draft.actions.length,50);v.vm.removeAction(3);assert.deepEqual(v.vm.state.draft.actions.map(a=>a.ordinal),Array.from({length:49},(_,i)=>i+1));for(let i=0;i<60;i++)v.vm.removeAction(0);assert.equal(v.vm.state.draft.actions.length,1)
})
test('browser-local deadline builder requires an explicit valid offset',async t=>{
 const v=await fixture(t,'PlanEditor',{patientId:1,planId:17,revisionId:23},server());const original=v.vm.state.draft.actions[0].dueAt;v.vm.localClocks[0]='2026-10-04T15:30:00';v.vm.localOffsets[0]=null;v.vm.applyLocalTime(0);assert.equal(v.vm.state.draft.actions[0].dueAt,original);const offset=v.vm.offsetsFor(0)[0];v.vm.localOffsets[0]=offset;v.vm.applyLocalTime(0);assert.match(v.vm.state.draft.actions[0].dueAt,/[+-]\d{2}:\d{2}$/)
})
test('CLOSE is offered only for all CONFIRMED current actions plus server permission',async t=>{
 const v=await fixture(t,'PlanDetail',{planId:17},transitionServer(closablePlan()));await v.click('close-plan');assert.equal(posts(v).length,0);await v.click('confirm-transition');assert.equal(posts(v)[0].url,'/care-plans/17/close');assert.equal(posts(v)[0].data.expectedVersion,4)
 assert.deepEqual(Object.keys(posts(v)[0].data).sort(),['commandKey','expectedVersion'])
 assert.match(posts(v)[0].data.commandKey,/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i)
 assert.equal(v.vm.state.plan.lifecycle,'COMPLETED');assert.equal(v.vm.state.commandPhase,'idle');assert.equal(v.vm.transitionAction,null);assert.equal(v.events.filter(e=>e[0]==='changed').length,1)
})
test('CLOSE confirmation has no unsupported note input or cancellation-reason draft',async t=>{
 const v=await fixture(t,'PlanDetail',{planId:17},transitionServer(closablePlan()));await v.click('cancel-plan');v.vm.transitionReason='Prior cancellation reason';await v.click('close-plan')
 const tags=node=>[node.tag,...(node.children||[]).flatMap(tags)]
 assert.equal(tags(v.root).includes('textarea'),false);assert.doesNotMatch(textOf(v.root),/Optional closing note|可选关闭说明/);assert.deepEqual(v.vm.state.draft,{})
 v.vm.transitionReason='Unrelated cancellation input';assert.deepEqual(v.vm.state.draft,{})
 await v.click('confirm-transition');assert.equal(v.vm.state.plan.lifecycle,'COMPLETED')
})
test('CANCEL keeps required trimmed code-point-limited reason on the strict API contract',async t=>{
 const v=await fixture(t,'PlanDetail',{planId:17},transitionServer(closablePlan()));await v.click('cancel-plan')
 for(const reason of ['', ' \n\t ', '😀'.repeat(1001)]){v.vm.transitionReason=reason;await v.click('confirm-transition');assert.equal(posts(v).length,0)}
 v.vm.transitionReason=`  ${'😀'.repeat(1000)}  `;await v.click('confirm-transition')
 assert.equal(posts(v)[0].url,'/care-plans/17/cancel');assert.deepEqual(Object.keys(posts(v)[0].data).sort(),['commandKey','expectedVersion','reason']);assert.equal(posts(v)[0].data.reason,'😀'.repeat(1000));assert.equal(posts(v)[0].data.expectedVersion,4);assert.equal(v.vm.state.plan.lifecycle,'CANCELLED')
})
test('UNKNOWN CLOSE replays the original metadata-only command and ignores cancellation input',async t=>{
 let attempts=0;const v=await fixture(t,'PlanDetail',{planId:17},transitionServer(closablePlan(),()=>{if(++attempts===1)throw new Error('Synthetic uncertain close')}));await v.click('close-plan');await v.click('confirm-transition');assert.equal(v.vm.state.commandPhase,'unknown')
 v.vm.transitionReason='Unrelated cancellation input';assert.deepEqual(v.vm.state.draft,{});await v.vm.transition();assert.equal(posts(v).length,1)
 await v.click('retry-detail-original');assert.equal(posts(v).length,2);assert.deepEqual(posts(v)[1].data,posts(v)[0].data);assert.equal(posts(v)[1].url,'/care-plans/17/close');assert.deepEqual(Object.keys(posts(v)[1].data).sort(),['commandKey','expectedVersion']);assert.equal(v.vm.state.plan.lifecycle,'COMPLETED');assert.equal(v.vm.transitionAction,null);assert.deepEqual(v.writes,[])
})
test('UNKNOWN CANCEL preserves newer reason while replaying the frozen original reason',async t=>{
 let attempts=0;const v=await fixture(t,'PlanDetail',{planId:17},transitionServer(closablePlan(),()=>{if(++attempts===1)throw new Error('Synthetic uncertain cancellation')}));await v.click('cancel-plan');v.vm.transitionReason='Original cancellation reason';await v.click('confirm-transition');assert.equal(v.vm.state.commandPhase,'unknown')
 v.vm.transitionReason='Newer local reason';await v.click('retry-detail-original');assert.equal(posts(v).length,2);assert.deepEqual(posts(v)[1].data,posts(v)[0].data);assert.equal(posts(v)[1].data.reason,'Original cancellation reason');assert.equal(v.vm.transitionReason,'Newer local reason');assert.deepEqual(v.vm.state.draft,{reason:'Newer local reason'});assert.equal(v.vm.state.plan.lifecycle,'CANCELLED');assert.deepEqual(v.writes,[])
})
test('pending CLOSE cannot settle into a replacement plan context',async t=>{
 const pending=deferred(),replacement={...closablePlan(),id:18,patientId:2,title:'Replacement patient plan'},transport=transitionServer(closablePlan(),()=>pending.promise)
 const v=await fixture(t,'PlanDetail',{planId:17},c=>c.url==='/care-plans/18'?{data:replacement}:transport(c));await v.click('close-plan');const sent=v.vm.transition();await flush();assert.equal(v.vm.state.commandPhase,'pending');await v.update({planId:18});assert.equal(posts(v)[0].signal.aborted,true)
 pending.resolve();await sent;await flush();assert.equal(v.vm.state.plan.id,18);assert.equal(v.vm.state.plan.lifecycle,'ACTIVE');assert.equal(v.vm.transitionAction,null);assert.equal(v.events.length,0);assert.deepEqual(v.writes,[])
})
test('pending CLOSE cannot settle after same-token session replacement',async t=>{
 const pending=deferred(),v=await fixture(t,'PlanDetail',{planId:17},transitionServer(closablePlan(),()=>pending.promise));await v.click('close-plan');const sent=v.vm.transition();await flush();assert.equal(v.vm.state.commandPhase,'pending')
 v.auth.clearAuthSession();v.storage.set('token','synthetic-token');v.storage.set('userId','51');await flush();assert.equal(posts(v)[0].signal.aborted,true);assert.equal(v.vm.state.plan,null)
 pending.resolve();await sent;await flush();assert.equal(v.vm.state.plan,null);assert.equal(v.vm.transitionAction,null);assert.equal(findNode(v.root,'close-plan'),undefined);assert.equal(v.events.length,0);assert.equal(posts(v).length,1);assert.deepEqual(v.writes,[])
})
test('overlapping patient assignee reads cannot restore old candidate identities',async t=>{
 const old=deferred();let n=0;const v=await fixture(t,'PlanEditor',{patientId:1},server({'/care-plans/assignees':()=>++n===1?old.promise:Promise.resolve({data:[{userId:22,displayName:'New authorized assignee',role:'family'}]})}));await v.update({patientId:2});old.resolve({data:[{userId:51,displayName:'Old patient assignee',role:'doctor'}]});await flush();assert.deepEqual(v.vm.assignees.map(p=>p.userId),[22]);assert.doesNotMatch(textOf(v.root),/Old patient assignee/)
})

test('DoctorWorkspace keeps legacy plans and enables explicit private copy only when capabilities allow',async t=>{
 const transport=server({'/doctor-workspace/summary':()=>({data:{patients:[{id:1,name:'Synthetic patient'}],reviewQueue:[]}}),'/doctor-workspace/notes':()=>({data:[]}),'/doctor-workspace/plans':()=>({data:[{id:9,title:'Historical internal title',instructions:'Old internal instructions',planType:'FOLLOW_UP',status:'ACTIVE'}]})});const v=await fixture(t,'DoctorWorkspace',{},transport);assert.match(textOf(v.root),/Historical internal title/);await v.click('copy-legacy-9');assert.equal(posts(v).length,0);assert.match(textOf(v.root),/Copied from a historical internal plan|复制自历史内部计划/)
 const disabled=await fixture(t,'DoctorWorkspace',{},server({'/doctor-workspace/summary':()=>({data:{patients:[{id:1,name:'Synthetic patient'}]}}),'/doctor-workspace/notes':()=>({data:[]}),'/doctor-workspace/plans':()=>({data:[{id:9,title:'Historical internal title',instructions:'Old internal instructions'}]}),'/care-plans/capabilities':()=>({data:{enabled:false}})}));assert.equal(findNode(disabled.root,'copy-legacy-9'),undefined);assert.match(textOf(disabled.root),/Historical internal title/)
})
test('a stale committed create blocks a second new command until history reload',async t=>{
 const pending=deferred();const v=await fixture(t,'PlanEditor',{patientId:1,legacySource:{id:9,title:'Legacy',instructions:'Synthetic instructions'}},server({'/care-plans':c=>c.method==='post'?pending.promise:Promise.resolve({data:{items:[plan],nextCursor:null}})}));await checkInitialHistory(v);Object.assign(v.vm.state.draft.actions[0],{instruction:'Synthetic',dueAt:'2026-10-04T12:00:00Z',assignedUserId:51});const sent=v.vm.save();v.vm.state.draft.title='Edited during send';pending.resolve({data:plan});await sent;await flush();assert.equal(v.vm.state.draft.title,'Edited during send');await v.vm.save();assert.equal(posts(v).length,1);await v.click('reload-server');assert.ok(findNode(v.root,'acknowledge-create-history'))
})
test('reload compares retained local draft with server draft before enabling publication',async t=>{
 let n=0;const v=await fixture(t,'PlanEditor',{patientId:1,planId:17,revisionId:23},server({'/care-plans/17/revisions/23':()=>({data:{...copy(plan),instructions:++n>1?'Changed server instructions':plan.instructions,version:n>1?5:4}})}));await v.click('reload-server');assert.equal(v.vm.state.draft.instructions,plan.instructions);await v.vm.preparePublish();assert.ok(!findNode(v.root,'confirm-publish'), 'stale or unsaved publication confirmation must remain hidden')
})
test('late publication-preview read cannot reopen confirmation after a newer editor reload',async t=>{
 const pending=deferred(),p={...copy(plan),currentRevisionId:20,revisionImpact:{currentRevisionId:20,actionIds:[70],digest:'old-impact'}};const v=await fixture(t,'PlanEditor',{patientId:1,planId:17,revisionId:23},server({'/care-plans/17/revisions/23':()=>({data:p}),'/care-plans/17':()=>pending.promise}));const old=v.vm.preparePublish();await v.click('reload-server');pending.resolve({data:{...p,title:'Old preview title'}});await old;await flush();assert.ok(!findNode(v.root,'confirm-publish'), 'stale or unsaved publication confirmation must remain hidden')
})
test('original submission timing compares full microseconds without hiding an originally late record',async t=>{
 const p={...copy(plan),actions:[{...action,firstSubmittedAt:'2026-10-04T12:00:00.000001Z',dueAt:'2026-10-04T12:00:00.000000Z'}]};const v=await fixture(t,'PlanDetail',{planId:17},server({'/care-plans/17':()=>({data:p})}));assert.match(textOf(v.root),/Originally submitted after the deadline|原始提交晚于截止时间/)
})
test('private draft and current detail contexts must close explicitly before workspace patient switching',async t=>{
 const v=await fixture(t,'DoctorWorkspace',{},server({'/doctor-workspace/summary':()=>({data:{patients:[{id:1,name:'Synthetic patient'},{id:2,name:'Synthetic other patient'}]}}),'/doctor-workspace/notes':()=>({data:[]}),'/doctor-workspace/plans':()=>({data:[]})}));await v.click('new-collaboration-draft');v.vm.selectPatient({id:2,name:'Synthetic other patient'});await flush();assert.equal(v.vm.selectedPatientId,1);await v.click('close-editor');v.vm.selectPatient({id:2,name:'Synthetic other patient'});await flush();assert.equal(v.vm.selectedPatientId,2)
})

test('action-controls scoped slot receives current aggregate and explicit preserving async refresh',async t=>{
 let slot,n=0;const p={...copy(plan),currentRevisionId:23,revisionStatus:'PUBLISHED',draftRevisionId:null};const v=await fixture(t,'PlanDetail',{planId:17},server({'/care-plans/17':()=>({data:{...p,version:4+(n++)}})}),{'action-controls':scope=>{slot=scope;return h('button',{'data-testid':'external-receipt-control'},'Participant control')}});assert.equal(slot.plan.version,4);assert.equal(slot.action.version,99);v.vm.state.draft={note:'Keep local draft'};const result=await slot.refresh();await flush();assert.equal(result?.status,'succeeded');assert.deepEqual(v.vm.state.draft,{note:'Keep local draft'});assert.equal(slot.plan.version,5);assert.ok(findNode(v.root,'external-receipt-control'))
})
test('obsolete publication-preview error cannot write feedback into a replacement patient editor',async t=>{
 const pending=deferred(),p={...copy(plan),currentRevisionId:20,revisionImpact:{currentRevisionId:20,actionIds:[70],digest:'old-impact'}};const v=await fixture(t,'PlanEditor',{patientId:1,planId:17,revisionId:23},server({'/care-plans/17/revisions/23':()=>({data:p}),'/care-plans/17':()=>pending.promise}));const old=v.vm.preparePublish();await v.update({patientId:2,planId:null,revisionId:null});const replacementMessage=v.vm.message;pending.reject(new Error('Old patient sensitive detail'));await old;await flush();assert.doesNotMatch(textOf(v.root),/Old patient sensitive detail/);assert.equal(v.vm.message,replacementMessage)
})



test('queue renders localized action and plan status labels',async t=>{
 const p={...copy(plan),lifecycle:'ACTIVE',revisionStatus:'PUBLISHED',currentRevisionId:23};const v=await fixture(t,'PlanReviewQueue',{patientId:1},server({'/care-plans':()=>({data:{items:[p],nextCursor:null}})}));assert.match(textOf(v.root),/Published version|已发布版本/);assert.match(textOf(v.root),/Submitted, awaiting doctor review|已提交，等待医生复核/);assert.doesNotMatch(textOf(v.root),/\b(?:ACTIVE|PUBLISHED|SUBMITTED)\b/)
})
test('version history renders localized revision status labels',async t=>{
 const v=await fixture(t,'PlanDetail',{planId:17},server());assert.match(textOf(v.root),/Private draft|私有草稿/);assert.doesNotMatch(textOf(v.root),/\bDRAFT\b/)
})
test('revision preview renders localized superseded action status labels',async t=>{
 const p={...copy(plan),currentRevisionId:20,revisionImpact:{currentRevisionId:20,actionIds:[70],digest:'impact'}};const v=await fixture(t,'PlanEditor',{patientId:1,planId:17,revisionId:23},server({'/care-plans/17/revisions/23':()=>({data:p}),'/care-plans/17':()=>({data:{...p,actions:[{...action,id:70,status:'OPEN'}]}})}));await v.click('prepare-publish');assert.match(textOf(v.root),/Awaiting execution or more information|待执行或待补充信息/);assert.doesNotMatch(textOf(v.root),/\bOPEN\b/)
})
test('invalid explicit deadline keeps input and renders localized validation feedback',async t=>{
 const v=await fixture(t,'PlanEditor',{patientId:1,planId:17,revisionId:23},server());v.vm.state.draft.actions[0].dueAt='2026-10-04';await v.click('save-draft');assert.match(v.vm.message,/Invalid deadline: use an exact ISO time with offset\.|截止时间无效，请使用含偏移的准确 ISO 时间。/);assert.equal(v.vm.state.draft.actions[0].dueAt,'2026-10-04');assert.equal(posts(v).length,0)
})
test('invalid browser-local deadline keeps input and renders localized offset feedback',async t=>{
 const v=await fixture(t,'PlanEditor',{patientId:1,planId:17,revisionId:23},server());const original=v.vm.state.draft.actions[0].dueAt;v.vm.localClocks[0]='2026-02-30T12:00:00';v.vm.localOffsets[0]=0;v.vm.applyLocalTime(0);assert.match(v.vm.message,/Choose a valid browser-local time and explicit UTC offset\.|请选择有效的浏览器本地时间和明确的 UTC 偏移。/);assert.equal(v.vm.state.draft.actions[0].dueAt,original)
})

test('nurse follow-up protocol events render localized follow-up labels',async t=>{
 const p={...copy(plan),actions:[{...action,events:[{id:91,actorName:'Synthetic nurse',actorRole:'nurse',eventType:'FOLLOW_UP_RECORDED',recordedAt:'2026-10-04T12:00:00Z',note:'Synthetic follow-up'}]}]};const v=await fixture(t,'PlanDetail',{planId:17},server({'/care-plans/17':()=>({data:p})}));assert.match(textOf(v.root),/Care-team follow-up|照护团队跟进/);assert.doesNotMatch(textOf(v.root),/FOLLOW_UP_RECORDED/)
})

test('publication preview renders readable reference types and assignee roles',async t=>{
 const p={...copy(plan),currentRevisionId:20,revisionImpact:{currentRevisionId:20,actionIds:[70],digest:'impact'},actions:[{...action,evidence:[{sourceType:'MEASUREMENT',sourceId:7}]}]};const v=await fixture(t,'PlanEditor',{patientId:1,planId:17,revisionId:23},server({'/care-plans/17/revisions/23':()=>({data:p}),'/care-plans/17':()=>({data:{...p,actions:[{...action,id:70,status:'OPEN',evidence:[{sourceType:'MEDICAL_RECORD',sourceId:9}]}]}})}));await v.click('prepare-publish');const rendered=textOf(v.root);assert.match(rendered,/Measurement #7|测量 #7/);assert.match(rendered,/Medical record #9|医疗记录 #9/);assert.match(rendered,/Synthetic clinician · (?:Doctor|医生)/);assert.doesNotMatch(rendered,/MEASUREMENT|MEDICAL_RECORD|· doctor/)
})
test('receipt history renders readable server actor roles',async t=>{
 const p={...copy(plan),actions:[{...action,events:[{id:91,actorName:'Synthetic nurse',actorRole:'nurse',eventType:'FOLLOW_UP_RECORDED',recordedAt:'2026-10-04T12:00:00Z'}]}]};const v=await fixture(t,'PlanDetail',{planId:17},server({'/care-plans/17':()=>({data:p})}));assert.match(textOf(v.root),/Synthetic nurse · (?:Nurse|护士)/);assert.doesNotMatch(textOf(v.root),/Synthetic nurse · nurse/)
})

const denied=code=>Object.assign(new Error('Synthetic denial'),{response:{status:code}})
const revised=()=>({...copy(plan),currentRevisionId:20,revisionImpact:{currentRevisionId:20,actionIds:[31],digest:'current-impact'}})
const validNew=v=>Object.assign(v.vm.state.draft.actions[0],{instruction:'Synthetic action',dueAt:'2026-10-04T12:00:00Z',assignedUserId:51})
const published=()=>({...copy(plan),lifecycle:'ACTIVE',revisionStatus:'PUBLISHED',currentRevisionId:23,draftRevisionId:null,allowedActions:[]})
function valuesOf(node){return [node.value,node.props?.value,...(node.children||[]).flatMap(valuesOf)].filter(value=>typeof value==='string')}
for(const code of [401,403,404])test(`direct publication preview ${code} clears every clinical editor snapshot`,async t=>{
 const v=await fixture(t,'PlanEditor',{patientId:1,planId:17,revisionId:23},server({'/care-plans/17/revisions/23':()=>({data:revised()}),'/care-plans/17':()=>{throw denied(code)}}));await v.click('prepare-publish');assert.equal(v.vm.state.plan,null);assert.equal(v.vm.state.draft,null);assert.deepEqual(v.vm.assignees,[]);assert.equal(v.vm.savedSnapshot,'');assert.equal(v.vm.currentPublished,null);assert.equal(v.vm.impact,null);assert.equal(findNode(v.root,'save-draft'),undefined);assert.ok(!valuesOf(v.root).includes(plan.instructions))
})
test('obsolete preview denial cannot clear a replacement patient editor',async t=>{
 const pending=deferred();const v=await fixture(t,'PlanEditor',{patientId:1,planId:17,revisionId:23},server({'/care-plans/17/revisions/23':()=>({data:revised()}),'/care-plans/17':()=>pending.promise}));const old=v.vm.preparePublish();await v.update({patientId:2,planId:null,revisionId:null});v.vm.state.draft.title='Replacement input';const replacementMessage=v.vm.message;pending.reject(denied(403));await old;await flush();assert.equal(v.vm.state.draft.title,'Replacement input');assert.equal(v.vm.assignees.length,1);assert.equal(v.vm.message,replacementMessage)
})
test('transient preview failure preserves clinical input and authorized candidates',async t=>{
 const v=await fixture(t,'PlanEditor',{patientId:1,planId:17,revisionId:23},server({'/care-plans/17/revisions/23':()=>({data:revised()}),'/care-plans/17':()=>{throw Object.assign(new Error('Synthetic transient'),{response:{status:500}})}}));await v.click('prepare-publish');assert.equal(v.vm.state.draft.instructions,plan.instructions);assert.equal(v.vm.assignees.length,1);assert.ok(findNode(v.root,'save-draft'))
})
for(const shape of ['confirmed','removed'])test(`retained RETURN cannot submit after target becomes ${shape}`,async t=>{
 let n=0;const v=await fixture(t,'PlanDetail',{planId:17},server({'/care-plans/17':()=>({data:++n===1?published():{...published(),version:5,actions:shape==='removed'?[]:[{...action,status:'CONFIRMED',allowedActions:[]}]}})}));await v.click('review-return-31');v.vm.reviewNote='Keep this explanation';await v.click('reload-detail');assert.equal(v.vm.reviewNote,'Keep this explanation');await v.vm.sendReturn();assert.equal(posts(v).length,0);assert.ok(findNode(v.root,'send-return')?.props.disabled)
})
test('retained RETURN needs deliberate current-version recheck before rebasing its note',async t=>{
 let n=0;const v=await fixture(t,'PlanDetail',{planId:17},server({'/care-plans/17':()=>({data:{...published(),version:++n===1?4:5}})}));await v.click('review-return-31');v.vm.reviewNote='Keep this explanation';await v.click('reload-detail');await v.vm.sendReturn();assert.equal(posts(v).length,0);assert.equal(v.vm.reviewNote,'Keep this explanation');await v.click('recheck-return');await v.click('send-return');assert.equal(posts(v)[0].data.expectedVersion,5);assert.equal(posts(v)[0].data.note,'Keep this explanation')
})
test('retained RETURN is blocked in a historical revision while preserving its explanation',async t=>{
 const v=await fixture(t,'PlanDetail',{planId:17},server({'/care-plans/17':()=>({data:published()}),'/care-plans/17/revisions/22':()=>({data:{...published(),revisionId:22}})}));await v.click('review-return-31');v.vm.reviewNote='Keep current explanation';v.vm.historyRevisionId=22;await flush();await v.vm.sendReturn();assert.equal(posts(v).length,0);assert.equal(v.vm.reviewNote,'Keep current explanation');assert.ok(findNode(v.root,'send-return')?.props.disabled)
})
test('UNKNOWN creation actual close and remount blocks a new key until all history pages are checked',async t=>{
 let history=0,created=0;const v=await fixture(t,'PlanEditor',{patientId:1,legacySource:{id:9,title:'Copied title',instructions:'Copied instructions'}},server({'/care-plans':c=>{if(c.method==='post'){created++;throw new Error('Unknown create')}history++;return {data:{items:[],nextCursor:c.params.cursor?null:'next-page'}}}}));await checkInitialHistory(v);validNew(v);await v.click('save-draft');await v.click('close-editor');await v.click('confirm-abandon');assert.ok(v.events.some(e=>e[0]==='closed'));const beforeRemount=history;await v.unmount();await v.remount();validNew(v);await v.vm.save();assert.equal(created,1);assert.equal(history,beforeRemount+1);assert.equal(findNode(v.root,'acknowledge-create-history'),undefined);await v.click('more-create-history');await v.click('acknowledge-create-history');await v.click('save-draft');assert.equal(created,2);assert.equal(history,beforeRemount+2);assert.notEqual(posts(v)[0].data.commandKey,posts(v)[1].data.commandKey);assert.deepEqual(v.writes,[])
})
for(const code of [500,403])test(`failed ${code} HISTORY reconciliation remains guarded across another close/remount`,async t=>{
 let created=0,reads=0,allow=true;const v=await fixture(t,'PlanEditor',{patientId:1,legacySource:{id:9,title:'Copied title',instructions:'Copied instructions'}},server({'/care-plans':c=>{if(c.method==='post'){created++;allow=false;throw new Error('Unknown create')}reads++;if(!allow)throw denied(code);return {data:{items:[],nextCursor:null}}}}));await checkInitialHistory(v);validNew(v);await v.click('save-draft');await v.click('close-editor');await v.click('confirm-abandon');await v.unmount();await v.remount();if(code===403){assert.equal(v.vm.state.draft,null);assert.deepEqual(v.vm.assignees,[])}await v.vm.acknowledgeCreateHistory();await v.vm.save();assert.equal(created,1);assert.equal(findNode(v.root,'acknowledge-create-history'),undefined);await v.click('close-editor');await v.unmount();await v.remount();await v.vm.save();assert.equal(created,1);allow=true;await v.click('retry-create-history');validNew(v);await v.click('acknowledge-create-history');await v.click('save-draft');assert.equal(created,2);assert.ok(reads>=3)
})
test('failed continuation cannot acknowledge incomplete uncertain-create history',async t=>{
 let created=0,failNext=false;const v=await fixture(t,'PlanEditor',{patientId:1,legacySource:{id:9,title:'Copied title',instructions:'Copied instructions'}},server({'/care-plans':c=>{if(c.method==='post'){created++;failNext=true;throw new Error('Unknown create')}if(c.params.cursor&&failNext)throw denied(500);return {data:{items:[],nextCursor:c.params.cursor?null:'next-page'}}}}));await checkInitialHistory(v);validNew(v);await v.click('save-draft');await v.click('close-editor');await v.click('confirm-abandon');await v.unmount();await v.remount();await v.click('more-create-history');await v.vm.acknowledgeCreateHistory();await v.vm.save();assert.equal(created,1);failNext=false;await v.click('more-create-history');await v.click('acknowledge-create-history');validNew(v);await v.click('save-draft');assert.equal(created,2)
})

for(const code of [200,500,403])test(`fresh new-editor startup ${code} reads authoritative history before any create`,async t=>{
 let reads=0,created=0;const v=await fixture(t,'PlanEditor',{patientId:1,legacySource:{id:9,title:'Copied title',instructions:'Copied instructions'}},server({'/care-plans':c=>{if(c.method==='post'){created++;return {data:plan}}reads++;if(code!==200)throw denied(code);return {data:{items:[],nextCursor:null}}}}));assert.equal(reads,1);await v.vm.save();assert.equal(created,0);if(code===200){assert.ok(findNode(v.root,'acknowledge-create-history'));validNew(v);await v.click('acknowledge-create-history');await v.click('save-draft');assert.equal(created,1)}else{assert.equal(findNode(v.root,'acknowledge-create-history'),undefined);if(code===403){assert.equal(v.vm.state.draft,null);assert.deepEqual(v.vm.assignees,[])}}
})
test('malformed history success does not authorize a fresh creation',async t=>{
 const v=await fixture(t,'PlanEditor',{patientId:1,legacySource:{id:9,title:'Copied title',instructions:'Copied instructions'}},server({'/care-plans':()=>({data:{items:[],nextCursor:''}})}));await v.vm.acknowledgeCreateHistory();validNew(v);await v.vm.save();assert.equal(posts(v).length,0);assert.equal(findNode(v.root,'acknowledge-create-history'),undefined)
})
test('obsolete startup history denial preserves replacement patient input',async t=>{
 const old=deferred();const v=await fixture(t,'PlanEditor',{patientId:1,legacySource:{id:9,title:'Copied title',instructions:'Copied instructions'}},server({'/care-plans':c=>c.params.patientId===1?old.promise:Promise.resolve({data:{items:[],nextCursor:null}})}));assert.ok(v.calls.some(c=>c.url==='/care-plans'&&c.params.patientId===1));await v.update({patientId:2});v.vm.state.draft.title='Replacement patient';old.reject(denied(403));await flush();assert.equal(v.vm.state.draft.title,'Replacement patient');assert.equal(v.vm.assignees.length,1);assert.ok(findNode(v.root,'acknowledge-create-history'))
})

for(const errorKind of ['cancelled','cursor-error'])test(`${errorKind} history continuation cannot release the new-create gate`,async t=>{
 const v=await fixture(t,'PlanEditor',{patientId:1,legacySource:{id:9,title:'Copied title',instructions:'Copied instructions'}},server({'/care-plans':c=>{if(c.params.cursor)throw errorKind==='cancelled'?Object.assign(new Error('Cancelled read'),{code:'ERR_CANCELED',notDispatched:true}):denied(409);return {data:{items:[],nextCursor:'next-page'}}}}));await v.click('more-create-history');await v.vm.acknowledgeCreateHistory();validNew(v);await v.vm.save();assert.equal(posts(v).length,0);assert.equal(findNode(v.root,'acknowledge-create-history'),undefined);assert.equal(v.vm.state.draft.title,'Copied title')
})
test('history reload after silent actor replacement cannot restore the old actor draft',async t=>{
 const v=await fixture(t,'PlanEditor',{patientId:1,legacySource:{id:9,title:'Old actor clinical title',instructions:'Old actor clinical instructions'}},server());await checkInitialHistory(v);const sent=v.calls.length;v.storage.set('userId','52');await v.vm.requestReload();await flush();assert.equal(v.calls.length,sent);assert.equal(v.vm.state.draft,null);assert.deepEqual(v.vm.assignees,[]);assert.equal(v.vm.savedSnapshot,'')
})
