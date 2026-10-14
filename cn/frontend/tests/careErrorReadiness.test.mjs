// Current component state/attribute contracts only. This does not establish browser visibility.
import assert from 'node:assert/strict'
import test from 'node:test'
import {readFileSync} from 'node:fs'
import {reactive,ref,watch,effectScope} from 'vue'
const source=readFileSync(new URL('../src/components/CareEntryDialog.vue',import.meta.url),'utf8')
test('live required-field errors persist until the corresponding input is corrected',t=>{
 const scope=effectScope(),form=reactive({kind:'ORDER',title:'',details:{}}),errors=ref({}),attempted=ref(true)
 const code=source.slice(source.indexOf('function validationErrors()'),source.indexOf('function clearSensitive()'))
 const validate=scope.run(()=>new Function('form','errors','attempted','watch',code+';return validationErrors')(form,errors,attempted,watch));t.after(()=>scope.stop());errors.value=validate()
 assert.deepEqual(Object.keys(errors.value),['title','medicationId','startDate','confirmed']);form.title='Synthetic order';assert.deepEqual(Object.keys(errors.value),['medicationId','startDate','confirmed']);form.details.medicationId=9;assert.deepEqual(Object.keys(errors.value),['startDate','confirmed']);form.details.startDate='2026-10-13';form.details.confirmed=true;assert.deepEqual(errors.value,{})
})
test('required-control association preserves other help and clears only its own error',()=>{
 const attrs=new Map([['aria-describedby','help-text']]),input={getAttribute:k=>attrs.get(k),setAttribute:(k,v)=>attrs.set(k,v),removeAttribute:k=>attrs.delete(k)},element={querySelector:()=>input}
 const code=source.slice(source.indexOf('function syncFieldError('),source.indexOf('const vCareFieldError'))
 const sync=new Function(code+';return syncFieldError')();sync(element,{value:{error:'Required',id:'care-entry-error-medicationId'}});assert.equal(attrs.get('aria-describedby'),'help-text care-entry-error-medicationId');assert.equal(attrs.get('aria-invalid'),'true');sync(element,{value:{error:'',id:'care-entry-error-medicationId'}});assert.equal(attrs.get('aria-describedby'),'help-text');assert.equal(attrs.has('aria-invalid'),false)
})
