import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'

const title='real report coalesced App A→B→A retains the same panel but rejects its original late preview and CSV'
const asyncFunction=Object.getPrototypeOf(async function(){}).constructor
function deferred(){let resolve;const promise=new Promise(done=>{resolve=done});return{promise,resolve}}
for(const edition of ['frontend','cn/frontend']){
 const source=fs.readFileSync(new URL(`../../${edition}/e2e/careExecutionReportRaces.spec.mjs`,import.meta.url),'utf8')
 const scenario=source.slice(source.indexOf(`test('${title}'`))
 const preparation=scenario.match(/for\(const endpoint of \['preview','export'\]\) \{([\s\S]*?)const original = await panel\(page\)\.elementHandle\(\)/)?.[1]
 test(`${edition}: actual A-B-A race preparation waits for fresh patient bootstrap before keyboard input`,async()=>{
  assert.ok(preparation,'Extract the real preparation inside each preview/export iteration')
  const prepare=new asyncFunction('page','expect','openReport',preparation)
  // Browser-boundary doubles make the independent App names request stay
  // pending after the report panel is ready. The code under test is the actual
  // race-spec preparation, not a second implementation of its ordering.
  for(const endpoint of ['preview','export']){
   const ready=deferred(),waiting=deferred(),actions=[]
   let enabled=false,focused=false,expanded=false
   const control={
    async focus(){assert.ok(enabled,'Cannot focus until the patient list is ready');focused=true;actions.push('focus')},
    async press(key){assert.ok(enabled,`${endpoint}: ArrowDown must wait for enabled patient selection`);assert.equal(key,'ArrowDown');focused=true;expanded=true;actions.push('ArrowDown')}
   }
   const option={kind:'empty-patient-option'}
   const page={locator(selector){assert.equal(selector,'.patient-switcher');return{getByRole(role){assert.equal(role,'combobox');return control}}},getByRole(role,options){assert.equal(role,'option');assert.deepEqual(options,{name:'Synthetic Empty Report Patient',exact:true});return option},keyboard:{async press(key){assert.equal(key,'Escape');expanded=false;actions.push('Escape')}}}
   const expect=target=>({
    async toBeEnabled(){assert.equal(target,control);waiting.resolve();await ready.promise;assert.ok(enabled)},
    async toBeFocused(){assert.equal(target,control);assert.ok(focused)},
    async toHaveAttribute(name,value){assert.equal(target,control);assert.equal(name,'aria-expanded');assert.equal(String(expanded),value)},
    async toBeVisible(){assert.equal(target,option);assert.ok(enabled&&expanded,'Real options need a ready, open selector');actions.push('option-visible')}
   })
   const work=prepare(page,expect,async()=>{assert.equal(enabled,false)})
   await Promise.race([waiting.promise,work.then(()=>assert.fail('Preparation bypassed the patient-readiness wait'))])
   assert.deepEqual(actions,[],'The report can be ready while patient options are still unavailable')
   enabled=true;ready.resolve();await work
   assert.deepEqual(actions,['focus','ArrowDown','option-visible','Escape'])
   assert.equal(expanded,false,'Close the real dropdown before starting the protected response race')
  }
 })
 test(`${edition}: readiness synchronization leaves the real patient-boundary and late-response assertions intact`,()=>{
  for(const contract of [
   'b.click()', 'await Promise.resolve()', 'a.click()',
   'expect(accepted).toEqual({middle:ids.emptyReportPatient,last:ids.reportPatient})',
   "element===document.querySelector('.execution-report')",
   'hold.release();await hold.dispose();await waitForIdle(page)',
   'await assertClearedReport(page);expect(counter.count()).toBe(0)'
  ])assert.ok(scenario.includes(contract),contract)
  assert.doesNotMatch(preparation,/waitForTimeout|setTimeout|localStorage\.setItem|\.evaluate\(/)
 })
}
