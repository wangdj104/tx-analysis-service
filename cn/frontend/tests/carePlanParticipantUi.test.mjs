import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import { parse, compileScript, compileTemplate } from '@vue/compiler-sfc'
import { createRenderer, h, nextTick } from 'vue'
const source = name => fs.readFileSync(new URL(`../src/${name}`, import.meta.url), 'utf8')
const dataModule = code => `data:text/javascript;base64,${Buffer.from(code).toString('base64')}`
const vueUrl = new URL('../node_modules/vue/dist/vue.runtime.esm-bundler.js', import.meta.url).href
let fixtureId = 0
function deferred() { let resolve, reject; const promise = new Promise((a,b) => {resolve=a;reject=b}); return {promise,resolve,reject} }
async function flush() { for(let i=0;i<12;i++){await Promise.resolve();await nextTick()} }
function findNode(node, testId) { if(node.props?.['data-testid']===testId)return node; for(const child of node.children||[]){const found=findNode(child,testId);if(found)return found} }
function textOf(node) { return (node.text||'')+(node.children||[]).map(textOf).join(' ') }
async function fixture(t, name, props, transport) {
  const file=name.includes('/')?`${name}.vue`:`components/care-plan/${name}.vue`
  assert.ok(fs.existsSync(new URL(`../src/${file}`,import.meta.url)),`${name} care-plan component exists`)
  const storage = new Map([['token','synthetic-token'],['userId','51'],['realName','Synthetic actual actor'],['userRoleCodes','["nurse"]'],['userMenus','["/family-health"]'],['userMenuNames','[]'],['permissionSession','synthetic-token']]), writes=[]
  globalThis.localStorage={getItem:k=>storage.get(k)??null,setItem:(k,v)=>{writes.push([k,v]);storage.set(k,String(v))},removeItem:k=>storage.delete(k),key:i=>[...storage.keys()][i],get length(){return storage.size}}
  globalThis.window=new EventTarget();window.setInterval=setInterval;window.clearInterval=clearInterval;globalThis.document={body:{classList:{toggle(){}}},activeElement:null};globalThis.CustomEvent=class extends Event{constructor(type,opts){super(type,opts);this.detail=opts?.detail}}
  globalThis.Document=class Document {}; globalThis.ShadowRoot=class ShadowRoot {}
  const calls=[],slot=`__doctorTransport${++fixtureId}`
  globalThis[slot]=config=>{calls.push(config);return transport(config)}
  const authUrl=new URL(`../src/utils/authSession.js?doctor=${fixtureId}`,import.meta.url).href
  const apiUrl=dataModule(source('api/carePlan.js').replace(/import request from ['"]@\/utils\/request['"]/g,`const request = globalThis.${slot}`).replace(/from ['"]@\/utils\/authSession['"]/g,`from '${authUrl}'`))
  const doctorApiUrl=dataModule(source('api/doctorWorkspace.js').replace(/import request from ['"]@\/utils\/request['"]/g,`const request = globalThis.${slot}`))
  const timeUrl=new URL('../src/utils/carePlanTime.js',import.meta.url).href
  const composableUrl=dataModule(source('composables/useCarePlan.js').replace(/from ['"]vue['"]/g,`from '${vueUrl}'`).replace(/from ['"]@\/api\/carePlan['"]/g,`from '${apiUrl}'`).replace(/from ['"]@\/utils\/authSession['"]/g,`from '${authUrl}'`).replace(/from ['"]@\/utils\/carePlanTime['"]/g,`from '${timeUrl}'`)+`\n// ${fixtureId}`)
  const patientUrl=dataModule(`import {ref,computed} from '${vueUrl}'; const selected=ref(1),list=ref([]); export function useCurrentPatient(){return {currentPatientId:selected,currentPatientName:computed(()=> 'Synthetic patient'),setPatientList:x=>{list.value=x},patientList:list}}\n// ${fixtureId}`)
  const epUrl=dataModule(`export const ElMessage={success(){},warning(){},info(){}};export const ElMessageBox={prompt:async()=>null,confirm:async()=>true}`)
  const routerUrl=dataModule(`export const useRoute=()=>({query:{tab:'${name==='views/CareJourneyManager'?'privacy':name==='views/FamilyHealthManager'?'timeline':''}'},params:{id:'17'}});export const useRouter=()=>({push(){},replace(){}});export const onBeforeRouteLeave=()=>{}; export const onBeforeRouteUpdate=()=>{}`)
  const apiModule=path=>dataModule(source(path).replace(/import request from ['"]@\/utils\/request['"];?/g,`const request=globalThis.${slot}`))
  const userUrl=apiModule('api/user.js'),careUrl=apiModule('api/care.js'),journeyUrl=apiModule('api/careJourney.js'),familyUrl=apiModule('api/familyHealth.js')
  const emptyUrl=dataModule(`export default {render(){return null}}`)
  const urls={}
  async function compile(componentName){
    if(urls[componentName])return urls[componentName]
    const filename=componentName.includes('/')?`${componentName}.vue`:`components/care-plan/${componentName}.vue`,id=`doctor-${fixtureId}-${componentName}`,{descriptor}=parse(source(filename),{filename})
    const script=compileScript(descriptor,{id}),template=compileTemplate({source:descriptor.template.content,filename,id,compilerOptions:{bindingMetadata:script.bindings,hoistStatic:false}})
    assert.deepEqual(template.errors,[],`${componentName} compiles`)
    let code=script.content.replace(/export default/,'const __component =')+'\n'+template.code.replace('function render','function render')+'\n__component.render=render; export default __component;'
    code=code.replace(/from ['"]vue['"]/g,`from '${vueUrl}'`).replace(/from ['"]@\/api\/carePlan['"]/g,`from '${apiUrl}'`).replace(/from ['"]@\/composables\/useCarePlan['"]/g,`from '${composableUrl}'`).replace(/from ['"]@\/utils\/authSession['"]/g,`from '${authUrl}'`).replace(/from ['"]@\/utils\/carePlanTime['"]/g,`from '${timeUrl}'`)
    code=code.replace(/from ['"]@\/api\/doctorWorkspace['"]/g,`from '${doctorApiUrl}'`).replace(/import \{ ElMessage, ElMessageBox \} from ['"]element-plus['"];?/g,`const ElMessage={success(){},warning(){}}; const ElMessageBox={prompt:async()=>({value:''})};`)
    code=code.replace(/import request from ['"]@\/utils\/request['"]/g,`const request=globalThis.${slot}`)
    code=code.replace(/from ['"]@\/api\/familyHealth['"]/g,`from '${familyUrl}'`).replace(/from ['"]@\/utils\/workspaceAccess['"]/g,`from '${new URL('../src/utils/workspaceAccess.js',import.meta.url).href}'`)
    code=code.replace(/from ['"]@\/api\/careJourney['"]/g,`from '${journeyUrl}'`).replace(/from ['"]@\/components\/ConsultationWorkspace\.vue['"]/g,`from '${emptyUrl}'`)
    code=code.replace(/from ['"]@\/api\/user(?:\.js)?['"]/g,`from '${userUrl}'`).replace(/from ['"]@\/api\/care['"]/g,`from '${careUrl}'`).replace(/from ['"]@\/composables\/useCurrentPatient['"]/g,`from '${patientUrl}'`).replace(/from ['"]element-plus['"]/g,`from '${epUrl}'`).replace(/from ['"]vue-router['"]/g,`from '${routerUrl}'`).replace(/import (\w+) from ['"]@\/assets\/[^'"]+['"]/g,(m,n)=>`const ${n}='synthetic-art'`).replace(/from ['"]@\/utils\/familyHealth['"]/g,`from '${new URL('../src/utils/familyHealth.js',import.meta.url).href}'`).replace(/from ['"]@\/utils\/timelineText['"]/g,`from '${new URL('../src/utils/timelineText.js',import.meta.url).href}'`)
    for(const match of code.matchAll(/from ['"]@\/components\/care-plan\/(\w+)\.vue['"]/g))code=code.replace(match[0],`from '${await compile(match[1])}'`)
    code=code.replace(/from ['"]@\/components\/(?:CareEntryDialog|FamilyBackupPanel)\.vue['"]/g,`from '${emptyUrl}'`)
    for(const match of code.matchAll(/from ['"]@\/components\/care-plan\/(\w+)\.vue['"]/g))code=code.replace(match[0],`from '${await compile(match[1])}'`)
    code=code.replace(/from ['"]@\/api\/user(?:\.js)?['"]/g,`from '${userUrl}'`).replace(/from ['"]@\/api\/care['"]/g,`from '${careUrl}'`).replace(/from ['"]@\/composables\/useCurrentPatient['"]/g,`from '${patientUrl}'`).replace(/from ['"]element-plus['"]/g,`from '${epUrl}'`).replace(/from ['"]vue-router['"]/g,`from '${routerUrl}'`).replace(/import (\w+) from ['"]@\/assets\/[^'"]+['"]/g,(m,n)=>`const ${n}='synthetic-art'`).replace(/from ['"]@\/utils\/familyHealth['"]/g,`from '${new URL('../src/utils/familyHealth.js',import.meta.url).href}'`).replace(/from ['"]@\/utils\/timelineText['"]/g,`from '${new URL('../src/utils/timelineText.js',import.meta.url).href}'`)
    for(const match of code.matchAll(/from ['"]@\/components\/care-plan\/(\w+)\.vue['"]/g))code=code.replace(match[0],`from '${await compile(match[1])}'`)
    code=code.replace(/from ['"]@\/components\/(?:CareEntryDialog|FamilyBackupPanel)\.vue['"]/g,`from '${emptyUrl}'`)
    for(const match of code.matchAll(/from ['"]\.\/(\w+)\.vue['"]/g))code=code.replace(match[0],`from '${await compile(match[1])}'`)
    urls[componentName]=dataModule(code);return urls[componentName]
  }
  const node=(tag,text='')=>({tag,tagName:tag.toUpperCase(),text,children:[],props:{},parent:null,style:{},addEventListener(){},removeEventListener(){},getRootNode(){return {}},getAttribute(k){return this.props[k]},setAttribute(k,v){this.props[k]=v},removeAttribute(k){delete this.props[k]},get options(){return this.children}})
  const renderer=createRenderer({createElement:tag=>node(tag),createText:s=>node('#text',s),createComment:s=>node('#comment',s),setText:(n,s)=>{n.text=s},setElementText:(n,s)=>{n.text=s;n.children=[]},parentNode:n=>n.parent,nextSibling:n=>n.parent?.children[n.parent.children.indexOf(n)+1]||null,patchProp:(n,k,o,v)=>{n.props[k]=v;if(k==='value')n.value=v},insert:(n,p,a)=>{if(n.parent){const i=n.parent.children.indexOf(n);if(i>=0)n.parent.children.splice(i,1)}n.parent=p;const i=a?p.children.indexOf(a):-1;p.children.splice(i<0?p.children.length:i,0,n)},remove:n=>{if(n.parent)n.parent.children.splice(n.parent.children.indexOf(n),1)}})
  const root=node('root'),component=(await import(await compile(name))).default,events=[]
  let currentProps={...props,onSaved:data=>events.push(['saved',data]),onClosed:()=>events.push(['closed']),onChanged:data=>events.push(['changed',data]),onOpen:id=>events.push(['open',id]),onSubmitted:data=>events.push(['submitted',data])}
  const app=renderer.createApp({render(){return null}})
  for(const tag of new Set(['el-collapse','el-collapse-item','el-slider','el-timeline','el-timeline-item','el-checkbox-group','el-checkbox','el-row','el-col','el-card','el-divider','el-date-picker','el-time-picker','el-container','el-main','el-header','el-radio-button','el-switch','el-alert','el-calendar','el-progress','el-popconfirm','el-input-number','el-button','el-icon','el-tabs','el-tab-pane','el-table','el-table-column','el-tag','el-empty','el-dialog','el-form','el-form-item','el-select','el-option','el-input','el-radio-group','el-radio','Refresh']))app.component(tag,{setup(p,{slots}){return ()=>h('div',{},tag==='el-table-column'?[]:slots.default?.())}})
  // Preserve the anchor presentation in this component-only fixture; real router/base behavior is covered separately.
  app.component('router-link',{props:['to'],setup(p,{slots}){return ()=>h('a',{href:p.to},slots.default?.())}})
  app.directive('loading',{})
  const render=()=>{const vnode=h(component,currentProps);vnode.appContext=app._context;renderer.render(vnode,root)}
  render();await flush()
  t.after(()=>{renderer.render(null,root);delete globalThis[slot]})
  return {root,calls,events,storage,writes,patient:await import(patientUrl),auth:await import(authUrl),get vm(){return root._vnode.component.setupState},async update(p){currentProps={...currentProps,...p};render();await flush()},async click(id){await flush();const target=findNode(root,id);assert.ok(target,`${id} control rendered`);assert.ok(!target.props.disabled,`${id} control enabled`);await target.props.onClick?.({preventDefault(){}});await flush()}}
}

const action={id:31,planId:17,patientId:1,revisionId:23,ordinal:1,assignedUserId:51,instruction:'Synthetic action',dueAt:'2026-10-04T12:00:00Z',status:'OPEN',version:99,evidence:[],events:[],allowedActions:['SUBMIT_RECEIPT','REQUEST_HELP','FOLLOW_UP'],allowedEntryModes:['ASSISTED','SELF']}
const plan={id:17,patientId:1,title:'Synthetic plan',instructions:'Synthetic instructions',planType:'FOLLOW_UP',revisionId:23,revisionNo:1,revisionStatus:'PUBLISHED',currentRevisionId:23,version:4,lifecycle:'ACTIVE',actions:[action],allowedActions:[]}
const copy=x=>JSON.parse(JSON.stringify(x))
function server(overrides={}){return async c=>{if(overrides[c.url])return overrides[c.url](c);if(c.url.endsWith('/capabilities'))return {code:200,data:{enabled:true}};if(c.url==='/care/home')return {data:[{patient:{id:1,name:'Synthetic patient'}}]};if(c.url==='/care/context')throw Object.assign(new Error('Full context denied'),{response:{status:403}});if(c.url==='/care-plans')return {data:{items:[copy(plan)],nextCursor:null}};if(c.url==='/care-nurse-assignments')return {data:[{id:8,patientId:1,nurseUserId:51,nurseName:'Synthetic nurse',status:'ACTIVE',assignedAt:'2026-10-03T01:00:00Z',expiresAt:null}]};if(c.method==='post')return {data:{planId:17,eventId:40,version:5,lifecycle:'ACTIVE'}};return {data:copy(plan)}}}
const posts=v=>v.calls.filter(c=>c.method==='post')

test('moduleOnlyCareWorksWhenFullContextDenied: mounted CareCenter keeps independent tasks after legacy 403',async t=>{let denied=false;const v=await fixture(t,'views/CareCenter',{},server({'/care/context':()=>{if(denied)throw Object.assign(new Error('denied'),{response:{status:403}});return {data:{}}}}));denied=true;await v.vm.reload().catch(()=>{});await flush();assert.ok(v.calls.some(c=>c.url==='/care/context'));assert.ok(v.calls.some(c=>c.url==='/care-plans'));assert.match(textOf(v.root),/Synthetic plan/);assert.ok(findNode(v.root,'legacy-care-restricted'))})
test('readOnlyCannotSubmit: rendered control absent and forged direct submit gets API denial with body cleared',async t=>{const readonly={...action,allowedActions:[],allowedEntryModes:[]};const v=await fixture(t,'ReceiptDialog',{action:readonly,planVersion:4},server());assert.equal(findNode(v.root,'submit-receipt'),undefined);v.vm.form.note='Synthetic note';await v.vm.submit();assert.equal(posts(v).length,0);const denied=await fixture(t,'ReceiptDialog',{action,planVersion:4},server({'/care-plans/actions/31/receipts':()=>{throw Object.assign(new Error('denied'),{response:{status:403}})}}));denied.vm.form.note='Synthetic note';await denied.click('submit-receipt');assert.equal(denied.vm.state.draft,null);assert.equal(findNode(denied.root,'submit-receipt'),undefined)})
test('assistedEntryShowsActualActor: defaults ASSISTED and sends aggregate version instead of action version',async t=>{const v=await fixture(t,'ReceiptDialog',{action,planVersion:4},server());assert.match(textOf(v.root),/Synthetic actual actor/);assert.equal(v.vm.form.entryMode,'ASSISTED');v.vm.form.note='Synthetic assisted execution';await v.click('submit-receipt');const submitted=posts(v)[0].data;assert.equal(submitted.entryMode,'ASSISTED');assert.equal(submitted.expectedVersion,4);assert.equal(submitted.actorId,undefined);assert.equal(submitted.evidence.length,0);assert.equal(v.events.filter(e=>e[0]==='submitted').length,1)})
test('returnedReceiptRetainsOriginalTimeliness: original timely submission stays distinct from current overdue',async t=>{const p={...copy(plan),actions:[{...action,overdue:true,firstSubmittedAt:'2026-10-04T11:00:00Z',latestSubmittedAt:'2026-10-04T11:00:00Z',events:[{id:3,actorName:'Synthetic nurse',actorRole:'nurse',entryMode:'ASSISTED',eventType:'RECEIPT_RETURNED',note:'More detail',recordedAt:'2026-10-05T10:00:00Z'}]}]};const v=await fixture(t,'PlanTaskList',{patientId:1,mode:'FAMILY'},server({'/care-plans':()=>({data:{items:[p],nextCursor:null}})}));assert.match(textOf(v.root),/Originally submitted by the deadline|原始提交按时/);assert.match(textOf(v.root),/Currently overdue|当前逾期/);assert.match(textOf(v.root),/Synthetic nurse/);assert.ok(findNode(v.root,'open-plan-17'))})
test('UNKNOWN explicitly retries frozen original and preserves newer form; abandonment warns then reloads',async t=>{let n=0;const v=await fixture(t,'ReceiptDialog',{action,planVersion:4},server({'/care-plans/actions/31/receipts':()=>{if(++n===1)throw new Error('uncertain');return {data:{planId:17,eventId:40,version:5}}}}));v.vm.form.note='Original note';await v.click('submit-receipt');assert.equal(v.vm.state.commandPhase,'unknown');v.vm.form.note='Newer note';await v.click('retry-original');assert.deepEqual(posts(v)[0].data,posts(v)[1].data);assert.equal(v.vm.form.note,'Newer note');assert.equal(v.events.some(e=>e[0]==='closed'),false);assert.ok(v.calls.some(c=>c.url==='/care-plans/17'));const u=await fixture(t,'ReceiptDialog',{action,planVersion:4},server({'/care-plans/actions/31/receipts':()=>{throw new Error('uncertain')}}));u.vm.form.note='Original';await u.click('submit-receipt');await u.click('close-receipt');assert.equal(u.events.length,0);assert.ok(findNode(u.root,'confirm-abandon'));await u.click('confirm-abandon');assert.ok(u.calls.some(c=>c.url==='/care-plans/17'));assert.ok(u.events.some(e=>e[0]==='closed'))})
test('receipt validates code-point bounds explicit offset future time duplicate and maximum evidence',async t=>{const v=await fixture(t,'ReceiptDialog',{action,planVersion:4},server());for(const mutate of [f=>f.note='😀'.repeat(2001),f=>f.occurredAt='2026-10-03T09:00',f=>f.occurredAt='9999-12-31T23:59:59Z',f=>f.evidence=[{sourceType:'MEASUREMENT',sourceId:1},{sourceType:'MEASUREMENT',sourceId:1}],f=>f.evidence=Array.from({length:6},(_,i)=>({sourceType:'MEASUREMENT',sourceId:i+1}))]){Object.assign(v.vm.form,{note:'Synthetic note',occurredAt:new Date().toISOString(),entryMode:'ASSISTED',evidence:[]});mutate(v.vm.form);await v.vm.submit();assert.equal(posts(v).length,0)}})
test('feature false hides task entry and submits no command or clinical list',async t=>{const v=await fixture(t,'PlanTaskList',{patientId:1},server({'/care-plans/capabilities':()=>({data:{enabled:false}})}));assert.equal(findNode(v.root,'collaboration-tasks'),undefined);assert.equal(v.calls.filter(c=>c.url==='/care-plans').length,0)})
test('patient and auth changes clear task text and reject obsolete list responses',async t=>{const d=deferred();let n=0;const v=await fixture(t,'PlanTaskList',{patientId:1},server({'/care-plans':()=>++n===1?{data:{items:[plan]}}:n===2?d.promise:{data:{items:[{...plan,patientId:2,title:'Current patient title'}]}}}));const pending=v.vm.reload();await v.update({patientId:2});d.resolve({data:{items:[{...plan,title:'Obsolete patient title'}]}});await pending;await flush();assert.doesNotMatch(textOf(v.root),/Obsolete patient title/);v.auth.clearAuthSession();await flush();assert.equal(v.vm.state.items.length,0);assert.doesNotMatch(textOf(v.root),/Synthetic plan/)})
test('restricted evidence never renders supplied title or unsafe links',async t=>{const p={...plan,actions:[{...action,evidence:[{sourceType:'MEDICAL_RECORD',sourceId:8,restricted:true,title:'Private evidence',detailLink:'/secret'},{sourceType:'MEASUREMENT',sourceId:9,restricted:false,title:'Allowed reference',detailLink:'//evil.test'}]}]};const v=await fixture(t,'PlanTaskList',{patientId:1},server({'/care-plans':()=>({data:{items:[p]}})}));assert.doesNotMatch(textOf(v.root),/Private evidence/);const walk=n=>[n,...(n.children||[]).flatMap(walk)];assert.equal(walk(v.root).filter(n=>n.tag==='a'&&n.props.href==='//evil.test').length,0)})

test('timeline uses authorized carePlanId locator and never treats event sourceId as plan ID',async t=>{const v=await fixture(t,'views/FamilyHealthManager',{},server({'/family-health/intakes':()=>({data:[]}),'/family-health/timeline':()=>({data:[]}),'/family-health/dialysis-schedules':()=>({data:[]}),'/family-health/target':()=>({data:{}})}));assert.equal(typeof v.vm.safePlanLink,'function','safe care-plan event locator exists');assert.equal(v.vm.safePlanLink({sourceType:'CARE_PLAN_EVENT',sourceId:99,carePlanId:17}),'/care-plans/17');for(const event of [{sourceType:'CARE_PLAN_EVENT',sourceId:17},{sourceType:'CARE_PLAN_EVENT',carePlanId:'//evil.test'},{sourceType:'CARE_PLAN_EVENT',carePlanId:0},{sourceType:'MEDICAL_RECORD',sourceId:17,carePlanId:17}])assert.equal(v.vm.safePlanLink(event),'')})

test('original timely display respects microseconds, and pending context changes require explicit warning',async t=>{const v=await fixture(t,'PlanTaskList',{patientId:1},server());assert.equal(v.vm.originalTiming({...action,dueAt:'2026-10-04T12:00:00.123456Z',firstSubmittedAt:'2026-10-04T12:00:00.123457Z'}).includes('after')||v.vm.originalTiming({...action,dueAt:'2026-10-04T12:00:00.123456Z',firstSubmittedAt:'2026-10-04T12:00:00.123457Z'}).includes('晚于'),true);const d=deferred(),r=await fixture(t,'ReceiptDialog',{action,planVersion:4},server({'/care-plans/actions/31/receipts':()=>d.promise}));r.vm.form.note='Pending synthetic note';const command=r.vm.submit();await flush();window.confirm=()=>false;const event=new Event('care-plan-before-context-change',{cancelable:true});window.dispatchEvent(event);assert.equal(event.defaultPrevented,true);assert.equal(posts(r).length,1);await r.click('close-receipt');assert.ok(findNode(r.root,'confirm-abandon'));d.resolve({data:{planId:17,eventId:40,version:5}});await command})


test('detail route consumes the frozen action-controls slot without an extra plan read and uses aggregate version',async t=>{
  const v=await fixture(t,'views/CarePlanDetailRoute',{},server())
  assert.equal(v.calls.filter(c=>c.url==='/care-plans/17').length,1)
  await v.click('route-record-31')
  assert.equal(v.vm.selected.version,4)
  const note=findNode(v.root,'receipt-note')
  assert.equal(typeof note.props['onUpdate:modelValue'],'function')
  note.props['onUpdate:modelValue']('Synthetic route receipt')
  await v.click('submit-receipt')
  assert.equal(posts(v)[0].data.expectedVersion,4)
  assert.equal(v.calls.filter(c=>c.url==='/care-plans/17').length,2)
  assert.equal(v.vm.selected,null)
})

test('one task receipt success refreshes its queue once despite submitted and closed events',async t=>{
  const v=await fixture(t,'PlanTaskList',{patientId:1},server())
  await v.click('record-31')
  findNode(v.root,'receipt-note').props['onUpdate:modelValue']('Synthetic list receipt')
  await v.click('submit-receipt')
  assert.equal(v.calls.filter(c=>c.url==='/care-plans').length,2,'initial load and exactly one refresh')
})

test('participant event actor roles use localized display labels instead of protocol codes',async t=>{
  const p={...plan,actions:[{...action,events:[{id:3,eventType:'FOLLOW_UP_RECORDED',actorName:'Synthetic nurse',actorRole:'nurse',entryMode:'ASSISTED',note:'Synthetic follow-up',recordedAt:'2026-10-03T09:00:00Z'}]}]}
  const v=await fixture(t,'PlanTaskList',{patientId:1},server({'/care-plans':()=>({data:{items:[p]}})}))
  const cn=import.meta.url.includes('/cn/')
  assert.match(textOf(v.root),cn?/护理人员/:/Nurse/)
  assert.doesNotMatch(textOf(v.root),/ · nurse/)
})


test('CARE_PLAN_EVENT timeline renders a UTC instant across midnight in the browser zone and preserves legacy timestamps',async t=>{
  const originalZone=process.env.TZ;process.env.TZ='America/New_York'
  t.after(()=>{if(originalZone===undefined)delete process.env.TZ;else process.env.TZ=originalZone})
  const rows=[
    {id:99,sourceId:99,sourceType:'CARE_PLAN_EVENT',carePlanId:17,eventDate:'2026-10-03',eventTime:'01:02Z',eventType:'RECEIPT_SUBMITTED',title:'Synthetic care update'},
    {id:10,sourceId:10,sourceType:'MANUAL',eventDate:'2026-10-03',eventTime:'01:02:00',eventType:'NOTE',title:'Synthetic legacy note'},
    {id:11,sourceId:11,sourceType:'MEASUREMENT',eventDate:'2026-10-03',eventTime:'01:02:00',eventType:'MEASUREMENT',title:'Synthetic legacy measurement'}
  ]
  const v=await fixture(t,'views/FamilyHealthManager',{},server({'/family-health/intakes':()=>({data:[]}),'/family-health/timeline':()=>({data:rows}),'/family-health/dialysis-schedules':()=>({data:[]}),'/family-health/target':()=>({data:{}})}))
  const walk=n=>[n,...(n.children||[]).flatMap(walk)],stamps=walk(v.root).filter(n=>n.props.timestamp).map(n=>n.props.timestamp)
  assert.equal(stamps.length,3)
  assert.match(stamps[0],/10\/02\/2026|2026\/10\/02/,'UTC October 3 is local October 2')
  assert.match(stamps[0],/09:02:00 PM|21:02:00/)
  assert.match(stamps[0],/UTC-04:00, America\/New_York/)
  assert.deepEqual(stamps.slice(1),['2026-10-03 01:02:00','2026-10-03 01:02:00'])
  assert.ok(walk(v.root).some(n=>n.tag==='a'&&n.props.href==='/care-plans/17'))
})

test('CARE_PLAN_EVENT timeline displays localized invalid-time feedback without guessing an offset or altering legacy missing clocks',async t=>{
  const invalid=[{eventDate:'2026-10-03'},{eventTime:'01:02Z'},{eventDate:'2026-10-03',eventTime:'01:02'},{eventDate:'2026-02-30',eventTime:'01:02Z'},{eventDate:'invalid',eventTime:'01:02Z'}]
  const rows=invalid.map((value,i)=>({id:100+i,sourceId:100+i,sourceType:'CARE_PLAN_EVENT',carePlanId:17,eventType:'RECEIPT_SUBMITTED',title:'Synthetic invalid timestamp',...value}))
  rows.push({id:10,sourceId:10,sourceType:'MANUAL',eventDate:'2026-10-03',eventType:'NOTE',title:'Synthetic legacy missing clock'})
  const v=await fixture(t,'views/FamilyHealthManager',{},server({'/family-health/intakes':()=>({data:[]}),'/family-health/timeline':()=>({data:rows}),'/family-health/dialysis-schedules':()=>({data:[]}),'/family-health/target':()=>({data:{}})}))
  const walk=n=>[n,...(n.children||[]).flatMap(walk)],stamps=walk(v.root).filter(n=>n.props.timestamp).map(n=>n.props.timestamp)
  assert.equal(stamps.length,6)
  const feedback=import.meta.url.includes('/cn/')?'照护计划事件时间无效或缺失':'Care-plan event time is invalid or missing'
  assert.deepEqual(stamps.slice(0,5),Array(5).fill(feedback))
  assert.equal(stamps[5],'2026-10-03 ')
})
