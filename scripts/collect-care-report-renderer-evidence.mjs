#!/usr/bin/env node
// Collect only the four freshly regenerated Task5 specimens, never target probes.
import assert from 'node:assert/strict'
import { join, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { inspectReportFile } from './inspect-care-report-files.mjs'
import { stageReportEvidence } from './package-care-report-artifacts.mjs'
const root=resolve(fileURLToPath(new URL('..',import.meta.url)))
export function rendererEvidenceSelection(project) {
  assert.ok(['.','cn'].includes(project),'Invalid renderer evidence edition')
  const edition=project==='cn'?'cn':'en'
  return ['en','zh-CN'].flatMap(language=>['coverage','long'].map(kind=>({edition,language,scenario:`task5-${kind}`,path:join(root,project,'target/care-execution-report-qa',language,`${kind}-report.pdf`)})))
}
export async function collectRendererEvidence(project,env=process.env) {
  assert.ok(env.CI==='true'&&env.GITHUB_ACTIONS==='true','Renderer evidence staging requires the real CI contract')
  const selected=rendererEvidenceSelection(project)
  for(const entry of selected) {
    const pngDirectory=join(root,project,'frontend/test-results/task5-report-pages',entry.language,entry.scenario)
    const expected={contains:[entry.language==='en'?'Care execution report':'照护执行报告',entry.scenario==='task5-long'?'END-OF-LONG-ORIGINAL':'原文：按既有计划记录，不自动翻译'],minPages:entry.scenario==='task5-long'?3:1}
    const result=await inspectReportFile(entry.path,{format:'pdf',language:entry.language,expected,pngDirectory})
    await stageReportEvidence(entry.path,{edition:entry.edition,language:entry.language,scenario:entry.scenario,format:'pdf',bytes:result.bytes,sha256:result.sha256,pageCount:result.pageCount,result:'passed'},env)
    console.log(`Task5 ${entry.edition}/${entry.language}/${entry.scenario}: ${result.pageCount} actual PDF pages extracted and rendered`)
  }
}
if(process.argv[1]&&import.meta.url===pathToFileURL(resolve(process.argv[1])).href)collectRendererEvidence(process.argv[2]).catch(()=>{console.error('Exact renderer evidence collection failed');process.exitCode=1})
