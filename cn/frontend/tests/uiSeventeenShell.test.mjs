import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import { ref, watch, computed, nextTick } from 'vue'
const root = new URL('../src/', import.meta.url)
const read = (file, cn=false) => fs.readFileSync(cn ? new URL('../../cn/frontend/src/'+file, new URL('../../', import.meta.url)) : new URL(file, root), 'utf8')
// Removing index movement or the selected-index navigation fails this real handler test.
test('feature search moves selection, resets query and protects IME', async () => {
 const source=read('App.vue'), calls=[], searchResults=ref([{path:'/one'},{path:'/two'},{path:'/three'}]), searchQuery=ref(''),searchVisible=ref(true)
 const block=source.slice(source.indexOf('const activeSearchIndex'),source.indexOf('function onSearchShortcut'))
 const deps={ref,watch,computed,searchResults,searchQuery,searchVisible,router:{push:x=>calls.push(x)},document:{activeElement:null,getElementById:()=>null},searchInput:ref(null)}
 const v=new Function(...Object.keys(deps),block+';return {activeSearchIndex,moveSearchResult,openFirstResult}')(...Object.values(deps))
 const event={preventDefault(){},stopPropagation(){}}
 v.moveSearchResult(1,event);v.moveSearchResult(1,event);v.openFirstResult(event)
 assert.deepEqual(calls,['/three'])
 v.openFirstResult({...event,isComposing:true});assert.equal(calls.length,1)
 searchQuery.value='new';await nextTick();assert.equal(v.activeSearchIndex.value,0)
 searchResults.value=[];v.moveSearchResult(-1,event);assert.equal(v.activeSearchIndex.value,0)
})
test('authorized patient selector shows name and record ID',()=>{
 const source=read('components/PatientSwitcher.vue')
 assert.match(source, /patient\.id.*档案|档案.*patient\.id/)
 assert.doesNotMatch(source, /:label="patient.patientName \|\| patient.name"/)
})
test('nurse guide and responsive search use stable visible targets without unrelated fallback',()=>{
 const guide=read('components/OnboardingGuide.vue'), app=read('App.vue')
 assert.match(guide,/nurse:\[/)
 assert.match(guide,/data-guide="feature-search"/)
 assert.equal((app.match(/data-guide="feature-search"/g)||[]).length,2)
 assert.doesNotMatch(guide,/querySelector\(step.selector\) \|\| document.querySelector\('.workspace-content'\)/)
 assert.match(guide,/targetUnavailable/)
})
test('export calendar range matches Monday-to-Sunday backend semantics and leap years',async()=>{
 const {exportDateRange}=await import('../src/utils/exportPresentation.js')
 assert.deepEqual(exportDateRange('week','2026-01-01'),{from:'2025-12-29',to:'2026-01-04'})
 assert.deepEqual(exportDateRange('month','2024-02'),{from:'2024-02-01',to:'2024-02-29'})
 assert.deepEqual(exportDateRange('year','2026'),{from:'2026-01-01',to:'2026-12-31'})
 assert.equal(exportDateRange('week','2026-02-30'),null)
 assert.equal(exportDateRange('week','2026-01-00'),null)
 assert.equal(exportDateRange('month','2026-00'),null)
 assert.equal(exportDateRange('month','2026'),null)
 assert.equal(exportDateRange('week','2026'),null)
})
test('health report submission repeats current identity and export scope beside actions',()=>{
 const source=read('views/HealthReportManager.vue')
 assert.match(source,/class="report-export-scope"/)
 assert.match(source,/currentPatientName/)
 assert.match(source,/reportDateRange/)
 assert.match(source,/不是匿名数据/)
})

test('browser onboarding fixture uses the same nurse role key as the product',()=>{
 const helper=fs.readFileSync(new URL('../e2e/helpers.mjs',import.meta.url),'utf8')
 const list=helper.match(/const roleCode = (\[[^\]]+\])/)[1]
 assert.equal(new Function('roles',`return ${list}.find(code=>roles.includes(code))||'patient'`)(['patient','nurse']),'nurse')
})
