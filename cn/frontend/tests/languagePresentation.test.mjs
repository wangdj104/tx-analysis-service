import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import {parse} from '@vue/compiler-sfc'
import {reportLabel} from '../src/utils/careExecutionReport.js'
const chinese=import.meta.url.includes('/cn/frontend/')
const src=file=>fs.readFileSync(new URL('../src/'+file,import.meta.url),'utf8')
test('edition HTML and component library explicitly declare the display language',()=>{
 assert.match(fs.readFileSync(new URL('../index.html',import.meta.url),'utf8'),chinese?/<html lang="zh-CN">/:/<html lang="en">/)
 assert.match(src('main.js'),chinese?/locale: zhCn/:/locale: en/)
})
test('clinical workbench dates keep the edition locale regardless of browser defaults',()=>{
 const code=parse(src('views/ClinicalWorkbench.vue')).descriptor.scriptSetup.content
 const fn=code.match(/function formatDate\(value\)\{[^\n]+\}/)[0]
 const formatter=new Function('return '+fn)()
 const date='2026-10-03T14:30:00Z'
 assert.equal(formatter(date),new Date(date).toLocaleString(chinese?'zh-CN':'en-US'))
 assert.match(fn,chinese?/toLocaleString\('zh-CN'\)/:/toLocaleString\('en-US'\)/)
})
test('print documents and embedded report previews carry edition language',()=>{
 const family=src('views/FamilyHealthManager.vue')
 assert.ok(family.includes(chinese?'<html lang="zh-CN">':'<html lang="en">'))
 assert.ok(family.includes(chinese?'<title>就诊摘要</title>':'<title>Visit Summary</title>'))
 assert.ok(src('views/HealthReportManager.vue').includes(chinese?'title="报告预览"':'title="Report preview"'))
})
test('medication sorting uses the edition collation',()=>{
 assert.ok(src('views/MedicationManager.vue').includes(chinese?"localeCompare(b.name, 'zh-CN')":"localeCompare(b.name, 'en-US')"))
})
test('report question timestamps use human labels in both output languages',()=>{
 assert.equal(reportLabel('questionTime','createdAtLocal','en'),'Created')
 assert.equal(reportLabel('questionTime','updatedAtLocal','zh-CN'),'更新时间')
 assert.equal(reportLabel('questionTime','eventAtLocal','zh-CN'),'事件时间')
 assert.ok(src('components/care-plan/ExecutionReportPanel.vue').includes("label('questionTime',key)"))
})

test('report provenance retains stable codes alongside translated human labels',()=>{
 assert.equal(reportLabel('actorRelation','SELF','zh-CN'),'本人')
 assert.equal(reportLabel('actorRelation','ASSISTANT','en'),'Assistant')
 assert.equal(reportLabel('sourceType','MEASUREMENT','zh-CN'),'健康测量')
 assert.equal(reportLabel('sourceType','MEDICAL_RECORD','en'),'Medical record')
 const panel=src('components/care-plan/ExecutionReportPanel.vue')
 assert.ok(panel.includes("label('sourceType',e.sourceType)"))
 assert.ok(panel.includes("label('actorRelation',event.actorRelation)"))
 assert.ok(panel.includes('{{ e.sourceType }}'))
 assert.ok(panel.includes('{{ event.actorRelation }}'))
})

test('switchable report sections declare their selected output language for screen readers and mixed-language print',()=>{
 assert.ok(src('components/care-plan/ExecutionReportPanel.vue').includes(':lang="form.language"'))
})

test('real-browser fixtures exercise the edition against the opposite browser language',()=>{
 assert.ok(fs.readFileSync(new URL('../e2e/carePlanSessions.mjs',import.meta.url),'utf8').includes("locale: 'en-US'"))
})
