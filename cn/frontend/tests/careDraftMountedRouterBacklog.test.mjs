import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import { parse, compileScript, compileTemplate } from '@vue/compiler-sfc'
import { createRenderer, h, ref, nextTick } from 'vue'
import { createMemoryHistory, createRouter, RouterView, isNavigationFailure, NavigationFailureType } from 'vue-router'
const moduleUrl = code => `data:text/javascript;base64,${Buffer.from(code).toString('base64')}`
const vueUrl = new URL('../node_modules/vue/dist/vue.runtime.esm-bundler.js', import.meta.url).href
const routerUrl = new URL('../node_modules/vue-router/dist/vue-router.mjs', import.meta.url).href
async function flush() { for(let i=0;i<10;i++){await Promise.resolve();await nextTick()} }
function findComponent(vnode,name){if(!vnode)return;const instance=vnode.component;if(instance){if(instance.type.__name===name)return instance;const found=findComponent(instance.subTree,name);if(found)return found}for(const child of Array.isArray(vnode.children)?vnode.children:[]){const found=findComponent(child,name);if(found)return found}}
async function fixture(t) {
 const {descriptor}=parse(fs.readFileSync(new URL('../src/components/CareEntryDialog.vue',import.meta.url),'utf8'),{filename:'CareEntryDialog.vue'})
 const script=compileScript(descriptor,{id:'care-draft-mounted'}),template=compileTemplate({source:descriptor.template.content,filename:'CareEntryDialog.vue',id:'care-draft-mounted',compilerOptions:{bindingMetadata:script.bindings,hoistStatic:false}})
 assert.deepEqual(template.errors,[])
 const code=(script.content.replace('export default','const __component =')+'\n'+template.code.replace('export function render','function render')+'\n__component.render=render;export default __component;')
  .replace(/from ['"]vue['"]/g,`from '${vueUrl}'`).replace(/from ['"]vue-router['"]/g,`from '${routerUrl}'`)
  .replace(/import \{ ElMessage \} from ['"]element-plus['"]/g,'const ElMessage={success(){},warning(){},error(){}}')
  .replace(/import \{ saveCareItem \} from ['"]@\/api\/care['"]/g,'const saveCareItem=async()=>({})')
  .replace(/from ['"]@\/utils\/authSession['"]/g,`from '${new URL('../src/utils/authSession.js',import.meta.url).href}'`)
  .replace(/import \{ localDateKey \} from ['"]@\/utils\/familyHealth['"]/g,"const localDateKey=()=> '2026-10-11'")
 const Entry=(await import(moduleUrl(code))).default,visible=ref(true),node=(tag,text='')=>({tag,text,children:[],props:{},parent:null,addEventListener(){},removeEventListener(){},getAttribute(key){return this.props[key]},setAttribute(key,value){this.props[key]=value},removeAttribute(key){delete this.props[key]},get options(){return this.children}})
 const renderer=createRenderer({createElement:tag=>node(tag),createText:text=>node('#text',text),createComment:text=>node('#comment',text),setText:(n,text)=>n.text=text,setElementText:(n,text)=>{n.text=text;n.children=[]},parentNode:n=>n.parent,nextSibling:n=>n.parent?.children[n.parent.children.indexOf(n)+1]||null,patchProp:(n,key,old,value)=>{n.props[key]=value;if(key==='value')n.value=value},insert:(n,parent,anchor)=>{if(n.parent){const i=n.parent.children.indexOf(n);if(i>=0)n.parent.children.splice(i,1)}n.parent=parent;const i=anchor?parent.children.indexOf(anchor):-1;parent.children.splice(i<0?parent.children.length:i,0,n)},remove:n=>{if(n.parent)n.parent.children.splice(n.parent.children.indexOf(n),1)}})
 const host={render:()=>h(Entry,{modelValue:visible.value,'onUpdate:modelValue':value=>visible.value=value,kind:'QUESTION',patientId:11,patientName:'Synthetic patient'})}
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/care',component:host},{path:'/other',component:{render:()=>h('p','Other page')}}]})
 const window=new EventTarget();globalThis.window=window
 const root=node('root'),app=renderer.createApp({render:()=>h(RouterView)})
 for(const tag of ['el-dialog','el-form','el-form-item','el-input','el-select','el-option','el-date-picker','el-button','el-alert','el-radio','el-radio-group','el-checkbox','el-checkbox-group','el-time-picker','el-input-number','el-slider'])app.component(tag,{props:['modelValue'],setup(props,{slots}){return()=>h('div',{},tag==='el-dialog'?[slots.default?.(),slots.footer?.()]:slots.default?.())}})
 app.use(router);await router.push('/other');await router.push('/care');await router.isReady();app.mount(root);await flush()
 t.after(()=>app.unmount())
 return {root,router,window,visible,get vm(){return findComponent(root._vnode,'CareEntryDialog')?.setupState},async push(path){const result=await router.push(path);await flush();return result}}
}
test('mounted care entry directive registers actual router and patient guards and removes them on leave',async t=>{
 const v=await fixture(t);v.vm.form.title='Synthetic dirty question';await flush()
 let result=await v.push('/other');assert.equal(isNavigationFailure(result,NavigationFailureType.aborted),true);assert.equal(v.router.currentRoute.value.path,'/care');assert.equal(v.vm.discardVisible,true)
 v.vm.keepEditing();result=await v.push('/care?tab=questions');assert.equal(isNavigationFailure(result,NavigationFailureType.aborted),true);assert.equal(v.vm.form.title,'Synthetic dirty question')
 const event=new Event('care-plan-before-context-change',{cancelable:true});v.window.dispatchEvent(event);assert.equal(event.defaultPrevented,true)
 v.vm.discardChanges();await flush();assert.equal(v.visible.value,false);assert.equal(v.vm.form.title,'');await v.push('/other');assert.equal(v.router.currentRoute.value.path,'/other')
 const clean=new Event('care-plan-before-context-change',{cancelable:true});v.window.dispatchEvent(clean);assert.equal(clean.defaultPrevented,false)
})
test('mounted care entry auth loss bypasses discard prompts and clears sensitive input',async t=>{
 const v=await fixture(t);v.vm.form.title='Synthetic confidential draft';await flush();v.window.dispatchEvent(new Event('auth-session-cleared'));await flush()
 assert.equal(v.vm.form.title,'');assert.equal(v.vm.discardVisible,false);assert.equal(v.visible.value,false);await v.push('/other');assert.equal(v.router.currentRoute.value.path,'/other')
})

for (const key of ['token','userId',null]) test(`mounted care entry cross-tab ${key ?? 'clear'} auth boundary clears sensitive input`,async t=>{
 const v=await fixture(t);v.vm.form.title='Synthetic previous-account draft';await flush()
 v.window.dispatchEvent(Object.assign(new Event('storage'),{key}));await flush()
 assert.equal(v.vm.form.title,'');assert.equal(v.visible.value,false)
})
