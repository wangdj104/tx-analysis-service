import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import {mountedReport,deferred,flush,syntheticReport,syntheticSummary} from './helpers/executionReportIntegration.mjs'

for(const first of ['legacy denial','care preview'])test(`actual paired print clears both rendered sources when ${first} settles first`,async t=>{
  let revoked=false,signal
  const legacy=deferred(),care=deferred()
  const v=await mountedReport(t,{family:true,
    summary:id=>revoked?legacy.promise:Promise.resolve({data:syntheticSummary(id,'Previously authorized legacy text')}),
    preview:(body,config)=>{if(!revoked)return Promise.resolve({data:syntheticReport(body,'Previously authorized care text')});signal=config.signal;return care.promise},
  })
  assert.equal(await v.family.loadSummary(),true)
  assert.ok(v.html.includes('Previously authorized legacy text'))
  assert.ok(v.html.includes('Previously authorized care text'))
  revoked=true
  const printing=v.family.printSummary();await flush()
  assert.equal(v.family.summary,null)
  assert.equal(v.panel.state.report,null)
  assert.equal(signal.aborted,false)
  if(first==='care preview'){
    care.resolve({data:syntheticReport({},'Fresh separately authorized care text')});await flush()
    assert.ok(v.html.includes('Fresh separately authorized care text'))
    assert.equal(v.windows.length,0,'Care authority alone cannot authorize a legacy popup')
  }
  legacy.reject(Object.assign(new Error('Synthetic legacy full-record denial'),{code:400,data:null}))
  assert.equal(await printing,false)
  assert.equal(signal.aborted,first==='legacy denial','Only an outstanding sibling is aborted')
  if(first==='legacy denial')care.resolve({data:syntheticReport({},'Late separately authorized care text')})
  await flush()
  assert.equal(v.family.summary,null);assert.equal(v.family.summaryLoading,false)
  assert.equal(v.panel.state.report,null);assert.equal(v.panel.state.lastExport,null)
  assert.equal(v.windows.length,0)
  for(const text of ['Previously authorized legacy text','Previously authorized care text','Fresh separately authorized care text','Late separately authorized care text'])assert.ok(!v.html.includes(text))
})

test('revoked full-record browser fixture observes authorized paired care before releasing unchanged legacy denial',async()=>{
  const source=fs.readFileSync(new URL('../e2e/careExecutionReportPrint.spec.mjs',import.meta.url),'utf8')
  const holdCode=source.slice(source.indexOf('async function holdLegacy('),source.indexOf('async function observeRealPrint('))
  const caseCode=source.slice(source.indexOf("test('real print authorization:"),source.indexOf("test('real print callback:"))
  const waiters=[],ids={reportPatient:9101,family:9002},owner={},full={id:71,grantee_role:'GUARDIAN',grantee_user_id:9002}
  let scenario,handler,careWaiter,careDelivered=false,denialDelivered=false,cleared=false,revoked=false,clicks=0,screenshots=0
  const checks={toBe:(actual,expected)=>assert.equal(actual,expected),toBeNull:actual=>assert.equal(actual,null),toHaveCount:(actual,expected)=>assert.equal(actual.count(),expected)}
  const expect=actual=>Object.assign(Object.fromEntries(Object.entries(checks).map(([name,check])=>[name,expected=>check(actual,expected)])),{not:{toHaveClass:pattern=>assert.ok(!pattern.test(actual.className()))}})
  const promise=()=>{const value=deferred();value.promise.catch(()=>{});return value}
  const denial={status:()=>200,json:async()=>({code:400,data:null}),request:()=>({method:()=> 'GET'}),url:()=> 'http://127.0.0.1:18081/api/family-health/visit-summary'}
  const page={
    on(){},off(){},locator:()=>({count:()=>cleared?0:1}),
    waitForResponse(predicate){const waiter=promise();waiters.push({predicate,...waiter});return waiter.promise},
    async route(pattern,callback){assert.equal(pattern,'**/api/family-health/visit-summary?**');assert.equal(handler,undefined);handler=callback},
    async unroute(pattern,callback){assert.equal(pattern,'**/api/family-health/visit-summary?**');assert.equal(handler,callback);handler=undefined},
  }
  function deliverDenial(){
    denialDelivered=true;cleared=true
    for(const waiter of waiters)if(waiter.predicate(denial))waiter.resolve(denial)
    if(!careDelivered)careWaiter.reject(new Error('Required paired HTTP200 cannot arrive after legitimate sibling cancellation'))
  }
  const bindings={
    test:(_name,callback)=>{scenario=callback},expect,ids,
    grant:async(actor,role,patient,options)=>{assert.equal(actor,owner);assert.equal(role,'family');assert.equal(patient,9101);assert.equal(options.accessLevel,'READ')},
    api:async(actor,path,options)=>{assert.equal(actor,owner);if(options?.method==='DELETE'){assert.equal(path,'/care-journey/access-grants/71');revoked=true;return}assert.equal(path,'/care-journey/access-grants?patientId=9101');return[full]},
    openSummary:async actual=>assert.equal(actual,page),generateSummary:async()=>{cleared=false},
    legacyResponse:actual=>actual.waitForResponse(response=>response.request().method()==='GET'&&new URL(response.url()).pathname==='/api/family-health/visit-summary'),
    reportResponse:actual=>{assert.equal(actual,page);careWaiter=promise();return careWaiter.promise},
    refresh:()=>({className:()=>''}),
    printButton:()=>({async click(){
      assert.equal(revoked,true);clicks++
      if(handler)void handler({fetch:async()=>denial,fulfill:async({response})=>{assert.equal(response,denial,'The real response object is forwarded unchanged');deliverDenial()}})
      else deliverDenial()
      queueMicrotask(()=>{if(!denialDelivered){careDelivered=true;careWaiter.resolve({status:()=>200})}})
    }}),
    assertClearedReport:async()=>assert.equal(cleared,true),
    screenshotReport:async actual=>{assert.equal(actual,page);assert.equal(cleared,true);screenshots++},
  }
  new Function(...Object.keys(bindings),holdCode+'\n'+caseCode)(...Object.values(bindings))
  assert.equal(typeof scenario,'function')
  await scenario({sessions:{owner,open:async role=>{assert.equal(role,'family');return page}}},{})
  assert.equal(clicks,1);assert.equal(careDelivered,true);assert.equal(denialDelivered,true);assert.equal(screenshots,1)
  assert.equal(handler,undefined,'The timing route is removed after the real case completes')
})
