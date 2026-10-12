// Uses actual installed Element Plus SSR input markup and the production directive.
// This does not claim browser focus or assistive-technology verification.
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import { createSSRApp, h, reactive, ref, watch, nextTick, effectScope } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { ElDatePicker, ElSelect, ElOption, ID_INJECTION_KEY, ZINDEX_INJECTION_KEY } from 'element-plus'
import { parse } from '@vue/compiler-sfc'
function productionDirective(t) {
 const script=parse(fs.readFileSync(new URL('../src/components/CareEntryDialog.vue',import.meta.url),'utf8')).descriptor.scriptSetup.content.replace(/^import.*$/gm,'')
 const scope=effectScope(),deps={reactive,ref,watch,nextTick,defineProps:()=>({modelValue:false}),defineEmits:()=>()=>{},localDateKey:()=> '2026-10-11'}
 const result=scope.run(()=>new Function(...Object.keys(deps),script+'\nreturn typeof vCareFieldError === "undefined" ? null : vCareFieldError')(...Object.values(deps)))
 t.after(()=>scope.stop());return result
}
for(const readonly of [false,true]) for(const [field,Component] of [['eventAt',ElDatePicker],['medicationId',ElSelect],['startDate',ElDatePicker]])test(`required ${field} ${readonly ? "readonly" : "editable"} error is attached to the actual Element Plus native input and clears when corrected`,async t=>{
 const directive=productionDirective(t);assert.ok(directive,'actual controls need an error-association directive')
 const app=createSSRApp({render:()=>h(Component,{modelValue:null,filterable:Component===ElSelect&&!readonly,readonly},()=>Component===ElSelect?h(ElOption,{value:17,label:'Synthetic medication'}):null)})
 app.provide(ID_INJECTION_KEY,{prefix:81,current:0});app.provide(ZINDEX_INJECTION_KEY,{current:0});app.config.warnHandler=()=>{}
 const html=await renderToString(app),inputMarkup=html.match(/<input\b[^>]*>/)?.[0];assert.ok(inputMarkup)
 const attrs=new Map([...inputMarkup.matchAll(/([\w:-]+)="([^"]*)"/g)].map(match=>[match[1],match[2]]))
 attrs.set('aria-describedby','existing-help')
 const input={getAttribute:key=>attrs.get(key)??null,setAttribute:(key,value)=>attrs.set(key,value),removeAttribute:key=>attrs.delete(key)}
 const element={querySelector:selector=>{assert.match(selector,/input/);return input}}
 const id='care-entry-error-'+field,binding={value:{id,error:'Synthetic required error'}}
 directive.mounted(element,binding)
 assert.equal(attrs.get('aria-describedby'),'existing-help '+id);assert.equal(attrs.get('aria-invalid'),'true')
 directive.updated(element,binding);assert.equal(attrs.get('aria-describedby'),'existing-help '+id,'updates must not duplicate error IDs')
 directive.updated(element,{value:{id,error:undefined}})
 assert.equal(attrs.get('aria-describedby'),'existing-help');assert.equal(attrs.has('aria-invalid'),false)
 directive.updated(element,binding);directive.unmounted?.(element,binding)
 assert.equal(attrs.get('aria-describedby'),'existing-help','unmount removes only this error association');assert.equal(attrs.has('aria-invalid'),false)
})

test('native control replacement retains the active error association without a parent rerender', t => {
 const directive=productionDirective(t),oldObserver=globalThis.MutationObserver
 let observerCallback, disconnected=false
 globalThis.MutationObserver=class {constructor(callback){observerCallback=callback}observe(){}disconnect(){disconnected=true}}
 t.after(()=>{globalThis.MutationObserver=oldObserver})
 const makeInput=()=>{const attrs=new Map([['aria-describedby','existing-help']]);return {attrs,getAttribute:key=>attrs.get(key)??null,setAttribute:(key,value)=>attrs.set(key,value),removeAttribute:key=>attrs.delete(key)}}
 let input=makeInput();const element={querySelector:()=>input},binding={value:{id:'care-entry-error-medicationId',error:'Synthetic error'}}
 directive.mounted(element,binding);input=makeInput();observerCallback?.()
 assert.equal(input.attrs.get('aria-describedby'),'existing-help care-entry-error-medicationId')
 assert.equal(input.attrs.get('aria-invalid'),'true')
 directive.updated(element,{value:{...binding.value,error:undefined}});input=makeInput();observerCallback?.()
 assert.equal(input.attrs.get('aria-describedby'),'existing-help');assert.equal(input.attrs.has('aria-invalid'),false)
 directive.unmounted(element,binding);assert.equal(disconnected,true)
 observerCallback?.();assert.equal(input.attrs.get('aria-describedby'),'existing-help','a queued callback cannot restore an unmounted error')
})
