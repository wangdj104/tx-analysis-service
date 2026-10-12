import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import { parse, compileScript, compileTemplate } from '@vue/compiler-sfc'
import { createRenderer, h, ref, nextTick } from 'vue'
import { createMemoryHistory, createRouter, RouterView, isNavigationFailure, NavigationFailureType } from 'vue-router'
let focused=null
const nodes=n=>[n,...(n.children||[]).flatMap(nodes)]
const moduleUrl = code => `data:text/javascript;base64,${Buffer.from(code).toString('base64')}`
const vueUrl = new URL('../node_modules/vue/dist/vue.runtime.esm-bundler.js', import.meta.url).href
const routerUrl = new URL('../node_modules/vue-router/dist/vue-router.mjs', import.meta.url).href
async function flush() { for(let i=0;i<10;i++){await Promise.resolve();await nextTick()} }
function findComponent(vnode,name){if(!vnode)return;const instance=vnode.component;if(instance){if(instance.type.__name===name)return instance;const found=findComponent(instance.subTree,name);if(found)return found}for(const child of Array.isArray(vnode.children)?vnode.children:[]){const found=findComponent(child,name);if(found)return found}}
async function fixture(t,edition) {
 const {descriptor}=parse(fs.readFileSync(new URL('../src/components/CareEntryDialog.vue',import.meta.url),'utf8'),{filename:'CareEntryDialog.vue'})
 const script=compileScript(descriptor,{id:'care-draft-mounted'}),template=compileTemplate({source:descriptor.template.content,filename:'CareEntryDialog.vue',id:'care-draft-mounted',compilerOptions:{bindingMetadata:script.bindings,hoistStatic:false}})
 assert.deepEqual(template.errors,[])
 const code=(script.content.replace('export default','const __component =')+'\n'+template.code.replace('export function render','function render')+'\n__component.render=render;export default __component;')
  .replace(/from ['"]vue['"]/g,`from '${vueUrl}'`).replace(/from ['"]vue-router['"]/g,`from '${routerUrl}'`)
  .replace(/import \{ ElMessage \} from ['"]element-plus['"]/g,'const ElMessage={success(){},warning(){},error(){}}')
  .replace(/import \{ saveCareItem \} from ['"]@\/api\/care['"]/g,'const saveCareItem=async()=>({})')
  .replace(/from ['"]@\/utils\/authSession['"]/g,`from '${new URL('../src/utils/authSession.js',import.meta.url).href}'`)
  .replace(/import \{ localDateKey \} from ['"]@\/utils\/familyHealth['"]/g,"const localDateKey=()=> '2026-10-11'")
 const Entry=(await import(moduleUrl(code))).default,visible=ref(true),node=(tag,text='')=>({tag,text,children:[],props:{},parent:null,addEventListener(){},removeEventListener(){},getAttribute(key){return this.props[key]},setAttribute(key,value){this.props[key]=value},removeAttribute(key){delete this.props[key]},focus(){focused=this},querySelector(selector){return nodes(this).slice(1).find(n=>n.tag==='input'&&n.props.type!=='hidden')||null},get options(){return this.children}})
 const renderer=createRenderer({createElement:tag=>node(tag),createText:text=>node('#text',text),createComment:text=>node('#comment',text),setText:(n,text)=>n.text=text,setElementText:(n,text)=>{n.text=text;n.children=[]},parentNode:n=>n.parent,nextSibling:n=>n.parent?.children[n.parent.children.indexOf(n)+1]||null,patchProp:(n,key,old,value)=>{n.props[key]=value;if(key==='value')n.value=value},insert:(n,parent,anchor)=>{if(n.parent){const i=n.parent.children.indexOf(n);if(i>=0)n.parent.children.splice(i,1)}n.parent=parent;const i=anchor?parent.children.indexOf(anchor):-1;parent.children.splice(i<0?parent.children.length:i,0,n)},remove:n=>{if(n.parent)n.parent.children.splice(n.parent.children.indexOf(n),1)}})
 const host={render:()=>h(Entry,{modelValue:visible.value,'onUpdate:modelValue':value=>visible.value=value,kind:'ORDER',patientId:11,patientName:'Synthetic patient'})}
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/care',component:host},{path:'/other',component:{render:()=>h('p','Other page')}}]})
 const window=new EventTarget();globalThis.window=window
 const root=node('root'),app=renderer.createApp({render:()=>h(RouterView)})
 for(const tag of ['el-dialog','el-form','el-form-item','el-input','el-select','el-option','el-date-picker','el-button','el-alert','el-radio','el-radio-group','el-checkbox','el-checkbox-group','el-time-picker','el-input-number','el-slider'])app.component(tag,{props:['modelValue','error'],setup(props,{slots,expose}){const native=ref(null);expose({focus:()=>native.value?.focus()});return()=>['el-input','el-date-picker','el-select'].includes(tag)?h('input',{ref:native}):h('div',{},tag==='el-dialog'?[slots.default?.(),slots.footer?.()]:[slots.default?.(),props.error?slots.error?.({error:props.error}):null])}})
 app.use(router);await router.push('/other');await router.push('/care');await router.isReady();app.mount(root);await flush()
 t.after(()=>app.unmount())
 return {root,router,window,visible,get vm(){return findComponent(root._vnode,'CareEntryDialog')?.setupState},async push(path){const result=await router.push(path);await flush();return result}}
}


for(const edition of [import.meta.url.includes('/cn/')?'cn/frontend':'frontend'])test(`${edition}: mounted production directive associates and removes required input errors`,async t=>{
 const v=await fixture(t,edition);v.vm.form.details.startDate=null;await v.vm.submit();await flush()
 assert.ok(v.vm.errors.title);assert.ok(v.vm.errors.medicationId);assert.ok(v.vm.errors.startDate);assert.ok(v.vm.errors.confirmed)
 for(const field of ['medicationId','startDate']){
  const wrapper=nodes(v.root).find(n=>n.props['data-care-required-field']===field);assert.ok(wrapper)
  const input=wrapper.querySelector('input');assert.ok(input);assert.equal(input.props['aria-describedby'],'care-entry-error-'+field);assert.equal(input.props['aria-invalid'],'true')
 }
 assert.equal(focused?.props['aria-describedby'],'care-entry-error-title')
 v.vm.form.title='Synthetic title';await v.vm.submit();await flush();assert.equal(focused?.props['aria-describedby'],'care-entry-error-medicationId')
 v.vm.form.details.medicationId=17;await flush()
 const medication=nodes(v.root).find(n=>n.props['data-care-required-field']==='medicationId').querySelector('input')
 assert.equal(medication.props['aria-describedby'],undefined);assert.equal(medication.props['aria-invalid'],undefined)
 await v.vm.submit();await flush();assert.equal(focused?.props['aria-describedby'],'care-entry-error-startDate')
})
