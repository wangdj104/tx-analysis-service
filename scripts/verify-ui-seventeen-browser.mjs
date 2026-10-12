// Fail-closed acceptance for fresh synthetic real-browser evidence, never source tests.
import { readFileSync, realpathSync, statSync, writeFileSync } from 'node:fs'
import { resolve, relative, isAbsolute, sep, join } from 'node:path'
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import { inflateSync } from 'node:zlib'
const [directory, commit]=process.argv.slice(2)
assert.ok(directory && /^[0-9a-f]{40}$/.test(commit||''),'Usage: evidence directory and full checkout SHA required')
const repo=realpathSync(fileURLToPath(new URL('../',import.meta.url)))
const hash=value=>createHash('sha256').update(value).digest('hex')
const sourceHash=(path)=>{assert.ok(typeof path==='string'&&!isAbsolute(path)&&!path.split(/[\\/]/).includes('..'),'Unsafe source path');const actual=realpathSync(resolve(repo,path));assert.ok(relative(repo,actual)&&!relative(repo,actual).startsWith(`..${sep}`),'Source path escapes repository');return hash(readFileSync(actual))}
const harnesses={care:'careDraftDialogBacklog',monitoring:'uiSeventeenMonitoring',clinical:'uiSeventeenClinical',shell:'uiSeventeenShell'}
const root=realpathSync(resolve(directory)), themes=['platform','white','blue','mint','sand','dark']
const groups={care:[1,3,6,7],monitoring:[2,4,8,10,13,17],clinical:[3,5,11,15,17],shell:[3,9,12,14,16,17]}
// Invalidate any prior success before reading evidence; a failing rerun must
// never upload an old passing summary. Only the completed gate writes passed.
writeFileSync(join(root,'acceptance-summary.json'),JSON.stringify({commit,mode:'browser',status:'failed',reason:'Acceptance validation incomplete or failed',verifiedAt:new Date().toISOString()},null,2)+'\n')
const coverage=new Set(), counts={}
for(const [group,required] of Object.entries(groups)) {
 const doc=JSON.parse(readFileSync(join(root,`${group}-results.json`),'utf8'))
 assert.equal(doc.commit,commit,`${group}: stale checkout`)
 assert.equal(doc.mode,'browser',`${group}: not an actual browser run`)
 for(const field of ['error','executionError','infrastructureError','fatalError'])assert.ok(!doc[field],`${group}: fatal ${field}`)
 assert.notEqual(doc.browserExecuted,false,`${group}: browser never executed`)
 if(doc.execution!==undefined)assert.equal(doc.execution,'executed',`${group}: not executed`)
 if(doc.browserAssertions!==undefined)assert.equal(doc.browserAssertions,'executed',`${group}: assertions never executed`)
 const harnessPath=`frontend/tests/${harnesses[group]}.browser.mjs`
 if(doc.harnessSha256!==undefined)assert.equal(doc.harnessSha256,sourceHash(harnessPath),`${group}: harness hash mismatch`)
 const sources=doc.sourceHashes||Object.fromEntries((doc.sourceFiles||[]).map(x=>[x.path,x.sha256]))
 assert.ok(Object.keys(sources).length,`${group}: source hashes missing`)
 for(const [path,digest] of Object.entries(sources))assert.equal(digest,sourceHash(path),`${group}: source hash mismatch ${path}`)
 assert.ok(doc.harnessSha256===sourceHash(harnessPath)||sources[harnessPath]===sourceHash(harnessPath),`${group}: harness integrity missing`)
 if(doc.sourceSha256!==undefined)assert.equal(doc.sourceSha256,hash(JSON.stringify(doc.sourceFiles)),`${group}: source aggregate mismatch`)
 assert.ok(Array.isArray(doc.results)&&doc.results.length,`${group}: no cases`)
 const cells=new Set()
 for(const row of doc.results) {
  assert.equal(row.status,'passed',`${group}: failing or unexecuted browser case`)
  assert.ok(['en','cn'].includes(row.edition)&&[1365,375].includes(row.viewport)&&themes.includes(row.theme),`${group}: unknown matrix cell`)
  assert.ok(Array.isArray(row.itemIds)&&row.itemIds.length&&row.itemIds.every(id=>Number.isInteger(id)&&id>=1&&id<=17),`${group}: invalid item IDs`)
  assert.ok(Array.isArray(row.screenshots)&&row.screenshots.length,`${group}: screenshot missing`)
  for(const file of row.screenshots) {
   assert.ok(typeof file==='string'&&!isAbsolute(file)&&!file.split(/[\\/]/).includes('..'),`${group}: unsafe screenshot path`)
   const path=realpathSync(resolve(root,file)), rel=relative(root,path)
   assert.ok(rel&&!rel.startsWith(`..${sep}`)&&!isAbsolute(rel),`${group}: screenshot escapes evidence directory`)
   assert.ok(statSync(path).isFile(),`${group}: screenshot not a file`)
   const bytes=readFileSync(path)
   const hashes=row.screenshotHashes||row.screenshotSha256
   assert.ok(hashes&&hashes[file],`${group}: screenshot hash missing`)
   assert.equal(hashes[file],hash(bytes),`${group}: screenshot hash mismatch`)
   if(row.screenshotHashes&&row.screenshotSha256)assert.equal(row.screenshotSha256[file],hash(bytes),`${group}: conflicting screenshot hash`)
   assert.ok(bytes.length>=24&&bytes.subarray(0,8).equals(Buffer.from('89504e470d0a1a0a','hex'))&&bytes.toString('ascii',12,16)==='IHDR'&&bytes.readUInt32BE(16)>0&&bytes.readUInt32BE(20)>0,`${group}: invalid PNG screenshot`)
   assert.equal(bytes.readUInt32BE(16),row.viewport,`${group}: screenshot width differs from actual viewport`)
   assert.ok(bytes.readUInt32BE(20)>=900,`${group}: screenshot does not show the requested 900px browser height`)
   const imageData=[];let cursor=8,ended=false
   while(cursor+12<=bytes.length){const size=bytes.readUInt32BE(cursor),type=bytes.toString('ascii',cursor+4,cursor+8);assert.ok(cursor+12+size<=bytes.length,`${group}: truncated PNG`);if(type==='IDAT')imageData.push(bytes.subarray(cursor+8,cursor+8+size));cursor+=12+size;if(type==='IEND'){ended=true;break}}
   assert.ok(ended&&imageData.length&&inflateSync(Buffer.concat(imageData),{maxOutputLength:128*1024*1024}).length>0,`${group}: PNG pixels missing`)
  }
  for(const id of row.itemIds){const key=`${id}/${row.edition}/${row.viewport}/${row.theme}`;cells.add(key);coverage.add(key)}
 }
 for(const id of required)for(const edition of ['en','cn'])for(const viewport of [1365,375])for(const theme of themes)assert.ok(cells.has(`${id}/${edition}/${viewport}/${theme}`),`${group}: missing item ${id} ${edition} ${viewport} ${theme}`)
 counts[group]=doc.results.length
}
assert.equal(coverage.size,17*2*2*6,'All 17 items must have complete matrix coverage')
const result={commit,mode:'browser',status:'passed',itemCount:17,coverageCells:coverage.size,counts,verifiedAt:new Date().toISOString(),scope:'Synthetic actual browser DOM assertions; screenshots require separate human visual review. No live assistive-technology pass implied.'}
writeFileSync(join(root,'acceptance-summary.json'),JSON.stringify(result,null,2)+'\n')
console.log(JSON.stringify(result))
