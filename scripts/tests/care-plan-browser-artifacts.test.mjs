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
  assert.deepEqual(result.groups.remainder.map(f=>f.path),[video('doctor'),'secondary/doctor-desktop-video/other.webm'])
  assert.equal(new Set(assigned(result)).size,files.length)
  assert.equal(assigned(result).length,files.length)
})
test('failed workflow raw footage is delivered intact when it fits the primary role budget',()=>{
  const result=plan([file(video('nurse'),9)],{budgetBytes:10})
  assert.deepEqual(result.groups['main-nurse'].map(f=>f.path),[video('nurse')])
  assert.equal(result.groups.remainder.length,0)
})
test('all six primary roles remain separate and formats are never duplicated across upload groups',()=>{
  const roles=['personal','family','doctor','nurse','admin','outsider']
  const files=roles.flatMap(role=>[file(video(role)),file(video(role,'mp4'))])
  const result=plan(files)
  for(const role of roles)assert.deepEqual(result.groups['main-'+role].map(file=>file.path),[video(role,'mp4')])
  assert.equal(result.groups.remainder.length,roles.length)
  assert.deepEqual(assigned(result).sort(),files.map(file=>file.path).sort())
})
test('oversized primary footage is preserved once in remainder and its delivery limit is explicit',()=>{
  const result=plan([file(video('doctor'),11)],{budgetBytes:10})
  assert.equal(result.groups['main-doctor'].length,0)
  assert.deepEqual(result.groups.remainder.map(f=>f.path),[video('doctor')])
  assert.deepEqual(result.oversizedPrimaryRoles,['doctor'])
})
test('screenshots use bounded fixed parts with excess retained once in remainder',()=>{
  const files=Array.from({length:6},(_,i)=>file(`scenario/screenshot-${i}.png`,7))
  const result=plan(files,{budgetBytes:10})
  for(let i=1;i<=4;i++)assert.equal(result.groups[`screenshots-${i}`].reduce((n,f)=>n+f.bytes,0),7)
  assert.equal(result.groups.remainder.length,2)
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
    const manifest=JSON.parse(await readFile(join(destination,'screenshots-1','delivery-manifest.json'),'utf8'))
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
