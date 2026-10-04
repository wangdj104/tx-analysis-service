import test from 'node:test'
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { mkdtemp, mkdir, writeFile, readFile, readdir, symlink, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
let reports={}
try {reports=await import('../package-care-report-artifacts.mjs')}catch(error){if(error.code!=='ERR_MODULE_NOT_FOUND')throw error}
const fixture=Buffer.from('Synthetic file-selection fixture, not PDF content evidence.')
const entry={edition:'en',language:'zh-CN',scenario:'owner-desktop',format:'pdf',bytes:fixture.length,sha256:createHash('sha256').update(fixture).digest('hex'),pageCount:2,result:'passed'}
const manifest=files=>({syntheticOnly:true,files})
test('report evidence derives exact filename and permits only fixed safe metadata',()=>{
  assert.equal(typeof reports.reportEvidenceFilename,'function')
  assert.equal(reports.reportEvidenceFilename(entry),'en-zh-CN-owner-desktop-pdf.pdf')
  for(const invalid of [{...entry,token:'forbidden'},{...entry,scenario:'arbitrary'},{...entry,language:'cn'},{...entry,result:'maybe'},{...entry,csvRowCount:4},{...entry,sha256:'abc'},{...entry,bytes:31*1024*1024}])assert.throws(()=>reports.reportEvidenceFilename(invalid),/report/)
  assert.throws(()=>reports.validateReportManifest({...manifest([entry]),headers:{}}),/report/)
  assert.throws(()=>reports.validateReportManifest(manifest([entry,entry])),/report/)
  assert.throws(()=>reports.validateReportManifest({...manifest([entry]),syntheticOnly:false}),/report/)
})
test('strict report package copies exact hashes once and rejects undeclared/raw session files',async()=>{
  assert.equal(typeof reports.packageReportArtifacts,'function')
  const base=await mkdtemp(join(tmpdir(),'report-artifact-fixture-')),source=join(base,'source'),output=join(base,'output')
  try {
    await mkdir(source);const filename=reports.reportEvidenceFilename(entry)
    await writeFile(join(source,filename),fixture);await writeFile(join(source,'report-evidence.json'),JSON.stringify(manifest([entry])))
    await writeFile(join(source,'storageState.json'),'forbidden')
    await assert.rejects(reports.packageReportArtifacts(source,output),/report/)
    assert.deepEqual(await readdir(base),['source'])
    await rm(join(source,'storageState.json'))
    const result=await reports.packageReportArtifacts(source,output)
    assert.equal(result.selectedFileCount,1)
    assert.deepEqual(await readFile(join(output,'report-1',filename)),fixture)
    const delivered=JSON.parse(await readFile(join(output,'report-manifest','report-evidence.json'),'utf8'))
    assert.deepEqual(delivered,manifest([entry]))
    await assert.rejects(reports.packageReportArtifacts(source,output),/destination/)
  } finally {await rm(base,{recursive:true,force:true})}
})
test('report package rejects symlinks and hash mismatches without producing output',async()=>{
  assert.equal(typeof reports.packageReportArtifacts,'function')
  const base=await mkdtemp(join(tmpdir(),'report-artifact-refusal-')),source=join(base,'source'),output=join(base,'output')
  try {
    await mkdir(source);const filename=reports.reportEvidenceFilename(entry)
    await writeFile(join(source,'report-evidence.json'),JSON.stringify(manifest([entry])))
    await symlink('/nonexistent-private',join(source,filename))
    await assert.rejects(reports.packageReportArtifacts(source,output),/symlink/)
    await rm(join(source,filename));await writeFile(join(source,filename),Buffer.alloc(fixture.length))
    await assert.rejects(reports.packageReportArtifacts(source,output),/hash/)
    assert.deepEqual(await readdir(base),['source'])
  } finally {await rm(base,{recursive:true,force:true})}
})
test('staging uses only owned CI evidence root and cannot adopt arbitrary manifest or duplicate files',async()=>{
  assert.equal(typeof reports.stageReportEvidence,'function')
  const base=await mkdtemp(join(tmpdir(),'report-stage-fixture-')),input=join(base,'download.pdf')
  try {
    await writeFile(input,fixture)
    const env={CI:'true',GITHUB_ACTIONS:'true',RUNNER_TEMP:base}
    await assert.rejects(reports.stageReportEvidence(input,entry,{...env,CI:undefined}),/CI/)
    await reports.stageReportEvidence(input,entry,env)
    const output=join(base,'care-report-evidence-en')
    assert.deepEqual(JSON.parse(await readFile(join(output,'report-evidence.json'),'utf8')),manifest([entry]))
    await assert.rejects(reports.stageReportEvidence(input,entry,env),/duplicate/)
    const second={...entry,language:'en'}
    await reports.stageReportEvidence(input,second,env)
    assert.equal(JSON.parse(await readFile(join(output,'report-evidence.json'),'utf8')).files.length,2)
  } finally {await rm(base,{recursive:true,force:true})}
})

test('report payloads and metadata have separate bounded partitions with no arbitrary JSON',async()=>{
  assert.equal(typeof reports.packageReportArtifacts,'function')
  const base=await mkdtemp(join(tmpdir(),'report-partition-fixture-')),source=join(base,'source'),output=join(base,'output')
  try {
    await mkdir(source)
    const bytes=Buffer.alloc(1000,65),sha256=createHash('sha256').update(bytes).digest('hex')
    const entries=['owner-desktop','family-desktop','doctor-desktop'].map(scenario=>({...entry,scenario,bytes:bytes.length,sha256}))
    for(const item of entries)await writeFile(join(source,reports.reportEvidenceFilename(item)),bytes)
    await writeFile(join(source,'report-evidence.json'),JSON.stringify(manifest(entries)))
    const result=await reports.packageReportArtifacts(source,output,{budgetBytes:1500})
    for(const group of ['report-1','report-2','report-3'])assert.equal(result.groups[group].length,1)
    assert.ok(Buffer.byteLength(await readFile(join(output,'report-manifest','report-evidence.json')))<=1500)
  } finally {await rm(base,{recursive:true,force:true})}
})
