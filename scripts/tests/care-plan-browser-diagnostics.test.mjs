import test from 'node:test'
import assert from 'node:assert/strict'
import * as runner from '../verify-care-plan-browser.mjs'
import { mkdtemp, mkdir, readFile, rm, symlink, writeFile, copyFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { spawnSync } from 'node:child_process'

const valid = { CARE_PLAN_TEST_ONLY: 'true', CARE_PLAN_BROWSER_REQUIRED: 'true', CI: 'true', GITHUB_ACTIONS: 'true',
  CARE_PLAN_MYSQL_HOST: '127.0.0.1', CARE_PLAN_MYSQL_PORT: '13306', CARE_PLAN_MYSQL_DATABASE: 'care_plan_test_fresh',
  CARE_PLAN_MYSQL_UPGRADE_DATABASE: 'care_plan_test_upgrade', CARE_PLAN_MYSQL_RESTORE_DATABASE: 'care_plan_test_restore',
  CARE_PLAN_MYSQL_USER: 'care_plan_test_browser', CARE_PLAN_MYSQL_PASSWORD: 'SyntheticTestOnly0123456789',
  CARE_PLAN_MYSQL_CONTAINER_ID: '1234567890abcdef', CARE_PLAN_MYSQL_PROJECT: '.', CARE_PLAN_E2E_PASSWORD: 'SyntheticLoginOnly0123456789' }

function tracker() {
  assert.equal(typeof runner.createBrowserDiagnostics, 'function', 'bounded browser stage diagnostics are required')
  return runner.createBrowserDiagnostics()
}

test('actual environment refusal records its stage and static reason without exposing the input', async () => {
  const diagnostics=tracker()
  await assert.rejects(runner.main({...valid, CARE_PLAN_MYSQL_HOST:'private-patient-host'}, diagnostics), /guard/)
  const result=diagnostics.snapshot()
  assert.equal(result.status,'failed')
  assert.equal(result.failure.stage,'environment-guard')
  assert.deepEqual(result.failure.error,{name:'AssertionError',code:'ERR_ASSERTION',reason:'environment-host'})
  assert.doesNotMatch(JSON.stringify(result),/private-patient-host|SyntheticTestOnly|SyntheticLoginOnly/)
})

test('actual missing font fails before any disposable service command', async () => {
  const diagnostics=tracker(),directory=await mkdtemp(join(tmpdir(),'care-plan-stage-font-'))
  try {
    await assert.rejects(runner.main({...valid,RUNNER_TEMP:directory,REPORT_PDF_FONT_PATH:join(directory,'care-report-font','wqy-microhei.ttf')},diagnostics),{code:'ENOENT'})
    const result=diagnostics.snapshot()
    assert.deepEqual(result.completedStages,['environment-guard'])
    assert.equal(result.failure.stage,'report-font')
    assert.equal(result.failure.error.code,'ENOENT')
    assert.doesNotMatch(JSON.stringify(result),new RegExp(directory))
  } finally {await rm(directory,{recursive:true,force:true})}
})

test('first failure survives cleanup and unknown error content is never serialized', async () => {
  const diagnostics=tracker()
  const failure=Object.assign(new Error('patient=private password=short secret'),{name:'private-patient',code:'private-token',signal:'private-signal',exitCode:'private-exit'})
  await assert.rejects(diagnostics.run('frontend-build',async()=>{throw failure}),error=>error===failure)
  await diagnostics.run('frontend-cleanup',async()=>{})
  await assert.rejects(diagnostics.run('backend-stop',async()=>{throw new TypeError('cleanup secret')}))
  const result=diagnostics.snapshot()
  assert.equal(result.failure.stage,'frontend-build')
  assert.deepEqual(result.failure.error,{name:'Error',reason:'unclassified'})
  assert.doesNotMatch(JSON.stringify(result),/private|patient|secret/)
  assert.ok(Buffer.byteLength(JSON.stringify(result))<16384)
})

test('successful stage results and repeated video stages remain bounded', async () => {
  const diagnostics=tracker()
  for(let i=0;i<100;i++)assert.equal(await diagnostics.run('recording-probe',async()=>42),42)
  diagnostics.complete()
  assert.equal(diagnostics.snapshot().status,'passed')
  assert.deepEqual(diagnostics.snapshot().completedStages,['recording-probe'])
  await assert.rejects(diagnostics.run('private-patient',async()=>{}),/diagnostic stage/)
  assert.doesNotMatch(JSON.stringify(diagnostics.snapshot()),/private-patient/)
})

test('captured subprocess overflow and exit are diagnosed without output or arguments', async () => {
  const diagnostics=tracker()
  assert.equal(typeof runner.capture,'function')
  await assert.rejects(diagnostics.run('production-jar-list',()=>runner.capture(process.execPath,['-e',"process.stdout.write('s'.repeat(100000))"],{})))
  assert.equal(diagnostics.snapshot().failure.error.reason,'capture-limit-exceeded')
  const exited=tracker()
  await assert.rejects(exited.run('service-identity',()=>runner.capture(process.execPath,['-e',"process.stderr.write('private-secret');process.exit(7)"],{})))
  assert.equal(exited.snapshot().failure.error.exitCode,7)
  assert.equal(exited.snapshot().failure.error.reason,'capture-command-failed')
  assert.doesNotMatch(JSON.stringify(exited.snapshot()),/private-secret|stderr|process\.exit/)
})

test('diagnostic writer refuses symlinks and exports only a bounded terminal snapshot', async () => {
  assert.equal(typeof runner.writeBrowserDiagnostics,'function')
  const directory=await mkdtemp(join(tmpdir(),'care-plan-stage-write-')),diagnostics=tracker()
  try {
    await mkdir(join(directory,'target'))
    const path=join(directory,'target','care-plan-browser-diagnostics.json')
    const outside=join(directory,'unrelated.json')
    await writeFile(outside,'keep')
    await symlink(outside,path)
    await assert.rejects(runner.writeBrowserDiagnostics(directory,diagnostics),/symlink|EEXIST/)
    assert.equal(await readFile(outside,'utf8'),'keep')
    await rm(path)
    await assert.rejects(diagnostics.run('production-jar',async()=>{throw Object.assign(new Error('secret path'),{code:'ENOENT'})}))
    await runner.writeBrowserDiagnostics(directory,diagnostics)
    const result=JSON.parse(await readFile(path,'utf8'))
    assert.equal(result.failure.stage,'production-jar')
    assert.equal(result.failure.error.code,'ENOENT')
    assert.doesNotMatch(JSON.stringify(result),/secret path/)
  } finally {await rm(directory,{recursive:true,force:true})}
})


test('startup cause chain and subprocess status are bounded and do not retain error payloads', async () => {
  const diagnostics=tracker()
  const safe='CARE_PLAN_BROWSER_BOOT_FAILURE org.springframework.beans.factory.BeanCreationException > java.lang.IllegalArgumentException'
  diagnostics.bootFailure(safe)
  diagnostics.bootFailure(safe+' password=private')
  diagnostics.backendFailure(Object.assign(new Error('private startup credentials'),{exitCode:1,signal:'SIGTERM'}))
  await assert.rejects(diagnostics.run('backend-readiness',async()=>{throw new Error('Actual Spring application stopped before readiness')}))
  const result=diagnostics.snapshot()
  assert.equal(result.bootFailure,safe)
  assert.deepEqual(result.backendFailure,{name:'Error',reason:'unclassified',exitCode:1,signal:'SIGTERM'})
  assert.equal(result.failure.error.reason,'backend-stopped')
  assert.doesNotMatch(JSON.stringify(result),/private|credentials/)
})

test('real CLI guard failure writes a collector-compatible artifact without running a service', async () => {
  const directory=await mkdtemp(join(tmpdir(),'care-plan-cli-diagnostic-'))
  try {
    await mkdir(join(directory,'scripts'))
    for(const name of ['verify-care-plan-browser.mjs','package-care-plan-browser-artifacts.mjs','collect-native-diagnostics.py'])
      await copyFile(new URL('../'+name,import.meta.url),join(directory,'scripts',name))
    const child=spawnSync(process.execPath,[join(directory,'scripts','verify-care-plan-browser.mjs')],{
      cwd:directory,env:{CI:'true',GITHUB_ACTIONS:'true',CARE_PLAN_MYSQL_PROJECT:'.'},encoding:'utf8',timeout:10000
    })
    assert.equal(child.status,1)
    assert.match(child.stderr,/CARE_PLAN_BROWSER_DIAGNOSTICS /)
    const result=JSON.parse(await readFile(join(directory,'target','care-plan-browser-diagnostics.json'),'utf8'))
    assert.equal(result.failure.stage,'environment-guard')
    assert.equal(result.failure.error.reason,'environment-care-plan-test-only')
    const collected=spawnSync('python3',[join(directory,'scripts','collect-native-diagnostics.py'),'.',join(directory,'out')],{cwd:directory,encoding:'utf8',timeout:10000})
    assert.equal(collected.status,0,collected.stderr)
    const artifact=JSON.parse(await readFile(join(directory,'out','native-diagnostics.json'),'utf8'))
    assert.equal(artifact.browserDiagnosticStatus,'present')
    assert.deepEqual(artifact.browser,result)
  } finally {await rm(directory,{recursive:true,force:true})}
})

test('native CI runs the browser diagnostic regressions before service acceptance', async () => {
  const workflow=await readFile(new URL('../../.github/workflows/ci.yml',import.meta.url),'utf8')
  const nativeJob=workflow.split('\n  care-plan-mysql:')[1] || ''
  const guardStep=nativeJob.match(/- name: Check MySQL test safety guards\n([\s\S]*?)(?=\n      - )/)?.[1] || ''
  assert.match(guardStep,/^          node --test scripts\/tests\/care-plan-browser-diagnostics\.test\.mjs$/m)
})
