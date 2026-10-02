import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import { computed, reactive, ref, watch, nextTick, effectScope } from 'vue'
import { parseQuery } from 'vue-router'
function setup(t,query={}){
 const route=reactive({query}),mount=[],scope=effectScope(),bindings={ref,computed,watch,onMounted:fn=>mount.push(fn),useRoute:()=>route}
 const script=fs.readFileSync(new URL('../src/views/HealthAnalysisManager.vue',import.meta.url),'utf8').match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import.*$/gm,'')
 const view=scope.run(()=>new Function(...Object.keys(bindings),script+'\nreturn {activeTab,resolveTab,currentTitle}')(...Object.values(bindings)))
 t.after(()=>scope.stop());return {...view,route,mount(){mount.forEach(fn=>fn())}}
}
test('repeated Vue Router tab parameters select the first tab without crashing',t=>{
 const view=setup(t,parseQuery('tab=health-report&tab=alert'));view.mount();assert.equal(view.activeTab.value,'health-report')
})
for (const value of [null,undefined,[],[null,'alert'],{},14]) {
 test(`malformed route tab ${JSON.stringify(value)} uses the existing fallback`,t=>{const view=setup(t);assert.equal(view.resolveTab(value),'complication')})
}
test('back and forward query changes select the corresponding tab',async t=>{
 const view=setup(t,{tab:'nutrition'});view.mount();assert.equal(view.activeTab.value,'nutrition')
 view.route.query=parseQuery('tab=health-report&tab=alert');await nextTick();assert.equal(view.activeTab.value,'health-report')
 view.route.query={tab:'nutrition'};await nextTick();assert.equal(view.activeTab.value,'nutrition')
 view.route.query={};await nextTick();assert.equal(view.activeTab.value,'complication')
})
test('existing valid tab names and casing stay supported',t=>{
 const view=setup(t)
 for(const tab of ['alert','bp-pattern','nutrition','nutrition-assessment','health-report','data-export','complication','automation']) assert.equal(view.resolveTab(tab.toUpperCase()),tab)
})
