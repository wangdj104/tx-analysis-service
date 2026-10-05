import test from 'node:test'
import assert from 'node:assert/strict'
import {OUTCOME_CATALOG} from '../care-plan-browser-outcome-catalog.mjs'
let module={};try{module=await import('../care-plan-browser-outcomes.mjs')}catch(e){if(e.code!=='ERR_MODULE_NOT_FOUND')throw e}
const entry=()=>({id:OUTCOME_CATALOG[0].id,status:'failed',errorClass:'test_error',phase:'download-hash',source:{file:'frontend/e2e/reportDownloads.mjs',line:31,column:5}})
const manifest=()=>({schemaVersion:1,edition:'en',runStatus:'failed',globalError:'none',tests:[entry()]})
const validate=(value)=>{assert.equal(typeof module.validateOutcomeManifest,'function');return module.validateOutcomeManifest(value)}
test('outcome manifest accepts only bounded static identities and fixed safe fields',()=>{assert.deepEqual(validate(manifest()),manifest())})
test('outcome manifest rejects unknown fields, identities, paths, enums, duplicates and numeric bounds',()=>{
 const invalid=[... [null,false,0,''].map(source=>({...manifest(),tests:[{...entry(),source}]})),{...manifest(),message:'private'},...['status','errorClass','phase'].map(k=>({...manifest(),tests:[{...entry(),[k]:'private'}]})),{...manifest(),tests:[{...entry(),id:'private'}]},{...manifest(),tests:[entry(),entry()]},{...manifest(),tests:Array(129).fill(entry())},{...manifest(),tests:[{...entry(),source:{file:'/tmp/private',line:1,column:1}}]},...[-1,0,10001,1.2].map(line=>({...manifest(),tests:[{...entry(),source:{file:'frontend/e2e/reportDownloads.mjs',line,column:1}}]})),{...manifest(),tests:[{...entry(),source:{file:'frontend/e2e/reportDownloads.mjs',line:1,column:1,stack:'private'}}]}]
 for(const value of invalid)assert.throws(()=>validate(value))
 assert.equal(typeof module.parseOutcomeManifest,'function');assert.throws(()=>module.parseOutcomeManifest(Buffer.alloc(65537,32)));assert.throws(()=>module.parseOutcomeManifest(Buffer.from('{"private":true}')))
})
test('reporter records final teardown failure, preserves first failing operation phase, and omits all raw data',async()=>{
 assert.equal(typeof module.default,'function');let output
 const reporter=new module.default({edition:'en',root:'/repo',write:async value=>{output=value}}),catalog=OUTCOME_CATALOG[0],testCase={title:catalog.title,location:{file:'/repo/frontend/e2e/'+catalog.file,line:1,column:1},retries:0,repeatEachIndex:0},result={status:'passed',errors:[]}
 reporter.onBegin({}, {allTests:()=>[testCase]});reporter.onTestBegin(testCase,result)
 reporter.onStepBegin(testCase,result,{title:'care-phase:preview',category:'test.step'})
 reporter.onStepBegin(testCase,result,{title:'care-phase:teardown',category:'test.step'})
 const error={message:'private patient token payload',stack:'private stack',snippet:'private snippet',location:{file:'/repo/frontend/e2e/carePlanSessions.mjs',line:72,column:3}}
 reporter.onStepEnd(testCase,result,{title:'care-phase:teardown',category:'test.step',error})
 reporter.onTestEnd(testCase,{status:'failed',errors:[error],error});await reporter.onEnd({status:'failed'})
 assert.equal(output.tests[0].status,'failed');assert.equal(output.tests[0].phase,'teardown');assert.equal(output.tests[0].errorClass,'test_error');assert.doesNotMatch(JSON.stringify(output),/private|payload|token|stack|snippet/)
 assert.deepEqual(output.tests[0].source,{file:'frontend/e2e/carePlanSessions.mjs',line:72,column:3})
})
test('reporter fails closed for uncatalogued tests and writer errors',async()=>{
 assert.equal(typeof module.default,'function');const reporter=new module.default({edition:'en',root:'/repo',write:async()=>{throw Error('private')}})
 reporter.onBegin({}, {allTests:()=>[{title:'private',location:{file:'/repo/private',line:1,column:1}}]});assert.deepEqual(await reporter.onEnd({status:'passed'}),{status:'failed'})
})

test('installed Playwright final reporter includes fixture teardown and first body failure without raw diagnostics',async()=>{
 const {mkdtemp,mkdir,writeFile,readFile,rm,access}=await import('node:fs/promises'),{tmpdir}=await import('node:os'),{join}=await import('node:path'),{fileURLToPath,pathToFileURL}=await import('node:url'),{execFile}=await import('node:child_process'),{promisify}=await import('node:util')
 let installed;for(const edition of ['frontend','cn/frontend']){const candidate=fileURLToPath(new URL(`../../${edition}/node_modules/`,import.meta.url));try{await access(join(candidate,'playwright/cli.js'));installed=candidate;break}catch{}}assert.ok(installed)
 const directory=await mkdtemp(join(tmpdir(),'care-outcome-lifecycle-')),specDirectory=join(directory,'frontend/e2e'),specs=OUTCOME_CATALOG.slice(0,3),output=join(directory,'outcome.json')
 try{
  await mkdir(specDirectory,{recursive:true})
  const reporterUrl=new URL('../care-plan-browser-outcomes.mjs',import.meta.url).href
  await writeFile(join(directory,'reporter.mjs'),`import Reporter from ${JSON.stringify(reporterUrl)};import {writeFile} from 'node:fs/promises';export default class extends Reporter{constructor(){super({edition:'en',root:${JSON.stringify(directory)},write:value=>writeFile(${JSON.stringify(output)},JSON.stringify(value))})}}`)
  const playwright=pathToFileURL(join(installed,'@playwright/test/index.mjs')).href
  const first=JSON.stringify(specs[0].title),second=JSON.stringify(specs[1].title),third=JSON.stringify(specs[2].title)
  await writeFile(join(specDirectory,specs[0].file),`import {test as base,expect} from ${JSON.stringify(playwright)};const test=base.extend({cleanup:async({},use,info)=>{await use();if(info.title===${second})await test.step('care-phase:teardown',async()=>{throw Error('PRIVATE_TEARDOWN_TOKEN')})}});test(${first},async()=>{await test.step('care-phase:preview',async()=>{});expect(1).toBe(1)});test(${second},async({cleanup})=>{expect(1).toBe(1)});test(${third},async()=>{await test.step('care-phase:download-hash',async()=>{throw Error('PRIVATE_BODY_TOKEN')})});`)
  await writeFile(join(directory,'config.mjs'),`export default {testDir:'./frontend/e2e',workers:1,retries:0,timeout:5000,reporter:[['./reporter.mjs']]}`)
  let failure;try{await promisify(execFile)(process.execPath,[join(installed,'playwright/cli.js'),'test','--config',join(directory,'config.mjs')],{cwd:directory,timeout:20000,maxBuffer:65536})}catch(error){failure=error}assert.equal(failure?.code,1)
  const value=module.parseOutcomeManifest(await readFile(output));assert.deepEqual(value.tests.map(row=>row.status),['passed','failed','failed']);assert.deepEqual(value.tests.map(row=>row.phase),['preview','teardown','download-hash']);assert.doesNotMatch(JSON.stringify(value),/PRIVATE|TOKEN|message|stack|snippet/)
 }finally{await rm(directory,{recursive:true,force:true})}
})

test('outcome packaging accepts one validated regular file and rejects oversized, symlink and extra-key inputs',async()=>{
 const {mkdtemp,mkdir,writeFile,readFile,rm,symlink}=await import('node:fs/promises'),{tmpdir}=await import('node:os'),{join}=await import('node:path')
 const dir=await mkdtemp(join(tmpdir(),'care-outcome-package-'));try{
  const file=join(dir,'source.json');await writeFile(file,JSON.stringify(manifest()));assert.equal(await module.packageOutcome(file,join(dir,'valid')),1);assert.deepEqual(JSON.parse(await readFile(join(dir,'valid/outcomes.json'),'utf8')),manifest())
  await symlink(file,join(dir,'link.json'));await assert.rejects(module.packageOutcome(join(dir,'link.json'),join(dir,'bad-link')))
  await writeFile(file,Buffer.alloc(65537,32));await assert.rejects(module.packageOutcome(file,join(dir,'bad-size')))
  await writeFile(file,JSON.stringify({...manifest(),stack:'private'}));await assert.rejects(module.packageOutcome(file,join(dir,'bad-extra')))
 }finally{await rm(dir,{recursive:true,force:true})}
})

test('strict outcome contract covers payload boundaries, clean catalog and dedicated upload allowlist',async()=>{
 const {readFile}=await import('node:fs/promises')
 assert.equal(OUTCOME_CATALOG.length,58);assert.equal(new Set(OUTCOME_CATALOG.map(row=>row.id)).size,58);assert.equal(new Set(OUTCOME_CATALOG.map(row=>row.file+'\0'+row.title)).size,58)
 const small=Buffer.from(JSON.stringify(manifest()));assert.deepEqual(module.parseOutcomeManifest(Buffer.concat([small,Buffer.alloc(65536-small.length,32)])),manifest())
 for(const [key,value] of [['runStatus','private'],['edition','private'],['globalError','private'],['schemaVersion',2]])assert.throws(()=>validate({...manifest(),[key]:value}))
 for(const column of [0,-1,1001,1.1])assert.throws(()=>validate({...manifest(),tests:[{...entry(),source:{file:'frontend/e2e/reportDownloads.mjs',line:1,column}}]}))
 assert.throws(()=>validate({...manifest(),tests:[{...entry(),status:'passed'}]}))
 const workflow=await readFile(new URL('../../.github/workflows/ci.yml',import.meta.url),'utf8'),block=workflow.slice(workflow.indexOf('- name: Preserve bounded final browser outcomes'),workflow.indexOf('- name: Stage bounded sanitized browser review artifacts'))
 assert.match(block,/steps\.browser_outcomes\.outcome == 'success'/);assert.match(block,/path: \$\{\{ runner\.temp \}\}\/care-plan-outcome-artifacts-\$\{\{ matrix\.language \}\}\/outcomes\.json\n/);assert.doesNotMatch(block,/\*|test-results|report-evidence/)
})

test('both installed Playwright configs resolve the exact shared reporter from their config directory',async()=>{
 const {readFile,realpath}=await import('node:fs/promises'),{fileURLToPath}=await import('node:url')
 for(const edition of ['frontend','cn/frontend']){
  const config=new URL(`../../${edition}/playwright.config.mjs`,import.meta.url),text=await readFile(config,'utf8'),path=text.match(/\['([^']+care-plan-browser-outcomes\.mjs)'/)?.[1];assert.ok(path)
  let actual;try{actual=await realpath(new URL(path,config))}catch{actual=null}
  assert.equal(actual,fileURLToPath(new URL('../care-plan-browser-outcomes.mjs',import.meta.url)))
 }
})
