// Fail-closed acceptance for fresh synthetic real-browser evidence, never source tests.
import { readFileSync, realpathSync, statSync, writeFileSync } from 'node:fs'
import { resolve, relative, isAbsolute, sep, join } from 'node:path'
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import { inflateSync } from 'node:zlib'
import { assertNineVisualMeasurement } from './ui-nine-measurements.mjs'
import { assertTaskHierarchy, assertTaskDensity, assertMedicationSemantics, assertReceiptHierarchy, assertSubmittingState } from '../frontend/tests/careNineMeasurements.mjs'
const [directory, commit]=process.argv.slice(2)
assert.ok(directory && /^[0-9a-f]{40}$/.test(commit||''),'Usage: evidence directory and full checkout SHA required')
const repo=realpathSync(fileURLToPath(new URL('../',import.meta.url)))
const hash=value=>createHash('sha256').update(value).digest('hex')
const sourceHash=(path)=>{assert.ok(typeof path==='string'&&!isAbsolute(path)&&!path.split(/[\\/]/).includes('..'),'Unsafe source path');const actual=realpathSync(resolve(repo,path));assert.ok(relative(repo,actual)&&!relative(repo,actual).startsWith(`..${sep}`),'Source path escapes repository');return hash(readFileSync(actual))}
const harnesses={care:'careDraftDialogBacklog',monitoring:'uiSeventeenMonitoring',clinical:'uiSeventeenClinical',shell:'uiSeventeenShell'}
const root=realpathSync(resolve(directory)), themes=['platform','white','blue','mint','sand','dark']
const widths=[320,375,390,768,1365]
const nineGroups={care:[5,6],monitoring:[1,2,7,8],clinical:[9],shell:[3,4,9]}
const densityBaselineHashes={en:'a9f32af9e90a781388d82e8db8891c2e6c015fa11f5e8c7164cb92db5edd74d1',cn:'76f7667b8b054ee1af9353566e50d1978e8c338f553dd962a4d77fab3ac08e4c'}
const groups={care:[1,3,6,7],monitoring:[2,4,8,10,13,17],clinical:[3,5,11,15,17],shell:[3,9,12,14,16,17]}
// Invalidate any prior success before reading evidence; a failing rerun must
// never upload an old passing summary. Only the completed gate writes passed.
writeFileSync(join(root,'acceptance-summary.json'),JSON.stringify({commit,mode:'browser',status:'failed',reason:'Acceptance validation incomplete or failed',verifiedAt:new Date().toISOString()},null,2)+'\n')
const coverage=new Set(), nineCoverage=new Set(), counts={}
function hasMeasuredValue(value){
 if(typeof value==='number')return Number.isFinite(value)
 if(typeof value==='string')return value.trim().length>0
 if(Array.isArray(value))return value.some(hasMeasuredValue)
 return !!value&&typeof value==='object'&&Object.values(value).some(hasMeasuredValue)
}
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
 const cells=new Set(), nineCells=new Set()
 for(const row of doc.results) {
  assert.equal(row.status,'passed',`${group}: failing or unexecuted browser case`)
  assert.ok(['en','cn'].includes(row.edition)&&widths.includes(row.viewport)&&themes.includes(row.theme),`${group}: unknown matrix cell`)
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
  if(group==='monitoring'){
   assert.equal(row.responsiveEditReturns?.length,2,'Both responsive Cancel and Save evidence required')
   for(const action of ['cancel','save']){
    const entries=row.responsiveEditReturns.filter(e=>e.action===action);assert.equal(entries.length,1)
    const e=entries[0],width=row.viewport<=768?1365:375,r=e.returned
    assert.equal(e.fromViewport,row.viewport);assert.equal(e.toViewport,width);assert.equal(e.recordId,217);assert.equal(e.origin.trusted,true)
    assert.equal(r.viewport,width);assert.equal(r.focused,true);assert.equal(r.unobscured,true);assert.ok(r.left>=0&&r.right<=width&&r.top>=0&&r.bottom<=900)
   }
  }
  if(group==='shell'&&row.viewport<=768){
   assert.equal(row.monitoringShellAnchors?.length,6,'Actual App sticky-header anchor evidence required')
   for(const id of ['monitoring-alerts','monitoring-trends','monitoring-tasks'])for(const action of ['click','keyboard']){
    const entries=row.monitoringShellAnchors.filter(e=>e.id===id&&e.action===action);assert.equal(entries.length,1)
    const e=entries[0];assert.equal(e.viewport,row.viewport);assert.equal(e.focused,true);assert.equal(e.unobscured,true);assert.ok(e.target.top>=e.header.bottom&&e.target.bottom<=900)
   }
  }
  for(const id of row.itemIds){const key=`${id}/${row.edition}/${row.viewport}/${row.theme}`;cells.add(key);if([1365,375].includes(row.viewport))coverage.add(key)}
  if(row.nineItemIds!==undefined){
   assert.ok(Array.isArray(row.nineItemIds)&&row.nineItemIds.length&&row.nineItemIds.every(id=>Number.isInteger(id)&&nineGroups[group].includes(id)),`${group}: invalid nine-improvement item IDs`)
   for(const id of row.nineItemIds){
    const measured=row.nineMeasurements?.[id]
    assert.ok(measured&&typeof measured==='object'&&!Array.isArray(measured)&&hasMeasuredValue(measured),`${group}: item ${id} lacks actual DOM measurements`)
    if(['monitoring','shell'].includes(group))assertNineVisualMeasurement(group,id,measured,row.viewport)
    if(group==='care'&&id===5){
     assertTaskHierarchy(measured.actions,row.viewport);assertTaskDensity(measured.density)
     assert.equal(measured.density.baselineSourceSha256,densityBaselineHashes[row.edition],'Frozen previous release source cannot drift with fixture file')
     assert.equal(measured.density.baselineCommit,'c8756525dd7f7892dc4a836ac22d677af764c132','Density baseline must be the reviewed previous release')
     assert.equal(measured.density.baselineSourceSha256,sourceHash(`frontend/tests/fixtures/care-density-baseline/${row.edition}.vue`),'Density baseline source does not match the rendered edition')
    }
    if(group==='care'&&id===6)assertMedicationSemantics(measured.statuses)
    if(group==='clinical'&&id===9){assertReceiptHierarchy(measured);assertSubmittingState(measured.enabledSubmit,measured.submittingSubmit)}
    const key=`${id}/${row.edition}/${row.viewport}/${row.theme}`;nineCells.add(key);nineCoverage.add(key)
   }
  }
 }
 for(const id of required)for(const edition of ['en','cn'])for(const viewport of [1365,375])for(const theme of themes)assert.ok(cells.has(`${id}/${edition}/${viewport}/${theme}`),`${group}: missing item ${id} ${edition} ${viewport} ${theme}`)
 for(const id of nineGroups[group])for(const edition of ['en','cn'])for(const viewport of widths)for(const theme of themes)assert.ok(nineCells.has(`${id}/${edition}/${viewport}/${theme}`),`${group}: missing new item ${id} ${edition} ${viewport} ${theme}`)
 counts[group]=doc.results.length
}
assert.equal(coverage.size,17*2*2*6,'All 17 items must have complete matrix coverage')
assert.equal(nineCoverage.size,9*2*5*6,'All nine new items need complete measured five-width matrix coverage')
const result={nineItemCount:9,nineCoverageCells:nineCoverage.size,viewports:widths,commit,mode:'browser',status:'passed',itemCount:17,coverageCells:coverage.size,counts,verifiedAt:new Date().toISOString(),scope:'Synthetic actual browser DOM assertions; screenshots require separate human visual review. No live assistive-technology pass implied.'}
writeFileSync(join(root,'acceptance-summary.json'),JSON.stringify(result,null,2)+'\n')
console.log(JSON.stringify(result))
