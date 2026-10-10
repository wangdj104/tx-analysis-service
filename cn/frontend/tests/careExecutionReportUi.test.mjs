import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import { canAccessWorkspace, resolveWorkspaceEntry } from '../src/utils/workspaceAccess.js'
import * as access from '../src/utils/workspaceAccess.js'
import * as display from '../src/utils/careExecutionReport.js'

for (const roles of [[], ['nurse'], ['family'], ['doctor'], ['admin']]) {
  test(`report and source locator routes allow only exact bounded scope: ${roles}`, () => {
    for (const path of ['/care-plans/reports?patientId=1', '/care-plans/reports?planId=2&patientId=1', '/care-journey?tab=measurements&patientId=1&measurementId=2', '/medical-record?tab=list&patientId=1&recordId=2']) {
      assert.equal(canAccessWorkspace(path, [], roles), true, path)
      assert.equal(resolveWorkspaceEntry(path, [], roles), path)
      for (const suffix of ['&patientId=1', '&redirect=/medical-record', '#private']) assert.equal(canAccessWorkspace(path + suffix, [], roles), false, path + suffix)
    }
    if (!roles.includes('admin')) for (const path of ['/health-report', '/medical-record', '/care-journey?tab=measurements']) assert.equal(canAccessWorkspace(path, [], roles), false)
  })
}
test('invalid and duplicate report identities cannot unlock routes, including admin', () => {
  for (const query of ['', '?patientId=0', '?patientId=-1', '?patientId=1e2', '?patientId=01', '?patientId=9007199254740992', '?patientId=1&planId=0', '?patientId=1&planId=2&planId=2']) assert.equal(canAccessWorkspace('/care-plans/reports' + query, [], ['admin']), false)
})
test('source locator parser binds exact IDs independently of stale selected patient', () => {
  assert.equal(typeof access.parseEvidenceRoute, 'function')
  assert.deepEqual(access.parseEvidenceRoute('/medical-record?tab=list&patientId=2&recordId=17'), {patientId:2, sourceId:17, sourceType:'MEDICAL_RECORD'})
  assert.deepEqual(access.parseEvidenceRoute('/care-journey?tab=measurements&patientId=2&measurementId=1201'), {patientId:2, sourceId:1201, sourceType:'MEASUREMENT'})
})
test('report defaults are 30 calendar dates in the explicit zone, across DST and year boundaries', () => {
  assert.equal(typeof display.defaultReportDates, 'function')
  assert.deepEqual(display.defaultReportDates('America/New_York',new Date('2026-03-09T02:00:00Z')), {fromDate:'2026-02-07',toDate:'2026-03-08'})
  assert.deepEqual(display.defaultReportDates('Asia/Shanghai',new Date('2025-12-31T18:00:00Z')), {fromDate:'2025-12-03',toDate:'2026-01-01'})
  assert.throws(()=>display.defaultReportDates('PST'))
})

import { parse, compileScript, compileTemplate } from '@vue/compiler-sfc'
import { createSSRApp, reactive } from 'vue'
import { renderToString } from '@vue/server-renderer'
const moduleUrl = text => `data:text/javascript;base64,${Buffer.from(text).toString('base64')}`
let serial=0
async function renderPanel(report, {error=null,lastExport=null,phase='ready',language='en',warnings=[]}={}) {
  const file=new URL('../src/components/care-plan/ExecutionReportPanel.vue',import.meta.url)
  assert.ok(fs.existsSync(file),'ExecutionReportPanel exists')
  const source=fs.readFileSync(file,'utf8'),{descriptor}=parse(source),id='report-ui-'+(++serial)
  const script=compileScript(descriptor,{id}),template=compileTemplate({source:descriptor.template.content,filename:file.pathname,id,compilerOptions:{bindingMetadata:script.bindings}})
  assert.deepEqual(template.errors,[])
  globalThis[id]={state:reactive({report,error,lastExport,phase}),preview:async()=>({status:'succeeded'}),download:async()=>{},clear(){}}
  let code=script.content.replace('export default','const Component =')+'\n'+template.code.replace('export function render','function render')+'\nComponent.render=render;export default Component;'
  code=code.replace(/from ['"]vue['"]/g,`from '${import.meta.resolve('vue')}'`)
    .replace(/import \{ useCareExecutionReport \} from ['"][^'"]+['"];?/g,`const useCareExecutionReport=()=>globalThis['${id}'];`)
    .replace(/from ['"]@\/utils\/careExecutionReport(?:\.js)?['"]/g,`from '${new URL('../src/utils/careExecutionReport.js',import.meta.url).href}'`)
  const Component=(await import(moduleUrl(code))).default
  const app=createSSRApp(Component,{patientId:1,planId:report?.scope.planId??null})
  app.config.warnHandler=message=>warnings.push(message)
  app.provide('reportLanguage',language)
  app.component('router-link',{props:['to'],template:'<a :href="to"><slot/></a>'})
  const html=await renderToString(app);delete globalThis[id];return html
}
const sample=()=>({reportSchemaVersion:1,patient:{id:1,displayName:'Synthetic patient'},scope:{planId:null,label:'ALL_PLANS'},metadata:{fromDate:'2026-09-05',toDate:'2026-10-04',timeZone:'UTC',language:'en',currentAsOf:'2026-10-04T12:00:00Z',generatedAt:'2026-10-04T12:01:00Z',rangeStartAt:'2026-09-05T00:00:00Z',rangeEndExclusiveAt:'2026-10-05T00:00:00Z'},currentSummary:{total:1,open:0,needsHelp:0,submitted:1,confirmed:0,overdue:0,needsSupplement:0},currentActions:[{planId:3,revisionId:4,revisionNo:2,actionId:5,planTitle:'原始计划',instructions:'原始说明',instruction:'原文不可翻译',status:'SUBMITTED',assignedUserId:7,assigneeAvailable:true,dueAt:'2026-09-01T00:00:00Z',reviewWaitingSince:'2026-08-30T00:00:00Z',overdue:false,evidence:[{restricted:true}],latestReceipt:{eventId:8,eventType:'RECEIPT_SUBMITTED',actorId:9,actorName:'Synthetic nurse',actorRole:'NURSE',actorRelation:'ASSISTANT',entryMode:'ASSISTED',note:'原始回执',recordedAt:'2026-08-30T00:00:00Z',occurredAt:'2026-08-29T00:00:00Z'}}],currentAttention:[],activitySummary:{eventCount:4,distinctActionCount:1,eventTypeCounts:{RECEIPT_SUBMITTED:2,RECEIPT_RETURNED:2}},periodEvents:[],questionsAvailability:'NOT_AUTHORIZED',questions:[],completeness:'COMPLETE'})
test('actual report template keeps current denominator, period events and restricted questions separate',async()=>{
 const html=await renderPanel(sample())
 for(const text of ['Synthetic patient','All plans','Current status','Period activity','Submitted awaiting doctor review','1 / 1','4','1 distinct','Not authorized','原文不可翻译','原始回执','Assisted entry','CARE_PLAN','UTC+00:00','2026-09-05','schema 1'])assert.ok(html.includes(text),text)
 assert.ok(!html.includes('Patient overdue'));assert.ok(!html.includes('0 questions'));assert.ok(!html.includes('completion rate'))
})
test('zero current denominator is not applicable and single-plan questions are explicitly excluded',async()=>{
 const report=sample();report.scope={planId:3,label:'SINGLE_PLAN'};report.currentSummary={total:0,open:0,needsHelp:0,submitted:0,confirmed:0,overdue:0,needsSupplement:0};report.currentActions=[];report.questionsAvailability='NOT_INCLUDED_IN_PLAN_SCOPE'
 const html=await renderPanel(report);assert.ok(html.includes('Only this plan'));assert.ok(html.includes('No current active-plan actions'));assert.ok(html.includes('Not applicable'));assert.ok(html.includes('Questions not included in single-plan scope'));assert.ok(!html.includes('0 / 0'))
})
test('recorded answers and unzoned legacy times preserve original text',async()=>{
 const report=sample();report.questionsAvailability='AVAILABLE';report.questions=[{id:11,title:'原始问题',status:'ANSWERED',description:'症状原文',answer:'答复原文',followUp:'下次讨论',actorId:10,actorName:'Synthetic recorder',updatedAtLocal:'2026-08-01 09:00:00'}]
 const html=await renderPanel(report);for(const text of ['Recorded answer','Legacy local time, timezone not recorded','原始问题','答复原文','Synthetic recorder'])assert.ok(html.includes(text),text)
})
test('a full-preview limit leaves independent CSV buttons enabled and errors announced',async()=>{
 const html=await renderPanel(null,{phase:'error',error:{errorCode:'REPORT_LIMIT_EXCEEDED',limitKind:'QUESTIONS',limit:200}})
 assert.ok(html.includes('role="alert"'));assert.match(html,/<button[^>]*data-testid="download-actions_csv"(?![^>]*disabled)/);assert.ok(html.includes('QUESTIONS'));assert.ok(html.includes('200'))
})
test('empty CSV notice uses fresh export metadata and never old preview facts',async()=>{
 const html=await renderPanel(null,{lastExport:{format:'events_csv',scope:{patientId:1,planId:null,label:'ALL_PLANS'},fromDate:'2026-08-01',toDate:'2026-08-02',rangeKnown:true,timeZone:'Asia/Shanghai',language:'zh-CN',reportSchemaVersion:1,generatedAt:'2026-10-04T12:00:00Z',rowCount:0,isEmpty:true}})
 for(const text of ['0 rows','header only','2026-08-01','2026-08-02','Asia/Shanghai','zh-CN','schema 1'])assert.ok(html.includes(text),text)
})
test('Chinese labels are localized while all clinical source text remains unchanged',async()=>{
 const report=sample();report.metadata.language='zh-CN';const html=await renderPanel(report,{language:'zh-CN'});assert.ok(html.includes('当前状态'));assert.ok(html.includes('已提交待医生复核'));assert.ok(html.includes('原文不可翻译'))
})
test('all existing entries and dedicated route reach the report without a legacy report request',()=>{
 for(const name of ['PlanTaskList','PlanDetail'])assert.match(fs.readFileSync(new URL(`../src/components/care-plan/${name}.vue`,import.meta.url),'utf8'),/\/care-plans\/reports/)
 assert.match(fs.readFileSync(new URL('../src/views/DoctorWorkspace.vue',import.meta.url),'utf8'),/\/care-plans\/reports/)
 for(const name of ['HealthReportManager','DataExportManager','FamilyHealthManager'])assert.match(fs.readFileSync(new URL(`../src/views/${name}.vue`,import.meta.url),'utf8'),/<ExecutionReportPanel/)
 const main=fs.readFileSync(new URL('../src/main.js',import.meta.url),'utf8');assert.match(main,/path: '\/care-plans\/reports'/);assert.match(main,/isNarrowCareRoute\(to.fullPath\)/)
 const wrapper=fs.readFileSync(new URL('../src/views/CareExecutionReportView.vue',import.meta.url),'utf8');assert.match(wrapper,/parseReportRoute/);assert.doesNotMatch(wrapper,/getPatient|getVisitSummary|generateReport|useCurrentPatient/)
 const app=fs.readFileSync(new URL('../src/App.vue',import.meta.url),'utf8');assert.match(app,/isNarrowCareRoute\(route.fullPath\)/)
})
test('report rendering never exposes a response for a different patient scope',async()=>{
 const report=sample();report.patient.id=2;report.patient.displayName='Other patient secret';report.currentActions[0].instruction='Other patient private instruction';const html=await renderPanel(report);assert.ok(!html.includes('Other patient secret'));assert.ok(!html.includes('Other patient private instruction'));assert.ok(html.includes('role="alert"'))
})
test('review wait is displayed from DTO snapshot times with exact nanosecond flooring, never wall-clock now',async()=>{
 assert.equal(typeof display.reportReviewWaitSeconds,'function');assert.equal(display.reportReviewWaitSeconds('2026-10-04T11:59:59.999999999Z','2026-10-04T12:00:00.000000001Z'),0);assert.equal(display.reportReviewWaitSeconds('2026-10-04T11:59:59.000000001Z','2026-10-04T12:00:00.000000000Z'),0)
 const html=await renderPanel(sample());assert.ok(html.includes('Review wait duration (seconds)'));assert.ok(html.includes('3067200'))
})
test('new plan-detail report entry and report controls have 44px keyboard-visible targets',()=>{
 const detail=fs.readFileSync(new URL('../src/components/care-plan/PlanDetail.vue',import.meta.url),'utf8');assert.match(detail,/class="execution-report-entry"/);assert.match(detail,/\.execution-report-entry\{[^}]*min-height:44px/)
 const panel=fs.readFileSync(new URL('../src/components/care-plan/ExecutionReportPanel.vue',import.meta.url),'utf8');assert.match(panel,/min-height:44px/);assert.match(panel,/:focus-visible/);assert.match(panel,/@media\(max-width:600px\)/)
})
test('administrative follow-up keeps its provenance and explicitly marks entry mode not applicable',async()=>{
 const report=sample();report.currentActions[0].latestFollowUp={eventId:19,eventType:'FOLLOW_UP_RECORDED',actorId:9,actorName:'Synthetic nurse',actorRole:'NURSE',actorRelation:'NURSE',followUpKind:'CONTACTED',recordedAt:'2026-10-01T12:00:00Z',note:'原始跟进'};const html=await renderPanel(report);for(const text of ['Administrative follow-up recorded','Entry mode: not applicable','does not resolve difficulty or confirm a receipt','原始跟进'])assert.ok(html.includes(text),text)
})
for (const field of ['completeness','reportSchemaVersion'])test(`report refuses an unsupported ${field} instead of claiming a complete report`,async()=>{const report=sample();report[field]=field==='completeness'?'PARTIAL':2;const html=await renderPanel(report);assert.ok(!html.includes('data-testid="execution-report-body"'));assert.ok(html.includes('role="alert"'))})
test('current-attention jumps do not add fragments that invalidate the exact report route',async()=>{const report=sample();report.currentAttention=[report.currentActions[0]];const html=await renderPanel(report);assert.ok(!html.includes('href="#report-action-'));assert.ok(html.includes('data-testid="jump-action-5"'))})

test('nonempty period renders plan and action events with original provenance independently of current state',async()=>{
 const report=sample();report.periodEvents=[
  {eventId:101,eventType:'PLAN_PUBLISHED',planId:30,revisionId:40,revisionNo:2,actionId:null,planTitle:'Synthetic original published plan',instructions:'原始第二版说明',actionInstruction:null,actionStatusAfterEvent:null,planLifecycleAtGeneration:'CANCELLED',revisionIsCurrentAtGeneration:false,actorId:9,actorName:'Synthetic original doctor',actorRole:'DOCTOR',actorRelation:'DOCTOR',entryMode:null,recordedAt:'2026-09-08T03:04:05Z',occurredAt:null,note:'原始发布备注',evidence:[{restricted:true}]},
  {eventId:102,eventType:'RECEIPT_RETURNED',planId:3,revisionId:4,revisionNo:2,actionId:5,planTitle:'Synthetic action source plan',instructions:'原始版本指示',actionInstruction:'原始历史事项',actionStatusAfterEvent:'OPEN',planLifecycleAtGeneration:'CLOSED',revisionIsCurrentAtGeneration:false,actorId:12,actorName:'Synthetic historical reviewer',actorRole:'DOCTOR',actorRelation:'DOCTOR',entryMode:null,recordedAt:'2026-09-10T06:07:08Z',occurredAt:'2026-09-09T04:05:06Z',note:'原始退回理由',evidence:[{restricted:false,sourceType:'MEASUREMENT',sourceId:42,title:'Synthetic original measurement',detailPath:'/care-journey?tab=measurements&patientId=1&measurementId=42'}]}
 ];report.activitySummary={eventCount:2,distinctActionCount:1,eventTypeCounts:{PLAN_PUBLISHED:1,RECEIPT_RETURNED:1}}
 for(const language of ['en','zh-CN']){
  report.metadata.language=language;const warnings=[];const html=await renderPanel(report,{language,warnings});assert.deepEqual(warnings,[],'nonempty period rendering has no Vue warnings')
  for(const value of ['101','102','30 / 40 /','3 / 4 / 5','Synthetic original published plan','原始第二版说明','原始版本指示','原始历史事项','原始退回理由','Synthetic historical reviewer','CANCELLED','CLOSED','CARE_PLAN','UTC+00:00','Synthetic original measurement','measurementId=42'])assert.ok(html.includes(value),value)
  for(const value of [display.reportLabel('eventType','PLAN_PUBLISHED',language),display.reportLabel('eventType','RECEIPT_RETURNED',language),display.reportLabel('status','OPEN',language),language==='en'?'Historical status after this event':'本事件后的历史状态',language==='en'?'Plan lifecycle at generation':'生成时计划生命周期',language==='en'?'Not applicable':'不适用',language==='en'?'A restricted or unavailable linked record exists':'存在受限或不可用的关联记录'])assert.ok(html.includes(value),value)
  for(const instant of ['2026-09-08T03:04:05Z','2026-09-10T06:07:08Z','2026-09-09T04:05:06Z'])assert.ok(html.includes(display.formatReportInstant(instant,'UTC',language)),instant)
  const period=html.slice(html.indexOf('id="period-report-heading"'));assert.ok(!period.includes(language==='en'?'Review wait duration':'本次待复核时长'),'period events never invent a current-action review wait')
 }
})

// Exercise the independent acceptance oracle against the real compiled panel,
// rather than duplicating its expected wording in another hand-built fixture.
import { assertDownloadClickMetadata } from '../../../scripts/care-report-fixture-oracle.mjs'
for(const language of ['en','zh-CN'])for(const format of ['html','pdf','actions_csv','events_csv'])test(`compiled ${language} ${format} download metadata meets the independent filename/scope/time oracle`,async()=>{
 const kind={html:'html.html',pdf:'pdf.pdf',actions_csv:'actions.csv',events_csv:'events.csv'}[format]
 const filename=`care-execution-report-${language}-20261004T230944Z-${kind}`
 const request={patientId:1,planId:null,fromDate:'2026-09-05',toDate:'2026-10-04',timeZone:'UTC',language}
 const csv=format.endsWith('_csv')
 const lastExport={format,scope:{patientId:1,planId:null,label:'ALL_PLANS'},...request,rangeKnown:true,reportSchemaVersion:1,generatedAt:'2026-10-04T23:09:44Z',rowCount:csv?0:null,isEmpty:csv}
 const html=await renderPanel(null,{language,lastExport})
 const notice=html.match(/<section[^>]*data-testid="last-export"[^>]*>([\s\S]*?)<\/section>/)?.[1]
 assert.ok(notice,'Actual rendered export notice is required')
 const paragraphs=[...notice.matchAll(/<p[^>]*>([\s\S]*?)<\/p>/g)].map(match=>match[1].replace(/<[^>]*>/g,''))
 const observation={paragraphs,text:paragraphs.join(' '),connected:true,fileName:filename}
 const options={request,format,filename,rowCount:csv?0:null}
 assert.deepEqual(assertDownloadClickMetadata(observation,options),{generatedSecond:'2026-10-04T23:09:44Z'})
 // Wording synchronization must not relax dates, identity, schema, or exact
 // time/UTC validation. Each independent corruption remains a hard failure.
 for(const [from,to] of [['2026-09-05','2026-09-04'],['#1','#2'],[language==='zh-CN'?'报告格式版本 1':'Report schema 1',language==='zh-CN'?'报告格式版本 2':'Report schema 2'],['09:44','09:45'],['UTC+00:00','UTC+08:00'],['UTC)','Asia/Shanghai)']]){
  const changed=paragraphs.map(value=>value.replace(from,to));assert.notDeepEqual(changed,paragraphs)
  assert.throws(()=>assertDownloadClickMetadata({...observation,paragraphs:changed},options),/scope|schema|time/)
 }
})
