import assert from 'node:assert/strict'
import fs from 'node:fs'
import test from 'node:test'
import { parse, compileScript, compileTemplate } from '@vue/compiler-sfc'
import { createRenderer, h, nextTick } from 'vue'
import { createMemoryHistory, createRouter, RouterView, isNavigationFailure, NavigationFailureType } from 'vue-router'

// Compile the mounted source and use actual Vue Router registrations/navigation.
// Only the HTTP transport and unrelated Element Plus presentation are substituted.
const source = file => fs.readFileSync(new URL(`../src/${file}`, import.meta.url), 'utf8')
const moduleUrl = code => `data:text/javascript;base64,${Buffer.from(code).toString('base64')}`
const vueUrl = new URL('../node_modules/vue/dist/vue.runtime.esm-bundler.js', import.meta.url).href
const routerUrl = new URL('../node_modules/vue-router/dist/vue-router.mjs', import.meta.url).href
let fixtureId = 0
const copy = value => JSON.parse(JSON.stringify(value))
const action = {id:31,planId:17,patientId:1,revisionId:23,ordinal:1,assignedUserId:51,instruction:'Synthetic action',dueAt:'2026-10-04T12:00:00Z',status:'SUBMITTED',version:99,evidence:[],events:[],allowedActions:['REVIEW_RECEIPT','SUBMIT_RECEIPT','REQUEST_HELP','FOLLOW_UP'],allowedEntryModes:['SELF','ASSISTED']}
const plan = {id:17,patientId:1,workflowVersion:1,title:'Synthetic plan',instructions:'Synthetic instructions',planType:'FOLLOW_UP',revisionId:23,revisionNo:1,revisionStatus:'PUBLISHED',currentRevisionId:23,draftRevisionId:null,version:4,lifecycle:'ACTIVE',actions:[action],allowedActions:['CANCEL_PLAN']}
function deferred(){let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b});return {promise,resolve,reject}}
async function flush(){for(let i=0;i<20;i++){await Promise.resolve();await nextTick()}}
function findNode(node,id){if(node.props?.['data-testid']===id)return node;for(const child of node.children||[]){const found=findNode(child,id);if(found)return found}}
function findComponent(vnode,name){if(!vnode)return;const instance=vnode.component;if(instance){if(instance.type.__name===name)return instance;const found=findComponent(instance.subTree,name);if(found)return found}for(const child of Array.isArray(vnode.children)?vnode.children:[]){const found=findComponent(child,name);if(found)return found}}
function server(overrides={}){return async config=>{if(overrides[config.url])return overrides[config.url](config);if(config.url.endsWith('/capabilities'))return {data:{enabled:true}};if(config.url.endsWith('/assignees'))return {data:[{userId:51,displayName:'Synthetic clinician',role:'doctor'}]};if(config.url==='/doctor-workspace/summary')return {data:{patients:[{id:1,name:'Synthetic patient'},{id:2,name:'Other synthetic patient'}]}};if(config.url.startsWith('/doctor-workspace/'))return {data:[]};if(config.method==='post')return {data:{...copy(plan),revisionId:23,draftRevisionId:23}};if(config.url==='/care-plans')return {data:{items:[copy(plan)],nextCursor:null}};if(config.url.endsWith('/revisions'))return {data:{items:[{id:23,revisionNo:1,status:'PUBLISHED'}],nextCursor:null}};return {data:{...copy(plan),id:Number(config.url.match(/care-plans\/(\d+)/)?.[1]||17)}}}}

async function fixture(t, initial='/care-plans/17', transport=server()){
  const id=++fixtureId,slot=`__routeGuardTransport${id}`,storage=new Map([['token','synthetic-token'],['userId','51'],['userRoleCodes','["doctor","nurse"]']]),calls=[],confirmations=[]
  globalThis.localStorage={getItem:key=>storage.get(key)??null,setItem:(key,value)=>storage.set(key,String(value)),removeItem:key=>storage.delete(key),key:index=>[...storage.keys()][index],get length(){return storage.size}}
  const eventWindow=new EventTarget();eventWindow.confirm=text=>{confirmations.push(text);return false};globalThis.window=eventWindow
  globalThis.document={activeElement:null};globalThis.Document=class {};globalThis.ShadowRoot=class {}
  globalThis[slot]=config=>{calls.push(config);return transport(config)}
  const authUrl=new URL('../src/utils/authSession.js',import.meta.url).href,timeUrl=new URL('../src/utils/carePlanTime.js',import.meta.url).href
  const api=file=>moduleUrl(source(file).replace(/import request from ['"]@\/utils\/request['"];?/g,`const request=(...args)=>globalThis.${slot}(...args)`).replace(/from ['"]@\/utils\/authSession['"]/g,`from '${authUrl}'`))
  const careApiUrl=api('api/carePlan.js'),doctorApiUrl=api('api/doctorWorkspace.js')
  const composableUrl=moduleUrl(source('composables/useCarePlan.js').replace(/from ['"]vue['"]/g,`from '${vueUrl}'`).replace(/from ['"]@\/api\/carePlan['"]/g,`from '${careApiUrl}'`).replace(/from ['"]@\/utils\/authSession['"]/g,`from '${authUrl}'`).replace(/from ['"]@\/utils\/carePlanTime['"]/g,`from '${timeUrl}'`))
  const urls={}
  async function compile(name){
    if(urls[name])return urls[name]
    const filename=name.includes('/')?`${name}.vue`:`components/care-plan/${name}.vue`,scope=`route-${id}-${name}`,{descriptor}=parse(source(filename),{filename})
    const script=compileScript(descriptor,{id:scope}),template=compileTemplate({source:descriptor.template.content,filename,id:scope,compilerOptions:{bindingMetadata:script.bindings,hoistStatic:false}})
    assert.deepEqual(template.errors,[],`${name} compiles`)
    let code=script.content.replace('export default','const __component =')+'\n'+template.code.replace('export function render','function render')+'\n__component.render=render;export default __component;'
    code=code.replace(/from ['"]vue['"]/g,`from '${vueUrl}'`).replace(/from ['"]vue-router['"]/g,`from '${routerUrl}'`).replace(/from ['"]@\/api\/carePlan['"]/g,`from '${careApiUrl}'`).replace(/from ['"]@\/api\/doctorWorkspace['"]/g,`from '${doctorApiUrl}'`).replace(/from ['"]@\/composables\/useCarePlan['"]/g,`from '${composableUrl}'`).replace(/from ['"]@\/utils\/authSession['"]/g,`from '${authUrl}'`).replace(/from ['"]@\/utils\/carePlanTime['"]/g,`from '${timeUrl}'`).replace(/import \{ ElMessage, ElMessageBox \} from ['"]element-plus['"];?/g,`const ElMessage={success(){},warning(){}};const ElMessageBox={prompt:async()=>({value:''})};`)
    for(const match of code.matchAll(/from ['"]@\/components\/care-plan\/(\w+)\.vue['"]/g))code=code.replace(match[0],`from '${await compile(match[1])}'`)
    for(const match of code.matchAll(/from ['"]\.\/(\w+)\.vue['"]/g))code=code.replace(match[0],`from '${await compile(match[1])}'`)
    return urls[name]=moduleUrl(code)
  }
  const node=(tag,text='')=>({tag,tagName:tag.toUpperCase(),text,children:[],props:{},parent:null,style:{},addEventListener(){},removeEventListener(){},getRootNode(){return {}},getAttribute(key){return this.props[key]},setAttribute(key,value){this.props[key]=value},removeAttribute(key){delete this.props[key]},get options(){return this.children}})
  const renderer=createRenderer({createElement:tag=>node(tag),createText:text=>node('#text',text),createComment:text=>node('#comment',text),setText:(target,text)=>{target.text=text},setElementText:(target,text)=>{target.text=text;target.children=[]},parentNode:target=>target.parent,nextSibling:target=>target.parent?.children[target.parent.children.indexOf(target)+1]||null,patchProp:(target,key,old,value)=>{target.props[key]=value;if(key==='value')target.value=value},insert:(target,parent,anchor)=>{if(target.parent){const index=target.parent.children.indexOf(target);if(index>=0)target.parent.children.splice(index,1)}target.parent=parent;const index=anchor?parent.children.indexOf(anchor):-1;parent.children.splice(index<0?parent.children.length:index,0,target)},remove:target=>{if(target.parent)target.parent.children.splice(target.parent.children.indexOf(target),1)}})
  const detail=(await import(await compile('views/CarePlanDetailRoute'))).default,doctor=(await import(await compile('views/DoctorWorkspace'))).default
  const router=createRouter({history:createMemoryHistory(),routes:[{path:'/care-plans/:id',component:detail},{path:'/doctor-workspace',component:doctor},{path:'/other',component:{render:()=>h('p','Other route')}},{path:'/care-journey',component:{render:()=>h('p','Consultation route')}}]})
  if(initial!=='/care')router.addRoute({path:'/care',component:{render:()=>h('p','Care route')}})
  if(initial==='/care'){const tasks=(await import(await compile('PlanTaskList'))).default;router.addRoute({path:'/care',component:{render:()=>h(tasks,{patientId:1})}})}
  if(initial==='/nurse-workspace')router.addRoute({path:'/nurse-workspace',component:(await import(await compile('views/NurseWorkspace'))).default})
  const root=node('root'),app=renderer.createApp({render:()=>h(RouterView)})
  for(const tag of ['el-button','el-icon','el-tabs','el-tab-pane','el-table','el-table-column','el-tag','el-empty','el-dialog','el-form','el-form-item','el-select','el-option','el-input','el-radio-group','el-radio','el-date-picker','Refresh'])app.component(tag,{setup(props,{slots}){return ()=>h('div',{},tag==='el-table-column'?[]:slots.default?.())}})
  app.directive('loading',{});app.use(router);await router.push(initial);await router.isReady();app.mount(root);await flush()
  t.after(()=>{app.unmount();delete globalThis[slot]})
  return {root,router,calls,confirmations,storage,auth:await import(authUrl),instance:name=>findComponent(root._vnode,name),get path(){return router.currentRoute.value.fullPath},async push(path){const result=await router.push(path);await flush();return result},async click(id){await flush();const target=findNode(root,id);assert.ok(target,`${id} rendered`);assert.ok(!target.props.disabled,`${id} enabled`);await target.props.onClick?.({preventDefault(){}});await flush()},contextChange(){const event=new Event('care-plan-before-context-change',{cancelable:true});window.dispatchEvent(event);return event},async pop(direction){let stop;const finished=new Promise(resolve=>{stop=router.afterEach((to,from,failure)=>resolve(failure))});router[direction]();const failure=await finished;stop();await flush();return failure}}
}
const posts=view=>view.calls.filter(call=>call.method==='post')
function aborted(result){assert.equal(isNavigationFailure(result,NavigationFailureType.aborted),true,'the registered router guard cancels navigation')}
async function openEditor(view){await view.click('new-collaboration-draft');const editor=view.instance('PlanEditor').setupState;if(findNode(view.root,'acknowledge-create-history'))await view.click('acknowledge-create-history');return editor}
function fillDraft(editor,title='Synthetic draft'){Object.assign(editor.state.draft,{title,instructions:'Synthetic private instructions'});Object.assign(editor.state.draft.actions[0],{instruction:'Synthetic action',assignedUserId:51,dueAt:'2026-10-04T12:00:00Z'})}

test('registered detail guards block pending and UNKNOWN review on route leave and update, retaining the frozen retry',async t=>{
  const pending=deferred();let attempt=0
  const view=await fixture(t,'/care-plans/17',server({'/care-plans/actions/31/reviews':()=>++attempt===1?pending.promise:{data:{planId:17,eventId:40,version:5}}}))
  const detail=view.instance('PlanDetail').setupState,command=detail.confirmReview(action);await flush()
  const original=copy(posts(view)[0].data),key=detail.state.commandKey
  aborted(await view.push('/other'));assert.equal(view.path,'/care-plans/17');assert.equal(detail.state.commandKey,key);assert.ok(findNode(view.root,'confirm-detail-abandon'))
  aborted(await view.push('/care-plans/18'));assert.equal(view.path,'/care-plans/17');assert.equal(posts(view).length,1)
  pending.reject(new Error('Synthetic response lost'));await command;await flush();assert.equal(detail.state.commandPhase,'unknown')
  aborted(await view.push('/other'));aborted(await view.push('/care-plans/18'));assert.equal(detail.state.commandKey,key)
  await view.click('retry-detail-original');assert.deepEqual(posts(view)[1].data,original);assert.equal(posts(view).length,2)
  await view.push('/care-plans/18');assert.equal(view.path,'/care-plans/18');assert.equal(view.instance('PlanDetail').setupState.state.plan.id,18)
})

test('UNKNOWN detail cancels Back and Forward until acknowledged server reload, then permits ordinary history',async t=>{
  const view=await fixture(t,'/other',server({'/care-plans/actions/31/reviews':()=>{throw new Error('Synthetic unknown')}}))
  await view.push('/care-plans/17');await view.push('/care-plans/18');await view.pop('back');assert.equal(view.path,'/care-plans/17')
  await view.click('review-confirm-31');const detail=view.instance('PlanDetail').setupState,key=detail.state.commandKey
  aborted(await view.pop('forward'));assert.equal(view.path,'/care-plans/17');assert.equal(detail.state.commandKey,key)
  aborted(await view.pop('back'));assert.equal(view.path,'/care-plans/17');assert.equal(posts(view).length,1)
  await view.click('confirm-detail-abandon');assert.equal(detail.state.commandPhase,'idle');assert.equal(posts(view).length,1)
  await view.pop('forward');assert.equal(view.path,'/care-plans/18');await view.pop('back');assert.equal(view.path,'/care-plans/17')
})

test('clean detail routes allow updates and leave without prompts',async t=>{
  const view=await fixture(t);await view.push('/care-plans/18');assert.equal(view.path,'/care-plans/18');await view.push('/other');assert.equal(view.path,'/other');assert.equal(view.confirmations.length,0);assert.equal(posts(view).length,0)
})

test('global patient-context event blocks UNKNOWN detail and releases after acknowledged reload',async t=>{
  const view=await fixture(t,'/care-plans/17',server({'/care-plans/actions/31/reviews':()=>{throw new Error('Synthetic unknown')}}))
  await view.click('review-confirm-31');const detail=view.instance('PlanDetail').setupState,key=detail.state.commandKey
  assert.equal(view.contextChange().defaultPrevented,true);assert.equal(detail.state.commandKey,key);assert.equal(posts(view).length,1)
  await view.click('confirm-detail-abandon');assert.equal(view.contextChange().defaultPrevented,false)
  await view.push('/other');assert.equal(view.contextChange().defaultPrevented,false,'unmounted route removed its context listener')
})

test('receipt guard asks once per canceled navigation/context event and keeps pending or UNKNOWN input',async t=>{
  const pending=deferred(),view=await fixture(t,'/care-plans/17',server({'/care-plans/actions/31/receipts':()=>pending.promise}))
  await view.click('route-record-31');const receipt=view.instance('ReceiptDialog').setupState;receipt.form.note='Synthetic receipt';const command=receipt.submit();await flush();const key=receipt.state.commandKey
  aborted(await view.push('/other'));assert.equal(view.confirmations.length,1);assert.equal(receipt.state.commandKey,key)
  aborted(await view.push('/care-plans/18'));assert.equal(view.confirmations.length,2);assert.equal(view.contextChange().defaultPrevented,true);assert.equal(view.confirmations.length,3,'container does not duplicate the receipt context prompt')
  pending.reject(new Error('Synthetic response lost'));await command;await flush();assert.equal(receipt.state.commandPhase,'unknown');assert.equal(receipt.form.note,'Synthetic receipt')
  aborted(await view.push('/other'));assert.equal(view.confirmations.length,4);assert.equal(receipt.state.commandKey,key)
  window.confirm=text=>{view.confirmations.push(text);return true};await view.push('/care-plans/18');assert.equal(view.path,'/care-plans/18');assert.equal(view.confirmations.length,5);assert.equal(view.instance('ReceiptDialog'),undefined);assert.equal(posts(view).length,1)
})

test('registered doctor workspace guards block dirty editor leave/update and patient remount until explicit discard',async t=>{
  const view=await fixture(t,'/doctor-workspace'),editor=await openEditor(view);fillDraft(editor,'Unsaved synthetic draft')
  aborted(await view.push('/other'));assert.equal(view.path,'/doctor-workspace');assert.equal(editor.state.draft.title,'Unsaved synthetic draft');assert.ok(findNode(view.root,'confirm-abandon'))
  await view.click('keep-editing');aborted(await view.push('/doctor-workspace?tab=plans'));assert.equal(editor.state.draft.title,'Unsaved synthetic draft')
  assert.equal(view.contextChange().defaultPrevented,true);assert.equal(posts(view).length,0)
  await view.click('confirm-abandon');assert.equal(view.instance('PlanEditor'),undefined);assert.equal(view.contextChange().defaultPrevented,false);await view.push('/other');assert.equal(view.path,'/other');assert.equal(posts(view).length,0)
})

test('clean collaboration editor preserves ordinary route navigation',async t=>{
  const view=await fixture(t,'/doctor-workspace');const editor=await openEditor(view);assert.equal(editor.dirty,false)
  await view.push('/doctor-workspace?tab=plans');assert.equal(view.path,'/doctor-workspace?tab=plans');assert.equal(findNode(view.root,'confirm-abandon'),undefined)
  assert.equal(view.contextChange().defaultPrevented,false);await view.push('/other');assert.equal(view.path,'/other');assert.equal(posts(view).length,0)
})

test('doctor editor pending and UNKNOWN create retain retry key across navigation and patient remount',async t=>{
  const pending=deferred(),view=await fixture(t,'/doctor-workspace',server({'/care-plans':config=>config.method==='post'?pending.promise:{data:{items:[],nextCursor:null}}})),editor=await openEditor(view);fillDraft(editor)
  const command=editor.save();await flush();const key=editor.state.commandKey,original=copy(posts(view)[0].data)
  aborted(await view.push('/other'));aborted(await view.push('/doctor-workspace?tab=plans'));assert.equal(view.contextChange().defaultPrevented,true);assert.equal(editor.state.commandKey,key)
  await view.click('keep-editing');assert.equal(editor.state.commandKey,key);pending.reject(new Error('Synthetic response lost'));await command;await flush();assert.equal(editor.state.commandPhase,'unknown')
  aborted(await view.push('/other'));assert.equal(view.contextChange().defaultPrevented,true);assert.equal(editor.state.commandKey,key);assert.deepEqual(posts(view)[0].data,original);assert.equal(posts(view).length,1)
  await view.click('confirm-abandon');assert.equal(view.instance('PlanEditor'),undefined);await view.push('/other');assert.equal(view.path,'/other');assert.equal(posts(view).length,1)
})

test('doctor workspace detail composes real UNKNOWN review guard for SPA and patient context',async t=>{
  const view=await fixture(t,'/doctor-workspace',server({'/care-plans/actions/31/reviews':()=>{throw new Error('Synthetic unknown')}}))
  await view.click('open-plan-17');await view.click('review-confirm-31');const detail=view.instance('PlanDetail').setupState,key=detail.state.commandKey
  aborted(await view.push('/other'));aborted(await view.push('/doctor-workspace?tab=plans'));assert.equal(view.contextChange().defaultPrevented,true);assert.equal(detail.state.commandKey,key);assert.equal(posts(view).length,1)
  await view.click('confirm-detail-abandon');await view.push('/other');assert.equal(view.path,'/other');assert.equal(view.contextChange().defaultPrevented,false)
})

test('dirty close uses the same explicit discard acknowledgment and cannot affect a reopened editor',async t=>{
  const view=await fixture(t,'/doctor-workspace'),editor=await openEditor(view);fillDraft(editor,'Old unsaved draft');await view.click('close-editor')
  assert.ok(view.instance('PlanEditor'));assert.equal(editor.state.draft.title,'Old unsaved draft');assert.ok(findNode(view.root,'confirm-abandon'))
  await view.click('confirm-abandon');const current=await openEditor(view);assert.notEqual(current,editor);assert.equal(current.state.draft.title,'');assert.equal(view.contextChange().defaultPrevented,false);assert.equal(posts(view).length,0)
})


test('pending editor acknowledgment cannot settle a late success into a replacement editor',async t=>{
  const pending=deferred(),view=await fixture(t,'/doctor-workspace',server({'/care-plans':config=>config.method==='post'?pending.promise:{data:{items:[],nextCursor:null}}})),previous=await openEditor(view);fillDraft(previous,'Previous draft')
  const command=previous.save();await flush();aborted(await view.push('/other'));await view.click('confirm-abandon')
  const current=await openEditor(view);fillDraft(current,'Current unsaved draft');const currentState=copy(current.state.draft)
  pending.resolve({data:{...copy(plan),draftRevisionId:23}});await command;await flush()
  assert.deepEqual(current.state.draft,currentState);assert.equal(current.state.commandKey,null);assert.equal(posts(view).length,1);aborted(await view.push('/other'));assert.equal(current.state.draft.title,'Current unsaved draft')
})

test('dirty doctor editor guards real Back and Forward attempts until acknowledged close',async t=>{
  const view=await fixture(t,'/other');await view.push('/doctor-workspace');await view.push('/care-plans/18');await view.pop('back');assert.equal(view.path,'/doctor-workspace')
  const editor=await openEditor(view);fillDraft(editor,'History-retained draft');aborted(await view.pop('back'));assert.equal(view.path,'/doctor-workspace');aborted(await view.pop('forward'));assert.equal(view.path,'/doctor-workspace');assert.equal(editor.state.draft.title,'History-retained draft')
  await view.click('confirm-abandon');await view.pop('forward');assert.equal(view.path,'/care-plans/18');assert.equal(posts(view).length,0)
})

for(const path of ['/care','/nurse-workspace'])test(`${path} existing registered receipt guards preserve pending/UNKNOWN and clean navigation`,async t=>{
  const pending=deferred(),view=await fixture(t,path,server({'/care-plans/actions/31/receipts':()=>pending.promise}));await view.click('record-31')
  const receipt=view.instance('ReceiptDialog').setupState;receipt.form.note='Synthetic assisted record';const command=receipt.submit();await flush();const key=receipt.state.commandKey
  aborted(await view.push('/other'));aborted(await view.push(`${path}?tab=changed`));assert.equal(view.path,path);assert.equal(receipt.state.commandKey,key);assert.equal(view.confirmations.length,2)
  assert.equal(view.contextChange().defaultPrevented,true);assert.equal(view.confirmations.length,3)
  pending.reject(new Error('Synthetic response lost'));await command;await flush();assert.equal(receipt.state.commandPhase,'unknown');aborted(await view.push('/other'));assert.equal(receipt.state.commandKey,key)
  window.confirm=text=>{view.confirmations.push(text);return true};await view.push('/other');assert.equal(view.path,'/other');assert.equal(posts(view).length,1);assert.equal(view.contextChange().defaultPrevented,false)
  await view.push(path);assert.equal(view.instance('ReceiptDialog'),undefined);await view.push('/other');assert.equal(view.path,'/other');assert.equal(view.confirmations.length,5)
})

test('a blocked detail owns the context acknowledgment without also prompting an active receipt',async t=>{
  const detailPending=deferred(),receiptPending=deferred(),view=await fixture(t,'/care-plans/17',server({'/care-plans/actions/31/reviews':()=>detailPending.promise,'/care-plans/actions/31/receipts':()=>receiptPending.promise}))
  await view.click('route-record-31');const receipt=view.instance('ReceiptDialog').setupState;receipt.form.note='Synthetic active receipt';const receiptCommand=receipt.submit();const detail=view.instance('PlanDetail').setupState,detailCommand=detail.confirmReview(action);await flush()
  aborted(await view.push('/other'));assert.equal(view.confirmations.length,0,'detail blocks before receipt route confirmation')
  assert.equal(view.contextChange().defaultPrevented,true);assert.equal(view.confirmations.length,0,'one blocked context does not stack native and inline acknowledgments');await flush();assert.ok(findNode(view.root,'confirm-detail-abandon'))
  detailPending.reject(new Error('Synthetic review response lost'));receiptPending.reject(new Error('Synthetic receipt response lost'));await Promise.all([detailCommand,receiptCommand]);assert.equal(posts(view).length,2)
})

import * as workspaceAccess from '../src/utils/workspaceAccess.js'
function actualMainGuard(){
 let guard,specialtyReads=0
 const text=source('main.js'),body=text.slice(text.indexOf('router.beforeEach('),text.indexOf('app.use(router)'))
 const deps={router:{beforeEach:value=>{guard=value}},localStorage:{getItem:key=>key==='token'?'synthetic':key==='userRoleCodes'?'["admin"]':'[]'},...workspaceAccess,loadPatientSpecialtyScope:async()=>{specialtyReads++;return{}},knownSpecialtyPath:()=>false,specialtyPathAllowed:()=>true}
 new Function(...Object.keys(deps),body)(...Object.values(deps))
 return{guard,get specialtyReads(){return specialtyReads}}
}
test('actual main guard permits exact cold report/source routes without full-patient specialty reads',async()=>{
 const v=actualMainGuard()
 for(const fullPath of ['/care-plans/reports?patientId=1','/care-journey?tab=measurements&patientId=1&measurementId=17','/medical-record?tab=list&patientId=1&recordId=18']){
  const parsed=new URL(fullPath,'http://test');let destination='unset';await v.guard({path:parsed.pathname,fullPath,query:Object.fromEntries(parsed.searchParams)},{},value=>{destination=value});assert.equal(destination,undefined)
 }assert.equal(v.specialtyReads,0)
})
test('actual main guard rejects malformed source locators before any specialty/bootstrap read, including admins',async()=>{
 const v=actualMainGuard()
 for(const fullPath of ['/medical-record?tab=list&patientId=1&recordId=18&extra=1','/care-journey?tab=measurements&patientId=1&measurementId=0','/medical-record?tab=list&patientId=1&recordId=18&patientId=2']){
  const parsed=new URL(fullPath,'http://test');let destination;await v.guard({path:parsed.pathname,fullPath,query:Object.fromEntries(parsed.searchParams)},{},value=>{destination=value});assert.equal(destination,'/monitoring')
 }assert.equal(v.specialtyReads,0)
})
