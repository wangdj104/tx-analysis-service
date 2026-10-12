// Independent non-browser review. Compiles real SFCs, mounts Vue and real Router;
// Element Plus presentation components are stubs, so these are not visual tests.
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
const frontend=fileURLToPath(new URL('../',import.meta.url)).replace(/\/$/,'')
const require=createRequire(`${frontend}/package.json`)
const {parse,compileScript,compileTemplate}=require('@vue/compiler-sfc')
const vueUrl=new URL(`file://${frontend}/node_modules/vue/dist/vue.runtime.esm-bundler.js`).href
const routerUrl=new URL(`file://${frontend}/node_modules/vue-router/dist/vue-router.mjs`).href
const {createRenderer,h,ref,nextTick}=await import(vueUrl)
const {createMemoryHistory,createRouter,RouterView,isNavigationFailure}=await import(routerUrl)
let fixtureId=0
const deferred=()=>{let resolve,reject;const promise=new Promise((yes,no)=>{resolve=yes;reject=no});return {promise,resolve,reject}}
const room=(id,extra={})=>({id:Number(id),patient_id:11,patient_name:'Synthetic patient',doctor_name:'Synthetic clinician',status:'OPEN',can_message:true,can_close:true,mode:'TEXT',messages:[],participants:[],...extra})
async function flush(){for(let i=0;i<20;i++){await Promise.resolve();await nextTick()}}
function findComponent(vnode,name){if(!vnode)return;const instance=vnode.component;if(instance){if(instance.type.__name===name)return instance;const found=findComponent(instance.subTree,name);if(found)return found}for(const child of Array.isArray(vnode.children)?vnode.children:[]){const found=findComponent(child,name);if(found)return found}}
async function fixture(t,edition,overrides={},confirm=async()=>{}){
 const id=++fixtureId,key=`__careReview${id}`,calls=[],messages=[],confirmations=[]
 const api={listConsultations:async()=>({data:[room(1),room(2),room(3)]}),listClinicians:async()=>({data:[]}),listConsultationInvitees:async()=>({data:[]}),listConsultationRecordOptions:async()=>({data:[]}),getConsultation:async id=>({data:room(id)}),...overrides}
 globalThis[key]={api,ElMessage:{success:message=>messages.push(['success',message]),warning:message=>messages.push(['warning',message]),error:message=>messages.push(['error',message])},ElMessageBox:{confirm:(...args)=>{confirmations.push(args);return confirm(...args)},prompt:async()=>({value:''})}}
 const {descriptor}=parse(fs.readFileSync(`${frontend}/src/components/ConsultationWorkspace.vue`,'utf8'),{filename:'ConsultationWorkspace.vue'})
 const script=compileScript(descriptor,{id:`consult-review-${id}`}),template=compileTemplate({source:descriptor.template.content,filename:'ConsultationWorkspace.vue',id:`consult-review-${id}`,compilerOptions:{bindingMetadata:script.bindings,hoistStatic:false}})
 assert.deepEqual(template.errors,[])
 let code=(script.content.replace('export default','const __component =')+'\n'+template.code.replace('export function render','function render')+'\n__component.render=render;export default __component;')
 .replace(/from ['"]vue['"]/g,`from '${vueUrl}'`).replace(/from ['"]vue-router['"]/g,`from '${routerUrl}'`)
 .replace(/import \{ ElMessage, ElMessageBox \} from ['"]element-plus['"]/g,`const {ElMessage,ElMessageBox}=globalThis['${key}']`)
 .replace(/import \* as api from ['"]@\/api\/careJourney['"]/g,`const {api}=globalThis['${key}']`)
 .replace(/from ['"]@\/utils\/authSession['"]/g,`from 'file://${frontend}/src/utils/authSession.js'`)
 .replace(/import\.meta\.env\.VITE_WEBRTC_ICE_SERVERS/g,"''")
 const Component=(await import(`data:text/javascript;base64,${Buffer.from(code).toString('base64')}`)).default
 const node=(tag,text='')=>({tag,text,children:[],props:{},parent:null,addEventListener(){},removeEventListener(){},getAttribute(key){return this.props[key]},setAttribute(key,value){this.props[key]=value},removeAttribute(key){delete this.props[key]},get options(){return this.children}})
 const renderer=createRenderer({createElement:tag=>node(tag),createText:text=>node('#text',text),createComment:text=>node('#comment',text),setText:(n,text)=>n.text=text,setElementText:(n,text)=>{n.text=text;n.children=[]},parentNode:n=>n.parent,nextSibling:n=>n.parent?.children[n.parent.children.indexOf(n)+1]||null,patchProp:(n,key,old,value)=>{n.props[key]=value;if(key==='value')n.value=value},insert:(n,parent,anchor)=>{if(n.parent){const i=n.parent.children.indexOf(n);if(i>=0)n.parent.children.splice(i,1)}n.parent=parent;const i=anchor?parent.children.indexOf(anchor):-1;parent.children.splice(i<0?parent.children.length:i,0,n)},remove:n=>{if(n.parent)n.parent.children.splice(n.parent.children.indexOf(n),1)}})
 const patientId=ref(11),enabled=ref(true),host={render:()=>h(Component,{patientId:patientId.value,enabled:enabled.value,locale:edition==='frontend'?'en':'zh'})}
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/care-journey',component:host},{path:'/other',component:{render:()=>h('p','Other page')}}]})
 const window=Object.assign(new EventTarget(),{isSecureContext:false,setTimeout(){return 0},setInterval(){return 0}})
 const oldWindow=globalThis.window,oldStorage=globalThis.localStorage
 globalThis.window=window;globalThis.localStorage={getItem:()=> '11'}
 const rootNode=node('root'),app=renderer.createApp({render:()=>h(RouterView)})
 for(const tag of ['el-card','el-form','el-form-item','el-input','el-select','el-option','el-switch','el-table','el-table-column','el-button','el-alert','el-radio','el-radio-group','el-radio-button','el-empty','el-tag'])app.component(tag,{props:['modelValue'],setup(props,{slots}){return()=>h('div',{},tag==='el-table-column'?[]:[slots.header?.(),slots.default?.()])}})
 app.use(router);await router.push('/care-journey?tab=consultation&consultationId=1');await router.isReady();app.mount(rootNode);await flush()
 t.after(()=>{app.unmount();delete globalThis[key];globalThis.window=oldWindow;globalThis.localStorage=oldStorage})
 const result={router,window,patientId,enabled,confirmations,messages,calls,get vm(){return findComponent(rootNode._vnode,'ConsultationWorkspace')?.setupState},async push(path){const outcome=await router.push(path);await flush();return outcome}}
 assert.equal(result.vm.activeConsultation.id,1)
 return result
}
for(const edition of [import.meta.url.includes('/cn/')?'cn/frontend':'frontend']){
 test(`${edition}: failed route-linked room must retain source draft`,async t=>{
  const v=await fixture(t,edition,{getConsultation:async id=>{if(Number(id)===2)throw Error('Synthetic target offline');return {data:room(id)}}})
  v.vm.draft.content='Synthetic retained source text'
  await v.push('/care-journey?tab=consultation&consultationId=2')
  assert.equal(v.vm.activeConsultation.id,1)
  assert.equal(v.vm.draft.content,'Synthetic retained source text')
 })
 test(`${edition}: edits typed during target loading require fresh discard approval`,async t=>{
  const pending=deferred(),v=await fixture(t,edition,{getConsultation:async id=>Number(id)===2?pending.promise:{data:room(id)}})
  // Source starts clean, so no discard consent has ever been given.
  const opening=v.vm.openConsultation(2);await flush()
  assert.equal(v.vm.openingId,2);assert.equal(v.confirmations.length,0)
  v.vm.draft.content='Synthetic new text typed while target loads'
  pending.resolve({data:room(2)});await opening;await flush()
  assert.equal(v.vm.activeConsultation.id,1,'source should remain until fresh discard approval')
  assert.equal(v.vm.draft.content,'Synthetic new text typed while target loads')
 })
 test(`${edition}: canceled replacement switch must not strand source send controls`,async t=>{
  const pending=deferred(),v=await fixture(t,edition,{getConsultation:async id=>Number(id)===2?pending.promise:{data:room(id)}},async()=>{throw 'cancel'})
  const opening=v.vm.openConsultation(2);await flush()
  v.vm.draft.content='Synthetic text after first target started'
  await v.vm.openConsultation(3);await flush()
  pending.resolve({data:room(2)});await opening;await flush()
  assert.equal(v.vm.activeConsultation.id,1)
  assert.equal(v.vm.openingId,null,'settled requests must release opening state after latest switch is canceled')
  assert.equal(v.vm.canSend,true)
 })
 test(`${edition}: router keep-editing keeps source and query; logout clears immediately`,async t=>{
  const v=await fixture(t,edition,{},async()=>{throw 'cancel'})
  v.vm.draft.content='Synthetic protected draft'
  const result=await v.push('/care-journey?tab=consultation&consultationId=2')
  assert.equal(isNavigationFailure(result),true);assert.equal(v.router.currentRoute.value.query.consultationId,'1');assert.equal(v.vm.draft.content,'Synthetic protected draft')
  v.window.dispatchEvent(new Event('auth-session-cleared'));await flush();assert.equal(v.vm.activeConsultation,null);assert.equal(v.vm.draft.content,'')
 })
 test(`${edition}: ordinary failed open preserves source and approved discard snapshot`,async t=>{
  const v=await fixture(t,edition,{getConsultation:async id=>{if(Number(id)===2)throw Error('Synthetic target offline');return {data:room(id)}}})
  v.vm.draft.content='Synthetic retained text';await v.vm.openConsultation(2);await flush()
  assert.equal(v.vm.activeConsultation.id,1);assert.equal(v.vm.draft.content,'Synthetic retained text');assert.equal(v.vm.openingId,null)
 })
}
for(const edition of [import.meta.url.includes('/cn/')?'cn/frontend':'frontend'])test(`${edition}: internal route write must not exempt unrelated concurrent navigation`,async t=>{
 const pending=deferred(),v=await fixture(t,edition,{},async()=>{throw 'cancel'})
 v.router.beforeEach(to=>to.query.consultationId==='2'?pending.promise:true)
 const opening=v.vm.openConsultation(2);await flush()
 assert.equal(v.vm.activeConsultation.id,2)
 v.vm.draft.content='Synthetic draft typed in newly loaded room'
 const leaving=await v.push('/other')
 pending.resolve(true);await opening;await flush()
 assert.equal(isNavigationFailure(leaving),true,'unrelated route must still request keep/discard')
 assert.equal(v.vm.draft.content,'Synthetic draft typed in newly loaded room')
})
for(const edition of [import.meta.url.includes('/cn/')?'cn/frontend':'frontend'])test(`${edition}: successful consultation creation is clean for next navigation`,async t=>{
 const v=await fixture(t,edition,{startConsultation:async()=>({data:room(2)})},async()=>{throw 'cancel'})
 v.vm.form.doctorUserId=77;v.vm.form.symptom='Synthetic submitted question'
 await v.vm.beginConsultation();await flush();assert.equal(v.vm.activeConsultation.id,2)
 assert.equal(v.vm.form.symptom,'')
 const leaving=await v.push('/other')
 assert.equal(isNavigationFailure(leaving),false,'successfully saved form must not prompt as still dirty')
 assert.equal(v.confirmations.length,0)
})
for(const edition of [import.meta.url.includes('/cn/')?'cn/frontend':'frontend'])for(const key of ['token','userId',null])test(`${edition}: cross-tab ${key ?? 'storage-clear'} auth boundary clears consultation draft`,async t=>{
 const v=await fixture(t,edition);v.vm.draft.content='Synthetic prior-account clinical text';v.vm.form.medicalHistory='Synthetic prior-account form'
 v.window.dispatchEvent(Object.assign(new Event('storage'),{key,oldValue:'synthetic-old',newValue:null}));await flush()
 assert.equal(v.vm.draft.content,'');assert.equal(v.vm.form.medicalHistory,'');assert.equal(v.vm.activeConsultation,null)
})

const edition=import.meta.url.includes('/cn/')?'cn/frontend':'frontend'
for (const key of ['token','userId',null]) test(`${edition}: auth storage boundary invalidates a pending room load`,async t=>{
 const pending=deferred(),v=await fixture(t,edition,{getConsultation:async id=>Number(id)===2?pending.promise:{data:room(id)}})
 const opening=v.vm.openConsultation(2);await flush();v.vm.draft.content='Synthetic source draft'
 v.window.dispatchEvent(Object.assign(new Event('storage'),{key}));pending.resolve({data:room(2)});await opening;await flush()
 assert.equal(v.vm.activeConsultation,null);assert.equal(v.vm.draft.content,'');assert.equal(v.vm.openingId,null)
})
test(`${edition}: auth storage boundary invalidates a pending message completion`,async t=>{
 const pending=deferred(),v=await fixture(t,edition,{sendConsultationMessage:()=>pending.promise})
 v.vm.draft.content='Synthetic message';const sending=v.vm.sendMessage();await flush()
 v.window.dispatchEvent(Object.assign(new Event('storage'),{key:'userId'}));pending.resolve({});await sending;await flush()
 assert.equal(v.vm.activeConsultation,null);assert.equal(v.vm.draft.content,'');assert.equal(v.vm.sending,false)
})
test(`${edition}: unrelated storage preserves current consultation input`,async t=>{
 const v=await fixture(t,edition);v.vm.draft.content='Synthetic current input';v.vm.form.medicalHistory='Synthetic unsent history'
 v.window.dispatchEvent(Object.assign(new Event('storage'),{key:'care-senior'}));await flush()
 assert.equal(v.vm.draft.content,'Synthetic current input');assert.equal(v.vm.form.medicalHistory,'Synthetic unsent history');assert.equal(v.vm.activeConsultation.id,1)
})
