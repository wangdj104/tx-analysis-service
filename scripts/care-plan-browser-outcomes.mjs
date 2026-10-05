// A separate bounded acceptance outcome. Never serialize Playwright objects.
import assert from 'node:assert/strict'
import {lstat,readFile,writeFile,mkdir} from 'node:fs/promises'
import {resolve,relative,join} from 'node:path'
import {fileURLToPath,pathToFileURL} from 'node:url'
import {OUTCOME_CATALOG} from './care-plan-browser-outcome-catalog.mjs'
import {assertDirectoryAncestors} from './package-care-plan-browser-artifacts.mjs'
export const OUTCOME_MAX_BYTES=65536
export const OUTCOME_PHASES=Object.freeze(['setup','test-body','preview','download-click','download-headers','download-request','download-stream','download-parse','download-hash','download-stage','pre-click-metadata','teardown'])
const statuses=['notRun','passed','failed','timedOut','skipped','interrupted'],classes=['none','test_error','non_error_throw','timeout','interrupted']
const files=['carePlanSessions.mjs','helpers.mjs','reportBrowser.mjs','reportDownloads.mjs','reportOutcomePhase.mjs',...new Set(OUTCOME_CATALOG.map(row=>row.file))]
const sources=new Set([...['frontend/e2e/','cn/frontend/e2e/'].flatMap(prefix=>files.map(file=>prefix+file)),...['inspect-care-report-files.mjs','care-report-fixture-oracle.mjs','package-care-report-artifacts.mjs'].map(file=>'scripts/'+file)])
const exact=(value,keys)=>value&&typeof value==='object'&&!Array.isArray(value)&&Object.keys(value).length===keys.length&&keys.every(key=>Object.hasOwn(value,key))
function validateSource(value){assert.ok(exact(value,['file','line','column'])&&sources.has(value.file),'Unsafe outcome source');assert.ok(Number.isSafeInteger(value.line)&&value.line>0&&value.line<=10000&&Number.isSafeInteger(value.column)&&value.column>0&&value.column<=1000,'Unsafe outcome location')}
export function validateOutcomeManifest(value){
 assert.ok(exact(value,['schemaVersion','edition','runStatus','globalError','tests'])&&value.schemaVersion===1&&['en','cn'].includes(value.edition)&&['passed','failed','timedout','interrupted'].includes(value.runStatus)&&classes.includes(value.globalError),'Unsafe outcome manifest')
 assert.ok(Array.isArray(value.tests)&&value.tests.length>0&&value.tests.length<=128,'Unsafe outcome count')
 const seen=new Set()
 for(const entry of value.tests){
  assert.ok(exact(entry,['id','status','errorClass','phase',...(Object.hasOwn(entry||{},'source')?['source']:[])]),'Unsafe outcome entry')
  assert.ok(OUTCOME_CATALOG.some(row=>row.id===entry.id)&&!seen.has(entry.id),'Unsafe outcome identity');seen.add(entry.id)
  assert.ok(statuses.includes(entry.status)&&classes.includes(entry.errorClass)&&OUTCOME_PHASES.includes(entry.phase),'Unsafe outcome enum')
  assert.ok(!['passed','notRun','skipped'].includes(entry.status)||entry.errorClass==='none','Inconsistent outcome status')
  if(Object.hasOwn(entry,'source'))validateSource(entry.source)
 }
 assert.ok(Buffer.byteLength(JSON.stringify(value))<=OUTCOME_MAX_BYTES,'Outcome payload exceeds bound');return value
}
export function parseOutcomeManifest(bytes){assert.ok(Buffer.isBuffer(bytes)&&bytes.length<=OUTCOME_MAX_BYTES,'Outcome payload exceeds bound');return validateOutcomeManifest(JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(bytes)))}
function errorClass(error,status){return status==='timedOut'?'timeout':status==='interrupted'?'interrupted':error?(typeof error.message==='string'?'test_error':'non_error_throw'):'none'}
const root=resolve(fileURLToPath(new URL('..',import.meta.url)))
async function ownedTemp(env){assert.ok(env.CI==='true'&&env.GITHUB_ACTIONS==='true'&&env.RUNNER_TEMP===resolve(env.RUNNER_TEMP||'.'),'Outcome requires declared CI runner temp');await assertDirectoryAncestors(join(env.RUNNER_TEMP,'owned'));const stat=await lstat(env.RUNNER_TEMP);assert.ok(stat.isDirectory()&&!stat.isSymbolicLink()&&stat.uid===process.getuid(),'Outcome temp must be owned');return env.RUNNER_TEMP}
async function writeOutcome(value){const temp=await ownedTemp(process.env);await writeFile(join(temp,`care-plan-outcomes-${value.edition}.json`),JSON.stringify(validateOutcomeManifest(value)),{flag:'wx',mode:0o600})}
export default class OutcomeReporter{
 constructor(options={}){this.edition=options.edition;this.root=options.root||root;this.write=options.write||writeOutcome;this.states=new Map();this.invalid=false;this.globalError='none'}
 printsToStdio(){return false}
 onBegin(config,suite){try{
  const tests=suite.allTests();assert.ok(tests.length>0&&tests.length<=128)
  for(const test of tests){const file=relative(this.root,test.location.file).replaceAll('\\','/'),prefix=this.edition==='cn'?'cn/frontend/e2e/':'frontend/e2e/';const row=OUTCOME_CATALOG.find(row=>file===prefix+row.file&&test.title===row.title);assert.ok(row&&!this.states.has(row.id)&&test.retries===0&&test.repeatEachIndex===0);this.states.set(row.id,{test,entry:{id:row.id,status:'notRun',errorClass:'none',phase:'setup'},phase:'setup',bodyPhase:'setup'})}
 }catch{this.invalid=true}}
 state(test){return [...this.states.values()].find(state=>state.test===test)}
 onTestBegin(test){const state=this.state(test);if(!state){this.invalid=true;return}state.phase='setup'}
 onStepBegin(test,result,step){const state=this.state(test);if(!state)return;if(step.category==='test.step'&&step.title.startsWith('care-phase:')){const phase=step.title.slice(11);if(!OUTCOME_PHASES.includes(phase)){this.invalid=true;return}if(phase==='teardown')state.bodyPhase=state.phase;state.phase=phase}}
 onStepEnd(test,result,step){const state=this.state(test);if(state&&step.error&&!state.failedPhase)state.failedPhase=state.phase}
 onTestEnd(test,result){try{const state=this.state(test);assert.ok(state);const error=result.errors?.[0]||result.error;state.entry.status=result.status;state.entry.errorClass=errorClass(error,result.status);state.entry.phase=state.failedPhase||(error&&state.phase==='teardown'?state.bodyPhase:state.phase);const location=error?.location;if(location){const safe={file:relative(this.root,location.file).replaceAll('\\','/'),line:location.line,column:location.column};try{validateSource(safe);state.entry.source=safe}catch{/* Unknown locations are omitted, never copied. */}}}catch{this.invalid=true}}
 onError(error){this.globalError=errorClass(error,'failed')}
 async onEnd(result){try{assert.ok(!this.invalid);await this.write(validateOutcomeManifest({schemaVersion:1,edition:this.edition,runStatus:result.status,globalError:this.globalError,tests:[...this.states.values()].map(state=>state.entry)}))}catch{return{status:'failed'}}}
}
export async function packageOutcome(source,destination){
 await assertDirectoryAncestors(source);await assertDirectoryAncestors(destination)
 const stat=await lstat(source);assert.ok(stat.isFile()&&!stat.isSymbolicLink()&&stat.uid===process.getuid()&&stat.size<=OUTCOME_MAX_BYTES,'Unsafe outcome input')
 const value=parseOutcomeManifest(await readFile(source));await mkdir(destination,{mode:0o700});await writeFile(join(destination,'outcomes.json'),JSON.stringify(value),{flag:'wx',mode:0o600});return value.tests.length
}
if(process.argv[1]&&import.meta.url===pathToFileURL(resolve(process.argv[1])).href){(async()=>{const [edition]=process.argv.slice(2);assert.ok(process.argv.length===3&&['en','cn'].includes(edition));const temp=await ownedTemp(process.env);await packageOutcome(join(temp,`care-plan-outcomes-${edition}.json`),join(temp,`care-plan-outcome-artifacts-${edition}`))})().catch(()=>{console.error('Strict outcome packaging failed');process.exitCode=1})}
