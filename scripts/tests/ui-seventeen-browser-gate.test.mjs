import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createHash } from 'node:crypto'
import { deflateSync } from 'node:zlib'
import { spawnSync } from 'node:child_process'
const sha='a'.repeat(40)
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
 const dir=mkdtempSync(join(tmpdir(),'ui17-gate-')),images=Object.fromEntries([1365,375].map(width=>[width,png(width)]))
 for(const [width,bytes] of Object.entries(images))writeFileSync(join(dir,`${width}.png`),bytes)
 for(const [group,itemIds] of Object.entries(groups)){
  const harness=`frontend/tests/${harnesses[group]}.browser.mjs`
  const doc={commit:sha,mode:'browser',harnessSha256:hash(readFileSync(harness)),sourceHashes:{'frontend/src/App.vue':hash(readFileSync('frontend/src/App.vue'))},results:[]}
  for(const edition of ['en','cn'])for(const viewport of [1365,375])for(const theme of ['platform','white','blue','mint','sand','dark'])doc.results.push({itemIds,edition,viewport,theme,status:'passed',screenshots:[`${viewport}.png`],screenshotHashes:{[`${viewport}.png`]:hash(images[viewport])}})
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
test('wrong screenshot hashes fail',()=>{assert.notEqual(fixture((g,d)=>d.results[0].screenshotHashes['1365.png']='0'.repeat(64)).status,0)})
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
