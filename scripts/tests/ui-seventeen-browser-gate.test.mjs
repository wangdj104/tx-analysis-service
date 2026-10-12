import { visualMeasurement } from './fixtures/nine-visual-measurements.mjs'
import { contrastRatio } from '../../frontend/tests/careNineMeasurements.mjs'
import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createHash } from 'node:crypto'
import { deflateSync } from 'node:zlib'
import { spawnSync } from 'node:child_process'
const sha='a'.repeat(40)
const nineGroups={care:[5,6],monitoring:[1,2,7,8],clinical:[9],shell:[3,4,9]}
const widths=[320,375,390,768,1365]
function measuredItem(id,group,viewport,edition){
 const box={visible:true,left:12,right:132,top:0,bottom:44,width:120,height:44}
 if(group==='care'&&id===5)return {actions:[{id:'synthetic-action',titleOccurrences:1,statusOccurrences:1,borderedAncestorDepth:2,bounds:box}],density:{baselineCommit:'c8756525dd7f7892dc4a836ac22d677af764c132',baselineSourceSha256:hash(readFileSync(`frontend/tests/fixtures/care-density-baseline/${edition}.vue`)),baselineContainerWidth:280,currentContainerWidth:280,baselineHeights:[200],currentHeights:[120]}}
 if(group==='care'&&id===6)return {statuses:[['PENDING','rgb(0, 0, 0)'],['MISSED','rgb(100, 0, 0)'],['SNOOZED','rgb(0, 0, 100)']].map(([status,color])=>({status,label:status,icon:status,color,background:'rgb(255, 255, 255)',contrast:contrastRatio(color,'rgb(255, 255, 255)'),semanticTokenColor:color,bounds:box}))}
 if(group==='clinical'&&id===9){
  const control=(testId,role,background)=>({testId,role,bounds:box,background,color:'rgb(0, 0, 0)',disabled:false,text:testId,opacity:'1',ariaBusy:'false'})
  const enabled=control('submit-receipt','primary','rgb(23, 107, 89)')
  return {surface:'rgb(255, 255, 255)',controls:[enabled,control('close-receipt','secondary','rgb(255, 255, 255)'),control('remove','remove','rgba(0, 0, 0, 0)')],referenceControls:[{referenceId:'ref1',referenceText:'Synthetic record',describedBy:'ref1',sameRow:true,bounds:box}],enabledSubmit:enabled,submittingSubmit:{...enabled,disabled:true,ariaBusy:'true',text:'Submitting...',opacity:'0.5'}}
 }
 return visualMeasurement(group,id,viewport)
}
const groups={care:[1,3,6,7],monitoring:[2,4,8,10,13,17],clinical:[3,5,11,15,17],shell:[3,9,12,14,16,17]}
const hash=value=>createHash('sha256').update(value).digest('hex')
const harnesses={care:'careDraftDialogBacklog',monitoring:'uiSeventeenMonitoring',clinical:'uiSeventeenClinical',shell:'uiSeventeenShell'}
function png(width,height=900){
 const crc=bytes=>{let n=0xffffffff;for(const byte of bytes){n^=byte;for(let i=0;i<8;i++)n=(n>>>1)^((n&1)?0xedb88320:0)}return(n^0xffffffff)>>>0}
 const chunk=(type,data)=>{const body=Buffer.concat([Buffer.from(type),data]), out=Buffer.alloc(data.length+12);out.writeUInt32BE(data.length);body.copy(out,4);out.writeUInt32BE(crc(body),out.length-4);return out}
 const header=Buffer.alloc(13);header.writeUInt32BE(width);header.writeUInt32BE(height,4);header[8]=8;header[9]=6
 return Buffer.concat([Buffer.from('89504e470d0a1a0a','hex'),chunk('IHDR',header),chunk('IDAT',deflateSync(Buffer.alloc((width*4+1)*height))),chunk('IEND',Buffer.alloc(0))])
}
function fixture(mutator=()=>{}) {
 const dir=mkdtempSync(join(tmpdir(),'ui17-gate-')),images=Object.fromEntries(widths.map(width=>[width,png(width)]))
 for(const [width,bytes] of Object.entries(images))writeFileSync(join(dir,`${width}.png`),bytes)
 for(const [group,itemIds] of Object.entries(groups)){
  const harness=`frontend/tests/${harnesses[group]}.browser.mjs`
  const doc={commit:sha,mode:'browser',harnessSha256:hash(readFileSync(harness)),sourceHashes:{'frontend/src/App.vue':hash(readFileSync('frontend/src/App.vue'))},results:[]}
  for(const edition of ['en','cn'])for(const viewport of widths)for(const theme of ['platform','white','blue','mint','sand','dark'])doc.results.push({itemIds,nineItemIds:[...nineGroups[group]],nineMeasurements:Object.fromEntries(nineGroups[group].map(id=>[id,measuredItem(id,group,viewport,edition)])),edition,viewport,theme,status:'passed',screenshots:[`${viewport}.png`],screenshotHashes:{[`${viewport}.png`]:hash(images[viewport])}})
  for(const row of doc.results){
   if(group==='monitoring')row.responsiveEditReturns=['cancel','save'].map(action=>({action,fromViewport:row.viewport,toViewport:row.viewport<=768?1365:375,recordId:217,origin:{trusted:true},returned:{viewport:row.viewport<=768?1365:375,left:10,right:120,top:100,bottom:144,focused:true,unobscured:true}}))
   if(group==='shell'&&row.viewport<=768)row.monitoringShellAnchors=['monitoring-alerts','monitoring-trends','monitoring-tasks'].flatMap(id=>['click','keyboard'].map(action=>({id,action,viewport:row.viewport,target:{top:150,bottom:180},header:{bottom:140},focused:true,unobscured:true})))
  }
  mutator(group,doc,dir);writeFileSync(join(dir,`${group}-results.json`),JSON.stringify(doc))
 }
 writeFileSync(join(dir,'acceptance-summary.json'),JSON.stringify({status:'passed',commit:sha}))
 const run=spawnSync(process.execPath,['scripts/verify-ui-seventeen-browser.mjs',dir,sha],{encoding:'utf8'});run.summary=JSON.parse(readFileSync(join(dir,'acceptance-summary.json'),'utf8'));rmSync(dir,{recursive:true,force:true});return run
}
test('all 17 actual browser items require both editions, widths, and six themes',()=>{const r=fixture();assert.equal(r.status,0,r.stderr)})
test('missing one theme cell fails',()=>{assert.notEqual(fixture((g,d)=>{if(g==='care')d.results.pop()}).status,0)})
test('compile-only output never counts as browser acceptance',()=>{assert.notEqual(fixture((g,d)=>d.mode='compile-only').status,0)})
test('failed browser assertion fails gate even when coverage is otherwise complete',()=>{assert.notEqual(fixture((g,d)=>{if(g==='care')d.results[0].status='failed'}).status,0)})
test('wrong checkout evidence is rejected',()=>{assert.notEqual(fixture((g,d)=>d.commit='b'.repeat(40)).status,0)})
test('missing screenshots cannot pass',()=>{assert.notEqual(fixture((g,d)=>d.results[0].screenshots=[]).status,0)})
test('screenshots outside synthetic evidence directory cannot pass',()=>{assert.notEqual(fixture((g,d)=>d.results[0].screenshots=['../sample.png']).status,0)})
test('CI runs all four new harnesses and fail-closed matrix collector',()=>{const workflow=readFileSync('.github/workflows/ci.yml','utf8'); const job=workflow.split('  ui-seventeen-browser:')[1]?.split('\n  care-plan-mysql:')[0]||''; for(const file of ['careDraftDialogBacklog','uiSeventeenMonitoring','uiSeventeenClinical','uiSeventeenShell'])assert.ok(job.includes(`node frontend/tests/${file}.browser.mjs`),file);assert.match(job,/node scripts\/verify-ui-seventeen-browser\.mjs/);assert.match(job,/if-no-files-found: error/);assert.doesNotMatch(job,/continue-on-error|\|\|\s*true/);assert.match(job,/npm ci --prefix cn\/frontend/);assert.match(job,/playwright install --with-deps chromium/)})

test('a PNG header without image pixels is rejected',()=>{assert.notEqual(fixture((g,d,dir)=>{if(g==='care'){const fake=Buffer.alloc(24);Buffer.from('89504e470d0a1a0a','hex').copy(fake);fake.write('IHDR',12);fake.writeUInt32BE(1365,16);fake.writeUInt32BE(900,20);writeFileSync(join(dir,'1365.png'),fake);for(const row of d.results)if(row.viewport===1365)row.screenshotHashes['1365.png']=hash(fake)}}).status,0)})
test('wrong screenshot hashes fail',()=>{assert.notEqual(fixture((g,d)=>d.results[0].screenshotHashes[d.results[0].screenshots[0]]='0'.repeat(64)).status,0)})
test('wrong source hashes fail',()=>{assert.notEqual(fixture((g,d)=>d.sourceHashes['frontend/src/App.vue']='0'.repeat(64)).status,0)})
test('wrong harness hashes fail',()=>{assert.notEqual(fixture((g,d)=>d.harnessSha256='0'.repeat(64)).status,0)})
test('fatal top-level error cannot be masked by passed row labels',()=>{assert.notEqual(fixture((g,d)=>d.error='fatal').status,0)})
test('browserExecuted false cannot pass',()=>{assert.notEqual(fixture((g,d)=>d.browserExecuted=false).status,0)})
test('unexecuted assertions cannot pass',()=>{assert.notEqual(fixture((g,d)=>d.browserAssertions='never-run').status,0)})
test('wrong actual screenshot width fails',()=>{assert.notEqual(fixture((g,d)=>{d.results[0].screenshots=['375.png'];d.results[0].screenshotHashes={'375.png':hash(png(375))}}).status,0)})

test('failed rerun replaces any old passed summary',()=>{const run=fixture((g,d)=>d.error='fatal');assert.notEqual(run.status,0);assert.equal(run.summary.status,'failed')})

test('UI evidence path uses runtime runner variables, not unavailable job expression context',()=>{
 const workflow=readFileSync('.github/workflows/ci.yml','utf8')
 const job=workflow.split('  ui-seventeen-browser:')[1]?.split('\n  care-plan-mysql:')[0]||''
 assert.doesNotMatch(job.split('    steps:')[0],/\$\{\{\s*runner\./)
 const command=job.match(/- name: Set synthetic evidence directory\n\s+run: ([^\n]+)/)?.[1]
 assert.ok(command,'runtime evidence initialization is required')
 const directory=mkdtempSync(join(tmpdir(),'ui17-runtime-env-')),file=join(directory,'github-env')
 try {
  const result=spawnSync('bash',['-euo','pipefail','-c',command],{encoding:'utf8',env:{PATH:process.env.PATH,RUNNER_TEMP:directory,GITHUB_ENV:file}})
  assert.equal(result.status,0,result.stderr)
  assert.equal(readFileSync(file,'utf8'),`UI_SEVENTEEN_EVIDENCE_DIR=${directory}/ui-seventeen\n`)
 } finally {rmSync(directory,{recursive:true,force:true})}
})

test('each completed UI suite publishes its own outcome JSON before the next suite, retaining the final gate',()=>{
 const workflow=readFileSync('.github/workflows/ci.yml','utf8')
 const job=workflow.split('  ui-seventeen-browser:')[1]?.split('\n  care-plan-mysql:')[0]||''
 const groups=[['care','careDraftDialogBacklog'],['monitoring','uiSeventeenMonitoring'],['clinical','uiSeventeenClinical'],['shell','uiSeventeenShell']]
 for(let i=0;i<groups.length;i++){
  const [group,harness]=groups[i],start=job.indexOf(`run: node frontend/tests/${harness}.browser.mjs`)
  const next=i+1<groups.length?job.indexOf(`run: node frontend/tests/${groups[i+1][1]}.browser.mjs`):job.indexOf('run: node scripts/verify-ui-seventeen-browser.mjs')
  assert.ok(start>=0&&next>start)
  const between=job.slice(start,next)
  assert.ok(between.includes(`name: Preserve ${group} browser outcomes immediately`),group)
  assert.match(between,/if: always\(\)/)
  assert.match(between,/uses: actions\/upload-artifact@v4/)
  assert.ok(between.includes(`name: ui-seventeen-${group}-outcomes-\${{ github.sha }}`))
  assert.ok(between.includes(`path: \${{ runner.temp }}/ui-seventeen/${group}-results.json`))
  assert.match(between,/if-no-files-found: error/)
  assert.doesNotMatch(between,/continue-on-error|\|\|\s*true/)
 }
 assert.match(job,/name: Preserve synthetic screenshots and per-item outcomes/)
 assert.match(job,/run: node scripts\/verify-ui-seventeen-browser\.mjs/)
})

// New acceptance remains additive: original 408 cells plus all nine items at five widths.
test('nine improvements require all 540 new measured browser cells',()=>{const r=fixture();assert.equal(r.status,0,r.stderr);assert.equal(r.summary.nineCoverageCells,540);assert.equal(r.summary.coverageCells,408);assert.equal(r.summary.nineItemCount,9)})
test('old 408 cells without the nine improvements cannot pass',()=>{assert.notEqual(fixture((g,d)=>{for(const row of d.results){delete row.nineItemIds;delete row.nineMeasurements}}).status,0)})
test('missing 320px new item coverage fails',()=>{assert.notEqual(fixture((g,d)=>{if(g==='monitoring')d.results=d.results.filter(r=>r.viewport!==320)}).status,0)})
test('a claimed new item without actual measurements fails',()=>{assert.notEqual(fixture((g,d)=>{if(g==='care')delete d.results[0].nineMeasurements[5]}).status,0)})
test('empty or Boolean-only measurements cannot stand in for measured geometry',()=>{for(const value of [{},{passed:true},true])assert.notEqual(fixture((g,d)=>{if(g==='care')d.results[0].nineMeasurements[5]=value}).status,0)})
test('unknown new item and unsupported viewport cannot pass',()=>{assert.notEqual(fixture((g,d)=>{if(g==='care')d.results[0].nineItemIds.push(10)}).status,0);assert.notEqual(fixture((g,d)=>{if(g==='care')d.results[0].viewport=400}).status,0)})

test('gate independently rejects insufficient measured density despite passed case status',()=>{assert.notEqual(fixture((g,d)=>{if(g==='care')d.results[0].nineMeasurements[5].density.currentHeights=[199]}).status,0)})
test('gate independently rejects fabricated contrast and duplicate rendered medication icons',()=>{for(const field of ['contrast','icon'])assert.notEqual(fixture((g,d)=>{if(g==='care'){const rows=d.results[0].nineMeasurements[6].statuses;rows[0][field]=field==='contrast'?20:rows[1].icon}}).status,0)})
test('gate rejects wrong baseline source provenance',()=>{assert.notEqual(fixture((g,d)=>{if(g==='care')d.results[0].nineMeasurements[5].density.baselineSourceSha256='0'.repeat(64)}).status,0)})
test('gate rejects an unmeasured or enabled submitting receipt',()=>{assert.notEqual(fixture((g,d)=>{if(g==='clinical')d.results[0].nineMeasurements[9].submittingSubmit.disabled=false}).status,0)})

test('collector rejects a generic monitoring or shell object even with passed row status',()=>{for(const group of ['monitoring','shell'])assert.notEqual(fixture((g,d)=>{if(g===group)d.results[0].nineMeasurements[d.results[0].nineItemIds[0]]={viewport:320}}).status,0)})

test('responsive focus and actual-shell anchor evidence are mandatory',()=>{for(const group of ['monitoring','shell'])assert.notEqual(fixture((g,d)=>{if(g===group)delete d.results[0][g==='monitoring'?'responsiveEditReturns':'monitoringShellAnchors']}).status,0)})
