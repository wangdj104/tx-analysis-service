import test from 'node:test'
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { mkdtemp, mkdir, writeFile, readFile, readdir, symlink, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

let artifacts={}
try { artifacts=await import('../package-care-plan-browser-artifacts.mjs') }
catch(error) { if(error.code!=='ERR_MODULE_NOT_FOUND' || !error.url?.endsWith('/package-care-plan-browser-artifacts.mjs'))throw error }
const main='carePlanCollaboration-real-dcac3-p-return-review-and-closure'
const video=(role,extension='webm',directory=main)=>`${directory}/${role}-desktop-video/${'a'.repeat(32)}.${extension}`
const file=(path,bytes=3)=>({path,bytes})
const plan=(files,options)=>{assert.equal(typeof artifacts.planArtifactGroups,'function','Bounded artifact planner is required');return artifacts.planArtifactGroups(files,options)}
const assigned=result=>Object.values(result.groups).flat().map(entry=>entry.path)

test('primary scenario contract matches the actual pinned Playwright output directory',async()=>{
  assert.equal(artifacts.PRIMARY_SCENARIO_DIRECTORY,main)
  const source=await readFile(new URL('../../frontend/e2e/carePlanCollaboration.spec.mjs',import.meta.url),'utf8')
  const title=source.match(/test\('([^']*real collaboration:[^']*)'/)?.[1]
  assert.ok(title,'The primary real collaboration scenario must be present')
  const full='carePlanCollaboration-'+title.replace(/[\x00-\x2C\x2E-\x2F\x3A-\x40\x5B-\x60\x7B-\x7F]+/g,'-')
  const middle='-'+createHash('sha1').update(full).digest('hex').slice(0,5)+'-',start=Math.floor((60-middle.length)/2)
  assert.equal(artifacts.PRIMARY_SCENARIO_DIRECTORY,full.slice(0,start)+middle+full.slice(-(60-middle.length-start)))
})
test('primary role delivery prefers existing MP4 and retains each raw source once in remainder',()=>{
  const files=[file(video('doctor')),file(video('doctor','mp4')),file('secondary/doctor-desktop-video/other.webm'),file(`${main}/en-completed-history.png`)]
  const result=plan(files)
  assert.deepEqual(result.groups['main-doctor'].map(f=>f.path),[video('doctor','mp4')])
  assert.deepEqual(result.groups['remainder-1'].map(f=>f.path),[video('doctor'),'secondary/doctor-desktop-video/other.webm'])
  assert.equal(new Set(assigned(result)).size,files.length)
  assert.equal(assigned(result).length,files.length)
})
test('failed workflow raw footage is delivered intact when it fits the primary role budget',()=>{
  const result=plan([file(video('nurse'),9)],{budgetBytes:10})
  assert.deepEqual(result.groups['main-nurse'].map(f=>f.path),[video('nurse')])
  assert.equal(result.groups['remainder-1'].length,0)
})
test('all six primary roles remain separate and formats are never duplicated across upload groups',()=>{
  const roles=['personal','family','doctor','nurse','admin','outsider']
  const files=roles.flatMap(role=>[file(video(role)),file(video(role,'mp4'))])
  const result=plan(files)
  for(const role of roles)assert.deepEqual(result.groups['main-'+role].map(file=>file.path),[video(role,'mp4')])
  assert.equal(result.groups['remainder-1'].length,roles.length)
  assert.deepEqual(assigned(result).sort(),files.map(file=>file.path).sort())
})
test('a single oversized recording fails instead of producing an undownloadable artifact',()=>{
  assert.throws(()=>plan([file(video('doctor'),11)],{budgetBytes:10}),/budget/)
})
test('screenshots use bounded fixed parts with excess retained once in remainder',()=>{
  const files=Array.from({length:6},(_,i)=>file(`scenario/screenshot-${i}.png`,7))
  const result=plan(files,{budgetBytes:10})
  for(let i=1;i<=4;i++)assert.equal(result.groups[`screenshots-${i}`].reduce((n,f)=>n+f.bytes,0),7)
  assert.equal(result.groups['remainder-1'].length,1)
  assert.equal(result.groups['remainder-2'].length,1)
  assert.equal(new Set(assigned(result)).size,files.length)
  assert.equal(assigned(result).length,files.length)
})
test('delivery whitelist excludes traces, sessions, dumps, raw logs, hidden paths and other JSON',()=>{
  const prohibited=['trace.zip','request.har','storageState.json','session.json','data.sql','raw.log','.last-run.json','.hidden/visible.png','nested/acceptance-evidence.json']
  const files=[...prohibited.map(path=>file(path)),file('acceptance-evidence.json'),file('scenario/screenshot.png')]
  const result=plan(files)
  assert.deepEqual(assigned(result).sort(),['acceptance-evidence.json','scenario/screenshot.png'])
  assert.throws(()=>plan([file('../outside.png')]),/path/)
  assert.throws(()=>plan([file('scenario/newline\nname.png')]),/path/)
})
test('packaging copies selected fixture bytes unchanged and leaves originals and excluded files intact',async()=>{
  assert.equal(typeof artifacts.packageBrowserArtifacts,'function','Safe file packaging is required')
  const base=await mkdtemp(join(tmpdir(),'care-plan-artifact-selection-')),source=join(base,'source'),destination=join(base,'delivery')
  // These are file-selection fixtures, not generated images or recorded media.
  const bytes=Buffer.from('File-selection fixture bytes; no media/content proof is claimed.')
  try {
    await mkdir(join(source,main),{recursive:true})
    await writeFile(join(source,main,'en-completed-history.png'),bytes)
    await writeFile(join(source,'storageState.json'),'Excluded synthetic session marker')
    const result=await artifacts.packageBrowserArtifacts(source,destination)
    const copy=join(destination,'screenshots-1',main,'en-completed-history.png')
    assert.deepEqual(await readFile(copy),bytes)
    assert.deepEqual(await readFile(join(source,main,'en-completed-history.png')),bytes)
    assert.equal(await readFile(join(source,'storageState.json'),'utf8'),'Excluded synthetic session marker')
    assert.equal(result.selectedFileCount,1)
    const manifest=JSON.parse(await readFile(join(destination,'manifest','delivery-manifest.json'),'utf8'))
    assert.equal(manifest.files[0].sha256,createHash('sha256').update(bytes).digest('hex'))
    assert.equal(manifest.files[0].group,'screenshots-1')
    assert.equal(JSON.stringify(manifest).includes('Excluded synthetic session marker'),false)
    await assert.rejects(artifacts.packageBrowserArtifacts(source,destination),/destination/)
  } finally {await rm(base,{recursive:true,force:true})}
})
test('packaging refuses source symlinks and unsafe evidence before creating any delivery payload',async()=>{
  assert.equal(typeof artifacts.packageBrowserArtifacts,'function','Safe file packaging is required')
  const base=await mkdtemp(join(tmpdir(),'care-plan-artifact-refusal-')),source=join(base,'source'),destination=join(base,'delivery')
  try {
    await mkdir(source)
    await symlink('/nonexistent-sensitive-source',join(source,'outside.png'))
    await assert.rejects(artifacts.packageBrowserArtifacts(source,destination),/symlink/)
    assert.deepEqual(await readdir(base),['source'])
    await rm(join(source,'outside.png'))
    await writeFile(join(source,'acceptance-evidence.json'),JSON.stringify({token:'SyntheticForbiddenMarker'}))
    await assert.rejects(artifacts.packageBrowserArtifacts(source,destination),/evidence/)
    assert.deepEqual(await readdir(base),['source'])
  } finally {await rm(base,{recursive:true,force:true})}
})
test('packaging rejects a source ancestor symlink before enumerating the physical outside source',async()=>{
  const base=await mkdtemp(join(tmpdir(),'care-plan-source-ancestor-')),outside=await mkdtemp(join(tmpdir(),'care-plan-source-outside-'))
  const destination=join(base,'delivery'),marker='Synthetic outside-tree source marker'
  try {
    await mkdir(join(outside,'source'))
    await writeFile(join(outside,'source','outside.png'),marker)
    await symlink(outside,join(base,'alias'),'dir')
    await assert.rejects(artifacts.packageBrowserArtifacts(join(base,'alias','source'),destination),/ancestor.*symlink/)
    assert.deepEqual((await readdir(base)).sort(),['alias'])
    assert.equal(await readFile(join(outside,'source','outside.png'),'utf8'),marker)
    assert.deepEqual(await readdir(outside),['source'])
  } finally {await rm(base,{recursive:true,force:true});await rm(outside,{recursive:true,force:true})}
})
test('packaging rejects a destination ancestor symlink before mkdir or copy can touch the outside tree',async()=>{
  const base=await mkdtemp(join(tmpdir(),'care-plan-destination-ancestor-')),outside=await mkdtemp(join(tmpdir(),'care-plan-destination-outside-'))
  const source=join(base,'source'),marker='Synthetic outside-tree destination marker'
  try {
    await mkdir(source)
    await writeFile(join(source,'fixture.png'),'File-selection fixture; not generated media')
    await writeFile(join(outside,'sentinel.txt'),marker)
    await symlink(outside,join(base,'alias'),'dir')
    await assert.rejects(artifacts.packageBrowserArtifacts(source,join(base,'alias','delivery')),/ancestor.*symlink/)
    assert.deepEqual(await readdir(outside),['sentinel.txt'])
    assert.equal(await readFile(join(outside,'sentinel.txt'),'utf8'),marker)
    assert.equal(await readFile(join(source,'fixture.png'),'utf8'),'File-selection fixture; not generated media')
  } finally {await rm(base,{recursive:true,force:true});await rm(outside,{recursive:true,force:true})}
})

// Removing remainder partitioning makes this fail: every original stays byte-identical exactly once.
test('all secondary recordings are bounded and partition overflow fails closed',()=>{
  const files=Array.from({length:16},(_,i)=>file(`secondary/clip-${i}.webm`,7))
  const result=plan(files,{budgetBytes:10})
  for(const entries of Object.values(result.groups))assert.ok(entries.reduce((sum,f)=>sum+f.bytes,0)<=10)
  assert.equal(assigned(result).length,files.length)
  assert.throws(()=>plan([...files,file('secondary/overflow.webm',7)],{budgetBytes:10}),/partition/)
})

test('CI explicitly uploads every bounded media and report partition',async()=>{
  const workflow=await readFile(new URL('../../.github/workflows/ci.yml',import.meta.url),'utf8')
  for(const group of [...artifacts.BROWSER_GROUPS,'manifest'])assert.ok(workflow.includes(`/care-plan-browser-artifacts-\${{ matrix.language }}/${group}/`),`Missing browser upload ${group}`)
  for(const group of [...Array.from({length:16},(_,i)=>'report-'+(i+1)),'report-manifest'])assert.ok(workflow.includes(`/care-report-artifacts-\${{ matrix.language }}/${group}/`),`Missing report upload ${group}`)
  assert.ok(workflow.includes('fonts-wqy-microhei python3-fonttools poppler-utils'))
  assert.ok(workflow.includes('/usr/bin/python3 scripts/prepare-care-report-font.py'))
  assert.ok(workflow.includes('for pass in 1 2; do'))
  assert.ok(workflow.includes("matrix:\n        directory: ['.', cn]"))
  assert.ok(workflow.includes('directory: [frontend, cn/frontend]'))
  assert.ok(workflow.includes('static-demo:'))
})

test('artifact source and destination trees must not contain each other',async()=>{
  const base=await mkdtemp(join(tmpdir(),'care-plan-ancestor-contract-')),source=join(base,'source')
  try {
    await mkdir(source);await writeFile(join(source,'fixture.png'),'Synthetic selection fixture')
    await assert.rejects(artifacts.packageBrowserArtifacts(source,join(source,'nested')),/ancestry/)
    assert.deepEqual(await readdir(source),['fixture.png'])
  }finally{await rm(base,{recursive:true,force:true})}
})

test('expanded acceptance has a 60-minute job budget while seven jobs and bounded requests remain intact',async()=>{
  const workflow=await readFile(new URL('../../.github/workflows/ci.yml',import.meta.url),'utf8')
  const jobs=workflow.slice(workflow.indexOf('\njobs:\n'))
  assert.deepEqual([...jobs.matchAll(/^  ([a-z-]+):$/gm)].map(match=>match[1]),['backend','frontend','static-demo','care-plan-mysql'])
  assert.match(jobs,/matrix:\n        directory: \['\.', cn\]/)
  assert.match(jobs,/matrix:\n        directory: \[frontend, cn\/frontend\]/)
  const native=jobs.slice(jobs.indexOf('  care-plan-mysql:'))
  assert.match(native,/timeout-minutes: 60\n/)
  assert.equal([...native.matchAll(/^            language: (en|cn)$/gm)].length,2)
  assert.ok(native.includes('for pass in 1 2; do'))
  for(const prefix of ['', 'cn/']) {
    const contract=await readFile(new URL(`../../${prefix}src/main/java/org/familyhealthcare/service/careplan/CareExecutionReportContracts.java`,import.meta.url),'utf8')
    const api=await readFile(new URL(`../../${prefix}frontend/src/api/careExecutionReport.js`,import.meta.url),'utf8')
    const requests=await readFile(new URL(`../../${prefix}frontend/src/utils/request.js`,import.meta.url),'utf8')
    const playwright=await readFile(new URL(`../../${prefix}frontend/playwright.config.mjs`,import.meta.url),'utf8')
    assert.match(contract,/REQUEST_TIMEOUT = Duration\.ofSeconds\(30\)/)
    assert.match(api,/timeout: 45000/)
    assert.match(requests,/timeout: 30000/)
    assert.match(playwright,/workers:1,retries:0,timeout:90000/)
  }
})

test('expanded media matrix permits 2048 exact files but refuses 2049 and paths above 256 bytes',()=>{
  const files=Array.from({length:2048},(_,index)=>file(`report/page-${String(index).padStart(4,'0')}.png`,3))
  const result=plan(files)
  assert.equal(assigned(result).length,2048)
  for(const entries of Object.values(result.groups))assert.ok(entries.reduce((sum,entry)=>sum+entry.bytes,0)<=30*1024*1024)
  assert.throws(()=>plan([...files,file('report/overflow.png')]),/count/)
  const path='r/'+ 'a'.repeat(249)+'.png'
  assert.equal(Buffer.byteLength(path),255)
  assert.equal(assigned(plan([file('r/'+'a'.repeat(250)+'.png')])).length,1)
  assert.throws(()=>plan([file('r/'+'a'.repeat(251)+'.png')]),/path/)
})

test('expanded media manifest stays bounded separately from every payload and refuses metadata overflow',async()=>{
  const base=await mkdtemp(join(tmpdir(),'care-plan-expanded-metadata-')),source=join(base,'source'),destination=join(base,'delivery')
  try {
    await mkdir(source)
    // Selection-only one-byte fixtures, not real screenshots or video evidence.
    for(let index=0;index<2048;index++)await writeFile(join(source,'p'.repeat(245)+String(index).padStart(4,'0')+'.png'),'x')
    const result=await artifacts.packageBrowserArtifacts(source,destination)
    assert.equal(result.selectedFileCount,2048)
    const manifest=await readFile(join(destination,'manifest','delivery-manifest.json'))
    assert.ok(manifest.length>256*1024 && manifest.length<=1024*1024)
    const metadata=JSON.parse(manifest)
    assert.equal(metadata.files.length,2048)
    for(const entries of Object.values(result.groups))assert.ok(entries.reduce((sum,file)=>sum+file.bytes,0)<=30*1024*1024)
    assert.ok(manifest.length<=30*1024*1024)
    // JSON-escaped quotes enlarge otherwise valid bounded paths, so the independent 1MiB cap must still fail.
    const overflow=join(base,'escaped'),output=join(base,'overflow');await mkdir(overflow)
    for(let index=0;index<2048;index++)await writeFile(join(overflow,'"'.repeat(245)+String(index).padStart(4,'0')+'.png'),'x')
    await assert.rejects(artifacts.packageBrowserArtifacts(overflow,output),/manifest size/)
    assert.ok(!(await readdir(base)).includes('overflow'))
  }finally{await rm(base,{recursive:true,force:true})}
})

test('safe recording counts up to 2048 fit bounded evidence while 2049 and oversized JSON fail',async()=>{
  const base=await mkdtemp(join(tmpdir(),'care-plan-expanded-evidence-')),source=join(base,'source'),destination=join(base,'delivery')
  const evidence={language:'en',actualSpring:true,actualMySQL:true,builtVue:true,videoCount:2048,screenshotCount:2048,recordings:Array.from({length:2048},()=>({width:1365,height:900,decodedFrames:999,durationSeconds:60})),trace:false}
  try {
    await mkdir(source);await writeFile(join(source,'acceptance-evidence.json'),JSON.stringify(evidence,null,2))
    assert.equal((await artifacts.packageBrowserArtifacts(source,destination)).selectedFileCount,1)
    await writeFile(join(source,'acceptance-evidence.json'),JSON.stringify({...evidence,videoCount:2049,recordings:[...evidence.recordings,evidence.recordings[0]]}))
    await assert.rejects(artifacts.packageBrowserArtifacts(source,join(base,'invalid-count')),/evidence counts/)
    await writeFile(join(source,'acceptance-evidence.json'),' '.repeat(512*1024+1))
    await assert.rejects(artifacts.packageBrowserArtifacts(source,join(base,'invalid-size')),/evidence size/)
  }finally{await rm(base,{recursive:true,force:true})}
})
