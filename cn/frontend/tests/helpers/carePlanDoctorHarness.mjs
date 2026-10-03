import assert from 'node:assert/strict'
import fs from 'node:fs'
import { parse, compileScript, compileTemplate } from '@vue/compiler-sfc'
import { createRenderer, h, nextTick, ref } from 'vue'
import { matchedRouteKey } from 'vue-router'
const source = name => fs.readFileSync(new URL(`../../src/${name}`, import.meta.url), 'utf8')
const dataModule = code => `data:text/javascript;base64,${Buffer.from(code).toString('base64')}`
const vueUrl = new URL('../../node_modules/vue/dist/vue.runtime.esm-bundler.js', import.meta.url).href
const routerUrl = new URL('../../node_modules/vue-router/dist/vue-router.mjs', import.meta.url).href
let fixtureId = 0
const compiledUrls = {}
export function deferred() { let resolve, reject; const promise = new Promise((a,b) => {resolve=a;reject=b}); return {promise,resolve,reject} }
export async function flush() { for(let i=0;i<12;i++){await Promise.resolve();await nextTick()} }
export function findNode(node, testId) { if(node.props?.['data-testid']===testId)return node; for(const child of node.children||[]){const found=findNode(child,testId);if(found)return found} }
export function textOf(node) { return (node.text||'')+(node.children||[]).map(textOf).join(' ') }
export async function fixture(t, name, props, transport, slots) {
  const file=name==='DoctorWorkspace'?'views/DoctorWorkspace.vue':`components/care-plan/${name}.vue`
  assert.ok(fs.existsSync(new URL(`../../src/${file}`,import.meta.url)),`${name} care-plan component exists`)
  const storage = new Map([['token','synthetic-token'],['userId','51']]), writes=[]
  globalThis.localStorage={getItem:k=>storage.get(k)??null,setItem:(k,v)=>{writes.push([k,v]);storage.set(k,String(v))},removeItem:k=>storage.delete(k),key:i=>[...storage.keys()][i],get length(){return storage.size}}
  globalThis.window=new EventTarget()
  globalThis.Document=class Document {}; globalThis.ShadowRoot=class ShadowRoot {}
  const calls=[],slot=`__doctorTransport`
  globalThis[slot]=config=>{calls.push(config);return transport(config)}
  const authUrl=new URL(`../../src/utils/authSession.js`,import.meta.url).href
  const apiUrl=dataModule(source('api/carePlan.js').replace(/import request from ['"]@\/utils\/request['"]/g,`const request = (...args) => globalThis.${slot}(...args)`).replace(/from ['"]@\/utils\/authSession['"]/g,`from '${authUrl}'`))
  const doctorApiUrl=dataModule(source('api/doctorWorkspace.js').replace(/import request from ['"]@\/utils\/request['"]/g,`const request = (...args) => globalThis.${slot}(...args)`))
  const timeUrl=new URL('../../src/utils/carePlanTime.js',import.meta.url).href
  const composableUrl=dataModule(source('composables/useCarePlan.js').replace(/from ['"]vue['"]/g,`from '${vueUrl}'`).replace(/from ['"]@\/api\/carePlan['"]/g,`from '${apiUrl}'`).replace(/from ['"]@\/utils\/authSession['"]/g,`from '${authUrl}'`).replace(/from ['"]@\/utils\/carePlanTime['"]/g,`from '${timeUrl}'`))
  const urls=compiledUrls
  async function compile(componentName){
    if(urls[componentName])return urls[componentName]
    const filename=componentName==='DoctorWorkspace'?'views/DoctorWorkspace.vue':`components/care-plan/${componentName}.vue`,id=`doctor-${componentName}`,{descriptor}=parse(source(filename),{filename})
    const script=compileScript(descriptor,{id}),template=compileTemplate({source:descriptor.template.content,filename,id,compilerOptions:{bindingMetadata:script.bindings,hoistStatic:false}})
    assert.deepEqual(template.errors,[],`${componentName} compiles`)
    let code=script.content.replace(/export default/,'const __component =')+'\n'+template.code.replace('export function render','function render')+'\n__component.render=render; export default __component;'
    code=code.replace(/from ['"]vue['"]/g,`from '${vueUrl}'`).replace(/from ['"]vue-router['"]/g,`from '${routerUrl}'`).replace(/from ['"]@\/api\/carePlan['"]/g,`from '${apiUrl}'`).replace(/from ['"]@\/composables\/useCarePlan['"]/g,`from '${composableUrl}'`).replace(/from ['"]@\/utils\/authSession['"]/g,`from '${authUrl}'`).replace(/from ['"]@\/utils\/carePlanTime['"]/g,`from '${timeUrl}'`)
    code=code.replace(/from ['"]@\/api\/doctorWorkspace['"]/g,`from '${doctorApiUrl}'`).replace(/import \{ ElMessage, ElMessageBox \} from ['"]element-plus['"];?/g,`const ElMessage={success(){},warning(){}}; const ElMessageBox={prompt:async()=>({value:''})};`)
    for(const match of code.matchAll(/from ['"]@\/components\/care-plan\/(\w+)\.vue['"]/g))code=code.replace(match[0],`from '${await compile(match[1])}'`)
    for(const match of code.matchAll(/from ['"]\.\/(\w+)\.vue['"]/g))code=code.replace(match[0],`from '${await compile(match[1])}'`)
    urls[componentName]=dataModule(code);return urls[componentName]
  }
  const node=(tag,text='')=>({tag,tagName:tag.toUpperCase(),text,children:[],props:{},parent:null,style:{},addEventListener(){},removeEventListener(){},getRootNode(){return {}},getAttribute(k){return this.props[k]},setAttribute(k,v){this.props[k]=v},removeAttribute(k){delete this.props[k]},get options(){return this.children}})
  const renderer=createRenderer({createElement:tag=>node(tag),createText:s=>node('#text',s),createComment:s=>node('#comment',s),setText:(n,s)=>{n.text=s},setElementText:(n,s)=>{n.text=s;n.children=[]},parentNode:n=>n.parent,nextSibling:n=>n.parent?.children[n.parent.children.indexOf(n)+1]||null,patchProp:(n,k,o,v)=>{n.props[k]=v;if(k==='value')n.value=v},insert:(n,p,a)=>{if(n.parent){const i=n.parent.children.indexOf(n);if(i>=0)n.parent.children.splice(i,1)}n.parent=p;const i=a?p.children.indexOf(a):-1;p.children.splice(i<0?p.children.length:i,0,n)},remove:n=>{if(n.parent)n.parent.children.splice(n.parent.children.indexOf(n),1)}})
  const root=node('root'),component=(await import(await compile(name))).default,events=[]
  let currentProps={...props,onSaved:data=>events.push(['saved',data]),onClosed:()=>events.push(['closed']),onChanged:data=>events.push(['changed',data]),onOpen:id=>events.push(['open',id])}
  const app=renderer.createApp({render(){return null}})
  // Give the real router hooks a matched record in this component-only harness.
  // Navigation behavior is exercised separately by carePlanRouteGuards.test.mjs.
  app.provide(matchedRouteKey,ref({leaveGuards:new Set(),updateGuards:new Set()}))
  for(const tag of ['el-button','el-icon','el-tabs','el-tab-pane','el-table','el-table-column','el-tag','el-empty','el-dialog','el-form','el-form-item','el-select','el-option','el-input','el-radio-group','el-radio','el-date-picker','router-link','Refresh'])app.component(tag,{setup(p,{slots}){return ()=>h('div',{},tag==='el-table-column'?[]:slots.default?.())}})
  app.directive('loading',{})
  const render=()=>{const vnode=h(component,currentProps,slots);vnode.appContext=app._context;renderer.render(vnode,root)}
  render();await flush()
  t.after(()=>{renderer.render(null,root);delete globalThis[slot]})
  return {root,calls,events,storage,writes,auth:await import(authUrl),get vm(){return root._vnode.component.setupState},async unmount(){renderer.render(null,root);await flush()},async remount(p={}){currentProps={...currentProps,...p};render();await flush()},async update(p){currentProps={...currentProps,...p};render();await flush()},async click(id){await flush();const target=findNode(root,id);assert.ok(target,`${id} control rendered`);assert.ok(!target.props.disabled,`${id} control enabled`);await target.props.onClick?.({preventDefault(){}});await flush()}}
}
