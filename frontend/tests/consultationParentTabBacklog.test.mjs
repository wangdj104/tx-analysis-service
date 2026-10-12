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
async function fixture(t,edition,overrides={},confirm=async()=>{},journey=false){
 const id=++fixtureId,key=`__careReview${id}`,calls=[],messages=[],confirmations=[]
 const api={listMeasurements:async()=>({data:[]}),listConsultations:async()=>({data:[room(1),room(2),room(3)]}),listClinicians:async()=>({data:[]}),listConsultationInvitees:async()=>({data:[]}),listConsultationRecordOptions:async()=>({data:[]}),getConsultation:async id=>({data:room(id)}),...overrides}
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
 const patientId=ref(11),enabled=ref(true);let host={render:()=>h(Component,{patientId:patientId.value,enabled:enabled.value,locale:edition==='frontend'?'en':'zh'})}

 if(journey){
  Object.assign(globalThis[key],{useCurrentPatient:()=>({currentPatientId:patientId,currentPatientName:ref('Synthetic patient')}),useFocusedCareSource:()=>({attempted:ref(false),locator:ref(null),state:{loading:false,record:null}}),getCarePlanCapabilities:async()=>({data:{enabled:false}}),listNurseAssignments:async()=>({data:[]}),captureAuthSession:()=>({}),isAuthSessionCurrent:()=>true,AUTH_STORAGE_KEYS:[],request:async()=>({data:[]}),ConsultationWorkspace:Component})
  const {descriptor}=parse(fs.readFileSync(`${frontend}/src/views/CareJourneyManager.vue`,'utf8'),{filename:'CareJourneyManager.vue'})
  const script=compileScript(descriptor,{id:key+'-journey'}),template=compileTemplate({source:descriptor.template.content,filename:'CareJourneyManager.vue',id:key+'-journey',compilerOptions:{bindingMetadata:script.bindings,hoistStatic:false}})
  assert.deepEqual(template.errors,[])
  let code=script.content.replace('export default','const __component=')+'\n'+template.code.replace('export function render','function render')+'\n__component.render=render;export default __component;'
  code=code.replace(/import \* as api from [^\n]+/g,`const {api}=globalThis.${key}`)
   .replace(/import \{([^}]+)\} from ['"]([^'"]+)['"]/g,(all,names,path)=>['vue','vue-router'].includes(path)?`import {${names}} from '${path==='vue'?vueUrl:routerUrl}'`:`const {${names}}=globalThis.${key}`)
   .replace(/import (\w+) from ['"][^'"]+['"]/g,(_,name)=>`const {${name}}=globalThis.${key}`)
  host=(await import('data:text/javascript;base64,'+Buffer.from(code).toString('base64'))).default
 }
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/care-journey',component:host},{path:'/other',component:{render:()=>h('p','Other page')}}]})
 const window=Object.assign(new EventTarget(),{isSecureContext:false,setTimeout(){return 0},setInterval(){return 0}})
 const oldWindow=globalThis.window,oldStorage=globalThis.localStorage
 globalThis.window=window;globalThis.localStorage={getItem:key=>key==='userRoleCodes'?'["patient"]':'11'}
 const rootNode=node('root'),app=renderer.createApp({render:()=>h(RouterView)})
 for(const tag of new Set(['el-collapse','el-collapse-item','el-slider','el-tabs','el-tab-pane','el-date-picker','el-input-number','el-time-picker','el-divider','el-timeline','el-timeline-item','el-dialog','el-checkbox','el-checkbox-group','el-card','el-form','el-form-item','el-input','el-select','el-option','el-switch','el-table','el-table-column','el-button','el-alert','el-radio','el-radio-group','el-radio-button','el-empty','el-tag']))app.component(tag,{props:['modelValue'],setup(props,{slots}){return()=>h('div',{},tag==='el-table-column'?[]:[slots.header?.(),slots.default?.()])}})
 app.use(router);await router.push('/care-journey?tab=consultation&consultationId=1');await router.isReady();app.mount(rootNode);await flush()
 t.after(()=>{app.unmount();delete globalThis[key];globalThis.window=oldWindow;globalThis.localStorage=oldStorage})
 const result={router,window,patientId,enabled,confirmations,messages,calls,get journey(){return findComponent(rootNode._vnode,'CareJourneyManager')?.setupState},get vm(){return findComponent(rootNode._vnode,'ConsultationWorkspace')?.setupState},async push(path){const outcome=await router.push(path);await flush();return outcome}}
 assert.equal(result.vm.activeConsultation.id,1)
 return result
}

for(const edition of [import.meta.url.includes('/cn/')?'cn/frontend':'frontend'])test(`${edition}: actual CareJourney tab selection protects consultation drafts`,async t=>{
 const v=await fixture(t,edition,{},async()=>{throw 'cancel'},true)
 v.vm.draft.content='Synthetic draft before tab switch'
 // Element Plus emits update:modelValue followed by tab-change on a click.
 v.journey.tab='measurements';v.journey.syncTab('measurements');await flush()
 assert.ok(v.confirmations.length>0,'tab click must request keep/discard before disabled-access clearing')
 assert.equal(v.vm.draft.content,'Synthetic draft before tab switch')
 assert.equal(v.router.currentRoute.value.query.tab,'consultation')
 assert.equal(v.journey.tab,'consultation')
})

const edition=import.meta.url.includes('/cn/')?'cn/frontend':'frontend'
test(`${edition}: before-leave keeps the visible tab until draft confirmation resolves`,async t=>{
 const confirmation=deferred(),v=await fixture(t,edition,{},()=>confirmation.promise,true);v.vm.draft.content='Synthetic protected input'
 const changing=v.journey.beforeTabLeave('measurements');await flush();assert.equal(v.journey.tab,'consultation');assert.equal(v.vm.draft.content,'Synthetic protected input')
 confirmation.reject('cancel');assert.equal(await changing,false);await flush();assert.equal(v.journey.tab,'consultation');assert.equal(v.vm.draft.content,'Synthetic protected input')
})
test(`${edition}: approved tab navigation clears only after confirmation and commits route content`,async t=>{
 const v=await fixture(t,edition,{},async()=>{},true);v.vm.draft.content='Synthetic deliberately discarded input'
 assert.equal(await v.journey.beforeTabLeave('measurements'),true);await flush();assert.equal(v.journey.tab,'measurements');assert.equal(v.router.currentRoute.value.query.tab,'measurements');assert.equal(v.vm.draft.content,'');assert.equal(v.vm.activeConsultation,null)
})
