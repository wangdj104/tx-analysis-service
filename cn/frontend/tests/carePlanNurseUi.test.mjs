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
async function fixture(t, name, props, transport, {realRequest=false}={}) {
  const file=name.includes('/')?`${name}.vue`:`components/care-plan/${name}.vue`
  assert.ok(fs.existsSync(new URL(`../src/${file}`,import.meta.url)),`${name} care-plan component exists`)
  const storage = new Map([['token','synthetic-token'],['userId','51'],['realName','Synthetic actual actor'],['userRoleCodes','["nurse"]'],['userMenus','["/family-health"]'],['userMenuNames','[]'],['permissionSession','synthetic-token']]), writes=[]
  globalThis.localStorage={getItem:k=>storage.get(k)??null,setItem:(k,v)=>{writes.push([k,v]);storage.set(k,String(v))},removeItem:k=>storage.delete(k),key:i=>[...storage.keys()][i],get length(){return storage.size}}
  globalThis.window=new EventTarget();window.setInterval=setInterval;window.clearInterval=clearInterval;globalThis.document={body:{classList:{toggle(){}}},activeElement:null};globalThis.CustomEvent=class extends Event{constructor(type,opts){super(type,opts);this.detail=opts?.detail}}
  globalThis.Document=class Document {}; globalThis.ShadowRoot=class ShadowRoot {}
  const calls=[],messages=[],slot=`__doctorTransport${++fixtureId}`,messageSlot=`__participantMessages${fixtureId}`
  globalThis[messageSlot]=messages
  window.location={pathname:"/care-journey",href:"https://synthetic.test/care-journey"}
  globalThis[slot]=config=>{calls.push(config);return transport(config)}
  const authUrl=new URL(`../src/utils/authSession.js?doctor=${fixtureId}`,import.meta.url).href
  let request=null
  if(realRequest){
    const requestUrl=dataModule(source('utils/request.js').replace(/from ['"]axios['"]/g,`from '${new URL('../node_modules/axios/index.js',import.meta.url).href}'`).replace(/import \{ ElMessage \} from ['"]element-plus['"];?/g,`const ElMessage={error:text=>globalThis.${messageSlot}.push({kind:'error',text})}`).replace(/from ['"]@\/utils\/authSession['"]/g,`from '${authUrl}'`).replace(/from ['"]@\/utils\/serverText['"]/g,`from '${new URL('../src/utils/serverText.js',import.meta.url).href}'`))
    request=(await import(requestUrl)).default
    request.defaults.adapter=async config=>{
      const observed={...config,data:typeof config.data==='string'?JSON.parse(config.data):config.data}
      calls.push(observed)
      return {data:await transport(observed),status:200,statusText:'OK',headers:{},config}
    }
    globalThis[slot]=request
  }
  const apiUrl=dataModule(source('api/carePlan.js').replace(/import request from ['"]@\/utils\/request['"]/g,`const request = globalThis.${slot}`).replace(/from ['"]@\/utils\/authSession['"]/g,`from '${authUrl}'`))
  const doctorApiUrl=dataModule(source('api/doctorWorkspace.js').replace(/import request from ['"]@\/utils\/request['"]/g,`const request = globalThis.${slot}`))
  const timeUrl=new URL('../src/utils/carePlanTime.js',import.meta.url).href
  const composableUrl=dataModule(source('composables/useCarePlan.js').replace(/from ['"]vue['"]/g,`from '${vueUrl}'`).replace(/from ['"]@\/api\/carePlan['"]/g,`from '${apiUrl}'`).replace(/from ['"]@\/utils\/authSession['"]/g,`from '${authUrl}'`).replace(/from ['"]@\/utils\/carePlanTime['"]/g,`from '${timeUrl}'`)+`\n// ${fixtureId}`)
  const patientUrl=dataModule(`import {ref,computed} from '${vueUrl}'; const selected=ref(1),list=ref([]); export function useCurrentPatient(){return {currentPatientId:selected,currentPatientName:computed(()=> 'Synthetic patient'),setPatientList:x=>{list.value=x},patientList:list}}\n// ${fixtureId}`)
  const epUrl=dataModule(`export const ElMessage={success:text=>globalThis.${messageSlot}.push({kind:'success',text}),warning:text=>globalThis.${messageSlot}.push({kind:'warning',text}),info(){}};export const ElMessageBox={prompt:async()=>null,confirm:async()=>true}`)
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
    code=code.replace(/from ['"]@\/api\/doctorWorkspace['"]/g,`from '${doctorApiUrl}'`).replace(/import \{ ElMessage, ElMessageBox \} from ['"]element-plus['"];?/g,`import {ElMessage,ElMessageBox} from '${epUrl}';`)
    code=code.replace(/import request from ['"]@\/utils\/request['"]/g,`const request=globalThis.${slot}`)
    code=code.replace(/from ['"]@\/api\/familyHealth['"]/g,`from '${familyUrl}'`).replace(/from ['"]@\/utils\/workspaceAccess['"]/g,`from '${new URL('../src/utils/workspaceAccess.js',import.meta.url).href}'`)
    code=code.replace(/from ['"]@\/api\/careJourney['"]/g,`from '${journeyUrl}'`).replace(/from ['"]@\/components\/ConsultationWorkspace\.vue['"]/g,`from '${emptyUrl}'`)
    code=code.replace(/from ['"]@\/api\/user(?:\.js)?['"]/g,`from '${userUrl}'`).replace(/from ['"]@\/api\/care['"]/g,`from '${careUrl}'`).replace(/from ['"]@\/composables\/useCurrentPatient['"]/g,`from '${patientUrl}'`).replace(/from ['"]element-plus['"]/g,`from '${epUrl}'`).replace(/from ['"]vue-router['"]/g,`from '${routerUrl}'`).replace(/import (\w+) from ['"]@\/assets\/[^'"]+['"]/g,(m,n)=>`const ${n}='synthetic-art'`).replace(/from ['"]@\/utils\/familyHealth['"]/g,`from '${new URL('../src/utils/familyHealth.js',import.meta.url).href}'`).replace(/from ['"]@\/utils\/timelineText['"]/g,`from '${new URL('../src/utils/timelineText.js',import.meta.url).href}'`)
    code=code.replace(/from ['"]@\/composables\/useFocusedCareSource['"]/g,`from '${new URL('../src/composables/useFocusedCareSource.js',import.meta.url).href}'`)
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
  for(const tag of new Set(['el-collapse','el-collapse-item','el-collapse','el-collapse-item','el-slider','el-timeline','el-timeline-item','el-checkbox-group','el-checkbox','el-row','el-col','el-card','el-divider','el-date-picker','el-time-picker','el-container','el-main','el-header','el-radio-button','el-switch','el-alert','el-calendar','el-progress','el-popconfirm','el-input-number','el-button','el-icon','el-tabs','el-tab-pane','el-table','el-table-column','el-tag','el-empty','el-dialog','el-form','el-form-item','el-select','el-option','el-input','el-radio-group','el-radio','router-link','Refresh']))app.component(tag,{setup(p,{slots}){return ()=>h('div',{},tag==='el-table-column'?[]:slots.default?.())}})
  app.directive('loading',{})
  const render=()=>{const vnode=h(component,currentProps);vnode.appContext=app._context;renderer.render(vnode,root)}
  render();await flush()
  t.after(()=>{renderer.render(null,root);delete globalThis[slot];delete globalThis[messageSlot]})
  return {root,calls,events,storage,writes,messages,request,patient:await import(patientUrl),auth:await import(authUrl),get vm(){return root._vnode.component.setupState},async update(p){currentProps={...currentProps,...p};render();await flush()},async click(id){await flush();const target=findNode(root,id);assert.ok(target,`${id} control rendered`);assert.ok(!target.props.disabled,`${id} control enabled`);await target.props.onClick?.({preventDefault(){}});await flush()}}
}

const action={id:31,planId:17,patientId:1,revisionId:23,ordinal:1,assignedUserId:51,instruction:'Synthetic action',dueAt:'2026-10-04T12:00:00Z',status:'OPEN',version:99,evidence:[],events:[],allowedActions:['SUBMIT_RECEIPT','REQUEST_HELP','FOLLOW_UP'],allowedEntryModes:['ASSISTED','SELF']}
const plan={id:17,patientId:1,title:'Synthetic plan',instructions:'Synthetic instructions',planType:'FOLLOW_UP',revisionId:23,revisionNo:1,revisionStatus:'PUBLISHED',currentRevisionId:23,version:4,lifecycle:'ACTIVE',actions:[action],allowedActions:[]}
const copy=x=>JSON.parse(JSON.stringify(x))
function server(overrides={}){return async c=>{if(overrides[c.url])return overrides[c.url](c);if(c.url.endsWith('/capabilities'))return {code:200,data:{enabled:true}};if(c.url==='/care/home')return {data:[{patient:{id:1,name:'Synthetic patient'}}]};if(c.url==='/care/context')throw Object.assign(new Error('Full context denied'),{response:{status:403}});if(c.url==='/care-plans')return {data:{items:[copy(plan)],nextCursor:null}};if(c.url==='/care-nurse-assignments')return {data:[{id:8,patientId:1,nurseUserId:51,nurseName:'Synthetic nurse',status:'ACTIVE',assignedAt:'2026-10-03T01:00:00Z',expiresAt:null}]};if(c.method==='post')return {data:{planId:17,eventId:40,version:5,lifecycle:'ACTIVE'}};return {data:copy(plan)}}}
const posts=v=>v.calls.filter(c=>c.method==='post')

import {canAccessWorkspace} from '../src/utils/workspaceAccess.js'
import {getFallbackWorkspaceMenus} from '../src/utils/workspaceNavigation.js'
test('nurseNavigationDoesNotGrantDoctorAccess: even explicit doctor menu cannot bypass nurse role',()=>{assert.equal(canAccessWorkspace('/nurse-workspace',[],['nurse']),true);assert.equal(canAccessWorkspace('/doctor-workspace',['/doctor-workspace'],['nurse']),false);assert.equal(canAccessWorkspace('/care-journey?tab=operations',['/care-journey'],['nurse']),false);const paths=getFallbackWorkspaceMenus([],['nurse']).map(x=>x.path);assert.ok(paths.includes('/nurse-workspace'));assert.ok(!paths.includes('/doctor-workspace'))})
test('nurse workspace only calls HELP REVIEW care-plan queues, never clinical APIs',async t=>{const v=await fixture(t,'views/NurseWorkspace',{},server());assert.equal(v.calls.find(c=>c.url==='/care-plans').params.queue,'HELP');const clinicalCalls=v.calls.filter(c=>/doctor-workspace|clinical-workbench|care\/operations|care-journey/.test(c.url));assert.equal(clinicalCalls.length,0);assert.ok(findNode(v.root,'nurse-help'));await v.click('nurse-review');assert.equal(v.calls.filter(c=>c.url==='/care-plans').at(-1).params.queue,'REVIEW');assert.equal(findNode(v.root,'review-confirm-31'),undefined)})
test('nurse follows up without confirming record and can only use server allowed assisted entry',async t=>{const v=await fixture(t,'ReceiptDialog',{action:{...action,allowedEntryModes:['ASSISTED']},planVersion:4,command:'followUp'},server());v.vm.form.note='Contacted synthetic patient';v.vm.form.kind='CONTACTED';await v.click('submit-receipt');assert.equal(posts(v)[0].url,'/care-plans/actions/31/follow-ups');assert.equal(posts(v)[0].data.kind,'CONTACTED');assert.equal(posts(v)[0].data.expectedVersion,4);assert.equal(posts(v).filter(c=>c.url.includes('/reviews')).length,0)})
test('assignment revocation clears previously loaded task body',async t=>{let n=0;const v=await fixture(t,'PlanTaskList',{patientId:null,mode:'NURSE',queue:'HELP'},server({'/care-plans':()=>{if(++n===1)return {data:{items:[plan]}};throw Object.assign(new Error('revoked'),{response:{status:403}})}}));assert.match(textOf(v.root),/Synthetic plan/);await v.vm.reload();assert.equal(v.vm.state.items.length,0);assert.doesNotMatch(textOf(v.root),/Synthetic plan/)})
test('admin assignments show consent remains separate and expiry requires explicit offset', async t => {
  t.mock.method(Date, 'now', () => Date.parse('2026-10-04T00:00:00Z'))
  const v = await fixture(t, 'NurseAssignments', {patientId:1}, server())
  v.storage.set('userRoleCodes', '["admin"]')
  await v.vm.initialize()
  assert.match(textOf(v.root), /patient.*authorization|患者.*授权/i)
  v.vm.form.nurseUserId = 51

  v.vm.form.expiresAt = '2026-10-04T12:00'
  await v.vm.assign()
  assert.equal(posts(v).length, 0, 'expiry without an explicit offset is rejected')

  v.vm.form.expiresAt = '2026-10-03T12:00:00+00:00'
  await v.vm.assign()
  assert.equal(posts(v).length, 0, 'expired time with an explicit offset is rejected')

  v.vm.form.expiresAt = '2026-10-04T12:00:00+00:00'
  await v.vm.assign()
  assert.equal(posts(v).length, 1, 'future expiry with an explicit offset submits one assignment')
  assert.equal(posts(v)[0].url, '/care-nurse-assignments')
  assert.equal(posts(v).filter(c => /grant/i.test(c.url)).length, 0)
  assert.equal(posts(v)[0].data.expiresAt, '2026-10-04T12:00:00+00:00')
})

test('privacy nurse candidates require current patient ACTIVE assignment and never auto-create authorization',async t=>{const rows=[{id:8,patientId:1,nurseUserId:51,nurseName:'Current synthetic nurse',status:'ACTIVE',expiresAt:null},{id:9,patientId:1,nurseUserId:52,nurseName:'Expired synthetic nurse',status:'ACTIVE',expiresAt:'2000-01-01T00:00:00Z'},{id:10,patientId:1,nurseUserId:53,nurseName:'Revoked synthetic nurse',status:'REVOKED',expiresAt:null},{id:11,patientId:2,nurseUserId:54,nurseName:'Other synthetic nurse',status:'ACTIVE',expiresAt:null}];const v=await fixture(t,'views/CareJourneyManager',{},server({'/care-nurse-assignments':()=>({data:rows}),'/care-journey/access-grants':()=>({data:[]}),'/care-journey/clinicians':()=>({data:[]})}));assert.equal(typeof v.vm.loadNurseCandidates,'function','assigned-nurse loader exists');await v.vm.loadNurseCandidates();assert.deepEqual(v.vm.nurseCandidates.map(x=>x.nurseUserId),[51]);v.vm.grant.granteeRole='NURSE';v.vm.grant.granteeUserId=52;v.vm.grantModules=['CARE_PLAN'];await v.vm.createGrant();assert.equal(posts(v).length,0);v.vm.grant.granteeUserId=51;await v.vm.createGrant();assert.equal(posts(v)[0].url,'/care-journey/access-grants');assert.equal(posts(v)[0].data.visibleModules,'CARE_PLAN');assert.equal(posts(v).filter(c=>c.url==='/care-nurse-assignments').length,0)})


test('privacy candidate refresh cannot retarget an old authorization after patient or form changes', async t => {
  let blocked = null
  const v = await fixture(t, 'views/CareJourneyManager', {}, server({
    '/care-nurse-assignments': c => blocked ? blocked.promise : {data:[{id:8,patientId:c.params.patientId,nurseUserId:51,nurseName:'Assigned synthetic nurse',status:'ACTIVE',expiresAt:null}]},
    '/care-journey/access-grants': c => c.method==='post' ? {data:{}} : {data:[]},
    '/care-journey/clinicians': () => ({data:[]})
  }))
  await v.vm.loadNurseCandidates()
  v.vm.grant.granteeRole='NURSE';v.vm.grant.granteeUserId=51;v.vm.grantModules=['CARE_PLAN']
  blocked=deferred()
  const pending=v.vm.createGrant();await flush()
  const old=blocked;blocked=null
  v.patient.useCurrentPatient().currentPatientId.value=2;await flush()
  await v.vm.loadNurseCandidates()
  v.vm.grant.granteeRole='NURSE';v.vm.grant.granteeUserId=52;v.vm.grantModules=['CARE_PLAN']
  old.resolve({data:[{id:8,patientId:1,nurseUserId:51,status:'ACTIVE',expiresAt:null}]})
  await pending
  assert.equal(posts(v).length,0,'old grant never sends to new patient')
  assert.equal(v.vm.grant.granteeUserId,52,'new recipient survives old completion')
  assert.equal(v.messages.filter(m=>m.kind==='success').length,0,'obsolete grant does not claim authorization succeeded')
  v.vm.grant.granteeUserId=51;blocked=deferred()
  const edited=v.vm.createGrant();await flush()
  v.vm.grant.accessLevel='WRITE'
  blocked.resolve({data:[{id:8,patientId:2,nurseUserId:51,status:'ACTIVE',expiresAt:null}]})
  await edited
  assert.equal(posts(v).length,0,'form edited during refresh requires a fresh submit')
})

test('assignment list and revoke authorization denials clear the administrator form',async t=>{
  let deny=false
  const v=await fixture(t,'NurseAssignments',{patientId:1},server({'/care-nurse-assignments/8/revoke':()=>{throw Object.assign(new Error('revoked'),{response:{status:403}})},'/care-nurse-assignments':()=>{if(deny)throw Object.assign(new Error('revoked'),{response:{status:403}});return {data:[{id:8,patientId:1,nurseUserId:51,status:'ACTIVE'}]}}}))
  v.storage.set('userRoleCodes','["admin"]');await v.vm.initialize();v.vm.form.nurseUserId=51;v.vm.form.expiresAt='2026-10-04T12:00:00Z';deny=true
  await v.vm.load();assert.equal(v.vm.enabled,false);assert.equal(v.vm.form.nurseUserId,null);assert.equal(v.vm.form.expiresAt,'');assert.equal(v.vm.assignments.length,0)
  deny=false;await v.vm.initialize();v.vm.form.nurseUserId=51;v.vm.form.expiresAt='2026-10-04T12:00:00Z'
  await v.vm.revoke({id:8});assert.equal(v.vm.enabled,false);assert.equal(v.vm.form.nurseUserId,null);assert.equal(v.vm.form.expiresAt,'');assert.equal(v.vm.assignments.length,0)
})

test('administrator patient assignment entry template compiles as a literal title',()=>{
  const filename='views/PatientManager.vue', {descriptor}=parse(source(filename),{filename})
  const script=compileScript(descriptor,{id:'patient-assignment-compile'})
  const compiled=compileTemplate({source:descriptor.template.content,filename,id:'patient-assignment-compile',compilerOptions:{bindingMetadata:script.bindings}})
  assert.deepEqual(compiled.errors,[])
})


for (const change of ['account','patient']) {
  test(`real axios CARE_PLAN grant revocation: ${change} change in transforms cancels before adapter without a success toast`,async t=>{
    const row={id:71,patient_id:1,visible_modules:'CARE_PLAN',status:'ACTIVE'}
    const v=await fixture(t,'views/CareJourneyManager',{},server({'/care-journey/access-grants':()=>({code:200,data:[row]}),'/care-journey/clinicians':()=>({code:200,data:[]})}),{realRequest:true})
    await v.vm.loadGrants();v.messages.length=0
    v.request.interceptors.request.use(config=>{
      if(config.method==='delete'){
        const transforms=Array.isArray(config.transformRequest)?config.transformRequest:[config.transformRequest]
        config.transformRequest=[function(data){if(change==='account'){v.storage.set('token','synthetic-B');v.storage.set('userId','52')}else v.patient.useCurrentPatient().currentPatientId.value=2;return data},...transforms]
      }
      return config
    })
    await v.vm.revoke(row);await flush()
    assert.equal(v.calls.filter(c=>c.method==='delete').length,0,'no old grant reaches the adapter after context changes')
    assert.equal(v.messages.filter(m=>m.kind==='success').length,0)
  })
}

test('real axios CARE_PLAN grant completion after a patient change stays silent and does not reload replacement grants',async t=>{
  const pending=deferred(),arrived=deferred(),row={id:71,patient_id:1,visible_modules:'CARE_PLAN',status:'ACTIVE'}
  const v=await fixture(t,'views/CareJourneyManager',{},server({'/care-journey/access-grants':()=>({code:200,data:[row]}),'/care-journey/access-grants/71':()=>{arrived.resolve();return pending.promise},'/care-journey/clinicians':()=>({code:200,data:[]})}),{realRequest:true})
  await v.vm.loadGrants();v.messages.length=0
  assert.equal(v.vm.busy,false);assert.equal(v.vm.grants[0]?.id,71)
  const command=v.vm.revoke(row);await arrived.promise;assert.equal(v.calls.filter(c=>c.method==='delete').length,1)
  v.patient.useCurrentPatient().currentPatientId.value=2;await flush()
  const readsBefore=v.calls.filter(c=>c.url==='/care-journey/access-grants').length
  pending.resolve({code:200,data:{}});await command;await flush()
  assert.equal(v.calls.filter(c=>c.url==='/care-journey/access-grants').length,readsBefore,'old completion never reloads grants in the new patient context')
  assert.equal(v.messages.filter(m=>m.kind==='success').length,0)
})

test('CARE_PLAN family grant requires an enabled capability before transmitting',async t=>{
  const v=await fixture(t,'views/CareJourneyManager',{},server({'/care-plans/capabilities':()=>({data:{enabled:false}}),'/care-journey/access-grants':()=>({data:[]}),'/care-journey/clinicians':()=>({data:[]})}))
  v.vm.grant.granteeRole='FAMILY';v.vm.grant.granteeUserId=52;v.vm.grantModules=['CARE_PLAN']
  await v.vm.createGrant();assert.equal(posts(v).length,0);assert.equal(v.messages.filter(m=>m.kind==='success').length,0)
})
