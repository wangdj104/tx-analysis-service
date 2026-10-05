import fs from 'node:fs'
import assert from 'node:assert/strict'
import {parse,compileScript,compileTemplate} from '@vue/compiler-sfc'
import {createRenderer,h,ref,proxyRefs,nextTick} from 'vue'
import {createRouter,createMemoryHistory,RouterView} from 'vue-router'
import {parseReportRoute,isNarrowCareRoute} from '../../src/utils/workspaceAccess.js'

const source=file=>fs.readFileSync(new URL(`../../src/${file}`,import.meta.url),'utf8')
const moduleUrl=code=>`data:text/javascript;base64,${Buffer.from(code).toString('base64')}`
const vueUrl=import.meta.resolve('vue'),routerUrl=import.meta.resolve('vue-router')
export const flush=async()=>{for(let i=0;i<8;i++){await Promise.resolve();await nextTick()}}
export function deferred(){let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b});return{promise,resolve,reject}}
export function syntheticReport(body={},text='Fresh care clinical text'){
 const patientId=body.patientId??1
 return {reportSchemaVersion:1,completeness:'COMPLETE',patient:{id:patientId,displayName:`Synthetic patient ${patientId}`},scope:{planId:body.planId??null,label:body.planId?'SINGLE_PLAN':'ALL_PLANS'},metadata:{fromDate:body.fromDate||'2026-09-05',toDate:body.toDate||'2026-10-04',timeZone:body.timeZone||'UTC',language:body.language||'en',rangeStartAt:'2026-09-05T00:00:00Z',rangeEndExclusiveAt:'2026-10-05T00:00:00Z',currentAsOf:'2026-10-04T12:00:00Z',generatedAt:'2026-10-04T12:00:01Z'},currentSummary:{total:1,open:1,needsHelp:0,submitted:0,confirmed:0,overdue:0,needsSupplement:0},currentActions:[{planId:3,revisionId:4,revisionNo:2,actionId:5,assignedUserId:7,assigneeAvailable:true,planTitle:'Synthetic published plan',instructions:'Synthetic instructions',instruction:text,status:'OPEN',dueAt:'2026-10-10T00:00:00Z',overdue:false,needsSupplement:false,evidence:[]}],currentAttention:[],activitySummary:{eventCount:0,distinctActionCount:0,eventTypeCounts:{}},periodEvents:[],questionsAvailability:'NOT_AUTHORIZED',questions:[]}
}
export const syntheticSummary=(id=1,text='Fresh legacy clinical text')=>({patient:{id,name:`Synthetic legacy patient ${id}`,medicalHistory:text},generatedAt:'2026-10-04T12:00:00Z',medications:[],recentMeasurements:[],unresolvedAlerts:[],questions:[],careSymptoms:[],recentEvents:[],disclaimer:'Synthetic summary disclaimer'})
function findComponent(vnode,name){if(!vnode)return;const instance=vnode.component;if(instance){if(instance.type.__name===name)return instance;const child=findComponent(instance.subTree,name);if(child)return child}for(const child of Array.isArray(vnode.children)?vnode.children:[]){const found=findComponent(child,name);if(found)return found}}
const escape=value=>String(value).replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;').replaceAll('"','&quot;')
// This serializes the actual renderer tree after Vue's DOM-update tick. It never
// supplies a canned summaryElement string and therefore detects stale print markup.
function serialize(node){if(node.tag==='#comment')return '';if(node.tag==='#text')return escape(node.text);const attrs=Object.entries(node.props).filter(([key,value])=>!key.startsWith('on')&&value!=null&&typeof value!=='object'&&typeof value!=='function').map(([key,value])=>` ${key}="${escape(value)}"`).join('');return `<${node.tag}${attrs}>${escape(node.text||'')}${node.children.map(serialize).join('')}</${node.tag}>`}
let serial=0
export async function mountedReport(t,{family=false,journey=false,initialRoute,selectedPatient=1,preview,summary,exportFile,sourceRead,familyRead,blockPopup=false,mutateFamily}={}){
 const id=++serial,slot=`__reportIntegration${id}`,storage=new Map([['token',`synthetic-${id}`],['userId','7'],['permissionSession',`synthetic-${id}`],['userMenus','["/family-health"]'],['userMenuNames','[]'],['userRoleCodes','["family"]']])
 const currentPatientId=ref(selectedPatient),calls=[],windows=[],downloads=[],createdUrls=[],revoked=[],warnings=[]
 globalThis.localStorage={getItem:key=>storage.get(key)??null,setItem:(key,value)=>storage.set(key,String(value)),removeItem:key=>storage.delete(key),key:index=>[...storage.keys()][index],get length(){return storage.size}}
 globalThis.window=Object.assign(new EventTarget(),{setInterval:()=>1,clearInterval(){},URL:{createObjectURL(blob){createdUrls.push(blob);return`blob:synthetic-${id}-${createdUrls.length}`},revokeObjectURL:url=>revoked.push(url)},open(){if(blockPopup)return null;const popup={closed:false,printed:false,html:'',document:{body:{replaceChildren(){popup.html=''}},write(html){popup.html=html},close(){}},focus(){},print(){popup.printed=true},close(){popup.closed=true},setTimeout(callback){popup.pending=callback}};windows.push(popup);return popup}})
 // Vue v-model updates test whether a node root is a real DOM document.
 globalThis.Document=class Document {};globalThis.ShadowRoot=class ShadowRoot {}
 globalThis.document={activeElement:null,body:{appendChild(){}},createElement(tag){return{tag,style:{},click(){downloads.push({href:this.href,fileName:this.download})},remove(){}}}}
 globalThis[slot]={currentPatientId,warnings,transport:async config=>{
  calls.push(config)
  if(config.url==='/care-plans/reports/preview')return preview?preview(config.data,config):{code:200,data:syntheticReport(config.data)}
  if(config.url==='/care-plans/reports/export')return exportFile?exportFile(config.data,config):{blob:new Blob(['\ufeffheader\r\nsynthetic\r\n'],{type:'text/csv'}),headers:{'content-disposition':`attachment; filename="care-execution-report-${config.data.language}-20261004T120000Z-actions.csv"`}}
  if(config.url==='/care-journey/measurements')return sourceRead?sourceRead(config.params,config):{code:200,data:[]}
  if(familyRead && (config.url.startsWith('/family-health/') || config.url==='/patient/specialty-menu-scope'))return familyRead(config)
  if(config.url==='/family-health/visit-summary')return summary?summary(config.params.patientId,config):{code:200,data:syntheticSummary(config.params.patientId)}
  return{code:200,data:config.url==='/family-health/target'?{}:[]}
 }}
 const authUrl=new URL(`../../src/utils/authSession.js?integration=${id}`,import.meta.url).href,displayUrl=new URL('../../src/utils/careExecutionReport.js',import.meta.url).href
 const api=file=>moduleUrl(source(file).replace(/import request from ['"]@\/utils\/request['"];?/g,`const request=(...args)=>globalThis.${slot}.transport(...args)`)
  .replace(/from ['"]@\/utils\/authSession['"]/g,`from '${authUrl}'`).replace(/from ['"]@\/utils\/careExecutionReport['"]/g,`from '${displayUrl}'`))
 const reportApiUrl=api('api/careExecutionReport.js'),familyApiUrl=api('api/familyHealth.js'),journeyApiUrl=api('api/careJourney.js'),careApiUrl=api('api/carePlan.js'),emptyUrl=moduleUrl('export default {render(){return null}}')
 const clientUrl=moduleUrl(source('composables/useCareExecutionReport.js').replace(/from ['"]vue['"]/g,`from '${vueUrl}'`).replace(/from ['"]@\/api\/careExecutionReport['"]/g,`from '${reportApiUrl}'`).replace(/from ['"]@\/utils\/authSession['"]/g,`from '${authUrl}'`).replace(/from ['"]@\/utils\/careExecutionReport['"]/g,`from '${displayUrl}'`))
 const patientUrl=moduleUrl(`export const useCurrentPatient=()=>({currentPatientId:globalThis.${slot}.currentPatientId})`)
 const elementsUrl=moduleUrl(`export const ElMessage={warning:value=>globalThis.${slot}.warnings.push(value),success(){}};export const ElMessageBox={}`)
 const urls={}
 async function compile(file){
  if(urls[file])return urls[file]
  const text=file==='views/FamilyHealthManager.vue'&&mutateFamily?mutateFamily(source(file)):source(file),{descriptor}=parse(text,{filename:file}),scope=`integration-${id}-${file}`
  const script=compileScript(descriptor,{id:scope}),template=compileTemplate({source:descriptor.template.content,filename:file,id:scope,compilerOptions:{bindingMetadata:script.bindings,hoistStatic:false}});assert.deepEqual(template.errors,[])
  let code=script.content.replace('export default','const Component =')+'\n'+template.code.replace('export function render','function render')+'\nComponent.render=render;export default Component;'
  code=code.replace(/from ['"]vue['"]/g,`from '${vueUrl}'`).replace(/from ['"]vue-router['"]/g,`from '${routerUrl}'`).replace(/from ['"]@\/composables\/useCareExecutionReport['"]/g,`from '${clientUrl}'`).replace(/from ['"]@\/api\/familyHealth['"]/g,`from '${familyApiUrl}'`).replace(/from ['"]@\/composables\/useCurrentPatient['"]/g,`from '${patientUrl}'`).replace(/from ['"]@\/utils\/authSession['"]/g,`from '${authUrl}'`).replace(/from ['"]element-plus['"]/g,`from '${elementsUrl}'`)
  code=code.replace(/from ['"]@\/api\/careJourney['"]/g,`from '${journeyApiUrl}'`).replace(/from ['"]@\/api\/carePlan['"]/g,`from '${careApiUrl}'`).replace(/from ['"]@\/composables\/useFocusedCareSource['"]/g,`from '${new URL('../../src/composables/useFocusedCareSource.js',import.meta.url).href}'`).replace(/from ['"]@\/components\/ConsultationWorkspace\.vue['"]/g,`from '${emptyUrl}'`).replace(/import request from ['"]@\/utils\/request['"];?/g,`const request=(...args)=>globalThis.${slot}.transport(...args)`)
  code=code.replace(/from ['"]@\/utils\/(careExecutionReport|workspaceAccess|familyHealth|carePlanTime|timelineText)['"]/g,(_,utility)=>`from '${new URL(`../../src/utils/${utility}.js`,import.meta.url).href}'`)
  for(const match of code.matchAll(/from ['"]@\/components\/care-plan\/(\w+)\.vue['"]/g))code=code.replace(match[0],`from '${await compile(`components/care-plan/${match[1]}.vue`)}'`)
  return urls[file]=moduleUrl(code)
 }
 const node=(tag,text='')=>({tag,tagName:tag.toUpperCase(),text,children:[],props:{},parent:null,style:{},addEventListener(){},removeEventListener(){},getRootNode(){return {}},setAttribute(key,value){this.props[key]=value},getAttribute(key){return this.props[key]},removeAttribute(key){delete this.props[key]},get options(){return this.children},get innerHTML(){return escape(this.text||'')+this.children.map(serialize).join('')}})
 const renderer=createRenderer({createElement:tag=>node(tag),createText:text=>node('#text',text),createComment:text=>node('#comment',text),setText:(target,text)=>{target.text=text},setElementText:(target,text)=>{target.text=text;target.children=[]},parentNode:target=>target.parent,nextSibling:target=>target.parent?.children[target.parent.children.indexOf(target)+1]||null,patchProp:(target,key,old,value)=>{target.props[key]=value;if(key==='value')target.value=value},insert(target,parent,anchor){if(target.parent){const index=target.parent.children.indexOf(target);if(index>=0)target.parent.children.splice(index,1)}target.parent=parent;const index=anchor?parent.children.indexOf(anchor):-1;parent.children.splice(index<0?parent.children.length:index,0,target)},remove(target){if(target.parent){const index=target.parent.children.indexOf(target);if(index>=0)target.parent.children.splice(index,1)}}})
 const component=(await import(await compile(family?'views/FamilyHealthManager.vue':journey?'views/CareJourneyManager.vue':'views/CareExecutionReportView.vue'))).default
 const router=createRouter({history:createMemoryHistory(import.meta.url.includes('/cn/frontend/')?'/cn/':'/'),routes:[{path:'/care-plans/reports',component},{path:'/family-health',component},{path:'/care-journey',component},{path:'/care',component:{render:()=>h('p','Other workspace')}}]})
 const root=node('root'),app=renderer.createApp({render:()=>h(RouterView)})
 for(const tag of new Set((source('views/FamilyHealthManager.vue')+source('views/CareJourneyManager.vue')).match(/el-[a-z-]+/g)))app.component(tag,{setup(props,{slots}){return()=>h('div',{},tag==='el-table-column'?[]:slots.default?.())}})
 app.directive('loading',{});app.config.warnHandler=message=>warnings.push(message);app.use(router)
 await router.push(initialRoute||(family?'/family-health?tab=summary':journey?'/care-journey?tab=measurements&patientId=10&measurementId=17':'/care-plans/reports?patientId=1'));await router.isReady();app.mount(root);await flush()
 const route=new Proxy({},{get:(_,key)=>router.currentRoute.value[key]}),switchCode=source('App.vue').match(/function switchPatient\(id\) \{([\s\S]*?)\n\}/)[0]
 const deps={currentPatientId,window,appPatientList:ref([{id:1},{id:2}]),ElMessage:{success(){},info(){}},parseReportRoute,isNarrowCareRoute,router,route,routerViewKey:ref('7'),getAuthSessionKey:()=> '7'}
 const switchPatient=new Function(...Object.keys(deps),switchCode+';return switchPatient')(...Object.values(deps))
 t.after(()=>{app.unmount();delete globalThis[slot]})
 const instance=name=>findComponent(root._vnode,name)
 return{root,router,route,calls,windows,downloads,createdUrls,revoked,warnings,currentPatientId,storage,switchPatient,auth:await import(authUrl),instance,get panel(){return proxyRefs(instance('ExecutionReportPanel').exposed)},get panelSetup(){return instance('ExecutionReportPanel').setupState},get family(){return instance('FamilyHealthManager')?.setupState},get journey(){return instance('CareJourneyManager')?.setupState},get html(){return root.innerHTML}}
}
